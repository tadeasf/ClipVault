#!/usr/bin/env python3
"""Reference test vectors for docs/sync/handshake.md (clipvault-hs-v1) and docs/sync/pairing.md.

Written from the two documents, not from the Kotlin code, so the Kotlin HandshakeVectorsTest has an
independent implementation to agree with byte for byte.

    python3 tools/handshake_vectors.py          # (re)write the JSON
    python3 tools/handshake_vectors.py --check  # fail if the committed JSON differs (CI)

Needs the pinned versions in tools/requirements.txt (cryptography, PyNaCl).
"""

import argparse
import hashlib
import hmac
import json
import pathlib
import sys

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from nacl.bindings import crypto_aead_xchacha20poly1305_ietf_decrypt as xchacha_open
from nacl.bindings import crypto_aead_xchacha20poly1305_ietf_encrypt as xchacha_seal
from nacl.exceptions import CryptoError

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "sync-core/src/commonTest/resources/handshake-vectors.json"
WORDLIST = ROOT / "third_party/bip39/english.txt"

LABEL = b"clipvault-hs-v1"
MODES = {"session": 0x01, "pair_qr": 0x02, "pair_code": 0x03}

# handshake.md section 7: fixed inputs.
INPUTS = {
    "eph_a": bytes(range(0x00, 0x20)),
    "eph_b": bytes(range(0x20, 0x40)),
    "id_a_seed": bytes(range(0x40, 0x60)),
    "id_b_seed": bytes(range(0x60, 0x80)),
    "nonce_a": bytes([0xA1]) * 32,
    "nonce_b": bytes([0xB2]) * 32,
    "token": bytes([0x7E]) * 16,
    "k_pake": bytes([0x5C]) * 32,
    # For the replayed-Fin negative case: a fresh Resp from B.
    "eph_b2": bytes(range(0x80, 0xA0)),
    "nonce_b2": bytes([0xC3]) * 32,
    # For the J-PAKE keying-material KDF (pairing.md 2.3): a fixed 384-byte km.
    "pake_km": bytes(range(256)) + bytes(range(128)),
    "identity_name": "Test device".encode(),
    "frame_plaintext": b"clipvault test frame",
}


def lp(data: bytes) -> bytes:
    return len(data).to_bytes(4, "big") + data


def sha256(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(ikm)


def hmac_sha256(key: bytes, data: bytes) -> bytes:
    return hmac.new(key, data, hashlib.sha256).digest()


def x25519_pub(secret: bytes) -> bytes:
    return X25519PrivateKey.from_private_bytes(secret).public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)


def x25519(secret: bytes, peer: bytes) -> bytes:
    shared = X25519PrivateKey.from_private_bytes(secret).exchange(X25519PublicKey.from_public_bytes(peer))
    assert shared != bytes(32), "all-zero DH output aborts"
    return shared


def ed_pub(seed: bytes) -> bytes:
    return Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)


def sign(seed: bytes, message: bytes) -> bytes:
    return Ed25519PrivateKey.from_private_bytes(seed).sign(message)


def verifies(pub: bytes, message: bytes, signature: bytes) -> bool:
    try:
        Ed25519PublicKey.from_public_bytes(pub).verify(signature, message)
        return True
    except InvalidSignature:
        return False


def handshake(mode: int, binding: bytes, eph_b: bytes, nonce_b: bytes) -> dict:
    """handshake.md sections 3.2 to 4, for one mode."""
    i = INPUTS
    session = mode == MODES["session"]
    eph_a_pub, eph_b_pub = x25519_pub(i["eph_a"]), x25519_pub(eph_b)

    init = bytes([0x01]) + b"CVHS" + bytes([1, mode]) + eph_a_pub + i["nonce_a"]
    t1 = lp(LABEL) + lp(bytes([mode])) + lp(eph_a_pub) + lp(i["nonce_a"]) + lp(eph_b_pub) + lp(nonce_b) + lp(binding)
    h1 = sha256(t1)

    ss = x25519(i["eph_a"], eph_b_pub)
    assert ss == x25519(eph_b, eph_a_pub)
    keys = {
        "k_ab": hkdf(ss, h1, LABEL + b" key A->B"),
        "k_ba": hkdf(ss, h1, LABEL + b" key B->A"),
        "k_mac_a": hkdf(ss, h1, LABEL + b" mac A"),
        "k_mac_b": hkdf(ss, h1, LABEL + b" mac B"),
    }

    idpub_b = ed_pub(i["id_b_seed"]) if session else b""
    sig_b = sign(i["id_b_seed"], lp(LABEL + b" sig B") + lp(h1)) if session else b""
    mac_b = hmac_sha256(keys["k_mac_b"], lp(LABEL + b" mac B") + lp(h1) + lp(idpub_b))
    resp = bytes([0x02]) + eph_b_pub + nonce_b + sig_b + mac_b

    t2 = t1 + lp(sig_b) + lp(mac_b) + lp(idpub_b)
    h2 = sha256(t2)
    idpub_a = ed_pub(i["id_a_seed"]) if session else b""
    sig_a = sign(i["id_a_seed"], lp(LABEL + b" sig A") + lp(h2)) if session else b""
    mac_a = hmac_sha256(keys["k_mac_a"], lp(LABEL + b" mac A") + lp(h2) + lp(idpub_a))
    fin = bytes([0x03]) + sig_a + mac_a

    t3 = t2 + lp(sig_a) + lp(mac_a) + lp(idpub_a)
    h3 = sha256(t3)

    assert len(init) == 71 and len(resp) == (161 if session else 97) and len(fin) == (97 if session else 33)
    return {
        "mode": mode, "binding": binding, "init": init, "resp": resp, "fin": fin,
        "t1": t1, "h1": h1, "ss": ss, **keys,
        "sig_b": sig_b, "mac_b": mac_b, "h2": h2, "sig_a": sig_a, "mac_a": mac_a, "h3": h3,
    }


