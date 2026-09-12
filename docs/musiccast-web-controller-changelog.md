# musiccast-remote.html — change log

Full design rationale, CORS/mixed-content caveats, and API details live in `ready-made-remote-options.md`. This file is just a running changelog since that doc was getting long.

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
