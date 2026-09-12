# MusiCast remote — ready-made options research (2026-09-11)

## Goal
Control Yamaha MusiCast speakers (NX-N500) with a physical rotary-encoder remote, avoiding soldering/hardware hacking if possible. Starting point referenced: geekyboi.co.uk Arduino MusicCast article (ESP8266/Wemos D1-mini + rotary encoder + MusicCast's HTTP/JSON "Extended Control API").

**User decision (2026-09-11): wants to avoid running a smart-home hub (Home Assistant etc).** So the recommended path talks to the speaker's API directly from the remote's own firmware, not via HA.

## Recommended path: M5Stack Dial, talking to MusicCast's API directly

**Hardware:** [M5Stack Dial](https://shop.m5stack.com/products/m5stack-dial-v1-1) (v1.1) — ESP32-S3 board, fully assembled, built-in rotary encoder + 1.28" round touchscreen + speaker + battery option. No soldering, no assembly — just buy it (~$25-30) and plug in USB-C.
- Official Arduino IDE support and a rotary-encoder example straight from M5Stack's own docs: https://docs.m5stack.com/en/arduino/m5dial/encoder and flashing guide https://docs.m5stack.com/en/arduino/m5dial/program

**Software approach:** Flash it via Arduino IDE (this is the "build one using Arduino" the user wanted, minus any circuit building) with a sketch that:
1. Reads the rotary encoder (M5Stack's own library handles this — no bit-banging needed).
2. On rotation/press, fires a plain local HTTP GET/POST directly to the NX-N500's IP using Yamaha's **Extended Control (YXC) API** — unauthenticated, local-network only, e.g. `GET http://<speaker-ip>/YamahaExtendedControl/v1/main/setVolume?volume=50`, `.../main/setPlayback?playback=play_pause`, etc.
   - Full spec PDFs: [Basic](https://community.symcon.de/uploads/short-url/7r8QTdkYFNfJVJmKbtqvdleuzKt.pdf), [Advanced](https://community.symcon.de/uploads/short-url/vRXaJXAn6vI2DSQYMHF0aqLbdir.pdf)
   - Reference implementations to crib request formats from: [pyamaha](https://github.com/rsc-dev/pyamaha) (Python), [yamaha-yxc-nodejs](https://github.com/foxthefox/yamaha-yxc-nodejs) (Node), [musiccast2mqtt](https://github.com/jonaseickhoff/musiccast2mqtt)
3. Optionally shows volume/track on the round screen (M5Dial has one — geekyboi's project only had a basic screen).

No existing turnkey "M5Dial + MusicCast" project was found on GitHub — this still means writing a small Arduino sketch (a few dozen lines: encoder read + HTTP GET), but zero soldering and zero hub/Home Assistant dependency. It's the closest match to "ready device, pair without soldering, still Arduino-flavored."

## Alternative: reuse an IKEA TRADFRI (or STYRBAR) remote via a single-board Zigbee "mini-coordinator" (2026-09-11)

User already has/likes the IKEA TRADFRI remote (E1524/E1810 — center toggle button, brightness up/down, left/right arrow buttons) and asked if it can be connected. Short answer: **not directly** — the remote only speaks Zigbee and MusicCast only speaks local HTTP, so something still has to translate, same underlying issue as the Hue Tap Dial idea from the first round of research. But it does NOT require a full hub (Home Assistant/Zigbee2MQTT) — a single small board can act as its own private Zigbee coordinator *and* the Wi-Fi bridge to the speaker, with no separate server:

- **Existing open-source reference project, very close to a direct match:** [enderekici/esp32c6-zigbee-gateway](https://github.com/enderekici/esp32c6-zigbee-gateway) — runs on a **Waveshare ESP32-C6-LCD-1.47** board (~$15-20, fully assembled, no soldering, has its own screen). It forms its own Zigbee network on-chip (`esp-zigbee-sdk`, no external hub), auto-pairs an IKEA remote (built for STYRBAR; README explicitly notes it "also works as a reference for other Tradfri remotes"), decodes all buttons including long-press/release, and fires a plain **HTTP POST webhook** (`{"device","event"}`) to a listener on the LAN for each button/gesture. Confirmed the ESP32-C6 runs Wi-Fi and Zigbee **concurrently on one radio** via Espressif's coexistence framework — this is the nontrivial part the project already solved.
- **Adapting it:** swap the included Python webhook listener for direct calls to the MusicCast Extended Control API (or just point the webhook at a tiny always-on script that translates `{"device","event"}` → the right `setVolume` / `setInput` / `setPower` GET request). The remote-decoding and Zigbee-coordinator logic is reusable as-is; only the "what happens on each event" mapping needs to change.
- **Button mapping for MusiCast, per the user's requirements** (switch AUX/AirPlay, volume, play/stop, optional power off), using TRADFRI E1810's confirmed Zigbee2MQTT actions:
  - `toggle` (center button) → power on/standby
  - `brightness_up_click/hold` / `brightness_down_click/hold` → volume up/down
  - `arrow_right_click` → play/pause
  - `arrow_left_click` → toggle source between AUX and AirPlay
  - All 4 requirements fit on the 5-button remote with nothing left unmapped.

**Trade-offs vs. the M5Dial path:**
- Cost/parts are similar (~$15-30 either way), but this is two physical pieces (remote + a board that has to live somewhere on Wi-Fi) rather than one integrated device.
- The TRADFRI remote's volume control is step/hold-based (brightness_up/down "move" commands), not a true free-spinning rotary encoder — the user's stated preference was "ideally with rotary encoder," which the M5Dial satisfies literally and this doesn't.
- Firmware complexity is higher: this requires standing up a Zigbee coordinator role and pairing/binding logic, vs. the M5Dial's much simpler "read encoder, fire HTTP GET." The existing GitHub project substantially de-risks this (it's adapt-the-webhook-target rather than build-Zigbee-from-scratch), but it's still a bigger lift than the M5Dial sketch.
- Upside: reuses hardware the user may already own, and the Waveshare board's built-in screen could show status like the M5Dial would.

## Other options considered, and why they're set aside given "no hub" constraint

These all require a smart-home hub (e.g. Home Assistant) as the glue layer, since Yamaha doesn't support pairing third-party remotes directly:
- **Philips Hue Tap Dial Switch** (Zigbee rotary+buttons) — best off-the-shelf remote if a hub is ever added later; needs Zigbee coordinator + HA/Z2M + the official [MusicCast HA integration](https://www.home-assistant.io/integrations/yamaha_musiccast/) + a blueprint.
- **Moes Zigbee Smart Knob** — cheaper Zigbee alternative, same hub requirement.
- **Standalone Zigbee2MQTT (no Home Assistant)** — z2m itself doesn't require HA, just a Zigbee USB dongle (~$15) + an MQTT broker (Mosquitto) + a small script translating MQTT messages to MusicCast HTTP calls. Lighter than full HA but still "a service running somewhere," unlike the single-board Zigbee-coordinator approach above.

Not recommended regardless:
- **IKEA SYMFONISK Sound Remote** — original (true rotary dial) discontinued; Gen 2 dropped the wheel for buttons.
- **IKEA BILRESA** (the remote the user originally linked) — actually a Matter-over-Thread remote for smart-lighting color control, needs IKEA's DIRIGERA hub; wheel is built for color mixing, not volume, and still needs a hub either way.

## Web controller (built 2026-09-11, redesigned 2026-09-12, now-playing view added 2026-09-12)

Requested: a single-page web app with all commands on one page and an IP-address field in the UI, so the speakers can be controlled from any browser on the LAN without any app.

**File:** `musiccast-remote.html` (self-contained, no external dependencies).

**Visual design v1 (2026-09-12):** rebuilt to match a reference design the user provided — full-black background, a circular SVG "dial" gauge with a magenta/purple gradient arc (300° sweep, 60° gap at the bottom) showing volume as a percentage, a top icon row (Power · AirPlay · AUX · BT · USB, active source highlighted in accent color), and a bottom transport row. A **Settings** drawer (gear icon, slides up from the bottom) holds the IP field, explicit Power on/Standby, Mute, max-volume/step config, full source list + custom input + discover, and the activity log.

**Visual design v2 (2026-09-12) — now-playing view:** user supplied a further reference showing the ring surrounding an album-art circle instead of a bare percentage, with the +/− volume buttons moved beside the ring (not below it), and a bottom row split between song title/artist (left) and skip-next + play/pause (right). Rebuilt to match:
- The dial's center is now a circular art frame: shows a music-note placeholder by default, swaps to the real cover via `netusb/getPlayInfo`'s `albumart_url` when the browser can read it (loaded straight into an `<img>`, which — unlike `fetch()` — isn't blocked by the API's missing CORS headers for basic image display; the JSON read that supplies the URL in the first place still can be).
- Track title / artist text populate from the same `getPlayInfo` call, defaulting to literal "Song name" / "Artist" placeholders (matching the reference mockup's own placeholder text) until real data comes through.
- Added a "next track" button (`setPlayback=next`) next to play/pause.
- BT and USB switched from text labels to line-icons (Bluetooth glyph, a simple generic USB/connector glyph) matching AirPlay's icon-only style, per explicit request.
- Active source highlight explicitly uses the accent magenta (already the case, confirmed still correct after the layout change).
- Off state: no numeric/dash placeholder in the center — shows literal "OFF" over a dimmed, desaturated album-art circle. All interactive controls (volume, play/pause, next, source icons) get the HTML `disabled` attribute (not just visual dimming) while off, so they're genuinely inert, not just gray; Power and Settings stay enabled so the speaker can be turned back on.

**Why it's a plain HTML file and not a hosted (claude.ai) page:** the MusicCast API is plain HTTP (no TLS) and only reachable on the local network. A page hosted on claude.ai is served over HTTPS, and browsers hard-block "mixed content" (an HTTPS page fetching an HTTP resource) with no user override — so a hosted version could never actually reach the speaker. Opening the local HTML file directly (double-click / drag into a browser) avoids that, since the page itself isn't HTTPS.

**Known limitation — CORS:** the MusicCast API sends no `Access-Control-Allow-Origin` header, so the browser blocks *reading* JSON responses cross-origin (album art images are an exception — see above). The page tries a normal `fetch()` first, and if that's blocked, silently retries with `mode: "no-cors"` — the command still reaches and executes on the speaker, it just can't be read back for confirmation. Practical effect: commands (volume, power, source, play/pause, next) work either way; live status/readback (current volume, active source, now-playing text, "discover inputs") only populates if the browser happens to allow the read — the UI otherwise relies on optimistic local state updated from the user's own taps, backed by a connection-state check that treats any successfully-delivered command as proof the speaker is reachable. Noted in the drawer's footer text and the activity log.

## Open follow-ups
- Confirm NX-N500 responds to the same YXC endpoints as other MusicCast devices (should — it's a standard MusicCast product), and confirm its actual input ID for AUX (guessed as `"aux"` in the web controller — may need adjusting to `"aux1"`/`"audio1"`/`"line_in"` once tested against the real device, or use the controller's "Discover inputs" button if CORS allows it through).
- Decide screen UI for the M5Dial hardware remote (just volume % vs. track info) — affects how much of the Arduino sketch is needed.
- Decide between M5Dial (true rotary encoder, simpler firmware, one integrated device) vs. TRADFRI + ESP32-C6 Zigbee gateway (reuses owned remote, more firmware complexity, button-based not dial-based volume) as the actual hardware remote to build.
- Full YXC command reference (all endpoints, cross-checked against the pyamaha library) is saved separately at `docs/musiccast-api-command-reference.md`.
