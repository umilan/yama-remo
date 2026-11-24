/*
 * yamaremo.c - native Windows CE 6.0 remote for Yamaha MusicCast speakers
 * (NX-N500), the WinCE counterpart of musiccast-remote.html.
 *
 * Target: Medion GoPal E5270 (WinCE 6.0, ARM, 800x480 touch screen), but it
 * adapts to any screen size. Plain Win32 + GDI + Winsock, no other
 * dependencies, single .exe. Talks to the speaker's YXC HTTP API directly,
 * so there is no browser CORS problem and no proxy is needed.
 *
 * Build (Linux, CeGCC):   see build-wince.sh
 * Also compiles as a desktop Win32 app with mingw-w64 for quick testing.
 */
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif

#include <winsock2.h>
#include <windows.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

#define APP_VERSION "1.0"
#define APP_TITLE   L"MusicCast Remote"
#define APP_CLASS   L"YamaRemoWnd"
#define INI_NAME    L"yamaremo.ini"

#define WM_APP_STATE   (WM_APP + 1)

#define TIMER_REPEAT   1
#define POLL_MS        3000
#define CONNECT_MS     2000
#define RECV_MS        3000

/* ------------------------------------------------------------------ */
/* Colours                                                              */
/* ------------------------------------------------------------------ */
#define C_BG        RGB(0x12,0x14,0x18)
#define C_PANEL     RGB(0x22,0x26,0x2E)
#define C_PANEL_DN  RGB(0x3A,0x40,0x4C)
#define C_ACCENT    RGB(0x2F,0x7D,0xF6)
#define C_ACCENT_DN RGB(0x1E,0x5A,0xB8)
#define C_TEXT      RGB(0xE8,0xEA,0xED)
#define C_MUTED     RGB(0x8A,0x90,0x99)
#define C_GREEN     RGB(0x2E,0xA0,0x5A)
#define C_RED       RGB(0xC8,0x3C,0x3C)
#define C_AMBER     RGB(0xE0,0xA0,0x30)

/* ------------------------------------------------------------------ */
/* Speaker state (shared between UI and worker thread)                  */
/* ------------------------------------------------------------------ */
typedef struct {
    int  connected;        /* last request got an HTTP answer */
    int  everPolled;
    int  powerOn;
    int  volume;
    int  maxVolume;
    int  mute;
    int  playing;
    char input[32];
    WCHAR track[160];
    WCHAR artist[160];
} SpeakerState;

static CRITICAL_SECTION g_cs;
static SpeakerState g_state;          /* written by worker, under g_cs  */
static SpeakerState g_ui;             /* UI copy                        */
static char  g_ip[64] = "192.168.1.50";
static HANDLE g_wake;
static volatile LONG g_quit;
static volatile LONG g_pollNow;

/* command queue */
#define QLEN 16
static char g_queue[QLEN][96];
static int  g_qHead, g_qTail;
static int  g_pendingVolume = -1;     /* coalesced setVolume */
static DWORD g_volumeHoldUntil;       /* ignore polled volume until then */

static HWND g_hwnd;
static HWND g_taskbar;
static HFONT g_fBig, g_fMid, g_fSmall, g_fHuge;
static int  g_w = 800, g_h = 480;

/* ------------------------------------------------------------------ */
/* Small helpers                                                        */
/* ------------------------------------------------------------------ */
static void lock(void)   { EnterCriticalSection(&g_cs); }
static void unlock(void) { LeaveCriticalSection(&g_cs); }

static void queueCommand(const char *path)
{
    lock();
    if (((g_qTail + 1) % QLEN) != g_qHead) {
        strncpy(g_queue[g_qTail], path, sizeof(g_queue[0]) - 1);
        g_queue[g_qTail][sizeof(g_queue[0]) - 1] = 0;
        g_qTail = (g_qTail + 1) % QLEN;
    }
    unlock();
    SetEvent(g_wake);
}

static void iniPath(WCHAR *out, int cap)
{
    WCHAR *slash;
    GetModuleFileNameW(NULL, out, cap);
    slash = wcsrchr(out, L'\\');
    if (slash) slash[1] = 0; else out[0] = 0;
    wcscat(out, INI_NAME);
}

static void loadSettings(void)
{
    WCHAR path[MAX_PATH];
    HANDLE f;
    char buf[80];
    DWORD n = 0;
    iniPath(path, MAX_PATH);
    f = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (f == INVALID_HANDLE_VALUE) return;
    if (ReadFile(f, buf, sizeof(buf) - 1, &n, NULL) && n > 0) {
        char *p;
        buf[n] = 0;
        p = strstr(buf, "ip=");
        if (p) {
            int i = 0;
            p += 3;
            while (*p && *p != '\r' && *p != '\n' && i < (int)sizeof(g_ip) - 1)
                g_ip[i++] = *p++;
            g_ip[i] = 0;
        }
    }
    CloseHandle(f);
}

static void saveSettings(void)
{
    WCHAR path[MAX_PATH];
    HANDLE f;
    char buf[96];
    DWORD n;
    iniPath(path, MAX_PATH);
    f = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    if (f == INVALID_HANDLE_VALUE) return;
    sprintf(buf, "ip=%s\r\n", g_ip);
    WriteFile(f, buf, (DWORD)strlen(buf), &n, NULL);
    CloseHandle(f);
}

/* ------------------------------------------------------------------ */
/* HTTP (blocking winsock with timeouts, runs on worker thread)         */
/* ------------------------------------------------------------------ */
/* Returns body length (>=0) on HTTP 200, -1 on network error, -2 on
 * non-200. body is NUL-terminated. */
