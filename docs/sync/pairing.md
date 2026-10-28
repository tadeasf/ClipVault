# ClipVault pairing, v1

Status: step 6 part A, spec for review. No code yet.

Pairing introduces two devices to each other: afterwards each holds the other's Ed25519 identity
key (handshake.md section 2). Both methods run **one connection that is both the pairing and the
first session**:
1. a binding secret (QR token or PAKE key) enters the handshake transcript
   (`PAIR_QR`/`PAIR_CODE` mode, handshake.md section 3);
2. the identities are exchanged inside the encrypted channel (section 3);
3. both users compare six words (section 1.4).

**Roles:** the device that *shows* the QR code or the 6-digit code is the handshake responder
**B**: it listens. The device that scans or types is the initiator **A**: it connects.

## 1. QR pairing

### 1.1 Pairing window
- B opens a window that lasts **2 minutes**.
- It draws a **128-bit token** from a CSPRNG and shows the QR code.
- While the window is open, B accepts handshakes in `PAIR_QR` mode. At any other time it aborts
  them.
- The token is **single use**: the first `PAIR_QR` handshake that reaches the Fin check consumes
  it, whether that check passes or fails. Success, failure or timeout closes the window.

### 1.2 QR payload
The QR code holds the text `CVP1:` followed by unpadded base64url of this CBOR map. The map uses
deterministic encoding (RFC 8949 section 4.2.1) and small integer keys:

| Key | Field | CBOR type | Bytes in the encoding |
|---|---|---|---|
| 1 | version, = 1 | uint | 1 + 1 |
| 2 | addresses: 1 to 4 site-local addresses B listens on | array of bstr, 4 bytes (IPv4) or 16 bytes (IPv6) each | 1 + 1, then per address 1 + 4 or 1 + 16 |
| 3 | port | uint (≤ 65535) | 1 + 3 |
| 4 | token | bstr, 16 bytes | 1 + 17 |
| 5 | expiresAt (unix seconds; informational, B enforces the window) | uint | 1 + 5 |

The map header takes 1 byte.
- **One IPv4 address:** 38 bytes of CBOR, 51 base64url characters, 56 characters with the
  prefix.
- **Worst case, four IPv6 addresses:** 101 bytes, 140 characters.
- **Not in the payload:**
  - **No identity key, device ID or name.** Those travel encrypted (section 3), so a photo of the
    QR reveals only an address and a token that expires within 2 minutes.
  - **No ephemeral key.** Design 5.3 planned one; it's dropped because the token already
    authenticates B.

### 1.3 Flow
1. A scans the code, checks `version` and `expiresAt`, and connects to the first address that
   answers.
2. A runs the handshake in `PAIR_QR` mode with `binding = token`. The token is never sent.
   - A man in the middle doesn't know the token, so it can't derive the handshake keys, and the
     MACs fail (handshake.md section 4).
3. The identity exchange follows (section 3), then the fingerprint check (1.4).

### 1.4 Fingerprint words
Both devices show the same six words, computed from both identities:

```
lo, hi = the two identity public keys, ordered bytewise
fp     = H(lp("clipvault-fp-v1") || lp(lo) || lp(hi))
words  = the first 66 bits of fp, as six 11-bit indices into the BIP-39 English wordlist
```

- **Wordlist:** 2048 words, MIT licence:
  https://raw.githubusercontent.com/bitcoin/bips/master/bip-0039/english.txt
- **Confirmation:** the user confirms on each device that the words match (desktop: type `yes`).
  Each device sends `PairConfirm` when its user confirms.
- **Storing the peer:** a device stores the peer only once its own user has confirmed *and* it has
  received the peer's `PairConfirm`.
- **Mismatch, rejection or 2-minute timeout:** nothing is stored and the connection closes.
- The words are defence in depth. The token already authenticates the session, and the words catch
  a QR code that was swapped for someone else's.

## 2. 6-digit code (PAKE)

### 2.1 Which PAKE: finite-field J-PAKE, not EC J-PAKE (open question)
You asked for BouncyCastle's EC J-PAKE. I checked BouncyCastle 1.86 with `javap`:

