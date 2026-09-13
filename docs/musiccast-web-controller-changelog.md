# musiccast-remote.html — change log

Full design rationale, CORS/mixed-content caveats, and API details live in `ready-made-remote-options.md`. This file is just a running changelog since that doc was getting long.

## 2026-09-14, system theme by default + sticky settings header
- Theme now defaults to **System** (was Dark) on first run and on "Reset to defaults", so a fresh install follows the OS's light/dark preference instead of forcing dark.
- The Settings drawer's header ("Settings" title + close button) is now sticky — stays pinned while the rest of the drawer's content scrolls underneath it, instead of scrolling away with everything else. Same fix applies to the Device Features screen's header, since it shares the same markup/class and can also get long. Verified by scrolling the drawer in headless Chrome.

## 2026-09-14, consistent Prev/Next icon style
- Prev/Next used a hand-drawn filled-triangle icon (left over from before lucide icons were introduced) while every other icon in the same row/app (Shuffle, Play/Pause, Repeat, Power, Settings, +/-) is lucide's thin outline style — most noticeable in the txt-in-middle transport row where all five sit side by side. Switched Prev (both layouts) and Next (txt-in-middle's copy; the radial one already used lucide) to lucide's `skip-back`/`skip-forward`, matching the rest.

## 2026-09-14, txt-in-middle volume bar: per-step dots, value bubble, click-to-jump
- The dashed volume bar now renders exactly 100 dots — one per raw volume step on the NX500 — instead of a fixed 24, so each dot corresponds to an exact, addressable value rather than an approximate percentage band.
- Interacting with volume in this layout (the +/- buttons, or the bar itself) now shows the numeric value in a small bubble above the bar for 3 seconds, then fades out. Implemented by folding into the existing `showVolumeOverlay()` (already called from every volume-changing path — nudge, scroll, radial-arc-click), so it stays in sync for free rather than needing separate wiring per interaction.
- Clicking/tapping directly on the dashed line jumps volume straight to that exact step — the linear counterpart to the radial layout's click-on-the-ring gauge, reusing the same `setVolumeAbsolute()`. Verified all three (dot count, value bubble, click-to-jump) via headless Chrome, including a simulated click at 75% along the bar landing exactly on step 75.

## 2026-09-14, new "Txt-in-middle" UI layout
- New **Layout** setting (Radial / Txt-in-middle), right below Theme, persisted like every other setting. The existing dial-based UI is preserved exactly as-is under "Radial" (default); "Txt-in-middle" is a new alternate presentation of the same underlying state, built from a provided layout mockup:
  - Top bar: source switcher becomes a row of short text tabs (Aux/AirPlay/BT/Radio/Opto/USB — a curated subset of the full source list, matching the mockup; the rest stay reachable by switching back to Radial) instead of a dropdown.
  - Center: artist (bold) + track (dimmed, truncated) for playable sources; a big source name (e.g. "AUX") for line-in sources (aux/optical/usb) — reuses the existing line-in detection.
  - Transport: new Prev/Shuffle/Play-Pause/Repeat/Next row for playable sources, or just Mute for line-in — replacing the album-art tap gesture with a real, dedicated play/pause button that swaps icon based on actual playback state.
  - Volume: a tick-bar (24 segments) instead of the radial dial, driven by the same volume state.
  - Both layouts read from and write to the exact same JS state (volume, mute, source, playback) — switching layouts mid-session never loses sync.
- Added real **Shuffle/Repeat** toggle support (`netusb/toggleShuffle`/`toggleRepeat`) — previously not implemented at all. Active-state reflection (button highlights on) depends on `shuffle`/`repeat` fields in `netusb/getPlayInfo` per community documentation, not verified live; if a real device uses different field names, the toggles still work, they just won't show as "active" until confirmed.
- Fixed two real bugs found while building this: (1) an element with both a `hidden` attribute and a class-based `display:flex` rule ignored `hidden` (author CSS beats the UA default at equal specificity) — caused two control rows to show at once; (2) `min-width:0` was needed at every nested flex level (`.content` down to `.txt-controls`), not just on individual buttons, to prevent one row's content from inflating everything above it. Verified the fix, and both mockup states (playing-track and line-in), by screenshotting via headless Chrome.

