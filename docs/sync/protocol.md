# ClipVault sync protocol, v1.0

Status: step 5. Wire model and replication rules only. No network, no crypto: step 6 seals every
envelope in the handshake's session keys and encrypts `ClipCreated.payload`, step 7 carries the
envelopes over the LAN. Code: `:sync-core`, package `cz.tadeasf.clipvault.sync`.

## 1. Goals

- Every device ends up with the same visible history, whatever order events arrive in, however
  often they arrive, and whichever device relays them.
- A device only ever asks a peer for "what is new since last time", and never misses an event that
  reached that peer through a third device.
- Nothing the user did not opt into leaves the device: sensitive clips, excluded apps, and history
  from before sync was turned on stay local.

## 2. Encoding: CBOR

Messages are encoded with CBOR (RFC 8949) through `kotlinx-serialization-cbor` 1.12.0.

Why CBOR rather than protobuf-lite:
- It is self-describing, so a v1.0 reader can skip fields added in v1.1 without a schema. That is
  exactly the minor-version rule in section 4.
- `kotlinx-serialization-protobuf` needs a `@ProtoNumber` on every field to stay compatible, and it
  is just as experimental as the CBOR format.
- `ByteArray` is a native CBOR byte string (`alwaysUseByteString`), with no base64 step.
- Field names cost a few bytes per message. That does not matter on a LAN with clips of a few
  hundred bytes.

JSON (`WireCodec.debugJson`) is only for logs and tests. It is never sent.

## 3. Envelope

```
Envelope { version: {major, minor}, type: String, seq: Long, payload: ByteArray }
```

- **`type`** is the message's name (`hello`, `catchup_request`, ...). It is a string rather than an
  enum, so an unknown type from a newer minor version can be ignored instead of failing the decode.
- **`seq`** counts envelopes per connection and direction, starting at 1. A receiver drops a
  connection whose `seq` does not increase. Step 6 binds it into the AEAD nonce for replay
  protection. It is unrelated to the event `seq` in section 6.
- **`payload`** is the CBOR encoding of the message named by `type`.

## 4. Versioning

The current version is **1.0** (`SyncProtocol.MAJOR`, `SyncProtocol.MINOR`).

- **A different major version is refused.** The receiver answers `Bye("unsupported major N")` and
  closes. A higher major means a breaking change. There is no older major to fall back to.
- **A higher minor version is accepted.** Decoding ignores unknown fields
  (`ignoreUnknownKeys = true`), and unknown message types are dropped. A minor version may only add
  optional fields (with defaults) and new message types.
- **New event kinds need a major bump.** An event a peer cannot fold would break convergence.

## 5. Messages

| type | body | sent by | meaning |
|---|---|---|---|
| `hello` | `deviceId, sensitiveOptIn: Set<String>, maxPayloadBytes` | both, first | who I am, which sensitive categories I accept, the largest payload I take inline |
| `catchup_request` | `sinceSeq, onlySensitive: Set<String>?` | receiver | "send me your events with seq > sinceSeq" |
| `catchup_batch` | `events, hasMore, throughSeq` | sender | one page, in seq order |
| `push` | `event` | sender | a new event after catch-up finished |
| `ack` | `seq` | receiver | applied everything up to `seq` |
| `fetch_payload` | `clipId` | receiver | "send me this over-cap clip's content" |
| `payload` | `clipId, bytes?` | sender | the content, or null when it is gone or not allowed |
| `bye` | `reason` | either | closing, with a reason for the log |

Two fields go beyond the step 5 brief:
- **`catchup_batch.throughSeq`** is the highest seq the sender *scanned* for this page, including
  events it filtered out. Without it, a page where every event was filtered would leave the
  receiver's cursor where it was, and it would ask for the same page forever.
- **`catchup_request.onlySensitive`** asks for a re-scan from seq 0 restricted to sensitive clips of
  the given categories (section 9).

Sensitive categories travel as strings. A name this version doesn't know maps to `UNKNOWN`.

## 6. Events

```
SyncEvent = ClipCreated | MetaChanged      common: seq, lamport, deviceId

ClipCreated { clipId, createdAt, contentType, contentHash, sensitive, categories,
              payload: ByteArray?, payloadSize }
MetaChanged { clipId, field: "PINNED" | "DELETED", value }
```

- **`seq`** is the *sender's* local seq for this event. That is all a cursor needs.
- **`lamport`** and **`deviceId`** are the *author's*. They never change when an event is relayed,
  because last-writer-wins (LWW) ordering depends on them.
- **`payload`** is the clip's UTF-8 text in step 5 and opaque bytes from step 6. It is null when the
  clip is over the receiver's cap (section 10). `contentHash` is SHA-256 of the plaintext, which lets
  the receiver check a payload fetched later.
