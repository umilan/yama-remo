# Yamaha Extended Control (YXC) API — full command reference (2026-09-12)

Base URL for every command: `http://<speaker-ip>/YamahaExtendedControl/v1/<category>/<command>?<params>`. All plain HTTP GET (a few network-setup ones are POST, noted below), unauthenticated, LAN-only. Every response includes `response_code` (0 = success).

Cross-checked against: the official YXC Basic + Advanced spec PDFs, and the [pyamaha](https://github.com/rsc-dev/pyamaha) Python library's endpoint list (independent implementation — used to sanity-check the PDF extraction, since some entries came from automated PDF summarization and could contain errors).

**Important caveat specific to the NX-N500:** this is the full spec across Yamaha's whole MusicCast range (AV receivers, soundbars, wireless speakers). The NX-N500 is a compact 2-channel wireless speaker — it will NOT support the AVR/soundbar-oriented commands (HDMI, multi-zone, tuner, CD, surround decoding, etc.), only a subset. The authoritative way to know exactly what it supports is to call `system/getFeatures` and `system/getAdvancedFeatures` against the real unit once you have its IP — those return the device's actual capability list, valid input IDs, and volume range. Everything below is the full menu; treat anything marked "(AVR-only, unlikely on NX-N500)" as probably not applicable.

## Zone (`/v1/main/...` — NX-N500 only has a "main" zone, no zone2/3/4)

Core, definitely relevant:
- `getStatus` — power, volume, mute, input, sound program, current state
- `getSignalInfo` — current input signal info
- `setPower?power=on|standby|toggle`
- `setSleep?sleep=0|30|60|90|120` — sleep timer (minutes)
- `setVolume?volume=<int>&step=<int>` — also accepts `volume=up|down&step=<int>` for relative steps
- `setMute?enable=true|false`
- `setInput?input=<id>&mode=autoplay_disabled` — valid `<id>` values come from `getFeatures`' input_list
- `getSoundProgramList` / `setSoundProgram?program=<id>`
- `prepareInputChange?input=<id>` — pre-warms an input before switching (smoother transitions)

Audio processing (may or may not exist on a compact 2ch speaker — check getFeatures):
- `setToneControl?mode=<manual|auto|bypass>&bass=<val>&treble=<val>`
- `setEqualizer?mode=<...>&low=<val>&mid=<val>&high=<val>`
- `setBalance?balance=<int>`
- `setDirect?enable=true|false`, `setPureDirect?enable=true|false`
- `setEnhancer?enable=true|false`
- `setClearVoice?enable=true|false`
- `setBassExtension?enable=true|false`

(AVR-only, unlikely on NX-N500):
- `set3dSurround?enable=true|false`
- `setDialogueLevel?level=<int>`, `setDialogueLift?lift=<int>`
- `setSubwooferVolume?volume=<int>` (unless it treats its internal woofer this way — worth testing)
- `setSurroundDecoderType?decoder=<...>`
- `setActualVolume?mode=<db|numeric>&value=<float>`
- `setAudioSelect?audio=<auto|hdmi|coax_opt|analog|...>`
- `recallScene?scene=<int>`, `setContentsDisplay?enable=true|false`
- `controlCursor?action=<up|down|left|right|select|return>`, `controlMenu?action=<...>` (on-screen menu navigation — needs HDMI output, N/A here)
- `setLinkControl?control=<...>`, `setLinkAudioDelay?delay=<...>`, `setLinkAudioQuality?mode=<...>` (wired MusicCast Link between AVR zones)

## NetUSB (`/v1/netusb/...`) — covers AirPlay, DLNA/server, USB, streaming services. This is the category you'll use most for play/pause and source browsing.

- `getPlayInfo` — current track/playback state (playback status, artist/track/album, elapsed time)
- `getPresetInfo` — stored presets
- `setPlayback?playback=play|stop|pause|play_pause|previous|next|fast_reverse_start|fast_reverse_end|fast_forward_start|fast_forward_end`
- `toggleRepeat` / `toggleShuffle` — cycle repeat/shuffle modes (confirmed in both spec and pyamaha)
- `getListInfo?start=<int>&end=<int>` — browse content lists (e.g. DLNA server folders)
- `setListControl?type=select|return|move&index=<int>` — navigate into/out of a list item
- `setSearchString?keyword=<text>`
- `recallPreset?zone=main&num=<int>` / `storePreset?num=<int>`
- `getAccountStatus`, `switchAccount` — for services that need login (Spotify Connect etc. — AirPlay/Bluetooth don't)
- `getServiceInfo`

Note: some third-party docs/implementations also mention `setRepeat?mode=off|one|all` and `setShuffle?mode=off|on` as direct (non-toggle) setters; unlike the toggle versions these weren't independently confirmed in pyamaha's list, so test against your unit before relying on them.

## System (`/v1/system/...`)

Info/discovery:
- `getDeviceInfo` — model name, API/firmware version
- `getFeatures` — **the most useful one**: full list of what this specific unit supports (zones, inputs, functions)
- `getAdvancedFeatures`
- `getNetworkStatus`, `getFuncStatus`, `getLocationInfo`
- `getNameText?id=<...>` / `setNameText?id=<...>&text=<...>` (POST) — rename zones/inputs
- `getStereoPairInfo` — if paired with another NX-N500 as a stereo pair

Bluetooth (relevant — NX-N500 supports Bluetooth input):
- `getBluetoothInfo`, `getBluetoothDeviceList`, `updateBluetoothDeviceList`
- `connectBluetoothDevice?address=<12-hex-digit-mac>`, `disconnectBluetoothDevice`
- `setBluetoothStandby?enable=true|false`, `setBluetoothTxSetting?enable=true|false`

Network setup (POST, one-time config — not remote-control territory):
- `setWiredLan`, `setWirelessLan`, `setWirelessDirect`, `setIpSettings`, `setNetworkName`, `setAirPlayPin`
- `getMacAddressFilter` / `setMacAddressFilter`
- `getNetworkStandby` / `setNetworkStandby?standby=off|on|auto`

General device behavior:
- `setAutoPowerStandby?enable=true|false`
- `setDimmer?value=-1|0..N` — display/LED brightness (`-1` = auto)
- `setPartyMode?enable=true|false` — MusicCast Party Mode (plays same audio on all party-enabled speakers — likely relevant for multi-speaker setups)
- `sendIrCode?code=<8-hex-digit>` — makes the unit emit an IR code itself (not for receiving remote input)
- `requestNetworkReboot`, `requestSystemReboot`

(AVR-only, unlikely on NX-N500): `setSpeakerA`/`setSpeakerB`, `setZoneBVolumeSync`, `setHdmiOut1`/`setHdmiOut2`, `setSpeakerPattern?num=<int>`, `setIrSensor?enable=true|false`.

## Dist / MusicCast Link (`/v1/dist/...`) — multi-room grouping, likely very relevant since this is MusicCast's headline feature

- `getDistributionInfo` — current group/distribution state
- `startDistribution?num=<int>` / `stopDistribution` — start/stop distributing audio from a server device to a group
- `setServerInfo` (POST), `setClientInfo` (POST) — configure a device's role in a distribution group
- `setGroupName` (POST)

## Tuner / CD / Clock — not applicable to NX-N500

Full spec also defines `/v1/tuner/...` (AM/FM/DAB — `setFreq`, `recallPreset`, `setDabService`…) and `/v1/cd/...` (`toggleTray`, playback) for units with those physical components, and `/v1/clock/...` (`setDateAndTime`, `setAlarmSettings`, `setClockFormat`) for units with a clock/alarm feature. Skipped here as almost certainly not present on a compact wireless speaker, but listed for completeness if you're ever working with a different MusicCast device.

## Practical next step

Once the NX-N500 is on your network, run:
```
curl "http://<speaker-ip>/YamahaExtendedControl/v1/system/getFeatures"
```
That single response gives the ground truth: exact input IDs (confirms whether AUX is `"aux"`, `"aux1"`, or `"audio1"`), actual volume min/max/step, and which of the above commands this specific unit actually implements.