## 2026-09-13, Net Radio favorite-station navigation + MusicCast app deep link
- Previous/Next now cycle through saved **Net Radio presets** instead of sending a meaningless track-skip, specifically when the active source is `net_radio` — other sources (USB, server, Spotify, etc.) keep the existing track-previous/next behavior unchanged. Presets are fetched once from `netusb/getPresetInfo`, filtered to populated Net Radio entries, and cached; the cache is cleared whenever the speaker IP changes (a different unit may have different presets).
  - **Caveat**: `netusb/getPresetInfo`'s exact response shape (`preset_info` array with `input`/`text`/`attribute` fields) is based on community/vendor documentation, not verified against a live device from this session — if it comes back in an unexpected shape, the app logs the raw response (Settings → Show log) instead of silently doing nothing, so it's diagnosable on first real use.
  - No in-app way to *save* a preset yet — that still has to be done via the official Yamaha MusicCast app; this only recalls/cycles what's already saved there.
- Added an **"Open MusicCast app"** link in Settings → Advanced, using Yamaha's iOS deep link (`jp.co.yamaha.avkk.musiccastcontroller://`) for anything outside this remote's scope. Only resolves to anything on iOS with the official app installed; harmless no-op elsewhere.

## 2026-09-13, radically reduced color palette
- Collapsed the whole palette down to exactly four values per theme: black/white at 100% (text, headings), 50% (secondary/marginal text, subheadings, inactive states), 10% (all backgrounds and hover backgrounds), plus one accent (purple, `#c400ff`) for the volume dial and selection/active states. `--fg-dim` merged into `--fg-muted`; `--surface`/`--surface-2`/`--surface-3`/`--border`/`--handle`/`--hover-weak`/`--hover-strong`/`--hover-active`/`--track`/`--art-placeholder-bg` all collapsed into one `--surface`.
- Removed the volume dial's two-color gradient (`--accent` → `--accent-2`) in favor of a single solid `var(--accent)` stroke; `--accent-2` and the unused `--active-border` are gone.
- Dropped the separate danger/warn/ok status colors from the activity log and features-status banner: "confirmed/success" now uses the one accent color, "failed" uses full-strength `--fg`, and "warning" (e.g. CORS-blind sends) no longer gets its own color — it reads the same as a normal log line.
- Known side effect: since inputs/selects/buttons now share the exact same background tier as their parent drawer/card, they no longer read as visually distinct boxes the way they did with separate surface shades before — everything is flatter by design.
- Verified by screenshotting the main screen and Settings drawer in both themes (headless Chrome) after the change.

