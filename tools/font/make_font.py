"""Fa i due file di Manrope dell'app: res/font/manrope_semibold.ttf (600) e manrope_extrabold.ttf (800).

Manrope (Mikhail Sharanda, SIL OFL 1.1) arriva da @fontsource-variable/manrope 5.3.0 su npm: un carattere variabile
diviso in parti (latino, latino esteso, greco, cirillico...). Per ogni peso si fa un'istanza statica di ogni parte
(fontTools varLib.instancer), poi si uniscono le parti in un file solo (fontTools.merge). Il cinese e il resto
ripiegano sul carattere di sistema.

I .ttf stanno nel repository: la build non li rifà e resta riproducibile. Questo script serve solo a rifarli (per
esempio con una versione nuova del carattere). Stesso pacchetto, stessi file: niente date né percorsi dentro.

Serve fontTools con brotli (per i woff2):
    python3 -m venv /tmp/font && /tmp/font/bin/pip install fonttools==4.60.1 brotli==1.2.1
    /tmp/font/bin/python tools/font/make_font.py
"""
import base64
import hashlib
import io
import os
import sys
import tarfile
import urllib.request

from fontTools.merge import Merger
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

VERSION = "5.3.0"
URL = f"https://registry.npmjs.org/@fontsource-variable/manrope/-/manrope-{VERSION}.tgz"
# L'impronta che dà npm ("integrity" del pacchetto): se il file è un altro, ci si ferma.
INTEGRITY = "sha512-6D5dgokHsWDDMtmXHznKa0hK229NN+1a4BLPmUCLqcO1Pw5EEhWY5RFt0AcXnVRAljFFPfRtLkJePQj6LSsV6g=="
# Le parti tenute, nell'ordine in cui si uniscono (la prima vince quando due hanno lo stesso carattere).
PARTS = ["latin", "latin-ext", "greek", "cyrillic"]
WEIGHTS = {600: ("SemiBold", "manrope_semibold.ttf"), 800: ("ExtraBold", "manrope_extrabold.ttf")}

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
FONT_DIR = os.path.join(ROOT, "app", "src", "main", "res", "font")
LICENSE_FILE = os.path.join(ROOT, "app", "src", "main", "assets", "licenses", "OFL-Manrope.txt")


def download(path):
    """Il pacchetto di npm: da un file già scaricato (primo argomento) o dalla rete."""
    if path:
        data = open(path, "rb").read()
    else:
        with urllib.request.urlopen(URL, timeout=60) as answer:
            data = answer.read()
    digest = "sha512-" + base64.b64encode(hashlib.sha512(data).digest()).decode()
    if digest != INTEGRITY:
        sys.exit(f"Il pacchetto non è quello atteso: {digest}")
    return tarfile.open(fileobj=io.BytesIO(data), mode="r:gz")


def read(archive, name):
    member = archive.getmember(f"package/{name}")
    return archive.extractfile(member).read()


def instance(woff2, weight):
    """L'istanza statica di una parte al peso dato, come TrueType semplice (in memoria, per il merge)."""
    font = TTFont(io.BytesIO(woff2))
    static = instancer.instantiateVariableFont(font, {"wght": weight}, updateFontNames=False)
    static.flavor = None
    out = io.BytesIO()
    static.save(out)
    out.seek(0)
    return out


def name_it(font, style):
    """
    Nomi del carattere come le istanze statiche di Google Fonts: famiglia «Manrope SemiBold» (o ExtraBold) di stile
    Regular, e la famiglia tipografica Manrope col suo stile (16 e 17). Il peso vero lo dice usWeightClass; l'app lo
    dice comunque da sé (Font(…, FontWeight.SemiBold)).
    """
    full = f"Manrope {style}"
    postscript = f"Manrope-{style}"
    table = font["name"]
    keep = {0, 1, 2, 3, 4, 5, 6, 13, 14}
    table.names = [n for n in table.names if n.nameID in keep]
    for platform in ((3, 1, 0x409), (1, 0, 0)):
        table.setName(full, 1, *platform)
        table.setName("Regular", 2, *platform)
        table.setName(f"{VERSION};fontsource;{postscript}", 3, *platform)
        table.setName(full, 4, *platform)
        table.setName(postscript, 6, *platform)
    # Famiglia tipografica e stile (16 e 17): così Android e Skia leggono il peso giusto.
    table.setName("Manrope", 16, 3, 1, 0x409)
    table.setName(style, 17, 3, 1, 0x409)
    # Senza STAT e fvar (resti del carattere variabile, ora inutili).
    for tag in ("STAT", "fvar", "avar", "HVAR", "MVAR", "gvar"):
        if tag in font:
            del font[tag]


def make(archive, weight, style, file_name):
    # La data scritta nel file (head.created e head.modified) è quella del carattere di partenza: lo stesso pacchetto dà
    # lo stesso file.
    born = TTFont(io.BytesIO(read(archive, f"files/manrope-{PARTS[0]}-wght-normal.woff2")))["head"].created
    parts = []
    for part in PARTS:
        woff2 = read(archive, f"files/manrope-{part}-wght-normal.woff2")
        parts.append(instance(woff2, weight))
    merged = Merger().merge(parts)
    name_it(merged, style)
    merged["OS/2"].usWeightClass = weight
    # Stile Regular (bit 6) anche per l'800: non è il Bold della famiglia, e niente grassetto finto sopra.
    merged["OS/2"].fsSelection = (merged["OS/2"].fsSelection & ~0b1100001) | 0b1000000
    merged["head"].macStyle = 0
    merged["head"].created = born
    merged["head"].modified = born
    # Senza questo fontTools scriverebbe in head.modified l'ora del salvataggio.
    merged.recalcTimestamp = False
    os.makedirs(FONT_DIR, exist_ok=True)
    path = os.path.join(FONT_DIR, file_name)
    merged.save(path, reorderTables=True)
    font = TTFont(path)
    glyphs = len(font.getGlyphOrder())
    chars = len(font.getBestCmap())
    digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
    print(f"{file_name}: peso {weight}, {glyphs} glifi, {chars} caratteri, {os.path.getsize(path)} byte, sha256 {digest}")


def main():
    archive = download(sys.argv[1] if len(sys.argv) > 1 else None)
    for weight, (style, file_name) in WEIGHTS.items():
        make(archive, weight, style, file_name)
    os.makedirs(os.path.dirname(LICENSE_FILE), exist_ok=True)
    with open(LICENSE_FILE, "wb") as out:
        out.write(read(archive, "LICENSE"))
    print(f"Licenza in {os.path.relpath(LICENSE_FILE, ROOT)}")


if __name__ == "__main__":
    main()
