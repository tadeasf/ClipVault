# ClipVault

ClipVault is a privacy-first clipboard history manager for Android and Linux desktop. History
stays on your devices, encrypted at rest. Sensitive clips such as passwords, one-time codes and
card numbers are detected locally, hidden and expired quickly. Optional sync works only between
your own devices on the local network, end-to-end encrypted. There is no cloud, no account and no
telemetry. Design: [docs/design.md](docs/design.md). Versions: [docs/versions.md](docs/versions.md).

**Not yet on F-Droid.** Early development; build from source for now.

## Two Android flavors

- **`vault`** (`cz.tadeasf.clipvault`) declares no network permission at all and contains no sync
  code. CI fails the build if either ever changes.
- **`sync`** (`cz.tadeasf.clipvault.sync`) adds LAN sync with your other devices. It is the only
  flavor with network permissions.

The two flavors install side by side and will be separate F-Droid listings.

## Desktop: `clipvault-agent` with CopyQ

On Linux, [CopyQ](https://hluk.github.io/CopyQ/) is the clipboard manager you see and use.
`clipvault-agent` is a small background service that keeps ClipVault's encrypted history next to
it, and (with sync, later) puts clips from your phone into CopyQ.

```sh
sudo dnf install ./clipvault-agent-*.rpm      # or the .deb / AppImage from CI
clipvault-agent install-service                # systemd user service, starts at login
clipvault-agent install-copyq                  # CopyQ hands every new clip to the agent
clipvault-agent status
```

- **Encryption:** ClipVault's own store is encrypted. The key is kept in GNOME Keyring, KWallet or
  KeePassXC.
- **Sensitive clips:** clips detected as sensitive (codes, card numbers, keys) are stored hidden
  in ClipVault, expire quickly, and are never written to CopyQ. Clips from password managers are
  not stored at all.
- **Plaintext risk:** CopyQ keeps its own history, and unless you turn on CopyQ's tab encryption
  (Preferences, "Encrypt tabs" with "Use external key store"), that history is plaintext on disk.
  That includes anything you copied, even before ClipVault saw it. `clipvault-agent status` says
  `copyq: tabs unencrypted` while this is the case.
- **GNOME on Wayland:** CopyQ cannot tell which app a clip came from, so per-app exclusions only
  work where CopyQ reports a window title (X11, some compositors).

## Modules

- `shared`: domain and storage (KMP android + jvm). No UI, no network.
- `ui`: shared Compose screens (KMP android + jvm).
- `sync-core`: sync protocol, crypto, transport, discovery (KMP android + jvm).
- `sync`: Android glue for the `sync` flavor; holds the network permissions.
- `androidApp`: Android app, flavors `vault` (offline, no sync code) and `sync`.
- `desktopApp`: Compose for Desktop app (Linux), sync always included.

## Build

- Android: `./gradlew :androidApp:assembleVaultDebug` / `:androidApp:assembleSyncDebug`
- Desktop: `./gradlew :desktopApp:run`
- Tests: `./gradlew :shared:allTests :sync-core:allTests`
- Offline guard: `./gradlew :androidApp:verifyVaultReleaseOffline` fails if the vault flavor's
  merged manifest declares a network permission or its classpath contains sync code.