static int httpGet(const char *ip, const char *path, char *body, int cap)
{
    SOCKET s;
    struct sockaddr_in sa;
    unsigned long addr, nb;
    fd_set ws, rs, es;
    struct timeval tv;
    char req[256];
    static char resp[16384];
    int total = 0, r, len;
    char *hdrEnd;

    body[0] = 0;
    addr = inet_addr(ip);
    if (addr == INADDR_NONE) {
        struct hostent *he = gethostbyname(ip);
        if (!he) return -1;
        memcpy(&addr, he->h_addr, 4);
    }

    s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) return -1;

    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons(80);
    sa.sin_addr.s_addr = addr;

    /* non-blocking connect so an absent speaker can't hang us for ~20 s */
    nb = 1; ioctlsocket(s, FIONBIO, &nb);
    connect(s, (struct sockaddr *)&sa, sizeof(sa));
    FD_ZERO(&ws); FD_SET(s, &ws);
    FD_ZERO(&es); FD_SET(s, &es);
    tv.tv_sec = CONNECT_MS / 1000; tv.tv_usec = (CONNECT_MS % 1000) * 1000;
    if (select(0, NULL, &ws, &es, &tv) <= 0 || FD_ISSET(s, &es) || !FD_ISSET(s, &ws)) {
        closesocket(s);
        return -1;
    }
    nb = 0; ioctlsocket(s, FIONBIO, &nb);

    len = sprintf(req,
        "GET /YamahaExtendedControl/v1/%s HTTP/1.0\r\n"
        "Host: %s\r\nConnection: close\r\n\r\n", path, ip);
    if (send(s, req, len, 0) != len) { closesocket(s); return -1; }

    for (;;) {
        FD_ZERO(&rs); FD_SET(s, &rs);
        tv.tv_sec = RECV_MS / 1000; tv.tv_usec = (RECV_MS % 1000) * 1000;
        if (select(0, &rs, NULL, NULL, &tv) <= 0) break;
        r = recv(s, resp + total, (int)sizeof(resp) - 1 - total, 0);
        if (r <= 0) break;
        total += r;
        if (total >= (int)sizeof(resp) - 1) break;
    }
    closesocket(s);
    resp[total] = 0;
    if (total < 12 || strncmp(resp, "HTTP/", 5) != 0) return -1;

    hdrEnd = strstr(resp, "\r\n\r\n");
    if (hdrEnd) {
        int bl = total - (int)(hdrEnd + 4 - resp);
        if (bl > cap - 1) bl = cap - 1;
        memcpy(body, hdrEnd + 4, bl);
        body[bl] = 0;
    }
    {
        char *sp = strchr(resp, ' ');
        if (!sp || strncmp(sp + 1, "200", 3) != 0) return -2;
    }
    return (int)strlen(body);
}

/* ------------------------------------------------------------------ */
/* Minimal JSON field extraction (flat keys only, good enough for YXC)  */
/* ------------------------------------------------------------------ */
static const char *jsonFind(const char *json, const char *key)
{
    char pat[48];
    const char *p;
    sprintf(pat, "\"%s\"", key);
    p = strstr(json, pat);
    if (!p) return NULL;
    p += strlen(pat);
    while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
    if (*p != ':') return NULL;
    p++;
    while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
    return p;
}

static int jsonInt(const char *json, const char *key, int def)
{
    const char *p = jsonFind(json, key);
    if (!p || !(*p == '-' || (*p >= '0' && *p <= '9'))) return def;
    return atoi(p);
}

static int jsonBool(const char *json, const char *key, int def)
{
    const char *p = jsonFind(json, key);
    if (!p) return def;
    if (strncmp(p, "true", 4) == 0) return 1;
    if (strncmp(p, "false", 5) == 0) return 0;
    return def;
}

static void putUtf8(char **o, char *end, unsigned cp)
{
    char *d = *o;
    if (cp < 0x80)        { if (d + 1 > end) return; *d++ = (char)cp; }
    else if (cp < 0x800)  { if (d + 2 > end) return; *d++ = (char)(0xC0 | (cp >> 6)); *d++ = (char)(0x80 | (cp & 0x3F)); }
    else                  { if (d + 3 > end) return; *d++ = (char)(0xE0 | (cp >> 12)); *d++ = (char)(0x80 | ((cp >> 6) & 0x3F)); *d++ = (char)(0x80 | (cp & 0x3F)); }
    *o = d;
}

/* Reads a JSON string value as UTF-8 into out. Returns 1 if found. */
static int jsonStr(const char *json, const char *key, char *out, int cap)
{
    const char *p = jsonFind(json, key);
    char *o = out, *end = out + cap - 1;
    out[0] = 0;
    if (!p || *p != '"') return 0;
    p++;
    while (*p && *p != '"' && o < end) {
        if (*p == '\\' && p[1]) {
            p++;
            switch (*p) {
            case 'n': *o++ = ' '; break;
            case 't': *o++ = ' '; break;
            case 'r': break;
            case 'b': case 'f': break;
            case 'u': {
                unsigned cp = 0; int i;
                for (i = 1; i <= 4 && p[i]; i++) {
                    char c = p[i];
                    cp <<= 4;
                    if (c >= '0' && c <= '9') cp |= c - '0';
                    else if (c >= 'a' && c <= 'f') cp |= c - 'a' + 10;
                    else if (c >= 'A' && c <= 'F') cp |= c - 'A' + 10;
                }
                p += 4;
                putUtf8(&o, end, cp);
                break;
            }
            default: *o++ = *p; break; /* \" \\ \/ */
            }
            p++;
        } else {
            *o++ = *p++;
        }
    }
    *o = 0;
    return 1;
}

