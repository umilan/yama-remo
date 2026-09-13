# yama-remo

A local web remote for Yamaha MusiCast speakers (built/tested against the [NX-N500](https://europe.yamaha.com/en/audio/home-audio/products/speakers/nx-n500/)), plus research notes on building a physical rotary-encoder remote for the same speaker without soldering.

## What's here

- **`musiccast-remote.html`** — the actual remote. A self-contained, single-file HTML/CSS/JS page (no build step, no dependencies). Talks directly to the speaker's local **Yamaha Extended Control (YXC)** HTTP API from your browser.
  - Switch sources (AirPlay / AUX / Bluetooth / USB)
  - Volume up/down via a circular dial-style control
  - Play/pause, next track
  - Power on/standby, with an optional "remember last state" mode that restores the volume/source it had before standby instead of a fixed preset
  - Now-playing song/artist, kept live via periodic polling and refreshed after playback commands or a tap on any blank part of the screen
  - Settings drawer: theme (Dark/Light/System), IP address, mute, max-volume/step config, full source list with custom input + "discover from speaker," and an activity log — settings persist across reloads
  - Advanced: a full-screen device features browser (`system/getFeatures`) — real inputs/sound programs become one-tap buttons, everything else shown read-only
- **`proxy.py`** — optional local proxy (Python stdlib only) that fixes the CORS limitation below, so status and now-playing actually populate instead of staying blind. See "Getting live status / song info" below.
- **`docs/ready-made-remote-options.md`** — research on hardware remote options (M5Stack Dial, IKEA TRADFRI + ESP32-C6 Zigbee gateway, etc.) and the design history of the web controller.
- **`docs/musiccast-api-command-reference.md`** — a fairly complete reference of the YXC HTTP API endpoints (zone/power/volume, NetUSB/playback, system, multi-room distribution), with notes on which are actually likely to apply to the NX-N500.
- **`docs/musiccast-web-controller-changelog.md`** — running changelog for `musiccast-remote.html`.

## Using the web remote

1. Open `musiccast-remote.html` directly in a browser (double-click it, or drag it into a browser window). It must be opened as a **local file, not hosted over HTTPS** — see "Why a plain file" below.
2. Tap the gear icon (Settings) and enter your speaker's IP address on your LAN.
3. Close Settings and start controlling the speaker — Power, source buttons, the volume dial, and play/pause/next all send commands immediately.

### Why a plain HTML file, not a hosted page

The MusicCast API is plain, unencrypted local HTTP with no auth — by design, since it's LAN-only. Browsers block a page served over HTTPS from fetching plain HTTP resources ("mixed content"), so a page hosted on any HTTPS service could never actually reach the speaker. Opening the file directly avoids that entirely.

### Using it on your phone

The hosted copy at `https://umilan.github.io/yama-remo/` **cannot control your speaker, and never will** — it's a preview/download page only. That's the same HTTPS→plain-HTTP mixed-content block described above, and it's enforced by the browser before any request leaves the device, on mobile exactly as on desktop. No amount of client-side JavaScript can work around it, so don't use that link for actual control.

Downloading the file and opening it locally works in principle, but is clunky on a phone — there's no simple double-click, and iOS in particular makes opening a local HTML file awkward. The practical fix is to host the file over **plain HTTP** on something that's always on and already on your LAN, so the phone's browser talks HTTP-to-HTTP with nothing to block:

- **Synology NAS:**
  1. Log into DSM, open **Package Center**, search for **Web Station**, and install it (this sets up a lightweight web server and creates a `web` shared folder).
  2. Open the **Web Station** app once installed — on DSM 7 confirm there's a Web Service Portal bound to HTTP port 80 with its document root set to the `web` shared folder (this exists by default); on DSM 6 it serves `web` on port 80 automatically, no extra config needed.
  3. Open **File Station** and copy `musiccast-remote.html` into that `web` shared folder.
  4. Find the NAS's LAN IP (Control Panel → Network → Network Interface — it's the same IP you use to reach DSM).
  5. From your phone, on the same Wi-Fi, open `http://<nas-ip>/musiccast-remote.html` and add it to your home screen. If port 80 is already taken by something else on the NAS, use whatever port the Web Station portal is actually bound to instead (`http://<nas-ip>:<port>/musiccast-remote.html`).
- **QNAP NAS:** App Center → Web Server, drop `musiccast-remote.html` into its web root, and open `http://<nas-ip>/musiccast-remote.html` from your phone.
- **Anything else** (Raspberry Pi, an always-on Mac/PC, a router with USB storage, etc.) — from the folder containing the file, run:
  ```
  python3 -m http.server 8080
  ```
  then open `http://<that machine's LAN IP>:8080/musiccast-remote.html` from your phone. Keep the process running (or set it up as a startup service) so it stays reachable.

Bookmark or "Add to Home Screen" the resulting `http://` URL on your phone instead of the github.io link — connectivity then works exactly as it does opening the file directly on desktop.

### Known limitation: CORS

The speaker's API sends no `Access-Control-Allow-Origin` header, so the browser can't *read* JSON responses (`getStatus`, `getPlayInfo`, `getFeatures`) even though the command still reaches and executes on the speaker. The page tries a normal `fetch()` first and silently falls back to a fire-and-forget request if that's blocked — so buttons (volume, power, source, play/pause) work either way, but live status/now-playing readback only populates when the browser happens to allow the read. In practice, whether it happens to allow the read varies by device/browser — on some it works, on others every request comes back blind. Any request that does reach the speaker — readable or not — is treated as proof of connectivity, so the "Connected" / "Not connected" indicator reflects real reachability rather than just the background status poll. `proxy.py` (below) is the actual fix for this, not a workaround around it — reading cross-origin JSON in a browser without server cooperation isn't something client-side code can do.

### Getting live status / song info: the local proxy

If status/now-playing never populates for you (stuck on "Not playing" / "Connected" even while something's audibly playing), that's the CORS limitation above — confirm it via Settings → Show log, which logs "Speaker reachable, but the browser can't read its response (CORS)" when this is happening.

The fix is `proxy.py`, a single stdlib-only Python script:

```
python3 proxy.py         # serves on port 8080 by default; pass a port to override
```

Then open `http://localhost:8080/musiccast-remote.html` (or `http://<this-machine's-LAN-IP>:8080/musiccast-remote.html` from your phone, same Wi-Fi). It serves the app's files and relays YXC requests server-side — where there's no browser CORS enforcement at all — handing the JSON back to the page same-origin so the browser actually reads it. The page detects the proxy automatically and only reroutes through it when present; opening the file directly, or hosting it on a plain static server (the NAS/`http.server` options above, with no proxy), behaves exactly as before.

Leave it running (or set it up as a startup service, same idea as the NAS options above) for it to stay available.

## Hardware remote (not yet built)

See `docs/ready-made-remote-options.md` for the full writeup. Short version: the recommended path is an **M5Stack Dial** (ESP32-S3, built-in rotary encoder + touchscreen, no soldering) flashed via Arduino IDE with a small sketch that reads the encoder and fires HTTP requests straight at the YXC API — no smart-home hub required. An alternative reusing an existing IKEA TRADFRI remote via a small ESP32-C6 Zigbee-gateway board is also documented, with trade-offs.

## Speaker

[Yamaha NX-N500](https://europe.yamaha.com/en/audio/home-audio/products/speakers/nx-n500/) — a compact 2-channel MusicCast wireless speaker. The full YXC command set spans Yamaha's whole product range (AVRs, soundbars, etc.); run `system/getFeatures` against your own unit to get the ground truth on exactly what it supports (valid input IDs, volume range, available functions).
