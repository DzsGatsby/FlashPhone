#!/bin/sh
set -eu
cd "$(dirname "$0")"
OUT=${1:-build}
mkdir -p "$OUT/FlashMacBridge.app/Contents/MacOS"
cp native/AudioBridge.swift "$OUT/main.swift"
xcrun swiftc -O -swift-version 5 -framework CoreAudio -framework AudioToolbox -framework ApplicationServices "$OUT/main.swift" native/PhoneController.swift -framework AppKit -o "$OUT/FlashMacBridge.app/Contents/MacOS/FlashMacBridge"
cp native/Info.plist "$OUT/FlashMacBridge.app/Contents/Info.plist"
codesign --force --sign - "$OUT/FlashMacBridge.app" >/dev/null
printf '%s\n' "$OUT/FlashMacBridge.app/Contents/MacOS/FlashMacBridge"
