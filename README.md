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
  in ClipVault, expire after 2 minutes, and are never written to CopyQ. Clips from password
  managers are not stored at all.
- **`scrub_copyq_sensitive` (default on):** CopyQ sees a clip before ClipVault does. When the agent
  classifies a new clip as sensitive, it also keeps it out of CopyQ's history: the CopyQ command
  drops it (`ignore()`), and as a backstop the agent removes CopyQ's newest item if, and only if,
  its hash matches. Only the newest item is checked; older history is never scanned.
  Turn it off with `clipvault-agent config scrub_copyq_sensitive off`.
- **Plaintext risk:** CopyQ keeps its own history, and unless CopyQ's tab encryption is on, that
  history is plaintext on disk. `clipvault-agent status` says `copyq: tabs unencrypted` while this
  is the case. ClipVault never changes CopyQ's encryption: `clipvault-agent install-copyq
  --check-encryption` only reports it (exit 1 while off) and prints how to turn it on in CopyQ's
  Preferences, with a password you choose.
- **Lost keyring:** if the store's key is gone (for example, the keyring was replaced), the
  service exits once with status 78 and a one-line message, instead of restarting in a loop. Move
  `~/.local/share/clipvault/history.db` aside and restart the service to start a new, empty
  history.
- **GNOME on Wayland:** CopyQ cannot tell which app a clip came from, so per-app exclusions are
  unavailable on GNOME for now. A small ClipVault GNOME extension that reports the focused app is
  planned for step 9. Exclusions work where CopyQ reports a window title (X11, some compositors).

## Secrets and third-party config

- ClipVault generates the keys it needs itself, at runtime, for the user running it. It never
  generates or stores passwords for other apps.
- It writes only to its own directories (`~/.local/share/clipvault`, `$XDG_RUNTIME_DIR/clipvault`),
  its own systemd unit, and its own keyring entries (service `cz.tadeasf.clipvault`).
- The only change it makes to CopyQ is adding its "ClipVault ingest" automatic command, and only
  when you run `clipvault-agent install-copyq`.
- Development follows the same rules (docs/design.md section 12): no writes to other apps' config
  or the keyring without an explicit yes, a timestamped backup to
  `~/clipvault-backups/<app>-<timestamp>/` before any such change, and "verified" only for things
  that were actually run.

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