## 2026-09-13, dark/light theme switch
- New **Theme** setting (Dark / Light / System) at the top of the Settings drawer, persisted like every other setting. Converted every hardcoded chrome color (backgrounds, borders, hover states, the gauge's resting track color, input/select fields, the connect screen, the features screen) to CSS custom properties with a light-mode override set, so the whole app actually re-themes rather than just the accent color.
- Deliberately left constant across both themes: the brand accent (magenta/purple), status colors (danger/warn/ok), and the album-art disc's own always-dark treatment (it already darkens real artwork via a brightness filter for overlay-text legibility, so the empty-state placeholder disc and its overlays stay on that same "dark stage" logic regardless of page theme).
- "System" tracks `prefers-color-scheme` live; "Dark"/"Light" force that look regardless of OS setting. Verified by screenshotting the main screen and Settings drawer in both forced themes (headless Chrome) before shipping.

## 2026-09-13, full-screen device features browser
- "Get device features" now opens a dedicated full-screen view instead of just logging raw JSON. It walks the entire `system/getFeatures` response (recursing into `zone[]`, `system`, `netusb`, etc.) and renders it as:
  - **Inputs** and **Sound Programs** as real tappable buttons — collected from every `input_list`/`sound_program_list` found anywhere in the tree (top-level or nested per-zone), deduplicated. Tapping one sends `setInput`/`setSoundProgram` immediately and shows the result inline on the screen (and in the activity log).
  - Everything else (`func_list` capability flags, `range_step` min/max/step, service/account info, etc.) shown read-only, grouped by its path in the response — these have no safe parameterless command to invoke, so they're informational rather than buttons.
- This makes the screen a genuine per-unit capability browser: since `getFeatures` is device-specific, it surfaces exactly what a given speaker actually supports (real input IDs, real sound programs) rather than the app's own hardcoded `SOURCES` list.

## 2026-09-13, volume +/- buttons use real icons
- Volume up/down buttons were the only `.icon-btn`s using a plain text glyph (`.button-symbol`, a `−`/`+` character) instead of an actual svg icon like every other button, so they didn't pick up the shared `.icon-btn svg` sizing rule. Switched to lucide's `minus`/`plus` icons via the same `replaceIcon()` path already used for Power/Settings/Next, and removed the now-unused `.button-symbol` CSS.

## 2026-09-13, thinner ring + click-to-set volume
- Both `trackArc` and `progressArc` are now 8px wide instead of 22px, for an overall thinner ring.
- Clicking/tapping directly on the ring now jumps volume straight to that position, like a circular slider — instead of only being adjustable via the +/- buttons or a vertical swipe on the album art. Works via a generous radius tolerance around the ring so exact stroke width doesn't matter; taps landing in the open gap at the bottom of the ring are ignored, not clamped to an end.

## 2026-09-13, local proxy for CORS-blind status/song info
- Root-caused a real user report of "no song info at all" (on both Net Radio and AirPlay) to the CORS limitation being a hard, consistent wall in that browser/network, not an intermittent one — confirmed via the app's own log ("Speaker reachable, but the browser can't read its response (CORS)") appearing on every request, never just some.
- Added `proxy.py`: an optional, stdlib-only local Python proxy. It serves the app's files and relays YXC requests server-side (no browser, no CORS enforcement), handing JSON back to the page same-origin so the browser can actually read it. Fixes status/song-info readback for good, rather than working around the symptom.
- `musiccast-remote.html` auto-detects the proxy (a `/yxc-health` probe at load) and only reroutes YXC calls through it when present, via a new `/yxc/<speaker-ip>/<path>` relay path. Opening the file directly, or hosting it on a plain static server with no proxy, is unaffected — same direct-to-speaker behavior as before.

## 2026-09-13, persisted settings + remember-last-state
- Settings (IP address, speaker, volume limit/increment, power-on volume, and the new "remember last state" toggle) now persist to `localStorage` and survive closing/reopening the page — previously everything reset to the defaults baked into the HTML on every load.
- New **"Remember last state on power-on"** setting. When on, turning the speaker on restores the volume and source it actually had right before standby (captured the moment "Turn off" is pressed) instead of jumping to the fixed "Power-on volume" preset. Off by default, preserving the existing safety behavior of always waking at a known, capped volume. The "Power-on volume" select is disabled while this is on since it no longer applies.

## 2026-09-13, now-playing (song/artist) reliability
- Fixed a bug where `getPlayInfo` (song/artist) was silently never even attempted whenever the preceding `getStatus` call came back CORS-blind — even though the two are independent requests and one being unreadable doesn't mean the other is. Now-playing info is now fetched any time the speaker is reachable at all, not only when `getStatus` itself happened to be readable.
- Song/artist now also refreshes automatically: right after Play/Pause, Previous, or Next (with a short delay so the speaker has time to update its own metadata), and on a 10s poll while connected and powered on — so it reflects track changes made from another remote/app too, not just what was playing at the moment this page connected.
- Tapping anywhere that isn't an actual control (blank background, the now-playing text, gaps in the top bar, etc.) now triggers an immediate full status refresh (song/artist + volume), throttled to once/second. This replaces the narrower previous behavior where only tapping the now-playing text did anything, and only while disconnected.

## 2026-09-12, connection-state fixes
- Any command that actually reaches the speaker (Power, volume, source, playback, mute) now marks the app "Connected" itself, instead of that state only ever coming from a separate periodic status poll. Previously a successful button press and the "Not connected" banner could disagree.
- Removed a redundant, racy behavior where clicking *any* button also fired a parallel `getStatus` check — since basically every action button already sends its own command, that parallel check could resolve after (and overwrite) the button's own successful result. Only tapping the now-playing text (which sends no command of its own) still triggers an explicit connection check.
- "Turn on" / "Turn off" optimistically enables/disables the volume, transport, and source buttons immediately for responsiveness. If that power command doesn't actually reach the speaker, the optimistic change is now rolled back — so the controls never sit "enabled" while the app also says "Not connected".
- The now-playing area distinguishes three states: **"Not connected"** (never successfully reached the speaker), **"Not playing" / "Connected"** (reached it, nothing currently playing), and the real track/artist when something is playing.
- Settings (gear) icon in the top row now matches the size, color, and label style of the other top-row buttons (Power/AirPlay/AUX/BT/USB) instead of being smaller and greyed out.
- Power button's label now reflects state: "Turn off" while on, "Turn on" while off.

## 2026-09-12, earlier pass
- "Song name"/"Artist" placeholders replaced with **"Not playing"** whenever there's no confirmed current track — either no data yet, or `getPlayInfo` reports `playback` as anything other than `play`/`pause` (e.g. `stop`). The artist line is hidden entirely (not just blank) when there's no track.
- Center placeholder icon (shown whenever there's no album art loaded) now mirrors the **currently selected input's icon** — AirPlay/AUX/Bluetooth/USB — instead of a generic music note. A generic note icon remains the fallback before any source is known. Implemented via a small `SOURCE_ICONS` map keyed by input id, applied in `highlightActiveInput()` so it stays in sync everywhere the active source is set (manual click, or a successful `getStatus` read).
- Confirmed AUX is included in that icon map (it already existed as a top-row source button; this ensures it's also one of the swappable center icons).

## Earlier layout fixes (same day)
- Ring/artwork circle is now vertically centered in the available space (was sitting high) — done by making the row that holds it (`.now-row`) a flexible (`flex:1`) child of the page's column layout with `align-items:center`, rather than a fixed-margin block.
- Placeholder icon centering inside the artwork circle made robust (`position:absolute; inset:0` + flex centering) instead of relying on the icon's own path being visually balanced.
- Top row icons (Power, AirPlay, AUX, Bluetooth, USB, Settings) now sit as direct flex siblings with `justify-content:space-between` — previously the four source icons were nested in their own wrapper, which gave them different spacing than their gap to Power/Settings. Verified equal 18px gaps between every pair.

## Still true from before
- Plain HTML file (not a hosted claude.ai page) because the MusicCast API is unencrypted local HTTP and a hosted HTTPS page can't fetch it (mixed content blocking).
- CORS: the speaker's API sends no `Access-Control-Allow-Origin`, so JSON reads (`getStatus`, `getPlayInfo`, `getFeatures`) often can't be read back by the browser even though the command still executes; `<img>` loads for album art aren't subject to that same restriction, which is why art can still show up even when the JSON that names its URL couldn't be read on a given try — but that URL still comes from a read that can fail, so art may simply not appear if the browser blocked it.
- Off state disables (real `disabled` attribute) all transport/volume/source controls; Power and Settings stay usable.
