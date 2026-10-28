# ClipVault design

Status: draft before step 0. Research date 2026-10-28. Items marked **[verify]** were not
confirmed against an official source and are checked at the step that first needs them.

## 1. What we are building

A clipboard history manager for Android (F-Droid) and Linux desktop, in a single Kotlin
Multiplatform repo. Privacy is the product: no cloud, no accounts, no telemetry, nothing leaves
the LAN. History is encrypted at rest on both platforms. Every captured clip runs through local
sensitive-content detection; flagged clips are hidden, expire fast (default 2 min) and never sync
unless the user opts that category in.

Optional sync is device-to-device over the LAN: every device runs a small embedded server,
peers find each other over mDNS (or a manually entered IP:port), pair once via QR or a 6-digit code,
and then push clips live over a WebSocket and catch up over HTTP after a disconnect. All sync
payloads are end-to-end encrypted with keys derived during pairing; the network is untrusted.

The Android app ships in two flavors. `vault` has no network permission and does not compile any
sync code. `sync` adds the network permissions and the sync module. Desktop always includes sync,
off until the user enables it.

## 2. Module layout

The wizard (non-interactive endpoint, see 6.1) generates `androidApp`, `desktopApp` and `shared` at
the root when no server target is selected. We keep that and add modules for the sync boundary:

```
:shared      KMP (android + jvm). Domain models, SQLDelight history store, search, paging,
             sensitive detection, transformations, grouping, settings model. ClipboardSource,
             key storage and file-path expect/actuals. NO network code, no Ktor, no mDNS.
:ui          KMP Compose module: history list, clip detail, settings screens and their
             ViewModels, shared by both apps (wizard puts shared UI in `shared`; we split it out
             so :shared stays UI-free).
:sync-core   KMP (android + jvm). Sync protocol, conflict resolution, sync bookkeeping DB
             (separate SQLDelight database), crypto, pairing, Ktor server/client, discovery
             expect/actuals (NsdManager / JmDNS), sync UI screens.
:sync        com.android.library. Android-only glue: WorkManager catch-up, connection lifecycle,
             Wi-Fi allowlist, DI wiring, the network permissions in its manifest.
:androidApp  com.android.application. Activity, capture components, flavors vault/sync.
             Only `syncImplementation(project(":sync"))` pulls in :sync and :sync-core.
:desktopApp  Compose for Desktop. Window, tray, hotkey, clipboard watcher, packaging. Depends on
             :shared, :ui, :sync-core.
```

**Deviation from the brief, flagged:** the brief puts the sync protocol, crypto and mDNS in
`:shared`. That would make `vault` compile sync code and pull Ktor into its classpath, which
contradicts principle 2. Principles win, so all of it moves to `:sync-core`. The shared history
schema still carries `deviceId` and `lamportClock` columns so turning sync on needs no migration;
those are plain data, not network code.

