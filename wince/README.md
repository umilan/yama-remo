# yama-remo for Windows CE 6.0

A native Windows CE version of `musiccast-remote.html`, written for the **Medion GoPal E5270**
(WinCE 6.0, ARM, 800×480 touch screen). It's one small `.exe` with no dependencies, and it
needs no .NET or browser.

The browser on WinCE 6.0 is an old Internet Explorer 6-era one that can't run the web page,
so this is a plain Win32/GDI program instead. It talks to the speaker's YXC HTTP API over
Winsock directly. Because no browser is involved, the CORS limitation doesn't apply and
`proxy.py` isn't needed.

## Features

- Power on/standby. Choosing a source while the speaker is in standby switches it on first.
- Sources: AirPlay, AUX, Optical, Bluetooth, USB, Net Radio. The active source is highlighted.
- Volume −/+ (hold to repeat), tap or drag on the volume bar to jump, mute/unmute.
- Previous, play/pause, next.
- Now playing (track, artist, source), polled every 3 s. Tap the now-playing panel to refresh at once.
- On-screen numeric keypad for the speaker IP (nav devices usually have no soft keyboard).
  The IP is saved to `yamaremo.ini` next to the `.exe`.
- Full screen. It hides the shell taskbar while it is in front, and the **X** button exits.

## Requirements on the device

- **A Wi-Fi connection to the same network as the speaker.** Without a network connection the
  app can only show "Not connected".
- That's all. The app uses only `coredll` and `ws2`, which are part of every CE 6.0 image.

## Build

```sh
./build-wince.sh
```

`build-wince.sh` shows how to install the CeGCC 0.59.1 ARM toolchain on Linux.
The result is `yamaremo.exe`.

The same source also compiles as a desktop Windows app (800×480 window), which is handy for
testing on a PC:

```sh
i686-w64-mingw32-gcc -municode -O2 yamaremo.c -o yamaremo-desktop.exe -mwindows -lws2_32
```

## Install on the GoPal

1. Connect the device over USB, or put its SD card in a card reader.
2. Copy `yamaremo.exe` to a folder on it, for example `\Storage Card\YamaRemo\`.
3. Start it with Explorer (or point your launcher/menu replacement at it).
4. Tap **IP …** at the top, type the speaker's IP and tap **OK**.