- **Bumps:** a `ClipCreated` for a known `clipId` with a larger `createdAt` is a re-copy bump. The
  receiver keeps the maximum.

## 7. The local log

Each device keeps one log in its encrypted `ClipDatabase`. Every event it stores, its own or
received, gets the next value of the shared `SeqCounter` (design 5.1).

- **`ClipMember`** (migration `4.sqm`): one row per `ClipCreated` id the device knows,
  `{id, clipId, deviceId, createdAt, lamportClock, seq}`. `clipId` points at the `Clip` row that
  shows it.
- **`Clip`**: one row per distinct live content, the *group*. Its pinned, deleted and createdAt
  values are always recomputed from its members (section 8).
- **`MetaEvent`**: unchanged, except that `clipId` names a member.
- **Re-copy** of a live clip does not create a member. It bumps `createdAt` of the newest live member
  and gives it a new seq, so the bump is replicated.

### Why groups (dedupe by content hash)

Two devices can copy the same text independently. That gives two `ClipCreated` events with
different ids and one content. Each device shows them as one clip.

- **Join rule:** an incoming `ClipCreated` whose hash matches a live local row joins that row.
  Otherwise it creates a new row.
- **Member state:** each member keeps its own flags. Metadata always targets a member id, never a
  row.
- **Row state:** a row's visible state depends only on its live members (section 8). All live
  members with the same hash are always in one row, because members are never undeleted. So the
  visible state for each content depends only on the set of events. That is what makes it converge,
  even though devices may group deleted members differently.
- **UI actions:** pin, unpin and delete in the UI act on a row, so they write one `MetaChanged` per
  live member of that row.

## 8. Folding

Per member (design 5.1, unchanged `foldMeta`):
- **Pinned:** LWW on `(lamport, deviceId)`. A higher Lamport wins, and a tie goes to the larger
  `deviceId`. This is stable whatever order the events arrive in.
- **Deleted:** any `DELETED=true` wins over every flag, whatever its clock. A delete is final.

Per row:
- **`deleted`** = no live member.
- **`pinned`** = some live member is pinned.
- **`createdAt`** = the newest `createdAt` among live members, or among all members if none is live.
- **`sensitive` / `categories`** = OR / union over members. The detector is deterministic in the
  content, so members of the same content agree anyway.
- **When the row becomes deleted**, its content and hash are wiped (design 5.1).

## 9. Applying a received event: `SyncApplier.apply(event, fromPeer)`

The whole apply runs in one `ClipDatabase` transaction.

1. Lamport receive rule: `clock = max(clock, event.lamport) + 1` (the `LamportClock` table, migration
   4). Local events do `clock += 1`. The clock is a table, not "max over rows", because retention
   deletes rows, and the clock must never go backwards.
2. `ClipCreated` for a known member:
   - a larger `createdAt` means a bump: store it, assign a new local seq, refold the row;
   - a payload for a row that is still missing content fills it, after the hash check;
   - anything else is a no-op (duplicate).
3. `ClipCreated` for a new member: join the live row with the same hash, or create a row. Insert the
   member with a new local seq, then refold. MetaEvents that arrived before the clip are already
   stored, so the refold picks them up.
4. `MetaChanged`:
   - an exact duplicate (`clipId, field, value, lamport, deviceId`, unique index) is a no-op;
   - otherwise store it with a new local seq and refold its member's row, if the member is known;
   - a meta for an unknown member is kept and folded in when the member arrives.
5. Record `EventOrigin(localSeq -> fromPeer)` in the sync database, so the event is never sent back
   to the peer it came from.

Every step is idempotent, and folds are commutative, so duplicates and reordering give the same
state.

**Crash safety:** step 5 writes to a second database. A crash between the two transactions can only
cause an event to be sent back to its origin once, and the origin ignores it as a duplicate.
Cursors (section 11) advance only after the apply, so a crash re-delivers events, which is also
harmless.

**Retention** stays local policy (design 5.1). It removes rows and their members outright.
Orphaned `DELETED` metas are kept, so a deleted clip that reaches the device again later through a
slower path stays deleted.

## 10. Sending: what leaves the device

An event goes to peer P only if all of these hold:

- **Not P's own:** P is not the event's `EventOrigin`.
- **Sensitive:** the clip is not sensitive, or all of its categories are in P's `hello.sensitiveOptIn`.
- **Local filters, own events only** (`deviceId == self`):
  - its source app is not excluded;
  - `createdAt >= syncEnabledAt`.
  The author already applied these to events it relays from others. The source app itself is never
  sent.