Why `:sync` cannot just be a flavor of `:shared`: the new AGP KMP library plugin
(`com.android.kotlin.multiplatform.library`) is single-variant and does not support product
flavors (https://developer.android.com/kotlin/multiplatform/plugin). Flavors live only in
`:androidApp`.

## 3. Pinned versions

Recorded again in `docs/versions.md` at step 0. Wizard output is the baseline; we only move a
version when we have a reason.

| Component | Version | Verified at |
|---|---|---|
| Gradle (wrapper) | 9.5.1 | wizard output; AGP 9.1 needs >= 9.3.1 (https://developer.android.com/build/releases/gradle-plugin) |
| Kotlin | 2.4.21 | https://github.com/JetBrains/kotlin/releases; KGP 2.4.2x supports AGP 8.5.2-9.3.1 (https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) |
| AGP | 9.1.1 | wizard output, inside Kotlin's tested range. 9.4.x exists but is outside it |
| Compose Multiplatform | 1.12.2 | https://github.com/JetBrains/compose-multiplatform/blob/master/CHANGELOG.md |
| CMP material3 | 1.12.0-alpha03 | wizard pins an alpha; we accept that, a stable is not published for this line **[verify]** |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 | SDK 37 installed locally. minSdk 26 (wizard says 24; sqlcipher-android needs 23; 26 gives us java.time and NotificationChannel without desugaring) |
| SQLDelight | 2.4.1 | https://github.com/sqldelight/sqldelight/releases |
| AndroidX Paging (KMP) | 3.5.3 | https://developer.android.com/jetpack/androidx/releases/paging; jvm target **[verify at step 1]** |
| Ktor | 3.6.0 | https://github.com/ktorio/ktor/releases |
| kotlinx-coroutines | 1.11.0 | wizard output |
| kotlinx-serialization | 1.12.0 | https://github.com/Kotlin/kotlinx.serialization/releases; Kotlin pairing **[verify]** |
| Kover | 0.9.11 | https://github.com/Kotlin/kotlinx-kover; KMP android+jvm documented, new AGP KMP plugin **[verify]** |

Note: the locally installed Gradle (8.14.3 via mise) is too old for AGP 9. The wrapper is used.

## 4. Library choices

Every runtime dependency, with the source it was verified against. New ones beyond this list get
a one-line justification in their PR.

| Need | Choice | Why / source |
|---|---|---|
| Android DB encryption | `net.zetetic:sqlcipher-android` 4.20.0 + `androidx.sqlite:sqlite` | Successor to android-database-sqlcipher, actively maintained, minSdk 23. Plug `SupportOpenHelperFactory(keyBytes)` into SQLDelight's `AndroidSqliteDriver`. https://github.com/sqlcipher/sqlcipher-android |
| Desktop DB encryption | `io.github.willena:sqlite-jdbc` 3.53.4.0 (sqlite-jdbc-crypt), replacing `org.xerial:sqlite-jdbc` under `app.cash.sqldelight:sqlite-driver` | Same `org.sqlite` package, so it drops in under `JdbcSqliteDriver`. Bundles Linux x86_64 + aarch64 natives. Configure with `SQLiteMCSqlCipherConfig`. https://github.com/Willena/sqlite-jdbc-crypt |
| Android key storage | Platform Android Keystore: AES-GCM key in AndroidKeyStore wraps a random 32-byte DB key | `androidx.security:security-crypto` is deprecated in favour of the platform API (https://developer.android.com/jetpack/androidx/releases/security). https://developer.android.com/privacy-and-security/keystore |
| Linux keyring | `de.swiesend:secret-service` 3.x, fallback to `com.github.hypfvieh:dbus-java` 5.2.2 against `org.freedesktop.secrets` | swiesend is active but only publishes alphas; java-keyring is stale since 2023. https://github.com/swiesend/secret-service, https://github.com/hypfvieh/dbus-java |
| Keyring-less fallback | Passphrase-encrypted key file: Argon2id via Tink/BouncyCastle **[verify which exposes Argon2id]**, UI warns that this is the weaker mode | |
| Crypto primitives | Google Tink `tink-android` / `tink` 1.24.0 | Pure Java (no native libs, good for F-Droid reproducibility), Google-maintained, exposes X25519, HKDF, XChaCha20-Poly1305, Ed25519. The libsodium KMP binding (ionspin 0.9.5) labels itself "Experimental" and has no RFC 5869 HKDF. https://github.com/tink-crypto/tink-java |
| PAKE (6-digit code) | BouncyCastle `bcprov-jdk18on` 1.86, EC J-PAKE (`org.bouncycastle.crypto.agreement.ecjpake`, RFC 8236) | No maintained SPAKE2/CPace/OPAQUE on Maven Central. EC J-PAKE is the best maintained real PAKE we found. Pure Java. https://github.com/bcgit/bc-java |
| Transport | Ktor 3.6.0 server (CIO engine) + client, WebSockets plugin | CIO is the lightest pure-Kotlin engine. Ktor does not officially list Android for any engine **[verify on device at step 7]**. https://ktor.io/docs/server-engines.html |
| Desktop mDNS | `org.jmdns:jmdns` 3.6.4 | Released 2026-10-10, active. https://github.com/jmdns/jmdns |
| Android mDNS | Platform `NsdManager` | https://developer.android.com/develop/connectivity/wifi/use-nsd |
| QR generate | `com.google.zxing:core` 3.5.4 | Pure Java, both platforms |
| QR scan (Android) | CameraX + zxing core analyzer | zxing-android-embedded is stale (2021); ML Kit needs Google Play Services, not allowed on F-Droid. CameraX version **[verify at step 8]** |
| Paging | AndroidX Paging 3.5.3 + `app.cash.sqldelight:androidx-paging3-extensions` 2.4.1 | cashapp multiplatform-paging has had no release since 2024-02 |
| Coverage | Kover 0.9.11 | |

**F-Droid and native libraries.** The Inclusion Policy allows prebuilt FLOSS binaries from
trusted Maven repositories, Maven Central included
(https://f-droid.org/docs/Inclusion_Policy/). sqlcipher-android ships prebuilt `.so` files from
Maven Central, so it should pass. We still confirm with an RFP before the first submission,
because the policy text is not explicit about native code inside AARs, and native code can break
reproducible builds (https://f-droid.org/docs/Reproducible_Builds/). Tink and BouncyCastle are pure
Java, so they add no native code.

## 5. Sync design (refined)

### 5.1 Data model

Clip event as in the brief: `{id: UUIDv7, deviceId, createdAt, contentType, contentHash,
payload, sensitive, pinned, deleted, lamportClock}`. Two refinements:

- **Metadata changes are their own log entries** (`MetaEvent{clipId, field, value, lamportClock,
  deviceId}`), so the log really is append-only. The current pinned/deleted state is a fold over
  them: LWW on `(lamportClock, deviceId)`, and a delete beats any flag change no matter the clock.
- **Catch-up does not use the Lamport clock.** "Everything after clock N" loses events once three
  devices relay through each other. Example: C creates a clip at clock 5 and syncs it to B. A has
  already seen B up to clock 10, so asking B for "after 10" never returns C's clip. Instead, each
  device numbers every event it *stores* (own or received) with a local monotonically increasing
  `seq`. A peer remembers "last seq I got from you" and asks for `seq > N`. Lamport stays for LWW
  ordering only. `docs/sync/protocol.md` will include this example.

`contentHash` is SHA-256 of the plaintext. It only ever travels inside the encrypted session and
is only stored in the encrypted DB, so a plain hash is acceptable. A short OTP's hash would be
brute-forceable if it ever leaked, which is one more reason sensitive clips do not sync.

### 5.2 Session security

There is no maintained Noise implementation for the JVM (rweather/noise-java and the Signal fork
have both been dormant since early 2024). TLS with pinned self-signed certificates would need a TLS
server engine on Android, and Ktor's only documented TLS path is JKS + Netty/Jetty, which is
unverified on Android. So the plan is an authenticated handshake assembled from Tink primitives:

1. Each device has a long-term Ed25519 identity key. Pairing exchanges and pins the identity keys.
2. On every connect, both sides generate an ephemeral X25519 key and exchange it along with an
   Ed25519 signature over the handshake transcript (SIGMA-I style, comparable to Noise KK). That
   gives forward secrecy and a fresh key on every reconnect.
3. HKDF-SHA256 over the shared secret and the transcript hash yields two directional keys.
4. Frames are sealed with XChaCha20-Poly1305 and carry a 64-bit per-direction counter, which is
   bound into the nonce. A non-increasing counter is rejected, which gives replay protection.

We do not implement any primitive, but the *composition* is ours, and it is the most
security-sensitive custom code in the project. It gets its own spec section, test vectors, and an
explicit request for external review. See open question 3.

### 5.3 Pairing

- **QR code:** the QR holds the device ID, the LAN address:port, an ephemeral X25519 public key and
  a 128-bit one-time token valid for 2 minutes. The scanner connects, both sides run the handshake
  bound to the token, and they exchange identity keys.
- **6-digit code:** the two devices run EC J-PAKE with the code as the secret. The code is
  single-use: a failed attempt invalidates it and shows a new one. That limits online guessing to
  1-in-a-million per displayed code.
- **Desktop to desktop:** neither side has a camera, so these pairs use the 6-digit code.

### 5.4 Discovery and privacy

- **Advertised data:** advertising a stable device ID over mDNS lets anyone on any Wi-Fi network
  track the device. **More private option, chosen and flagged:** advertise a random instance name
  that rotates on every app start, plus the protocol version. Peers only learn real identities
  inside the encrypted handshake. Discovery then tries the handshake against each candidate.
- **Binding:** the server binds only to the site-local addresses of the active interfaces and
  rebinds when the network changes. Desktop never binds `0.0.0.0` unless the user flips an
  explicit setting.

### 5.5 Size cap

The default payload cap is 1 MB. Larger items sync their metadata only, and the receiver fetches
the full payload on demand. The brief mentions thumbnails, but the content types it lists are all
text. v1 captures text only, so there are no thumbnails, just a size and a preview of the first
N chars (see section 8).

## 6. Where the brief collides with the platforms

### 6.1 Wizard

The wizard can be driven non-interactively. `GET https://kmp.jetbrains.com/generateKmtProject`
with a JSON `spec` returns a zip (documented at https://kmp.jetbrains.com/SKILL.md, and tested).
It no longer generates `composeApp`: we get `androidApp` + `desktopApp` + `shared`, with `shared`
on the new AGP KMP library plugin. It generates no convention plugins, which matches the brief.

### 6.2 Android clipboard capture (blocker, open question 1)

The brief's primary mechanism does not work. "Unless your app is the default input method editor
(IME) or is the app that currently has focus, your app cannot access clipboard data on Android 10
or higher" (https://developer.android.com/about/versions/10/privacy/changes). A foreground service
gets no exemption, and no newer release relaxes the rule. AccessibilityServices are not exempt
either. Clipboard managers that work on Android 10+ use one of these routes:

- **Accessibility, then focus.** Accessibility detects a likely copy event, then a transparent
  activity briefly takes focus to read the clip. It is fragile and not documented anywhere official.
- **ADB-granted `READ_LOGS` or Shizuku.** These need a developer-mode setup step.
- **Being the keyboard (IME).** This is a large scope increase.
- **User-initiated capture.** A Quick Settings tile or notification action opens an activity,
  which then has focus and can read the clip. "ClipVault" in the text-selection menu
  (`ACTION_PROCESS_TEXT`) and the share sheet need no clipboard read at all.

Also:

- **Paste toast:** on Android 12+ every read shows the system "ClipVault pasted from your
  clipboard" toast (https://developer.android.com/develop/ui/views/touch-and-input/copy-paste).
- **Foreground service types:** none of them fits a clipboard watcher. The only candidate is
  `specialUse`. `dataSync` is capped at 6 h per 24 h on targetSdk 35+, which matters for keeping
  the sync connection open (https://developer.android.com/develop/background-work/services/fgs/timeout).
- **Source app:** Android has no API that says which app put the clip on the clipboard. That
  breaks three planned features: the per-app exclusion list, password-manager detection by package
  name, and grouping by source app. All three only work when the accessibility path is on, because
  it sees the foreground package. Without it, detection falls back to content heuristics plus
  `ClipDescription.EXTRA_IS_SENSITIVE`. Password managers set that flag on API 33+, and we honour it
  as "sensitive, never store".

### 6.3 GNOME Wayland desktop capture (open question 2)

- **wl-paste:** `wl-paste --watch` needs the wlr or ext data-control protocol. GNOME Mutter
  supports neither (https://wayland.app/protocols/ext-data-control-v1). Confirmed on this machine
  (GNOME 50.6): "Watch mode requires a compositor that supports the data-control protocol". KDE
  6.x, Sway, Hyprland, niri and COSMIC do support it.
- **AWT polling:** AWT goes through XWayland, which only syncs the clipboard to X clients that
  have focus (see https://invent.kde.org/plasma/kwin/-/merge_requests/5436). So polling while
  unfocused is expected to miss copies made in native Wayland apps **[verify at step 4]**.
- **Implication:** the only reliable route on GNOME is a GNOME Shell extension, which is how GPaste
  and Clipboard Indicator work.
- **Decision: reuse CopyQ's extension.** CopyQ 17 ships the "CopyQ Clipboard Monitor" extension
  (`copyq-clipboard@hluk.github.com`, GNOME 45-50). It is a generic session-D-Bus bridge.
  - **Service:** `com.github.hluk.copyq.GnomeClipboard` at `/com/github/hluk/copyq/GnomeClipboard`,
    interface `com.github.hluk.CopyQ.GnomeClipboard1`.
  - **Methods:** `RegisterClipboardClient(i)`, `GetClipboardData(i, as) -> a{sv}`,
    `GetClipboardFormats`, `SetClipboardData`.
  - **Callback:** it calls `ClipboardChanged(i)` on every registered sender at
    `/com/github/hluk/copyq/GnomeClipboardClient`.
  - **Integration:** it keeps a map of clients, so ClipVault registers as a second client through
    dbus-java and needs neither the CopyQ app nor a second extension.
  - **Risks:** this is CopyQ's interface, not a public contract. It is versioned (`...1`) but could
    change. If it breaks, we write our own extension of the same shape (about 100 lines of GJS).
    The extension is GPL-3; we only talk to it over D-Bus, so no license coupling.
- **Privacy note on CopyQ itself:** running CopyQ alongside ClipVault means a second history that
  CopyQ stores unencrypted under `~/.local/share/copyq`, sensitive items included. The UI says so
  when it detects the CopyQ extension. Users can keep the extension and quit the CopyQ app.
- **Desktop capture order:**
  1. CopyQ extension D-Bus, when present on GNOME.
  2. `wl-paste --watch`, when the compositor supports data-control (KDE, Sway, Hyprland and others).
  3. AWT polling as the X11 / last-resort path, labelled "may miss copies" on Wayland.

### 6.4 Other desktop points

- **Tray:** AWT `SystemTray` (which Compose `Tray` uses) does not show icons on GNOME Wayland or
  KDE (JDK-8146318, still open). Plan: implement a StatusNotifierItem over dbus-java (the same
  dependency as the keyring fallback). On GNOME that still needs the AppIndicator extension, which
  most distros ship. We fall back to "no tray, window only" and say so in the UI.
- **Global hotkey:** the XDG `GlobalShortcuts` portal works on GNOME 48+ and KDE
  (https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.GlobalShortcuts.html).
  JNativeHook is X11-only. Plan: use the portal over dbus-java, and treat the hotkey as optional,
  as the brief allows.
- **Per-window-class exclusion:** this works on X11 via `_NET_ACTIVE_WINDOW` + `WM_CLASS`. No
  standard Wayland protocol exposes the active window, so on Wayland it is unavailable, except
  through the GNOME extension if we build one.
- **Packaging:** the Compose Gradle plugin builds `.deb` and `.rpm` on Linux. It builds no
  AppImage (https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html). Plan:
  produce the jpackage app-image directory and wrap it with `appimagetool` in CI. That is a build
  tool, not a runtime dependency.

### 6.5 Android sync flavor

- **Local network permission:** Android 17 enforces `ACCESS_LOCAL_NETWORK`, a runtime permission,
  for apps targeting SDK 37. It covers LAN TCP/UDP, multicast and NSD
  (https://developer.android.com/privacy-and-security/local-network-permission). With targetSdk 37,
  the `sync` flavor declares it alongside `INTERNET`, `ACCESS_NETWORK_STATE` and
  `CHANGE_WIFI_MULTICAST_STATE`, and asks for it when sync is first enabled. `vault` declares none.
  The CI check fails if the merged `vault` manifest contains `INTERNET` or `ACCESS_LOCAL_NETWORK`.
- **Wi-Fi allowlist:** reading the SSID needs a location-class permission on Android 10+
  (`ACCESS_FINE_LOCATION`, plus `NEARBY_WIFI_DEVICES` on 13+), and possibly location services
  switched on **[verify exact rule at step 8]**. More private default: the allowlist is off, and we
  request the permission only when the user turns it on, with the explanation in-UI. Without it,
  sync simply runs on whatever LAN can reach a paired peer, and the encrypted handshake fails
  against anything that is not one.

### 6.6 KMP-specific awkwardness

- **Java libraries in common code:** Tink, BouncyCastle, JmDNS and the SQLCipher drivers are all
  JVM libraries, but `commonMain` cannot call Java APIs. Crypto sits behind a small `commonMain`
  interface. We try a shared `jvmAndAndroid` intermediate source set first, so both targets compile
  the same Tink-calling code; if dependency resolution there is unreliable, we fall back to
  near-identical `androidMain`/`jvmMain` actuals.
- **SQLDelight drivers:** the driver differs per platform (AndroidSqliteDriver vs JdbcSqliteDriver)
  and so does the key format (ByteArray vs hex-string key). This is an expect/actual factory.
  Whether a desktop database can be opened on Android (`legacy=4`) is **[verify at step 1]**. We
  never copy DB files between devices, so it only matters for tests.
- **Flavors:** single-variant AGP KMP plugin, so flavors only in `:androidApp` (section 2).
- **AGP 9 restriction:** `com.android.application` cannot share a module with KGP's multiplatform
  plugin. That is why the app modules are separate.
- **Coverage:** Kover with the new AGP KMP plugin is **[verify at step 1]**. The fallback is to
  measure coverage on the `jvm` target only, since domain and service code is common anyway.

## 7. Delivery plan

The brief's steps 0-9 stay as given. Adjustments:

- **Step 0:** generate with the wizard, add `:ui`, `:sync-core` and `:sync` as empty modules,
  set up flavors, and add a CI job (GitHub Actions) that runs `processVaultReleaseManifest` and
  greps the merged manifest. Both apps launch with placeholder UI.
- **Step 3:** the capture path depends on question 1.
- **Step 4:** the GNOME path depends on question 2.
- **Step 5:** the sync bookkeeping DB lives in `:sync-core`, catch-up is seq-based, and metadata
  changes are log entries (5.1).
- **Step 6:** `docs/sync/handshake.md` is written and reviewed before any handshake code.

## 8. Decisions (answered 2026-10-28)

1. **Android capture:** user-initiated capture is the default and needs no extra permissions:
   - a Quick Settings tile,
   - a notification action,
   - the share sheet,
   - the text-selection "ClipVault" action (`ACTION_PROCESS_TEXT`).

   Opt-in Accessibility auto-capture comes on top: it detects the copy, then a transparent focus
   activity reads the clip. It is off by default and explained in-UI. Source-app features (the
   per-app exclusion list, password-manager detection, grouping by source app) need it. There is no
   foreground service for capture. A foreground service only exists in the `sync` flavor, to hold
   the live connection (6.2).
2. **GNOME capture:** a GNOME extension is acceptable, and we reuse CopyQ's existing extension
   over D-Bus (6.3).
3. **Session security:** a SIGMA-style handshake built on Tink (5.2). `docs/sync/handshake.md` is
   written before any handshake code and flagged for external review.
4. **Naming:** the display name stays "ClipVault". The applicationId is `cz.tadeasf.clipvault`
   (vault) and `cz.tadeasf.clipvault.sync` (sync, via `applicationIdSuffix`), so the two install
   side by side and get separate F-Droid listings.

Smaller defaults, which hold unless you object:

- v1 captures text only, with no images and no thumbnails.
- Shared screens and ViewModels live in a `:ui` module.
- The minSdk is 26.
- The mDNS instance name rotates on every app start.
- The Wi-Fi allowlist is off by default.