static void utf8ToWide(const char *s, WCHAR *out, int cap)
{
    if (!MultiByteToWideChar(CP_UTF8, 0, s, -1, out, cap)) {
        /* fall back to Latin-1 style copy */
        int i;
        for (i = 0; s[i] && i < cap - 1; i++) out[i] = (unsigned char)s[i];
        out[i] = 0;
    }
    out[cap - 1] = 0;
}

/* ------------------------------------------------------------------ */
/* Worker thread                                                        */
/* ------------------------------------------------------------------ */
static void pollSpeaker(const char *ip)
{
    static char body[8192];
    char tmp[480];
    int r;
    SpeakerState s;

    lock(); s = g_state; unlock();

    r = httpGet(ip, "main/getStatus", body, sizeof(body));
    s.everPolled = 1;
    if (r < 0 && r != -2) {
        s.connected = 0;
    } else {
        s.connected = 1;
        if (r >= 0 && jsonInt(body, "response_code", 0) == 0) {
            char pw[16];
            if (jsonStr(body, "power", pw, sizeof(pw)))
                s.powerOn = strcmp(pw, "on") == 0;
            s.maxVolume = jsonInt(body, "max_volume", s.maxVolume > 0 ? s.maxVolume : 100);
            if (GetTickCount() >= g_volumeHoldUntil && g_pendingVolume < 0)
                s.volume = jsonInt(body, "volume", s.volume);
            s.mute = jsonBool(body, "mute", s.mute);
            jsonStr(body, "input", s.input, sizeof(s.input));
        }
        if (s.powerOn) {
            r = httpGet(ip, "netusb/getPlayInfo", body, sizeof(body));
            if (r >= 0) {
                char pb[16];
                jsonStr(body, "playback", pb, sizeof(pb));
                s.playing = strcmp(pb, "play") == 0;
                if (strcmp(pb, "play") == 0 || strcmp(pb, "pause") == 0) {
                    jsonStr(body, "track", tmp, sizeof(tmp));
                    utf8ToWide(tmp, s.track, 160);
                    jsonStr(body, "artist", tmp, sizeof(tmp));
                    utf8ToWide(tmp, s.artist, 160);
                } else {
                    s.track[0] = 0; s.artist[0] = 0;
                }
            }
        } else {
            s.playing = 0; s.track[0] = 0; s.artist[0] = 0;
        }
    }

    lock();
    /* keep optimistic local volume if user changed it meanwhile */
    if (GetTickCount() < g_volumeHoldUntil || g_pendingVolume >= 0)
        s.volume = g_state.volume;
    g_state = s;
    unlock();
    PostMessageW(g_hwnd, WM_APP_STATE, 0, 0);
}

