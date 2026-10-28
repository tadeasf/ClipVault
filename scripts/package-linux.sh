#!/usr/bin/env bash
# Builds clipvault-agent packages from the jpackage app image:
#   .rpm (rpmbuild + packaging/clipvault-agent.spec), .deb (jpackage's, patched) and AppImage.
# Both packages put the CLI on PATH as /usr/bin/clipvault-agent -> /opt/clipvault-agent/bin/clipvault-agent.
# Usage: scripts/package-linux.sh [all|rpm|deb|appimage]   (default all; CI runs all on Ubuntu)
set -euo pipefail
cd "$(dirname "$0")/.."
what=${1:-all}
out=desktopApp/build/compose/binaries/main
version=$(sed -n 's/.*packageVersion = "\(.*\)".*/\1/p' desktopApp/build.gradle.kts)
want() { [ "$what" = all ] || [ "$what" = "$1" ]; }

tasks=(:desktopApp:createDistributable)
want deb && tasks+=(:desktopApp:packageDeb)
./gradlew "${tasks[@]}"

if want rpm; then
  top=$(mktemp -d)
  rpmbuild -bb --quiet --define "_topdir $top" --define "pkgversion $version" \
    --define "appdir $PWD/$out/app/clipvault-agent" packaging/clipvault-agent.spec
  mkdir -p "$out/rpm"
  rm -f "$out"/rpm/*.rpm
  cp "$top"/RPMS/x86_64/*.rpm "$out/rpm/"
  rpm -qpl "$out"/rpm/*.rpm | grep -x /usr/bin/clipvault-agent
fi

if want deb; then
  # jpackage can only write hard Depends; CopyQ is optional (the agent runs sync-only without it).
  deb=$(ls "$out"/deb/*.deb)
  work=$(mktemp -d)
  dpkg-deb -R "$deb" "$work"
  # Insert before Description: jpackage's control ends with a blank line, so appending would start a second stanza.
  grep -q '^Recommends:' "$work/DEBIAN/control" || sed -i '/^Description:/i Recommends: copyq' "$work/DEBIAN/control"
  # jpackage's postinst/prerm run xdg-desktop-menu under `set -e`; it fails where /usr/share/applications is
  # missing (minimal/headless systems) and leaves the package half-configured. The agent doesn't need a menu entry.
  sed -i 's/^\(xdg-desktop-menu .*\)$/\1 || true/' "$work"/DEBIAN/postinst "$work"/DEBIAN/prerm
  mkdir -p "$work/usr/bin"
  ln -sf /opt/clipvault-agent/bin/clipvault-agent "$work/usr/bin/clipvault-agent"
  fakeroot dpkg-deb -b "$work" "$deb"
  dpkg-deb -f "$deb" Package Version Recommends
fi

if want appimage; then
  # AppImage: wrap the jpackage app-image. appimagetool is a build tool, pinned to a release.
  appdir=$(mktemp -d)/ClipVaultAgent.AppDir
  cp -a "$out/app/clipvault-agent" "$appdir"
  ln -s bin/clipvault-agent "$appdir/AppRun"
  cp packaging/clipvault-agent.desktop packaging/clipvault-agent.svg "$appdir/"
  tool=$(mktemp -d)/appimagetool
  curl -fsSL -o "$tool" https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage
  chmod +x "$tool"
  ARCH=x86_64 "$tool" --appimage-extract-and-run "$appdir" "$out/clipvault-agent-x86_64.AppImage"
fi
ls -la "$out"/rpm/*.rpm "$out"/deb/*.deb "$out"/*.AppImage 2>/dev/null || true
