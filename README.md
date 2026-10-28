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

The two flavors install side by side and will be separate F-Droid listings. The desktop app
always includes sync, off until you enable it.

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
