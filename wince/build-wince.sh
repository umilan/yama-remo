#!/bin/sh
# Builds yamaremo.exe for Windows CE 6.0 (ARM) with the CeGCC toolchain.
#
# One-time toolchain setup (Linux x86, prebuilt CeGCC 0.59.1 from SourceForge):
#   curl -L -o mingw32ce.tar.bz2 \
#     https://downloads.sourceforge.net/project/cegcc/cegcc/0.59.1/mingw32ce-0.59.1.tar.bz2
#   sudo tar xjf mingw32ce.tar.bz2 -C /      # installs into /opt/mingw32ce
#
# Override the toolchain location with CEGCC=/path/to/mingw32ce if needed.
set -e
cd "$(dirname "$0")"
CEGCC=${CEGCC:-/opt/mingw32ce}
CC="$CEGCC/bin/arm-mingw32ce-gcc"
STRIP="$CEGCC/bin/arm-mingw32ce-strip"

"$CC" -O2 -Wall -D_WIN32_WCE=0x600 -DUNICODE -D_UNICODE \
      yamaremo.c -o yamaremo.exe -lws2
"$STRIP" yamaremo.exe
ls -l yamaremo.exe