- **Deleted members:** a member that is deleted is not sent as `ClipCreated`. Its `DELETED` meta is
  sent, so peers that got it some other way delete it too.
- **MetaChanged:** follows its member's clip through the same filters. A meta for a member the
  device doesn't have, such as an orphan, is relayed as is.

Payload cap (design 5.5):
- `ClipCreated.payload` is null when the content is larger than
  `min(own cap, P.maxPayloadBytes)` (default 1 MB). `payloadSize` says how large it is.
- The receiver stores the row with empty content and the real hash, which is how a missing payload
  is recognised: `content = '' AND contentHash != ''`. A blank clip is never stored, so this is
  unambiguous.
- `fetch_payload` asks for the content on demand. The answer goes through the same filters, so a
  fetch cannot get around them. The receiver checks the hash before storing.

**Opting in later:**
- A cursor has already moved past the sensitive events that were filtered out. So when a device's
  opt-in set gains categories since its last sync with P, it sends
  `catchup_request(0, onlySensitive = added)` after the normal catch-up.
- **Opting out later** only stops new sensitive events. Clips already received stay until their
  TTL removes them. They are not deleted retroactively.

## 11. Session flow and cursors

Both sides run the same state machine (`SyncSession`). A connection is symmetric: each side is
sender and receiver at once.

```
A -> B  hello(A, optIns, cap)                 B -> A  hello(B, optIns, cap)
A -> B  catchup_request(cursor[B])            B -> A  catchup_request(cursor[A])
B -> A  catchup_batch(events, hasMore, through)  ... A applies, cursor[B] = through, ack(through)
A -> B  catchup_request(through)    while hasMore
                    after the last page: B pushes new events to A as they are stored
```

- **`PeerCursor{peerId, lastSeqReceived, optIns}`** lives in the sync bookkeeping database.
  - It is advanced after the events are applied. On a `push`, it moves to `event.seq`; skipped seqs
    were filtered for this peer anyway.
  - `optIns` is the opt-in set this device had when it last synced with that peer. A gain against
    it triggers the re-scan above.
- **Live phase:** once a sender has answered a request with `hasMore = false`, it pushes every new
  event with seq > the last `throughSeq`, in seq order. That includes events it just received from a
  third device.
- **On disconnect** the session is dropped. Reconnecting starts over from `hello`. The cursor makes
  catch-up resume where it stopped.

### Why seq and not Lamport for catch-up (the relay bug)

1. B has made 10 events (clock 10), and A has synced them, so A remembers "B up to clock 10".
2. C makes 5 events at clocks 1..5 and syncs them to B. B stores them with their author clocks 1..5.
3. A reconnects and asks B for "clock > 10". C's events never come.

With seq, B stores C's events as B-seq 11..15, A asks for "B-seq > 10", and gets them.
`RelayTest` reproduces both: a naive Lamport-cursor implementation loses the events, and the seq
implementation delivers them.

## 12. The sync bookkeeping database (`SyncDatabase`)

- **Location:** a separate SQLDelight database in `:sync-core`, so the vault flavor's `ClipDatabase`
  holds no sync state.
- **Encryption:** encrypted with the same key from the same `DbKeyProvider`, opened through the same
  platform driver factories.
- **`PeerCursor(peerId PK, lastSeqReceived, optIns)`:** catch-up position per peer (section 11).
- **`EventOrigin(seq PK, peerId)`:** which peer a stored event came from (section 9).

## 13. Tests

All tests run in-process, with three devices (A, B, C) and a message bus between them. No network.
Each device has a real encrypted `ClipDatabase` and `SyncDatabase`.

**Table-driven (`ConvergenceTest`):**
- a pin on A and a delete on B, in both arrival orders;
- clock ties broken by deviceId;
- the C→B→A relay, naive versus seq;
- duplicates and reordering;
- catch-up after a disconnect, including events relayed from a third device;
- sensitive opt-in and opt-out;
- an over-cap payload.

**Property-based (`ConvergencePropertyTest`):** random local operations and random interleavings of
message delivery and disconnects. After reconnecting everyone and draining the bus, all three
visible states are equal.

- **Library:** kotest-property 6.2.6. It is multiplatform, runs inside plain `kotlin.test` tests
  without the kotest engine, and shrinks failing cases. jqwik would need the JUnit 5 platform and
  works on the JVM only.

## 14. Not in v1.0

- No Lamport-based conflict on content. Clips are immutable; only flags and `createdAt` merge.
- No partial or streamed payloads: `payload` carries the whole clip.
- No re-scan for categories after an opt-out followed by another opt-in within one session. The
  next connect handles it.