def frame(key: bytes, counter: int, plaintext: bytes) -> dict:
    """handshake.md section 5."""
    header = b"CV" + bytes([1, 0]) + counter.to_bytes(8, "big")
    nonce = counter.to_bytes(8, "big") + bytes(16)
    ciphertext = xchacha_seal(plaintext, header, nonce, key)
    assert xchacha_open(ciphertext, header, nonce, key) == plaintext
    return {"counter": counter, "header": header, "nonce": nonce, "aad": header, "plaintext": plaintext, "ciphertext": ciphertext}


def must_not_open(key: bytes, f: dict, ciphertext: bytes) -> None:
    try:
        xchacha_open(ciphertext, f["aad"], f["nonce"], key)
    except CryptoError:
        return
    raise AssertionError("a negative frame opened")


def fingerprint(pub_a: bytes, pub_b: bytes, words: list) -> dict:
    """pairing.md section 1.4."""
    lo, hi = sorted([pub_a, pub_b])
    fp = sha256(lp(b"clipvault-fp-v1") + lp(lo) + lp(hi))
    bits = int.from_bytes(fp, "big") >> (256 - 66)
    indices = [(bits >> (11 * (5 - k))) & 0x7FF for k in range(6)]
    return {"fp": fp, "indices": indices, "words": [words[n] for n in indices]}


def device_id(pub: bytes) -> str:
    """design.md 5.3.1."""
    return sha256(lp(b"clipvault-devid-v1") + lp(pub))[:16].hex()


def build() -> dict:
    i = INPUTS
    words = WORDLIST.read_text().split()
    assert len(words) == 2048
    modes = {name: handshake(m, {"session": b"", "pair_qr": i["token"], "pair_code": i["k_pake"]}[name], i["eph_b"], i["nonce_b"])
             for name, m in MODES.items()}
    session = modes["session"]

    ab = frame(session["k_ab"], 1, i["frame_plaintext"])
    ba = frame(session["k_ba"], 2**32 - 1, i["frame_plaintext"])
    tampered = ab["ciphertext"][:-1] + bytes([ab["ciphertext"][-1] ^ 0x01])
    must_not_open(session["k_ab"], ab, tampered)
    must_not_open(session["k_ba"], ab, ab["ciphertext"])

    # A Fin replayed against a fresh Resp: the old sig_A and mac_A cover the old h2.
    fresh = handshake(MODES["session"], b"", i["eph_b2"], i["nonce_b2"])
    pub_a = ed_pub(i["id_a_seed"])
    replay_sig_ok = verifies(pub_a, lp(LABEL + b" sig A") + lp(fresh["h2"]), session["sig_a"])
    replay_mac_ok = hmac.compare_digest(
        hmac_sha256(fresh["k_mac_a"], lp(LABEL + b" mac A") + lp(fresh["h2"]) + lp(pub_a)), session["mac_a"])
    assert not replay_sig_ok and not replay_mac_ok

    pair_h3 = modes["pair_qr"]["h3"]
    name = i["identity_name"]
    identity_sig = sign(i["id_a_seed"], lp(b"clipvault-id-v1") + lp(pair_h3) + lp(name))

    return {
        "spec": "docs/sync/handshake.md and docs/sync/pairing.md, clipvault-hs-v1",
        "generator": "tools/handshake_vectors.py (cryptography 50.0.2, PyNaCl 1.6.2)",
        "encoding": "all byte values are lowercase hex",
        "inputs": {**{k: v for k, v in i.items()},
                   "eph_a_pub": x25519_pub(i["eph_a"]), "eph_b_pub": x25519_pub(i["eph_b"]),
                   "id_a_pub": pub_a, "id_b_pub": ed_pub(i["id_b_seed"])},
        "modes": modes,
        "frames": {"a_to_b": ab, "b_to_a": ba},
        "negative": {
            "tampered_a_to_b": tampered,
            "a_to_b_opened_with_k_ba": "must fail",
            "fresh_resp": fresh["resp"],
            "fresh_h2": fresh["h2"],
            "fresh_k_mac_a": fresh["k_mac_a"],
            "replayed_fin_verifies": False,
        },
        "pake_kdf": {"km": i["pake_km"], "k_pake": hkdf(i["pake_km"], b"", b"clipvault-pake-v1")},
        "fingerprint": fingerprint(pub_a, ed_pub(i["id_b_seed"]), words),
        "device_id": {"a": device_id(pub_a), "b": device_id(ed_pub(i["id_b_seed"]))},
        "identity": {"signer": "id_a", "h3": "modes.pair_qr.h3", "name": name, "sig": identity_sig},
    }


def to_json(value):
    if isinstance(value, bytes):
        return value.hex()
    if isinstance(value, dict):
        return {k: to_json(v) for k, v in value.items()}
    if isinstance(value, list):
        return [to_json(v) for v in value]
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail if the committed JSON differs")
    args = parser.parse_args()
    text = json.dumps(to_json(build()), indent=2) + "\n"
    if args.check:
        if not OUT.exists() or OUT.read_text() != text:
            print(f"{OUT.relative_to(ROOT)} is out of date: run python3 tools/handshake_vectors.py", file=sys.stderr)
            return 1
        print(f"{OUT.relative_to(ROOT)} matches the reference")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text)
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
