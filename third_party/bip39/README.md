# BIP-39 English wordlist

`english.txt` is the BIP-39 English wordlist (2048 words), used for the pairing fingerprint words
(docs/sync/pairing.md section 1.4). It is vendored unchanged.

- Source: https://github.com/bitcoin/bips/blob/ce1862ac6bcffa1dd20aad858380e51e66e949ea/bip-0039/english.txt
- SHA-256 of `english.txt`: `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`
- Licence: MIT, as stated in the header of BIP-0039 (`License: MIT`). The full text is in `LICENSE`.

The Kotlin copy (`Bip39English.kt` in `:sync-core`) is generated from this file; a test checks
that the two match.
