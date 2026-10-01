#!/bin/bash
# CI (macOS): compile a universal (Apple Silicon + Intel) app, ad-hoc sign, zip.
set -euo pipefail
RUN="${1:-0}"
NAME="Medica Mall Scope Test"
OUT=build
APP="$OUT/$NAME.app"
rm -rf "$OUT"; mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
swiftc --version
for a in arm64 x86_64; do
  swiftc -O -swift-version 5 -target "$a-apple-macos12.0" \
    -framework Cocoa -framework WebKit -framework AVFoundation -framework IOKit \
    mac/MedicaMallScope/main.swift -o "$OUT/scope-$a"
done
lipo -create "$OUT/scope-arm64" "$OUT/scope-x86_64" -output "$APP/Contents/MacOS/MedicaMallScope"
cp mac/MedicaMallScope/mac-shim.js "$APP/Contents/Resources/"
[ -f mac/web/index.html ] && cp mac/web/index.html "$APP/Contents/Resources/index.html"
[ -d mac/AppIcon.iconset ] && iconutil -c icns mac/AppIcon.iconset -o "$APP/Contents/Resources/AppIcon.icns"
sed -e "s/__VERSION__/1.0.$RUN/" -e "s/__BUILD__/$RUN/" mac/MedicaMallScope/Info.plist > "$APP/Contents/Info.plist"
plutil -lint "$APP/Contents/Info.plist"
codesign --force --deep --sign - "$APP"
codesign -dv "$APP" 2>&1 | head -5
ditto -c -k --sequesterRsrc --keepParent "$APP" MedicaMall-Scope-Mac-Test.zip
ls -la MedicaMall-Scope-Mac-Test.zip
