# Built by scripts/package-linux.sh from the jpackage app image (:desktopApp:createDistributable).
# jpackage's own .rpm can't put the CLI on PATH; this one adds /usr/bin/clipvault-agent.
Name:           clipvault-agent
Version:        %{pkgversion}
Release:        1
Summary:        ClipVault agent: encrypted clipboard history with CopyQ as the desktop front end
License:        MIT
URL:            https://github.com/tadeasf/ClipVault
BuildArch:      x86_64
Recommends:     copyq
# The app image bundles its Java runtime; automatic scanning would turn its private .so files into
# provides/requires. The one system library the runtime links is zlib.
AutoReqProv:    no
Requires:       libz.so.1()(64bit)
%global debug_package %{nil}
%global __os_install_post %{nil}

%description
Background service that keeps ClipVault's encrypted clipboard history next to CopyQ and syncs it
with paired devices on the local network.

%install
mkdir -p %{buildroot}/opt/clipvault-agent %{buildroot}%{_bindir}
cp -a %{appdir}/. %{buildroot}/opt/clipvault-agent/
ln -s /opt/clipvault-agent/bin/clipvault-agent %{buildroot}%{_bindir}/clipvault-agent

%files
/opt/clipvault-agent
%{_bindir}/clipvault-agent
