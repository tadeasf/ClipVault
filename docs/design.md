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
             ViewModels. Android-only in product terms since step 4 (its jvm target exists only so
             the ViewModel tests run on the JVM).
:sync-core   KMP (android + jvm). Sync protocol, conflict resolution, sync bookkeeping DB
             (separate SQLDelight database), crypto, pairing, Ktor server/client, discovery
             expect/actuals (NsdManager / JmDNS), sync UI screens.
:sync        com.android.library. Android-only glue: WorkManager catch-up, connection lifecycle,
             Wi-Fi allowlist, DI wiring, the network permissions in its manifest.
:androidApp  com.android.application. Activity, capture components, flavors vault/sync.
             Only `syncImplementation(project(":sync"))` pulls in :sync and :sync-core.
:desktopApp  `clipvault-agent` (step 4): a headless daemon + CLI, systemd user service. CopyQ is
             the desktop front end; the agent captures through it, keeps the encrypted store (the
             sync source of truth) and writes phone-origin clips into CopyQ. The Compose desktop
             target stays in the build for a small pairing/status window (step 8). Depends on
             :shared, :sync-core. See section 11.
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
| AndroidX Paging (KMP) | 3.5.3 | https://developer.android.com/jetpack/androidx/releases/paging; jvm variant is `paging-common-desktop` (resolved step 1) |
| Ktor | 3.6.0 | https://github.com/ktorio/ktor/releases |
| kotlinx-coroutines | 1.11.0 | wizard output |
| kotlinx-serialization | 1.12.0 | https://github.com/Kotlin/kotlinx.serialization/releases; Kotlin pairing **[verify]** |
| Kover | 0.9.11 | https://github.com/Kotlin/kotlinx-kover; works with the new AGP KMP plugin, host tests only (resolved step 1, see 6.6) |

Note: the locally installed Gradle (8.14.3 via mise) is too old for AGP 9. The wrapper is used.

## 4. Library choices

Every runtime dependency, with the source it was verified against. New ones beyond this list get
a one-line justification in their PR.

