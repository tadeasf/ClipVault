#!/usr/bin/env bash
# Builds clipvault-agent's .deb (adding a soft "Recommends: copyq") and AppImage from the Compose/jpackage
# outputs. Runs in CI on Ubuntu (needs dpkg-deb, fakeroot, network for appimagetool). The .rpm is built
# with :desktopApp:packageRpm directly.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :desktopApp:packageDeb :desktopApp:createDistributable
out=desktopApp/build/compose/binaries/main

# .deb: jpackage can only write hard Depends; CopyQ is optional (the agent runs sync-only without it).
deb=$(ls "$out"/deb/*.deb)
work=$(mktemp -d)
dpkg-deb -R "$deb" "$work"
# Insert before Description: jpackage's control ends with a blank line, so appending would start a second stanza.
grep -q '^Recommends:' "$work/DEBIAN/control" || sed -i '/^Description:/i Recommends: copyq' "$work/DEBIAN/control"
fakeroot dpkg-deb -b "$work" "$deb"
dpkg-deb -f "$deb" Package Version Recommends

# AppImage: wrap the jpackage app-image. appimagetool is a build tool, pinned to a release.
appdir=$(mktemp -d)/ClipVaultAgent.AppDir
cp -a "$out/app/clipvault-agent" "$appdir"
ln -s bin/clipvault-agent "$appdir/AppRun"
cp packaging/clipvault-agent.desktop packaging/clipvault-agent.svg "$appdir/"
tool=$(mktemp -d)/appimagetool
curl -fsSL -o "$tool" https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage
chmod +x "$tool"
ARCH=x86_64 "$tool" --appimage-extract-and-run "$appdir" "$out/clipvault-agent-x86_64.AppImage"
ls -la "$deb" "$out/clipvault-agent-x86_64.AppImage"