static DWORD WINAPI workerProc(LPVOID arg)
{
    static char body[2048];
    DWORD lastPoll = 0;
    (void)arg;

    for (;;) {
        char ip[64];
        char cmd[96];
        int vol, didCmd = 0;
        DWORD now;

        WaitForSingleObject(g_wake, 500);
        if (g_quit) break;

        lock(); strcpy(ip, g_ip); unlock();

        /* drain commands */
        for (;;) {
            cmd[0] = 0; vol = -1;
            lock();
            if (g_pendingVolume >= 0) {
                vol = g_pendingVolume;
                g_pendingVolume = -1;
            } else if (g_qHead != g_qTail) {
                strcpy(cmd, g_queue[g_qHead]);
                g_qHead = (g_qHead + 1) % QLEN;
            }
            unlock();
            if (vol >= 0) sprintf(cmd, "main/setVolume?volume=%d", vol);
            if (!cmd[0]) break;
            {
                int r = httpGet(ip, cmd, body, sizeof(body));
                lock(); g_state.connected = (r >= 0 || r == -2); unlock();
            }
            didCmd = 1;
            if (g_quit) return 0;
        }

        now = GetTickCount();
        if (didCmd || g_pollNow || now - lastPoll >= POLL_MS || lastPoll == 0) {
            g_pollNow = 0;
            if (didCmd) Sleep(150); /* let the speaker apply the change */
            pollSpeaker(ip);
            lastPoll = GetTickCount();
        }
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* UI model: buttons                                                    */
/* ------------------------------------------------------------------ */
enum {
    B_NONE = -1,
    B_POWER, B_SETTINGS, B_EXIT,
    B_SRC0, B_SRC1, B_SRC2, B_SRC3, B_SRC4, B_SRC5,
    B_VOLDN, B_VOLUP, B_VOLBAR,
    B_PREV, B_PLAY, B_NEXT, B_MUTE,
    B_NOWPLAYING,
    /* keypad screen */
    B_K0, B_K1, B_K2, B_K3, B_K4, B_K5, B_K6, B_K7, B_K8, B_K9,
    B_KDOT, B_KDEL, B_KOK, B_KCANCEL, B_KCLEAR,
    B_COUNT
};

static const char  *SRC_ID[6]    = { "airplay", "aux", "optical", "bluetooth", "usb", "net_radio" };
static const WCHAR *SRC_LABEL[6] = { L"AirPlay", L"AUX", L"Optical", L"Bluetooth", L"USB", L"Net Radio" };

static RECT g_btn[B_COUNT];
static int  g_pressed = B_NONE;
static int  g_keypad = 0;            /* 1 = IP entry screen shown */
static WCHAR g_ipEdit[32];
static int  g_repeatCount;

static void setRect(int id, int x, int y, int w, int h)
{
    g_btn[id].left = x; g_btn[id].top = y;
    g_btn[id].right = x + w; g_btn[id].bottom = y + h;
}

static int sx(int v) { return v * g_w / 800; }
static int sy(int v) { return v * g_h / 480; }

static void layout(void)
{
    int i, gap = sx(8), x, w;
    memset(g_btn, 0, sizeof(g_btn));

    /* main screen (designed on an 800x480 grid, scaled) */
    setRect(B_POWER,    sx(8),   sy(8),  sx(120), sy(60));
    setRect(B_SETTINGS, sx(552), sy(8),  sx(160), sy(60));
    setRect(B_EXIT,     sx(720), sy(8),  sx(72),  sy(60));
    setRect(B_NOWPLAYING, sx(8), sy(76), sx(784), sy(104));

    w = (g_w - gap * 7) / 6;
    for (i = 0; i < 6; i++) {
        x = gap + i * (w + gap);
        setRect(B_SRC0 + i, x, sy(188), w, sy(72));
    }

    setRect(B_VOLDN,  sx(8),   sy(268), sx(150), sy(100));
    setRect(B_VOLBAR, sx(166), sy(268), sx(468), sy(100));
    setRect(B_VOLUP,  sx(642), sy(268), sx(150), sy(100));

    w = (g_w - gap * 5) / 4;
    setRect(B_PREV, gap,               sy(376), w, sy(96));
    setRect(B_PLAY, gap * 2 + w,       sy(376), w, sy(96));
    setRect(B_NEXT, gap * 3 + w * 2,   sy(376), w, sy(96));
    setRect(B_MUTE, gap * 4 + w * 3,   sy(376), w, sy(96));

    /* keypad screen: 4 columns x 4 rows on the right, display on the left */
    {
        int kx = sx(360), ky = sy(84), kw = sx(102), kh = sy(90), kg = sx(8);
        static const int grid[4][4] = {
            { B_K7, B_K8, B_K9, B_KDEL },
            { B_K4, B_K5, B_K6, B_KCLEAR },
            { B_K1, B_K2, B_K3, B_KCANCEL },
            { B_K0, B_KDOT, B_NONE, B_KOK },
        };
        int r, c;
        for (r = 0; r < 4; r++)
            for (c = 0; c < 4; c++)
                if (grid[r][c] != B_NONE)
                    setRect(grid[r][c], kx + c * (kw + kg), ky + r * (kh + kg), kw, kh);
        /* make OK span two columns (cols 2-3 of the last row) */
        setRect(B_KOK, kx + 2 * (kw + kg), ky + 3 * (kh + kg), kw * 2 + kg, kh);
    }
}

static int isKeypadButton(int id) { return id >= B_K0 && id <= B_KCLEAR; }

static int hitTest(int x, int y)
{
    int i;
    POINT pt; pt.x = x; pt.y = y;
    for (i = 0; i < B_COUNT; i++) {
        if (g_btn[i].right <= g_btn[i].left) continue;
        if (g_keypad != isKeypadButton(i)) continue;
        if (PtInRect(&g_btn[i], pt)) return i;
    }
    return B_NONE;
}

/* ------------------------------------------------------------------ */
/* Drawing                                                              */
/* ------------------------------------------------------------------ */
static HFONT makeFont(int px, int bold)
{
    LOGFONTW lf;
    memset(&lf, 0, sizeof(lf));
    lf.lfHeight = -px;
    lf.lfWeight = bold ? FW_BOLD : FW_NORMAL;
    lf.lfCharSet = DEFAULT_CHARSET;
    lf.lfQuality = ANTIALIASED_QUALITY;
    wcscpy(lf.lfFaceName, L"Tahoma");
    return CreateFontIndirectW(&lf);
}

static void fillRect(HDC dc, const RECT *r, COLORREF c)
{
    HBRUSH b = CreateSolidBrush(c);
    FillRect(dc, r, b);
    DeleteObject(b);
}

static void roundBox(HDC dc, const RECT *r, COLORREF c)
{
    HBRUSH b = CreateSolidBrush(c);
    HPEN p = CreatePen(PS_SOLID, 1, c);
    HGDIOBJ ob = SelectObject(dc, b), op = SelectObject(dc, p);
    RoundRect(dc, r->left, r->top, r->right, r->bottom, sx(18), sx(18));
    SelectObject(dc, ob); SelectObject(dc, op);
    DeleteObject(b); DeleteObject(p);
}

static void text(HDC dc, const RECT *r, const WCHAR *s, HFONT f, COLORREF c, UINT fmt)
{
    RECT rr = *r;
    HGDIOBJ of = SelectObject(dc, f);
    SetTextColor(dc, c);
    SetBkMode(dc, TRANSPARENT);
    DrawTextW(dc, s, -1, &rr, fmt);
    SelectObject(dc, of);
}

static void button(HDC dc, int id, const WCHAR *label, COLORREF bg, COLORREF fg, HFONT f)
{
    COLORREF c = bg;
    if (g_pressed == id) c = (bg == C_ACCENT) ? C_ACCENT_DN : C_PANEL_DN;
    roundBox(dc, &g_btn[id], c);
    text(dc, &g_btn[id], label, f, fg, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
}

static void triangle(HDC dc, int cx, int cy, int size, int dir, COLORREF c)
{
    POINT p[3];
    HBRUSH b = CreateSolidBrush(c);
    HPEN pen = CreatePen(PS_SOLID, 1, c);
    HGDIOBJ ob = SelectObject(dc, b), op = SelectObject(dc, pen);
    if (dir > 0) { /* pointing right */
        p[0].x = cx - size / 2; p[0].y = cy - size / 2;
        p[1].x = cx - size / 2; p[1].y = cy + size / 2;
        p[2].x = cx + size / 2; p[2].y = cy;
    } else {
        p[0].x = cx + size / 2; p[0].y = cy - size / 2;
        p[1].x = cx + size / 2; p[1].y = cy + size / 2;
        p[2].x = cx - size / 2; p[2].y = cy;
    }
    Polygon(dc, p, 3);
    SelectObject(dc, ob); SelectObject(dc, op);
    DeleteObject(b); DeleteObject(pen);
}

static void bar(HDC dc, int x, int y, int w, int h, COLORREF c)
{
    RECT r; r.left = x; r.top = y; r.right = x + w; r.bottom = y + h;
    fillRect(dc, &r, c);
}

static void iconButton(HDC dc, int id, int kind, COLORREF bg, COLORREF fg)
{
    RECT *r = &g_btn[id];
    int cx = (r->left + r->right) / 2, cy = (r->top + r->bottom) / 2;
    int s = sy(36);
    COLORREF c = bg;
    if (g_pressed == id) c = (bg == C_ACCENT) ? C_ACCENT_DN : C_PANEL_DN;
    roundBox(dc, r, c);
    switch (kind) {
    case 0: /* prev: |< < */
        bar(dc, cx - s, cy - s / 2, s / 6, s, fg);
        triangle(dc, cx - s / 3, cy, s, -1, fg);
        triangle(dc, cx + s / 2, cy, s, -1, fg);
        break;
    case 1: /* next: > >| */
        triangle(dc, cx - s / 2, cy, s, 1, fg);
        triangle(dc, cx + s / 3, cy, s, 1, fg);
        bar(dc, cx + s - s / 6, cy - s / 2, s / 6, s, fg);
        break;
    case 2: /* play */
        triangle(dc, cx + s / 10, cy, s + s / 3, 1, fg);
        break;
    case 3: /* pause */
        bar(dc, cx - s / 2, cy - s / 2 - 2, s / 3, s + 4, fg);
        bar(dc, cx + s / 6, cy - s / 2 - 2, s / 3, s + 4, fg);
        break;
    }
}

static const WCHAR *sourceLabel(const char *id)
{
    static WCHAR buf[32];
    int i;
    for (i = 0; i < 6; i++)
        if (strcmp(id, SRC_ID[i]) == 0) return SRC_LABEL[i];
    utf8ToWide(id, buf, 32);
    return buf;
}

static void paintMain(HDC dc)
{
    SpeakerState *s = &g_ui;
    WCHAR buf[200];
    RECT r;
    int i, on = s->powerOn && s->connected;
    COLORREF dim = on ? C_TEXT : C_MUTED;

    /* top bar */
    button(dc, B_POWER, s->powerOn ? L"ON" : L"OFF",
           s->powerOn ? C_GREEN : C_PANEL, C_TEXT, g_fMid);

    r.left = g_btn[B_POWER].right + sx(16); r.right = g_btn[B_SETTINGS].left - sx(8);
    r.top = g_btn[B_POWER].top; r.bottom = g_btn[B_POWER].bottom;
    {
        RECT r1 = r, r2 = r;
        r1.bottom = (r.top + r.bottom) / 2 + sy(4);
        r2.top = r1.bottom - sy(2);
        text(dc, &r1, APP_TITLE, g_fMid, C_TEXT, DT_LEFT | DT_BOTTOM | DT_SINGLELINE);
        if (!s->everPolled)       wcscpy(buf, L"Connecting...");
        else if (!s->connected)   wcscpy(buf, L"Not connected - tap the IP button to change address");
        else if (!s->powerOn)     wcscpy(buf, L"Connected - standby");
        else                      wcscpy(buf, L"Connected");
        text(dc, &r2, buf, g_fSmall,
             !s->everPolled ? C_MUTED : (s->connected ? C_GREEN : C_RED),
             DT_LEFT | DT_TOP | DT_SINGLELINE | DT_END_ELLIPSIS);
    }
    {
        WCHAR ipw[64];
        utf8ToWide(g_ip, ipw, 64);
        wsprintfW(buf, L"IP %s", ipw);
        button(dc, B_SETTINGS, buf, C_PANEL, C_TEXT, g_fSmall);
    }
    button(dc, B_EXIT, L"X", C_PANEL, C_TEXT, g_fMid);

    /* now playing */
    roundBox(dc, &g_btn[B_NOWPLAYING], C_PANEL);
    r = g_btn[B_NOWPLAYING];
    InflateRect(&r, -sx(20), -sy(10));
    {
        RECT r1 = r, r2 = r;
        r1.bottom = r.top + (r.bottom - r.top) * 55 / 100;
        r2.top = r1.bottom;
        if (!s->connected || !s->powerOn) {
            text(dc, &r1, s->connected ? L"Speaker is in standby" : L"No speaker", g_fBig, C_MUTED,
                 DT_LEFT | DT_BOTTOM | DT_SINGLELINE | DT_END_ELLIPSIS);
            text(dc, &r2, s->connected ? L"Tap ON to switch it on" : L"Check Wi-Fi and speaker IP",
                 g_fSmall, C_MUTED, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
        } else if (s->track[0]) {
            text(dc, &r1, s->track, g_fBig, C_TEXT, DT_LEFT | DT_BOTTOM | DT_SINGLELINE | DT_END_ELLIPSIS);
            wsprintfW(buf, L"%s   \x2022   %s", s->artist[0] ? s->artist : L"", sourceLabel(s->input));
            if (!s->artist[0]) wcscpy(buf, sourceLabel(s->input));
            text(dc, &r2, buf, g_fMid, C_MUTED, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
        } else {
            text(dc, &r1, L"Not playing", g_fBig, C_MUTED, DT_LEFT | DT_BOTTOM | DT_SINGLELINE);
            wsprintfW(buf, L"Source: %s", sourceLabel(s->input));
            text(dc, &r2, buf, g_fMid, C_MUTED, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
        }
    }

    /* sources */
    for (i = 0; i < 6; i++) {
        int active = strcmp(s->input, SRC_ID[i]) == 0;
        button(dc, B_SRC0 + i, SRC_LABEL[i], active ? C_ACCENT : C_PANEL,
               active ? C_TEXT : dim, g_fSmall);
    }

    /* volume */
    button(dc, B_VOLDN, L"\x2212", C_PANEL, dim, g_fHuge);
    button(dc, B_VOLUP, L"+", C_PANEL, dim, g_fHuge);
    roundBox(dc, &g_btn[B_VOLBAR], C_PANEL);
    {
        int maxv = s->maxVolume > 0 ? s->maxVolume : 100;
        int v = s->volume < 0 ? 0 : (s->volume > maxv ? maxv : s->volume);
        RECT tr = g_btn[B_VOLBAR], track, fill;
        tr.bottom = tr.top + (tr.bottom - tr.top) * 62 / 100;
        if (s->mute) wsprintfW(buf, L"Volume %d  (muted)", v);
        else         wsprintfW(buf, L"Volume %d", v);
        text(dc, &tr, buf, g_fMid, s->mute ? C_AMBER : dim, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        track = g_btn[B_VOLBAR];
        InflateRect(&track, -sx(24), 0);
        track.top = tr.bottom + sy(4);
        track.bottom = track.top + sy(14);
        fillRect(dc, &track, C_BG);
        fill = track;
        fill.right = track.left + (track.right - track.left) * v / maxv;
        fillRect(dc, &fill, s->mute ? C_MUTED : (on ? C_ACCENT : C_MUTED));
    }

    /* transport */
    iconButton(dc, B_PREV, 0, C_PANEL, dim);
    iconButton(dc, B_PLAY, s->playing ? 3 : 2, on ? C_ACCENT : C_PANEL, C_TEXT);
    iconButton(dc, B_NEXT, 1, C_PANEL, dim);
    button(dc, B_MUTE, s->mute ? L"Unmute" : L"Mute", s->mute ? C_AMBER : C_PANEL,
           s->mute ? C_BG : dim, g_fMid);
}

static void paintKeypad(HDC dc)
{
    static const WCHAR *lbl[] = { L"0",L"1",L"2",L"3",L"4",L"5",L"6",L"7",L"8",L"9",
                                  L".", L"Del", L"OK", L"Cancel", L"Clear" };
    RECT r;
    int i;

    r.left = sx(24); r.top = sy(16); r.right = sx(780); r.bottom = sy(70);
    text(dc, &r, L"Speaker IP address", g_fBig, C_TEXT, DT_LEFT | DT_VCENTER | DT_SINGLELINE);

    r.left = sx(24); r.top = sy(84); r.right = sx(340); r.bottom = sy(174);
    roundBox(dc, &r, C_PANEL);
    {
        WCHAR shown[40];
        wsprintfW(shown, L"%s_", g_ipEdit);
        text(dc, &r, shown, g_fBig, C_TEXT, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    }
    r.top = sy(190); r.bottom = sy(470);
    text(dc, &r,
         L"Find the speaker's IP in the MusicCast app (Settings > device info) "
         L"or in your router's device list. Saved to yamaremo.ini next to the program.",
         g_fSmall, C_MUTED, DT_LEFT | DT_TOP | DT_WORDBREAK);

    for (i = B_K0; i <= B_KCLEAR; i++) {
        COLORREF bg = C_PANEL;
        if (i == B_KOK) bg = C_ACCENT;
        button(dc, i, lbl[i - B_K0], bg, C_TEXT, i <= B_KDOT ? g_fBig : g_fMid);
    }
}

static void paint(HWND hwnd)
{
    PAINTSTRUCT ps;
    HDC dc = BeginPaint(hwnd, &ps);
    HDC mem = CreateCompatibleDC(dc);
    HBITMAP bmp = CreateCompatibleBitmap(dc, g_w, g_h);
    HGDIOBJ old = SelectObject(mem, bmp);
    RECT all; all.left = 0; all.top = 0; all.right = g_w; all.bottom = g_h;

    fillRect(mem, &all, C_BG);
    if (g_keypad) paintKeypad(mem); else paintMain(mem);

    BitBlt(dc, 0, 0, g_w, g_h, mem, 0, 0, SRCCOPY);
    SelectObject(mem, old);
    DeleteObject(bmp);
    DeleteDC(mem);
    EndPaint(hwnd, &ps);
}

/* ------------------------------------------------------------------ */
/* Actions                                                              */
/* ------------------------------------------------------------------ */
static void changeVolume(int delta)
{
    int maxv, v;
    lock();
    maxv = g_state.maxVolume > 0 ? g_state.maxVolume : 100;
    v = g_state.volume + delta;
    if (v < 0) v = 0;
    if (v > maxv) v = maxv;
    g_state.volume = v;
    g_pendingVolume = v;
    g_volumeHoldUntil = GetTickCount() + 2500;
    g_ui.volume = v;
    unlock();
    SetEvent(g_wake);
    InvalidateRect(g_hwnd, NULL, FALSE);
}

static void setVolumeAt(int x)
{
    RECT *r = &g_btn[B_VOLBAR];
    int left = r->left + sx(24), right = r->right - sx(24), maxv, v;
    if (x < left) x = left;
    if (x > right) x = right;
    lock();
    maxv = g_state.maxVolume > 0 ? g_state.maxVolume : 100;
    v = (x - left) * maxv / (right - left);
    unlock();
    lock(); v -= g_state.volume; unlock();
    changeVolume(v);
}

static void doAction(int id, int x)
{
    char cmd[96];
    SpeakerState s;
    lock(); s = g_state; unlock();

    if (g_keypad) {
        int len = (int)wcslen(g_ipEdit);
        if (id >= B_K0 && id <= B_K9) {
            if (len < 15) { g_ipEdit[len] = (WCHAR)(L'0' + id - B_K0); g_ipEdit[len + 1] = 0; }
        } else if (id == B_KDOT) {
            if (len < 15) { g_ipEdit[len] = L'.'; g_ipEdit[len + 1] = 0; }
        } else if (id == B_KDEL) {
            if (len > 0) g_ipEdit[len - 1] = 0;
        } else if (id == B_KCLEAR) {
            g_ipEdit[0] = 0;
        } else if (id == B_KCANCEL) {
            g_keypad = 0;
        } else if (id == B_KOK) {
            if (g_ipEdit[0]) {
                lock();
                WideCharToMultiByte(CP_ACP, 0, g_ipEdit, -1, g_ip, sizeof(g_ip), NULL, NULL);
                memset(&g_state, 0, sizeof(g_state));
                g_state.maxVolume = 100;
                g_ui = g_state;
                unlock();
                saveSettings();
                g_pollNow = 1;
                SetEvent(g_wake);
            }
            g_keypad = 0;
        }
        InvalidateRect(g_hwnd, NULL, FALSE);
        return;
    }

    switch (id) {
    case B_POWER:
        queueCommand(s.powerOn ? "main/setPower?power=standby" : "main/setPower?power=on");
        lock(); g_state.powerOn = !s.powerOn; g_ui.powerOn = g_state.powerOn; unlock();
        break;
    case B_SETTINGS:
        utf8ToWide(g_ip, g_ipEdit, 32);
        g_keypad = 1;
        break;
    case B_EXIT:
        DestroyWindow(g_hwnd);
        return;
    case B_SRC0: case B_SRC1: case B_SRC2: case B_SRC3: case B_SRC4: case B_SRC5:
        sprintf(cmd, "main/setInput?input=%s", SRC_ID[id - B_SRC0]);
        if (!s.powerOn) queueCommand("main/setPower?power=on");
        queueCommand(cmd);
        lock();
        strcpy(g_state.input, SRC_ID[id - B_SRC0]);
        strcpy(g_ui.input, g_state.input);
        unlock();
        break;
    case B_VOLDN: changeVolume(-1); break;
    case B_VOLUP: changeVolume(+1); break;
    case B_VOLBAR: setVolumeAt(x); break;
    case B_PREV: queueCommand("netusb/setPlayback?playback=previous"); break;
    case B_NEXT: queueCommand("netusb/setPlayback?playback=next"); break;
    case B_PLAY:
        queueCommand("netusb/setPlayback?playback=play_pause");
        lock(); g_state.playing = !s.playing; g_ui.playing = g_state.playing; unlock();
        break;
    case B_MUTE:
        queueCommand(s.mute ? "main/setMute?enable=false" : "main/setMute?enable=true");
        lock(); g_state.mute = !s.mute; g_ui.mute = g_state.mute; unlock();
        break;
    case B_NOWPLAYING:
    case B_NONE:
        /* tap on blank area / now playing: refresh right away */
        g_pollNow = 1;
        SetEvent(g_wake);
        break;
    }
    InvalidateRect(g_hwnd, NULL, FALSE);
}

/* ------------------------------------------------------------------ */
/* Window                                                               */
/* ------------------------------------------------------------------ */
static void restoreTaskbar(void)
{
    if (g_taskbar) { ShowWindow(g_taskbar, SW_SHOW); g_taskbar = NULL; }
}

static LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg) {
    case WM_PAINT:
        paint(hwnd);
        return 0;
    case WM_ERASEBKGND:
        return 1;
    case WM_SIZE:
        g_w = LOWORD(lp); g_h = HIWORD(lp);
        if (g_w < 100 || g_h < 100) { g_w = 800; g_h = 480; }
        layout();
        InvalidateRect(hwnd, NULL, FALSE);
        return 0;
    case WM_APP_STATE:
        lock(); g_ui = g_state; unlock();
        InvalidateRect(hwnd, NULL, FALSE);
        return 0;
    case WM_LBUTTONDOWN: {
        int x = (short)LOWORD(lp), y = (short)HIWORD(lp);
        g_pressed = hitTest(x, y);
        SetCapture(hwnd);
        if (g_pressed == B_VOLUP || g_pressed == B_VOLDN) {
            /* act immediately, then auto-repeat while held */
            doAction(g_pressed, x);
            g_repeatCount = 0;
            SetTimer(hwnd, TIMER_REPEAT, 450, NULL);
        } else if (g_pressed == B_VOLBAR) {
            doAction(g_pressed, x);
        }
        InvalidateRect(hwnd, NULL, FALSE);
        return 0;
    }
    case WM_MOUSEMOVE:
        if (g_pressed == B_VOLBAR && (wp & MK_LBUTTON))
            setVolumeAt((short)LOWORD(lp));
        return 0;
    case WM_TIMER:
        if (wp == TIMER_REPEAT && (g_pressed == B_VOLUP || g_pressed == B_VOLDN)) {
            if (g_repeatCount++ == 0) SetTimer(hwnd, TIMER_REPEAT, 150, NULL);
            doAction(g_pressed, 0);
        }
        return 0;
    case WM_LBUTTONUP: {
        int x = (short)LOWORD(lp), y = (short)HIWORD(lp);
        int was = g_pressed;
        KillTimer(hwnd, TIMER_REPEAT);
        ReleaseCapture();
        g_pressed = B_NONE;
        if (was != B_VOLUP && was != B_VOLDN && was != B_VOLBAR) {
            if (was == hitTest(x, y)) doAction(was, x);
        }
        InvalidateRect(hwnd, NULL, FALSE);
        return 0;
    }
    case WM_KEYDOWN:
        if (wp == VK_ESCAPE) {
            if (g_keypad) { g_keypad = 0; InvalidateRect(hwnd, NULL, FALSE); }
            else DestroyWindow(hwnd);
        }
        return 0;
    case WM_ACTIVATE:
        /* hide the shell taskbar while we're in front (nav devices often
           have one); give it back when we lose focus or exit */
        if (LOWORD(wp) != WA_INACTIVE) {
            HWND tb = FindWindowW(L"HHTaskBar", NULL);
            if (tb) { ShowWindow(tb, SW_HIDE); g_taskbar = tb; }
        } else {
            restoreTaskbar();
        }
        return 0;
    case WM_DESTROY:
        restoreTaskbar();
        g_quit = 1;
        SetEvent(g_wake);
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

#ifdef _WIN32_WCE
int WINAPI WinMain(HINSTANCE hInst, HINSTANCE hPrev, LPWSTR cmdLine, int nShow)
#else
int WINAPI wWinMain(HINSTANCE hInst, HINSTANCE hPrev, LPWSTR cmdLine, int nShow)
#endif
{
    WNDCLASSW wc;
    MSG m;
    WSADATA wsa;
    HANDLE mutex, thread;
    int scrW, scrH, fontBase;
    (void)hPrev; (void)cmdLine;

    /* single instance: bring the existing window to front */
    mutex = CreateMutexW(NULL, FALSE, L"YamaRemoSingleInstance");
    if (mutex && GetLastError() == ERROR_ALREADY_EXISTS) {
        HWND w = FindWindowW(APP_CLASS, NULL);
        if (w) SetForegroundWindow(w);
        return 0;
    }

    WSAStartup(MAKEWORD(2, 2), &wsa);
    InitializeCriticalSection(&g_cs);
    g_wake = CreateEventW(NULL, FALSE, FALSE, NULL);
    memset(&g_state, 0, sizeof(g_state));
    g_state.maxVolume = 100;
    g_ui = g_state;
    loadSettings();

    scrW = GetSystemMetrics(SM_CXSCREEN);
    scrH = GetSystemMetrics(SM_CYSCREEN);
#ifndef _WIN32_WCE
    scrW = 800; scrH = 480;   /* desktop test build: emulate the device */
#endif
    g_w = scrW; g_h = scrH;
    fontBase = scrH < scrW * 480 / 800 ? scrH * 800 / 480 : scrW;  /* width-equivalent */
    g_fHuge  = makeFont(fontBase * 56 / 800, 1);
    g_fBig   = makeFont(fontBase * 34 / 800, 1);
    g_fMid   = makeFont(fontBase * 24 / 800, 1);
    g_fSmall = makeFont(fontBase * 20 / 800, 0);
    layout();

    memset(&wc, 0, sizeof(wc));
    wc.lpfnWndProc = wndProc;
    wc.hInstance = hInst;
    wc.lpszClassName = APP_CLASS;
    wc.hbrBackground = NULL;
    RegisterClassW(&wc);

#ifdef _WIN32_WCE
    g_hwnd = CreateWindowExW(0, APP_CLASS, APP_TITLE, WS_POPUP | WS_VISIBLE,
                             0, 0, scrW, scrH, NULL, NULL, hInst, NULL);
#else
    {
        RECT r; r.left = 0; r.top = 0; r.right = scrW; r.bottom = scrH;
        AdjustWindowRect(&r, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU, FALSE);
        g_hwnd = CreateWindowExW(0, APP_CLASS, APP_TITLE,
                                 WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_VISIBLE,
                                 CW_USEDEFAULT, CW_USEDEFAULT, r.right - r.left, r.bottom - r.top,
                                 NULL, NULL, hInst, NULL);
    }
#endif
    if (!g_hwnd) return 1;
    ShowWindow(g_hwnd, nShow);
    UpdateWindow(g_hwnd);
    SetForegroundWindow(g_hwnd);

    thread = CreateThread(NULL, 0, workerProc, NULL, 0, NULL);

    while (GetMessageW(&m, NULL, 0, 0) > 0) {
        TranslateMessage(&m);
        DispatchMessageW(&m);
    }

    g_quit = 1;
    SetEvent(g_wake);
    if (thread) {
        WaitForSingleObject(thread, 4000);
        CloseHandle(thread);
    }
    restoreTaskbar();
    WSACleanup();
    if (mutex) CloseHandle(mutex);
    return 0;
}