| Need | Choice | Why / source |
|---|---|---|
| Android DB encryption | `net.zetetic:sqlcipher-android` 4.20.0 + `androidx.sqlite:sqlite` | Successor to android-database-sqlcipher, actively maintained, minSdk 23. Plug `SupportOpenHelperFactory(keyBytes)` into SQLDelight's `AndroidSqliteDriver`. https://github.com/sqlcipher/sqlcipher-android |
| Desktop DB encryption | `io.github.willena:sqlite-jdbc` 3.53.4.0 (sqlite-jdbc-crypt), replacing `org.xerial:sqlite-jdbc` under `app.cash.sqldelight:sqlite-driver` | Same `org.sqlite` package, so it drops in under `JdbcSqliteDriver`. Bundles Linux x86_64 + aarch64 natives. Configure with `SQLiteMCSqlCipherConfig`. https://github.com/Willena/sqlite-jdbc-crypt |
| Android key storage | Platform Android Keystore: AES-GCM key in AndroidKeyStore wraps a random 32-byte DB key | `androidx.security:security-crypto` is deprecated in favour of the platform API (https://developer.android.com/jetpack/androidx/releases/security). https://developer.android.com/privacy-and-security/keystore |
| Linux keyring | **Step 2:** `com.github.hypfvieh:dbus-java-core` + `dbus-java-transport-native-unixsocket` 5.2.2, talking to `org.freedesktop.secrets` directly (`SecretServiceKeyring`, 138 lines) | One dependency that the tray (StatusNotifierItem) and the GlobalShortcuts portal also need. Pure-Java unix-socket transport (JDK 16+), no JNI. Uses a "plain" session (local session bus only, which the spec allows). Items go into the `default` collection; an unlock prompt, if one is shown, is awaited (120 s). No default collection, or no bus, means `detect()` returns null and the Argon2id file fallback is used. Step 1 used libsecret's `secret-tool` CLI, now removed. https://github.com/hypfvieh/dbus-java |
| Keyring-less fallback | Passphrase-encrypted key file: Argon2id via BouncyCastle `Argon2BytesGenerator` (Tink has no Argon2), 64 MiB / t=3 / p=1 (RFC 9106 second recommended option), sealing with AES-256-GCM. The UI warns that this is the weaker mode | `org.bouncycastle:bcprov-jdk18on` 1.86, which step 6 also uses for J-PAKE and the handshake primitives |
| Crypto primitives | BouncyCastle `bcprov-jdk18on` 1.86, lightweight API: `X25519Agreement`, `Ed25519Signer`, `HKDFBytesGenerator`, `XChaCha20Poly1305` (decided 2026-10-28) | Already a dependency (Argon2id, J-PAKE), pure Java. Tink was the earlier choice and is dropped: its explicit-nonce XChaCha20-Poly1305 is only in its `internal` package (handshake.md section 2). The libsodium KMP binding (ionspin 0.9.5) labels itself "Experimental" and has no RFC 5869 HKDF. https://github.com/bcgit/bc-java |
| PAKE (6-digit code) | BouncyCastle `bcprov-jdk18on` 1.86, finite-field J-PAKE (`org.bouncycastle.crypto.agreement.jpake`, `NIST_3072`, SHA-256; decided 2026-10-28), because EC J-PAKE's `ECSchnorrZKP` has no public constructor (pairing.md section 2.1) | No maintained SPAKE2/CPace/OPAQUE on Maven Central. EC J-PAKE is the best maintained real PAKE we found. Pure Java. https://github.com/bcgit/bc-java |
| Transport | Ktor 3.6.0 server (CIO engine) + client, WebSockets plugin | CIO is the lightest pure-Kotlin engine. Ktor does not officially list Android for any engine **[verify on device at step 7]**. https://ktor.io/docs/server-engines.html |
| Desktop mDNS | `org.jmdns:jmdns` 3.6.4 | Released 2026-10-10, active. https://github.com/jmdns/jmdns |
| Android mDNS | Platform `NsdManager` | https://developer.android.com/develop/connectivity/wifi/use-nsd |
| QR generate | `com.google.zxing:core` 3.5.4 | Pure Java, both platforms |
| QR scan (Android) | CameraX + zxing core analyzer | zxing-android-embedded is stale (2021); ML Kit needs Google Play Services, not allowed on F-Droid. CameraX version **[verify at step 8]** |
| Paging | AndroidX Paging 3.5.3 + `app.cash.sqldelight:androidx-paging3-extensions` 2.4.1 | cashapp multiplatform-paging has had no release since 2024-02 |
| Sync wire format (step 5) | `kotlinx-serialization-cbor` 1.12.0 | Self-describing, so higher minor versions are read by skipping unknown fields; native byte strings; first-party and multiplatform. Protobuf would need field numbers on everything and is equally experimental (docs/sync/protocol.md section 2) |
| Property tests (step 5, test only) | `io.kotest:kotest-property` 6.2.6 | Multiplatform, works inside plain kotlin.test without the kotest engine, shrinks failures. jqwik is JUnit 5 and JVM-only |
| Coverage | Kover 0.9.11 | |

**F-Droid and native libraries.** The Inclusion Policy allows prebuilt FLOSS binaries from
trusted Maven repositories, Maven Central included
(https://f-droid.org/docs/Inclusion_Policy/). sqlcipher-android ships prebuilt `.so` files from
Maven Central, so it should pass. We still confirm with an RFP before the first submission,
because the policy text is not explicit about native code inside AARs, and native code can break
reproducible builds (https://f-droid.org/docs/Reproducible_Builds/). BouncyCastle is pure
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

Implementation notes (steps 1-2):
- **Event counter (step 2):** clip writes and MetaEvents share one counter, the single-row
  `SeqCounter` table.
  - **Allocation:** `nextSeq()` runs `UPDATE SeqCounter SET value = value + 1` and then reads the
    value, inside the caller's transaction. The UPDATE takes SQLite's write lock before the read,
    so two writers cannot get the same seq.
  - **Re-copy:** a re-copied clip (dedupe bump) gets a new seq, so catch-up delivers the bump.
  - **Catch-up (step 5):** `ClipMember.seq > N` merged with `MetaEvent.seq > N`, in seq order
    (protocol.md section 11).
  - **Migration:** `1.sqm` takes step-1 databases to this schema. Existing clips get seqs after
    the highest existing MetaEvent seq, so the two never collide. `MigrationTest` runs it on jvm
    and on device.
- **Sensitive clips (step 2):**
  - `ClipIngest` never stores clips from a sensitive source (`SkipReason.SENSITIVE_SOURCE`). That
    means the copying app set `EXTRA_IS_SENSITIVE`, or it is a known password manager by package
    or window title (`KnownSensitiveSources.matches`). The system clipboard still holds the value
    for pasting; ClipVault just doesn't record it.
  - Everything `SensitiveDetector` flags is stored with `sensitive = 1`: hidden from lists by
    default and deleted outright by the TTL sweep (default 2 min) in `applyRetention`.
  - **Pinned sensitive clips:** pinning wins, because it is an explicit user act. They are exempt
    from the sweep, but stay hidden by default in the list. The pin UI shows a one-line warning.
  - **Categories:** stored in `Clip.categories` as comma-separated `SensitiveCategory` names,
    empty when the clip is not sensitive (migration `2.sqm`, step 2). Rows flagged before that
    column existed are `UNKNOWN`. Step 5 uses this for per-category sync opt-in.
- **Delete:** deleting a clip tombstones the row (`deleted = 1`, kept for sync) and erases
  `content` and `contentHash`, which also drops it from the FTS index.
- **Retention:** retention by count or age is local policy. It removes rows outright, never
  tombstones them, so another device with a longer retention keeps its copy. Pinned clips are
  exempt.

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

Specified in [`docs/sync/pairing.md`](sync/pairing.md), which supersedes this summary.

- **QR code:** the QR holds the LAN addresses, the port and a 128-bit one-time token valid for 2
  minutes. No device ID and no ephemeral key: the token alone authenticates, and identities only
  travel encrypted. The scanner connects, both sides run the handshake bound to the token, and they
  exchange identity keys.
- **6-digit code:** the two devices run J-PAKE with the code as the secret. The code is
  single-use: a failed attempt invalidates it and shows a new one. That limits online guessing to
  1-in-a-million per displayed code.
  - **Variant:** finite-field J-PAKE instead of EC J-PAKE (decided 2026-10-28), because
    BouncyCastle's EC proof class can't be rebuilt from bytes with its public API (pairing.md
    section 2.1).
- **Desktop to desktop:** neither side has a camera, so these pairs use the 6-digit code.

### 5.3.1 Device ID (decided 2026-10-28)

- **Derivation:** once sync is enabled, a device's ID is
  `hex(first 16 bytes of H(lp("clipvault-devid-v1") || lp(idPub)))`, where `idPub` is its Ed25519
  identity key (handshake.md section 2). A paired peer can't claim another device's ID to win LWW
  tie-breaks, because the ID is checked against the identity the handshake authenticated.
- **Before sync:** a device without an identity (the vault flavor, or sync never enabled) keeps the
  random per-install ID in `Setting.deviceId`.
- **Invariant:** when sync is enabled, the device has not synced anything yet. There is no
  `PeerIdentity` and no `PeerCursor`, and the identity is created before the first pairing can
  happen. `enableSync` checks this and refuses otherwise.
- **Local relabel:** in the same transaction, `enableSync` relabels this device's own rows (`Clip`,
  `ClipMember` and `MetaEvent` with the old ID) to the new ID.
  - **Why it's needed:** the send filters (`syncEnabledAt`, excluded apps; protocol.md section 10)
    apply to events whose author is this device. Without the relabel, clips from before sync was
    enabled would look foreign and be sent.
  - **Why it's safe:** nothing has left the device yet, so no peer ever sees the old ID. It is a
    local relabel, not a rewrite of synced history.

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
- **Desktop capture order (revised at step 4, section 11):**
  1. A CopyQ automatic command that hands every new clipboard item to `clipvault-agent ingest`.
  2. The CopyQ GNOME extension over D-Bus, when the command is not installed.
  3. No local capture (sync only) when CopyQ is absent. `wl-paste --watch` and AWT polling are
     dropped: CopyQ is the front end now.

### 6.4 Other desktop points

- **Tray and global hotkey: dropped at step 4.** CopyQ already provides both. The research stays
  for reference:
  - AWT `SystemTray` (which Compose `Tray` uses) shows no icons on GNOME Wayland or KDE
    (JDK-8146318).
  - The XDG `GlobalShortcuts` portal works on GNOME 48+ and KDE.
- **Per-window-class exclusion:** this works on X11 via `_NET_ACTIVE_WINDOW` + `WM_CLASS`. No
  standard Wayland protocol exposes the active window.
  - **Confirmed at step 4:** on GNOME 50 Wayland, CopyQ's `currentWindowTitle()` and
    `currentClipboardOwner()` return empty strings, and the GNOME extension's D-Bus interface carries
    no window information.
  - **Consequence:** neither capture path knows the source app there, so desktop exclusions and the
    password-manager window-title rule only work where CopyQ reports a title (X11, some
    compositors).
- **Packaging (moved to step 4):** the Compose Gradle plugin builds `.deb` and `.rpm` on Linux, but
  no AppImage (https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html).
  - **AppImage:** CI wraps the jpackage app-image with `appimagetool`, a build tool and not a
    runtime dependency.
  - **Where each format is built:** `.rpm` locally, since `rpmbuild` is on the dev machine; `.deb`
    and AppImage in CI on Ubuntu.

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

- **Java libraries in common code:** BouncyCastle, JmDNS and the SQLCipher drivers are all
  JVM libraries, but `commonMain` cannot call Java APIs.
  - **Resolved (step 1):** a hand-wired `jvmAndAndroidMain` source set (`dependsOn(commonMain)`,
    with `jvmMain` and `androidMain` depending on it) compiles JDK APIs (`MessageDigest`,
    `javax.crypto`, `java.io.File`) once for both targets. `:shared` keeps SHA-256, AES-GCM key
    wrapping and atomic key-file writes there.
  - **Wiring caveat:** the hierarchy-template DSL (`group { withJvm(); withAndroidTarget() }`) does
    not match the new AGP KMP target and is experimental, so the wiring is manual, with
    `kotlin.mpp.applyDefaultHierarchyTemplate=false` in `shared/gradle.properties` to silence the
    template warning.
  - **Not yet tried:** a Java library that ships different artifacts per platform (`tink` vs
    `tink-android`) can't be declared once in the shared set. That gets decided at step 6.
- **SQLDelight drivers (resolved step 1):** both drivers get the same raw 32-byte key, with no
  passphrase KDF.
  - **Android:** `SupportOpenHelperFactory(byte[])` with SQLCipher's raw-key literal
    `x'<64 hex>'`.
  - **JVM:** `SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(key)` passed as
    `Properties` to `JdbcSqliteDriver(url, props, schema)`.
  - **Factory:** a `fun interface DriverFactory` in common with `AndroidDriverFactory(context)` and
    `JvmDriverFactory(file)` rather than an `expect class`. Expect/actual classes are still Beta and
    need a compiler flag, and the Android constructor needs a `Context` anyway.
  - **Cross-opening:** not tested whether a desktop file opens on Android, since there is no
    working emulator here (`docs/dev/emulator.md`). We never copy DB files between devices.
- **FTS5 (resolved step 1):** available on both platforms.
  - **JVM:** sqlite-jdbc-crypt passes the FTS5 tests.
  - **Android:** sqlcipher-android 4.20.0 (SQLite 3.54.1) lists `ENABLE_FTS5` in its compile
    options. Verified on device (SM-S916B, Android 16).
  - **Schema (step 2):** two FTS5 tables keyed by `Clip.rowid` and maintained by triggers.
    `ClipFts` uses `tokenize = 'trigram remove_diacritics 1'` for substring search, which needs
    SQLite 3.34+ for trigram and 3.45+ for `remove_diacritics`. `ClipFtsPrefix` uses the default
    unicode61 tokenizer for word-prefix search.
  - **Trigger workaround:** `UPDATE ClipFts SET content = new.content` inside a trigger overflows
    SQLDelight 2.4.1's resolver, so the update triggers are delete + insert.
  - **Query (step 2):** user input is quoted per token, so FTS operators are literal (`ftsQuery`).
    When every token has at least 3 characters the trigram index is used ("hub" finds "github");
    otherwise the prefix index, because trigram cannot match shorter tokens. Both indexes fold
    diacritics ("kod" finds "kód"). Verified on jvm and on device.
  - **Cost:** the index is stored twice (trigram + prefix). Clips are mostly short text, so this
    was accepted.
- **Regex portability (found on device, step 2):** Android's `java.util.regex` is ICU-backed and
  rejects the `(?U)` (UNICODE_CHARACTER_CLASS) flag, which crashed `SensitiveDetector` at class
  init. On the JVM, however, `\b` is ASCII-only without `(?U)`, so `\bověřovací\b` never matches
  there. Shared regexes therefore use explicit `(?<![\p{L}\p{N}])` / `(?![\p{L}\p{N}])` word edges
  and lowercase the text instead of relying on case-insensitive flags for non-ASCII. Any regex
  change must be tested on a device too, which `commonTest` running in `androidDeviceTest`
  provides.
- **Paging on jvm (resolved step 1):** AndroidX publishes the JVM variant of Paging 3.5.3 as
  `paging-common-desktop`, not `-jvm`. The `paging-common-jvm` artifact stops at 3.4.0-alpha02,
  which is a red herring. `QueryPagingSource` works on jvm in tests.
- **Flavors:** single-variant AGP KMP plugin, so flavors only in `:androidApp` (section 2).
- **AGP 9 restriction:** `com.android.application` cannot share a module with KGP's multiplatform
  plugin. That is why the app modules are separate.
- **Coverage (resolved step 1):** Kover 0.9.11 works with the new AGP KMP plugin.
  - **Variants:** it registers both `Jvm` and `Android` report variants. Android classes appear in
    the merged report.
  - **Limitation:** Kover only measures host-JVM tests, not instrumented ones. Android-only classes
    (`KeystoreDbKeyProvider`, `AndroidDriverFactory`) therefore show 0% even though device tests
    cover them.
  - **Rule:** `koverVerifyJvm` (the gate CI runs) enforces 80% line coverage on common + jvm code,
    excluding SQLDelight-generated code. The merged `koverVerify` also counts Android-only
    classes at 0% and is therefore not the gate.
- **Android tests are instrumented, not Robolectric:** SQLCipher's native library is built for
  Android ABIs and cannot load on a host JVM, and Robolectric has no AndroidKeyStore provider.
  `withDeviceTestBuilder { sourceSetTreeName = "test" }` makes `commonTest` part of the device
  tests, so the same repository tests run on jvm and on a device.

## 7. Delivery plan

The brief's steps 0-9 stay as given. Adjustments:

- **Step 0:** generate with the wizard, add `:ui`, `:sync-core` and `:sync` as empty modules,
  set up flavors, and add a CI job (GitHub Actions) that runs `processVaultReleaseManifest` and
  greps the merged manifest. Both apps launch with placeholder UI.
- **Step 3:** done. Android capture paths, shared UI, settings.
- **Step 4 (revised 2026-10-28):** `clipvault-agent`, a headless daemon with CopyQ as the desktop
  front end, replaces the Compose desktop history UI (section 11).
  - **Moved here from step 9:** packaging (`.rpm`/`.deb`/AppImage) and the systemd user unit.
  - **Dropped:** tray, global hotkey and the desktop history/detail/settings screens.
- **Step 5:** done. The protocol is specified in [`docs/sync/protocol.md`](sync/protocol.md). The
  sync bookkeeping DB lives in `:sync-core`, catch-up is seq-based, and metadata changes are log
  entries (5.1).
  - **Members:** migration `4.sqm` adds `ClipMember`, so one `Clip` row can stand for the same
    content created on several devices. It also adds a `LamportClock` table, because retention must
    not move the clock back, and a unique MetaEvent index so duplicates are no-ops.
  - **Re-copy:** a re-copy bumps the newest live member instead of re-seq'ing the row.
  - **Pin and delete:** these act on every live member of a row.
- **Step 6:** part A, the specs [`docs/sync/handshake.md`](sync/handshake.md) and
  [`docs/sync/pairing.md`](sync/pairing.md), is reviewed before any handshake or pairing code.
  Part B implements them against the test vectors.
- **Step 6 part B notes (2026-10-28):**
  - **R8:** a minified sync build needs no keep or dontwarn rule for bcprov
    (`sync-core/consumer-rules.pro`). The test-only `minifiedCheck` build type keeps the sync code
    reachable (`androidApp/r8-check.pro`), and `SyncCryptoOnDeviceTest` passes in it on the device.
    The other keeps in that file only exist so the older instrumented tests can reach app internals.
  - **mDNS and the desktop firewall:** Fedora's `FedoraWorkstation` firewalld zone opens TCP and
    UDP 1025-65535 but not the `mdns` service (UDP 5353).
    - `pair --join` discovery therefore finds nothing on such a machine; `--join --addr` works.
    - Step 7 has to handle the same problem for discovery: detect it, say so in `status`, and tell
      the user how to allow mDNS. The app never changes the firewall itself (section 12).
- **Step 7:** transport and discovery, plus the agent's sync loop.
  - **Required:** each `SyncNode` runs on one single-threaded dispatcher. A
    convergence-under-concurrency test (local writes, received events and several live sessions
    on real threads) must pass before step 7 is done.
- **Step 8:** the sync UI on Android. Desktop pairing and status stay in the terminal
  (`clipvault-agent pair`, pairing.md section 5); there is no Compose desktop window in v1.
  - **Required:** a settings change that affects sync (sensitive opt-ins, `syncEnabledAt`,
    exclusions) closes and reconnects live sessions, so peers see the new Hello at once.
- **Step 9:** hardening. Desktop packaging already landed in step 4. Also our own GNOME Shell
  extension (about 100 lines of GJS) that exposes the focused window's app ID over D-Bus, so
  per-app exclusions work on GNOME Wayland (decided 2026-10-28; until then they are unavailable
  there, 11.6).

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
3. **Session security:** a SIGMA-style handshake built on BouncyCastle primitives (5.2). `docs/sync/handshake.md` is
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

## 9. Local detection and transformations (step 2)

Everything here is pure `commonMain` code, run on every insert. Each rule leans towards missing a
secret over flagging ordinary text.

**Sensitive categories (`SensitiveDetector.classify(text, hints)`):**
- **PASSWORD:** `isSensitiveFlag`, a package in `KnownSensitiveSources.packages`, or a window title
  containing a name in `KnownSensitiveSources.windowTitles`. The detector is content-blind here;
  the source signal alone decides.
- **CARD_NUMBER:** 13-19 digits, contiguous or in 4-digit (or Amex 4-6-5) groups. Requires a
  card-network prefix and a valid Luhn checksum, and the number must not touch `/` or `-` digits,
  which rules out bank accounts such as `123456-1234567890/0300`.
- **IBAN:** the ISO 13616 shape with a valid mod-97 checksum.
- **OTP:**
  - A clip that is only 6 digits (or `123 456`).
  - In a text of up to 300 characters, a 4-8 digit number (not a year 1900-2099) next to a
    keyword: code, kód, OTP, PIN, ověřovací, jednorázový and similar.
  - Bare 4, 5, 7 or 8 digits are deliberately not OTP. Missing a bare 4-digit PIN is accepted;
    hiding a Prague postcode like `11000` would be the worse failure.
- **API_KEY:**
  - OpenAI/Anthropic `sk-`, Stripe live keys, GitHub `gh?_` and `github_pat_`.
  - AWS `AKIA`/`ASIA`, Slack `xox?-`, Google `AIza`, JWT (`eyJ...eyJ...sig`).
- **PRIVATE_KEY:** a `-----BEGIN ... PRIVATE KEY-----` block.
- **CREDENTIALS_PAIR:**
  - `user:secret` on a single line. URL schemes, ports and paths are excluded.
  - An email line followed by one secret-looking line.
  - "Secret-looking" means no spaces, a letter, and a digit or a symbol other than `-_.`.
- **SSN_LIKE_ID:** Czech/Slovak rodné číslo `YYMMDD/XXXX`, with a valid date (+50/+20/+70 month
  offsets) and a mod-11 check. The bare 10-digit form counts only next to a "rodné číslo"/"RČ"
  label.

**Content type (`detectContentType`):** JSON (an object or array that parses) -> URL (the whole
clip) -> EMAIL -> PHONE (E.164 or Czech 9-digit with optional +420/00420) -> CODE (at least 3
lines, at least 40% of them code-like) -> PLAIN.

**Transformations (`Transformation`, each `(String) -> Result<String>`):**
- **Case:** trim, upper, lower, title and sentence case.
- **Strip formatting:** drops zero-width characters, turns NBSP into spaces, collapses runs of
  spaces and blank lines.
- **URL cleanup:** drops `utm_*`, `fbclid`, `gclid`, `igshid` and `si` from every URL in the text.
- **JSON:** pretty-print and minify. Invalid JSON fails soft, as `Result.failure`.
- **Base64:** encode and decode. Decode only succeeds when the result is printable UTF-8.

## 10. Android app (step 3)

### 10.1 Capture paths

All paths go through `ClipIngest`; there is no foreground service. "Background" below means
another app is in front.

| Path | Component | Exported | Reads the clipboard | Source app known |
|---|---|---|---|---|
| Quick Settings tile | `CaptureTileService` -> `CaptureActivity` | tile service only (needs BIND_QUICK_SETTINGS_TILE) | yes, once focused | no |
| Notification action | `CaptureNotification` (opt-in, low importance, not a FGS) -> `CaptureActivity` | no | yes | no |
| Share sheet | `ShareActivity` (`ACTION_SEND text/plain`) | yes | no, text comes in the intent | no |
| Text selection | `ShareActivity` (`ACTION_PROCESS_TEXT`) | yes | no | no |
| Accessibility (opt-in) | `ClipAccessibilityService` -> `CaptureActivity` | no (bound by the system) | yes | yes, last foreground app |

- **`CaptureActivity` is not exported.** Only our tile, notification and Accessibility service can
  start it, so no other app can make ClipVault read the clipboard or fake a source package.
- **How it reads:** it is an invisible activity that reads in `onWindowFocusChanged(true)`. It
  gives up after 2 s without focus ("open ClipVault and try again"), so it never sits invisibly
  over another app swallowing touches.
- **Outcomes it reports:** empty clipboard, non-text clip, denied (no focus), duplicate, sensitive
  source and excluded app each get their own feedback text (`CaptureFeedback`).
- **Housekeeping after a stored clip:** retention runs after every capture and on app resume. A
  sensitive clip also schedules an inexact `AlarmManager` sweep. WorkManager would have been
  simpler, but it declares `ACCESS_NETWORK_STATE`, which the vault flavor's guard rejects.

### 10.2 Accessibility auto-capture: events and measured reliability

- **Configuration:** `canRetrieveWindowContent="false"`, `isAccessibilityTool="false"`. The
  service sees event text and labels only, never window content.
- **Events hooked** (`CopySignal`):
  - `TYPE_VIEW_CLICKED` on a "Copy"/"Kopírovat"/"Copy link" button.
  - `TYPE_NOTIFICATION_STATE_CHANGED`, `TYPE_ANNOUNCEMENT` and `TYPE_WINDOW_STATE_CHANGED` whose
    text says "copied"/"zkopírováno", ignoring our own package.
  - `TYPE_WINDOW_STATE_CHANGED` from real apps is also used to remember the source package.
- **Debounce and delay:** 1 s debounce, then `CaptureActivity` starts 150 ms later.
- **Which signal fired:** on the Samsung SM-S916B (One UI, Android 16) every capture was triggered
  by One UI's own "Copied" confirmation (`TYPE_NOTIFICATION_STATE_CHANGED` from
  `com.android.systemui`). It shows up for any app's copy, so detection does not depend on how
  each app labels its Copy button.

| App | Copies performed | Captured | Source package correct | Notes |
|---|---|---|---|---|
| Chrome | 9 (+1 failed automation tap) | 9 | 9 | Copied by long-press, then the toolbar's Copy |
| Firefox | 10 | 10 | 10 | |
| Firefox, excluded in settings | 2 | 0 stored, 2 skipped as `EXCLUDED_SOURCE` | 2 | |
| Messenger (Signal/WhatsApp/Telegram) | not run | | | needs the user's own chats, see below |
| Notes app | not run | | | no notes app installed |

- **The one Chrome miss was the test script, not ClipVault.** The scripted tap hit "Select all"
  instead of "Copy", so nothing was copied, and the service correctly saw no copy event.
- **Messenger and notes runs need the owner.** They require driving and dumping the owner's
  private chats, so they are left to them, with the debug log below.
- **Observed caveats:**
  - **Force-stop:** force-stopping ClipVault (Settings > Apps > Force stop) makes One UI remove it
    from `enabled_accessibility_services`. Auto-capture then stays off until the user re-enables
    it; settings show "off".
  - **Screen off:** with the screen off or dozing, `CaptureActivity` cannot get focus and reports
    "open ClipVault and try again". It never fails silently.
  - **Paste toast:** Android 12+ shows "ClipVault pasted from your clipboard" on every
    Accessibility capture, as the settings screen warns.
- **Measuring on a device:** `adb logcat -s ClipVaultA11y:I ClipVaultCapture:I`. Debug builds only;
  the log has the event type and package names, never clip content.

### 10.3 UI and state

- **Placement:** screens and ViewModels live in `:ui` (common). Each ViewModel exposes
  `StateFlow<sealed State>`: `HistoryState`, `DetailState` and `SettingsState`.
- **Platform glue:** `AndroidAppPlatform` provides clipboard writes (marking sensitive clips with
  `EXTRA_IS_SENSITIVE`), share, Accessibility status, `requestAddTileService` and the launchable
  apps for the exclusion picker. It uses a `<queries>` launcher intent, not QUERY_ALL_PACKAGES.
- **Settings** live in the encrypted database (`Setting` table, migration `3.sqm`), loaded once
  per process. On device: retention count, TTL, theme, capture notification and exclusions all
  survived a force-stop.
- **Bugs found on device and fixed:**
  - The history list kept its scroll anchor, so clips captured in the background landed above the
    viewport. It now scrolls to the top on resume.
  - Theme and exclusion rows were only tappable on the small radio/checkbox. Whole rows are now
    `selectable`/`toggleable` with proper roles.
  - In the Light theme the status-bar icons were white on white. System bars now follow the app
    theme.
  - A class named `cz.tadeasf.clipvault.AndroidPlatform` existed in both `:shared` (the wizard
    sample) and `:androidApp`. Dex merging kept the wrong one, causing a `NoSuchMethodError`. The
    app's class is now `AndroidAppPlatform`.

### 10.4 CopyQ as the desktop front end (raised and decided 2026-10-28)

The idea: keep CopyQ on the desktop and use ClipVault on the phone, with history synced between
them. It is feasible with CopyQ 17's CLI: `add`, `insert`, `read`, `write`, `remove`, `count`
and `tab`, plus its scripting.

- **Desktop to phone:** needs no CopyQ access. ClipVault desktop already watches the same
  clipboard through CopyQ's GNOME extension (6.3), so it can run as a headless sync agent instead
  of a second history UI.
- **Phone to desktop:** synced clips go into CopyQ via its CLI, into the history tab or a
  dedicated "Phone" tab.
- **Pins and deletes:** need a mapping between CopyQ rows and ClipVault ids. One-way at first is
  simplest.
- **Privacy cost:** CopyQ's tab files are unencrypted unless its tab encryption (GnuPG) is on, and
  on the dev machine `encrypt_tabs=false`. Synced phone clips would therefore sit on disk in
  plaintext, which breaks principle 3 for them. Sensitive clips would never be written to CopyQ.
- **Decided 2026-10-28: go.** See section 11.

## 11. `clipvault-agent`: desktop daemon, CopyQ as front end (step 4)

### 11.1 Roles
- **CopyQ** is what the user sees and uses on the desktop: history, search, tray, hotkeys.
- **The agent** has no history UI. It owns the encrypted SQLDelight store, the same step-1 code as
  Android, which is the desktop's sync source of truth. CopyQ is only a view: the agent writes to
  it and never assumes CopyQ still holds anything, because CopyQ's own dedupe and retention may
  drop items.

### 11.2 Process model
- **`clipvault-agent run`** is the daemon, started by the systemd user unit. It is the only
  process that writes the store.
  - It listens on `$XDG_RUNTIME_DIR/clipvault/agent.sock`, inside a 0700 directory.
  - It flushes the CopyQ outbox and runs retention.
  - It never reads the clipboard at start, so a restart cannot double-ingest what is already
    there.
- **`clipvault-agent ingest [--title T] [--non-text]`** is invoked by the CopyQ command, with the
  clip text on stdin, never in argv. It forwards the text to the daemon over the socket. If no
  daemon answers, it opens the store and ingests itself (same code), so nothing is lost while the
  service is down. It always exits 0: CopyQ does not need to see our errors.
- **Other subcommands:**
  - `status`: daemon, store, capture mode, counters, CopyQ state and encryption, outbox backlog.
  - `install-copyq`: idempotent; adds or replaces only the command named "ClipVault ingest".
  - `install-service`: writes and enables the systemd user unit.
  - `push-test TEXT`: pushes TEXT into CopyQ the way a phone-origin clip would travel.
  - `config KEY [VALUE]`.

### 11.3 CopyQ facts the design relies on (CopyQ 17.0.0: its docs and source, and behaviour observed on this machine)
- **Automatic commands:** these run on every new clipboard item. `data(mimeText)` returns the
  clipboard text, and `execute(prog, args..., null, stdin)` runs a program with stdin.
  Commands are managed with `commands()` / `setCommands()`, so the installer edits only its own
  entry and keeps the user's other commands, for example their automatic "Archive to history".
- **Window title:** `mimeWindowTitle` (`application/x-copyq-owner-window-title`) comes from
  `currentWindowTitle()`, which is **empty on GNOME 50 Wayland** (6.4). The command passes
  `--title` only when it is non-empty.
- **`mimeOwner`:** a clipboard set by CopyQ itself, for example via `copyq copy`, is ignored by
  CopyQ, and no automatic command runs on it (scripting docs, `mimeOwner`). So with
  `promote_to_clipboard` on, CopyQ itself breaks the capture loop.
- **Reading text from stdin:** `copyq tab NAME add -` takes the text on stdin; tested.
- **Escape processing:** CopyQ's CLI processes escape sequences in arguments (`\n`, `\"`), which
  breaks JavaScript passed as `copyq eval SCRIPT`. The installer therefore sends its script with
  `copyq eval -` on stdin. Found on the dev machine at step 4, when the first install failed with a
  SyntaxError.
- **Clipboard tab name:** the user's clipboard tab is the value of `copyq config clipboard_tab`
  (`&clipboard` on the dev machine, with a mnemonic `&`). That is the default target. A literal
  `clipboard` would create a second tab.
- **Encryption:** CopyQ 17 has built-in tab encryption (`copyq config encrypt_tabs`, readable from
  the CLI). Turning it on needs a password set in the UI, so `install-copyq` only checks and prints
  instructions. `status` reports `copyq: tabs unencrypted` while it is off.

### 11.4 Data flows
- **Desktop to store:**
  1. CopyQ command -> `ingest`.
  2. `ClipIngest`: blank, sensitive source, excluded app.
  3. `SensitiveDetector` and content type.
  4. Store.
  - Non-UTF-8 or non-text input is counted and dropped.
  - Content-sensitive clips (OTP, card...) are stored hidden and expire after the sensitive TTL,
    as on Android. They are never written to CopyQ. CopyQ itself already has them, because it
    captured first. ClipVault cannot prevent that and does not reach into CopyQ's history.
- **Store to CopyQ (phone-origin clips from step 5; plumbing and `push-test` now):**
  - **Outbox:** queued in the store's `Setting` table under `agent.copyqOutbox`, so it survives
    restarts. The daemon flushes it whenever `copyq` answers, via `copyq tab TAB add -`.
  - **Promote:** with `promote_to_clipboard` on, the clip is also put on the live clipboard via
    `copyq copy -`.
  - **Never written:** sensitive clips and clips from excluded apps.
  - **Loop guard:** CopyQ's `mimeOwner` rule stops CopyQ from re-capturing. The agent also keeps
    a 30 s echo set of content hashes it promoted, and drops matching ingests before they reach
    `ClipIngest`. That covers the D-Bus fallback path, which sees every owner change.
- **CopyQ missing:**
  - Not running: the outbox grows and `status` shows the backlog.
  - Not installed: the agent runs sync-only and `status` says so.

### 11.5 Keys and headless start
The store key comes from the Secret Service (dbus-java). With no keyring, the passphrase fallback
needs a terminal. `run` in a terminal prompts for the passphrase; as a systemd service it has
nowhere to ask.

**A key that can't be used is fatal, not retried** (`StoreKeyException`). The agent prints one
line saying what to do and exits with 78 (`EX_CONFIG`); the unit's `RestartPreventExitStatus=78`
stops systemd from restarting it. Three cases:
- the store exists but its key is not in the keyring (for example, the keyring was replaced);
- the key in the keyring doesn't open the store (`SQLITE_NOTADB`);
- there is no keyring and no terminal to ask for a passphrase.

Before this, a replaced keyring made the service crash-loop: 95 restarts with exit 1.

### 11.6 Desktop exclusions
`excludedPackages` matches the window title CopyQ reports, a case-insensitive substring per
entry. On GNOME Wayland there is no title, so exclusions are inert there (6.4).
`clipvault-agent config exclude "KeePassXC"` adds one. The built-in password-manager titles
(`KnownSensitiveSources`) still apply where a title exists. On GNOME they are documented as
unavailable until the step 9 extension reports the focused app ID.

### 11.7 Sensitive clips and CopyQ (decided 2026-10-28)
Same rule as Android: sensitive clips are stored hidden in ClipVault and expire after 2 minutes.
CopyQ stores a clip before ClipVault sees it, so with `scrub_copyq_sensitive` (Setting
`agent.scrubCopyqSensitive`, default on):

- The agent answers the CopyQ command with `... scrub` for sensitive and password-manager clips.
  The command is installed first in CopyQ's list and calls `ignore()` on that answer, so the clip
  never reaches the tab and later automatic commands (e.g. an archive command) skip it.
- Backstop for clips that arrive by the GNOME D-Bus path or a slow command: the agent runs
  `removeNewestIfHash(tab, sha256)` at 1, 2 and 5 s. The hash comparison runs inside CopyQ
  (`sha256sum(read(0))`), so the agent never reads a row. Only the newest row; no retroactive scan.

### 11.8 CopyQ tab encryption: report only (revised 2026-10-28)
ClipVault does not change CopyQ's encryption settings. `install-copyq --check-encryption` reads
`copyq config encrypt_tabs`, prints the result and the three manual steps (`ENCRYPTION_ADVICE`),
and exits 1 while it is `false`. `status` prints the same line.

**Incident (2026-10-28):** an `install-copyq --encrypt` command briefly existed and was removed the
same day.
- **What it did:** it generated a random password inside the agent, stored it only in the login
  keyring (CopyQ's own item plus a ClipVault "backup" item in the same keyring), and turned on
  `encrypt_tabs` on the user's real CopyQ.
- **What went wrong:** the user later replaced the login keyring, and CopyQ's history became
  unrecoverable. The backup shared the original's single point of failure, and no copy of CopyQ's
  config or data was made beforehand.
- **What stopped:** generating secrets on the user's behalf and writing another app's settings. The
  rules in section 12 come from this incident.

**What was established, and how:**
- **Observed on this machine** (offscreen session `copyq -s cvenc` with its own
  `COPYQ_SETTINGS_PATH`): with `COPYQ_PASSWORD` in the server's environment, `config encrypt_tabs
  true` enabled encryption without a dialog. A restart without the variable opened the same number
  of items.
- **Observed as Secret Service metadata:** an `org.qt.keychain` item with attributes
  `server=com.github.hluk.copyq` and `user=Password` (`PasswordForSession-<name>` for a named
  session), labelled `<user>@com.github.hluk.copyq`.
- **Read in source only** (CopyQ 17.0.0 `src/common/keychainaccess.cpp`): CopyQ stores that
  password through QtKeychain read, write and delete jobs.
- **Not established:** CopyQ's key derivation and where it keeps its encrypted data. The user
  reports the encrypted data was in `copyq_tab_*.dat` under `~/.local/share/copyq/copyq/`.

The systemd unit sets `SuccessExitStatus=143`: SIGTERM ends the JVM with 143, which is a clean
stop, not a failure.

### 11.9 Privacy rule for testing (from 2026-10-28, all future steps)
Never read rows from the user's CopyQ tabs, ClipVault store or clipboard. Tests that need real
data flow use a CopyQ tab `clipvault-test` that the test creates, fills with generated content and
deletes. Aggregate counts (`status`) are fine.

## 12. Secrets and third-party config (rules, from 2026-10-28)

These bind both the code and whoever works on it, including the assistant in a session.

1. **No secrets on the user's behalf.**
   - Nobody generates, stores or handles a secret for the user.
   - Keys ClipVault needs are generated by ClipVault at runtime, for the user running it, through
     its normal key paths (`keyFor()`, and later the identity key). A developer or assistant session
     never creates one.
   - ClipVault never generates passwords for other apps.
2. **Writes need a per-action yes.**
   - No writes under `~/.config` or `~/.local/share` outside ClipVault's own directories, and no
     keyring writes except ClipVault's own entries, without the user's explicit yes in chat for
     that action.
   - Reading state is fine.
   - ClipVault's own directories: `~/.local/share/clipvault`, `$XDG_RUNTIME_DIR/clipvault`, and its
     unit file `~/.config/systemd/user/clipvault-agent.service`.
   - The only thing the agent writes to CopyQ's configuration is its own "ClipVault ingest"
     automatic command (`install-copyq`).
3. **Back up before changing another app's config.**
   - Copy that app's config dir to `~/clipvault-backups/<app>-<timestamp>/` first, and name the copy
     in the report.
4. **Claim only what was checked.**
   - "Verified" means it was run or observed.
   - Reading code or docs is reported as "read in source" or "per the docs".
5. **No user data in tests** (11.9): no rows from the user's CopyQ, ClipVault store or clipboard;
   test data is generated, and lives in a `clipvault-test` tab or a throwaway session.
6. **No keyring daemons on the user's machine (from 2026-10-28, no exceptions).**
   - Nobody starts `gnome-keyring-daemon`, a `dbus-run-session` with a keyring, or any other Secret
     Service daemon on the user's machine, under any `HOME` or `XDG_DATA_HOME`. There are no
     "temp dir" variants either.
   - Keyring integration (`DesktopKeyTest.secretServiceRoundTrip`) is tested only in CI, on a fresh
     runner.
   - Locally, the root build gives every JVM test task a session bus address that can't connect, so
     no test reaches a Secret Service. Keyring-dependent tests use the in-process fake (`FakeKeyring`
     in `DesktopKeyTest`) or are skipped, and the skips are listed in the step report.
   - **Why:** on 2026-10-28, "throwaway" keyring daemons started for local test runs used the real
     `~/.local/share/keyrings` and rewrote the user's `login.keyring`, twice (17:36 and 19:26). The
     first time cost the CopyQ history and the ClipVault store.