- **EC J-PAKE can't be received.** `org.bouncycastle.crypto.agreement.ecjpake.ECJPAKEParticipant`
  ([doc](https://downloads.bouncycastle.org/java/docs/bcprov-jdk18on-javadoc/org/bouncycastle/crypto/agreement/ecjpake/ECJPAKEParticipant.html))
  can *create* its round payloads. But the Schnorr proof inside them, `ECSchnorrZKP`, has only a
  **package-private** constructor `ECSchnorrZKP(ECPoint, BigInteger)`. So a receiver can't rebuild
  a peer's payload from bytes through the public API.
- **The obvious workarounds both fail or are fragile:**
  - A helper class placed in BouncyCastle's package fails on the desktop JVM, because
    `bcprov-jdk18on-1.86.jar` is **signed** (`META-INF/BCRSA204.SF`). The JVM refuses classes of
    one package from jars with different signers.
  - Reflection on that constructor would work, but it depends on a private detail.
- **Finite-field J-PAKE works with public classes.**
  `org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant`
  ([doc](https://downloads.bouncycastle.org/java/docs/bcprov-jdk18on-javadoc/org/bouncycastle/crypto/agreement/jpake/JPAKEParticipant.html))
  has **public** payload constructors: `JPAKERound1Payload(String, BigInteger, BigInteger,
  BigInteger[], BigInteger[])` and `JPAKERound2Payload(String, BigInteger, BigInteger[])`. It
  ships the group `JPAKEPrimeOrderGroups.NIST_3072`.
- **Cost:** bigger messages, about 1.6 KB and 0.8 KB, which is irrelevant on a LAN.
- **Same protocol design** (J-PAKE, Hao and Ryan; the EC form is RFC 8236, with RFC 8235 Schnorr
  proofs).

**Proposal:** finite-field J-PAKE over `NIST_3072` with SHA-256. Everything below assumes it. If
you prefer EC J-PAKE through reflection, only the payload encoding in 2.3 changes.

### 2.2 Code window
- B shows a code: six digits, uniform from 000000 to 999999 (CSPRNG). It is valid for **2
  minutes** and for **one attempt**.
- Only B listens for `PAIR_CODE`, and only while a code is shown.
- **A failed attempt invalidates the code**, and B shows a new one. Failures are J-PAKE validation
  errors and handshake failures.
- **Rate limit:** 3 failures per 10 minutes per peer address. After that, B refuses code pairing
  from that address for the rest of the 10 minutes and says so on screen.
- **Guessing odds:** every attempt is one guess against a fresh code, so the chance of a correct
  guess is 10^-6 per attempt and at most 3 attempts per 10 minutes per address.

### 2.3 Exchange (two rounds, then the handshake, one connection)
```
participant ids: A = "clipvault-A", B = "clipvault-B"
both:  JPAKEParticipant(id, code.toCharArray(), JPAKEPrimeOrderGroups.NIST_3072, SHA256Digest(), SecureRandom())

A -> B  PakeR1  u8 0x10 || "CVPK" || u8 version=1 || payload1(A)
B -> A  PakeR1  u8 0x10 || payload1(B)
A -> B  PakeR2  u8 0x11 || payload2(A)
B -> A  PakeR2  u8 0x11 || payload2(B)
then    Init / Resp / Fin in mode PAIR_CODE (handshake.md section 3)
```

**Encoding:**
- An integer is `lp(unsigned big-endian, minimal length)`.
- A `BigInteger[]` is `u8 count || each integer`.
- `payload1 = lp(participantId) || gx1 || gx2 || proofX1[] || proofX2[]`.
- `payload2 = lp(participantId) || A || proofX2s[]`.
- Each message is length-framed like every other message on the stream (`u32be(len) || body`). A
  PAKE message over 4 KiB aborts.

**Deriving the binding:**
- Each side calls `validateRound1PayloadReceived`, then `validateRound2PayloadReceived`, then
  `calculateKeyingMaterial()`.
- `km` = that `BigInteger` as 384-byte unsigned big-endian.
- `k_pake = HKDF(km, "", "clipvault-pake-v1", 32)`.
- `binding = k_pake` for the `PAIR_CODE` handshake.

**No round 3:** J-PAKE's optional key-confirmation round is not used. The handshake MACs confirm
the key, so pairing and session stay one flow.

The identity exchange (section 3) and the fingerprint words (1.4) follow, the same as for QR.

## 3. Identity exchange (both methods)
Right after Fin, before any sync message, each side sends one encrypted frame (handshake.md
section 5). Its envelope holds:

```
Identity { idPub: bstr(32), name: tstr (≤ 64 bytes UTF-8), sig: bstr(64) }
sig = SIGN(id, lp("clipvault-id-v1") || lp(h3) || lp(name))      h3: handshake.md section 3.3
```

**The receiver checks:**
- `sig` verifies under `idPub`, which proves possession of the key and ties it to this session;
- `idPub` is not its own key.

**Re-pairing:** if `idPub` is already paired, the name is updated and `firstSeen` is kept.

**After both `PairConfirm`s** (1.4), each device stores
`PeerIdentity { idPub, name, firstSeen, lastSeen }` in `SyncDatabase`.

**New protocol message types:** `identity`, `pair_confirm` and `revoke` (section 4). Older
readers ignore unknown types, so they are minor-version additions (protocol 1.1).

## 4. Unpair and revoke
- **Unpair is local and immediate.** It deletes the `PeerIdentity` and its `PeerCursor`. Clips
  that came from the peer stay in history.
- **If a session to that peer is open,** the device sends an encrypted `Revoke{}` and closes.
- **If not,** it keeps a `PendingRevoke { idPub, until = now + 30 days }`. If that identity
  connects before then, the handshake is allowed *only* to deliver `Revoke`. Then the connection
  closes and nothing syncs.
- **Receiving `Revoke`:** the device deletes the sender's `PeerIdentity` and shows a notice.
- **No central revocation, and that is accepted.**
  - A device that never receives `Revoke` keeps trying to connect. Every handshake fails, because
    its key is no longer in the paired set (handshake.md section 3.4).
  - For a **stolen device**, the user unpairs it on every remaining device. Until then the thief
    can impersonate it to devices that still trust it. That is the threat model's stated limit
    (handshake.md section 1).

## 5. Desktop surface (v1, terminal only)
- **`clipvault-agent pair`** opens the QR window.
  - It prints the QR code to the terminal (Unicode half blocks, rendered from zxing core's
    `BitMatrix`).
  - It also writes a PNG to `$XDG_RUNTIME_DIR/clipvault/pair-<timestamp>.png` (0700 dir, tmpfs),
    deleted when the window closes.
  - It shows the countdown, then the six words, and asks `Do the words match? [yes/no]`.
- **`clipvault-agent pair --code`** shows the 6-digit code and a countdown, then the same
  confirmation.
- **No Compose window on desktop in v1.** This replaces the step 8 plan item "a small Compose
  desktop window for pairing".
- **Pairing two desktops:** one side runs `pair --code`. How the other side *enters* the code and
  finds the address is open question 4.
