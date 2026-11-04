#!/usr/bin/env python3
"""Confronta il profilo ART (assets/dexopt/baseline.prof) di due APK, per capire perché una build non è identica.

    profilo.py <apk della release> <apk compilato come F-Droid> [breve]

Il file è "pro\\0" + versione "010\\0" + numero di dex + dimensione scompattata + dimensione compattata + zlib.
Scrive le dimensioni, il primo byte diverso del contenuto scompattato e il profilo del secondo APK in base64
(così si può studiare fuori dal runner, dove c'è l'APK della release).
"""
import base64
import hashlib
import struct
import sys
import zipfile
import zlib

NAME = "assets/dexopt/baseline.prof"


def raw(apk: str) -> tuple[bytes, bytes]:
    with zipfile.ZipFile(apk) as z:
        data = z.read(NAME)
    if data[:4] != b"pro\0":
        raise SystemExit(f"{apk}: {NAME} non è un profilo ART")
    count = data[8]
    plain, packed = struct.unpack("<II", data[9:17])
    body = zlib.decompress(data[17:17 + packed])
    print(f"{apk}: versione {data[4:7].decode()}, dex {count}, scompattato {plain} ({len(body)}), "
          f"compattato {packed}, sha256 {hashlib.sha256(data).hexdigest()[:16]}, "
          f"contenuto {hashlib.sha256(body).hexdigest()[:16]}")
    return data, body


def main() -> None:
    first, a = raw(sys.argv[1])
    second, b = raw(sys.argv[2])
    if a == b:
        print("Contenuto scompattato uguale: cambia solo la compressione (zlib).")
    else:
        at = next((i for i, (x, y) in enumerate(zip(a, b)) if x != y), min(len(a), len(b)))
        print(f"Contenuto diverso dal byte {at} (lunghezze {len(a)} e {len(b)})")
        print("release :", a[max(0, at - 16):at + 32].hex())
        print("f-droid :", b[max(0, at - 16):at + 32].hex())
    if len(sys.argv) > 3:
        return
    print("Profilo del secondo APK in base64:")
    text = base64.b64encode(second).decode()
    for i in range(0, len(text), 120):
        print(text[i:i + 120])


if __name__ == "__main__":
    main()
