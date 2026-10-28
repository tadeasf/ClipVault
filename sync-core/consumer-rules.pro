# BouncyCastle (bcprov-jdk18on) is used through its lightweight API only: direct calls, no JCA provider
# registration and no reflection, so R8 may shrink and rename it freely. Checked 2026-10-28: a minified sync
# build (androidApp minifiedCheck) needs no rule for bcprov, and SyncCryptoOnDeviceTest (handshake, channel,
# J-PAKE, CBOR, QR payload) passes inside it on an SM-S916B. Add rules here only with a failing check.
