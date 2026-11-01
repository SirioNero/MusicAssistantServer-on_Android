"""
Il pacchetto di Music Assistant sul telefono, quello che Xonos scarica con "Fai diventare Xonos il tuo server
Music Assistant": dall'AAR del modulo server (Python 3.13 di Chaquopy, Music Assistant, i pacchetti, FFmpeg e
librespot) una cartella per un'ABI, compressa in uno zip.

    python3 make_payload.py --aar server-release.aar --abi arm64-v8a --ndk <NDK> --version 2.10.8-1 --out <zip>

Dentro lo zip:
    bin/       python3 (compilato qui con l'NDK), ffmpeg, ffprobe, librespot: Xonos li avvia attraverso il
               linker di sistema (da Android 10 un'app non può avviare i programmi scaricati)
    lib/       libpython3.13.so e le altre librerie (LD_LIBRARY_PATH), la libreria standard (python313.zip),
               i suoi moduli nativi (python3.13/lib-dynload), i certificati (cacert.pem)
    app/       Music Assistant preparato da ma/prepare.py e xonos_server.py
    site-packages/  i pacchetti di requirements.txt, installati per Android da Chaquopy
    rootfs.tar il piccolo sistema Linux (glibc, PulseAudio) dove gira Soloist dentro proot (rootfs.py): Xonos
               lo estrae la prima volta che serve (xonos_server.py), con i suoi collegamenti
    licenses/  i testi delle licenze (da licenses/ qui accanto, più quella di Xonos)
    NOTICE.txt di chi è ogni parte, con quale licenza e dove sta il sorgente
    payload.json    versione, ABI e Python

Lo zip è sempre uguale a parità di contenuto: voci in ordine e date fisse.
"""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from email.parser import HeaderParser
from pathlib import Path

HERE = Path(__file__).resolve().parent
SERVER = HERE.parent
REPO_URL = "https://github.com/SirioNero/MusicAssistantServer-on_Android"
PYTHON = "3.13"
FIXED_TIME = (1980, 1, 1, 0, 0, 0)
# I programmi che Android installa dall'APK come librerie (lib<nome>.so): qui tornano programmi.
PROGRAMS = {"libffmpeg.so": "ffmpeg", "libffprobe.so": "ffprobe", "liblibrespot.so": "librespot"}
# Il ponte Java di Chaquopy: fuori dall'app non serve.
SKIP_LIBS = {"libchaquopy_java.so"}
TRIPLES = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}
# L'architettura dei pacchetti di Ubuntu per il sistema Linux di Soloist.
UBUNTU_ARCH = {"arm64-v8a": "arm64", "x86_64": "amd64"}
MIN_SDK = 26
PAGE_16K = 16384
# Le librerie di lib/ e a quale parte di NOTICE.txt appartengono (per inizio del nome).
NATIVE = {
    "libpython3": "Python",
    "libssl_": "OpenSSL",
    "libcrypto_": "OpenSSL",
    "libsqlite3_": "SQLite",
    "libavcodec": "FFmpeg",
    "libavfilter": "FFmpeg",
    "libavformat": "FFmpeg",
    "libavutil": "FFmpeg",
    "libswresample": "FFmpeg",
    "libswscale": "FFmpeg",
    "libc++_shared": "LLVM libc++",
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aar", required=True, help="L'AAR del modulo server (o un APK che lo contiene)")
    parser.add_argument("--abi", required=True, choices=sorted(TRIPLES))
    parser.add_argument("--version", required=True, help="La versione del pacchetto (xonosServerPackage)")
    parser.add_argument("--out", required=True, help="Lo zip da scrivere")
    parser.add_argument("--ndk", help="L'NDK, per compilare python3")
    parser.add_argument("--launcher", help="Un python3 già compilato, invece di compilarlo")
    parser.add_argument("--rootfs-cache", help="Dove tenere i pacchetti di Ubuntu scaricati (fra un'ABI e l'altra)")
    args = parser.parse_args()
    if not args.ndk and not args.launcher:
        sys.exit("Serve --ndk (per compilare python3) o --launcher")
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp) / "payload"
        build(Path(args.aar), args.abi, args.version, root)
        python3 = root / "bin" / "python3"
        if args.launcher:
            shutil.copyfile(args.launcher, python3)
        else:
            compile_launcher(Path(args.ndk), args.abi, root, python3)
        cache = Path(args.rootfs_cache) if args.rootfs_cache else Path(tmp) / "rootfs-cache"
        ubuntu = add_rootfs(root, args.abi, cache)
        write_notice(root, args.version, args.abi, ubuntu)
        report_alignment(root)
        write_zip(root, Path(args.out))
        # Quanto occupa sul telefono, una volta estratto (Xonos chiede 4 volte lo zip libero, zip compreso).
        unpacked = sum(path.stat().st_size for path in root.rglob("*") if path.is_file())
    size = Path(args.out).stat().st_size
    print(f"pacchetto {args.version} per {args.abi}: {args.out}, {size / 1e6:.1f} MB, estratto {unpacked / 1e6:.1f} MB")


def build(aar: Path, abi: str, version: str, root: Path) -> None:
    lib = root / "lib"
    dynload = lib / f"python{PYTHON}" / "lib-dynload"
    site = root / "site-packages"
    for folder in (root / "bin", lib, dynload, site, root / "app"):
        folder.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(aar) as archive:
        names = archive.namelist()
        native = _native_prefix(names, abi)
        for name in names:
            if name.startswith(native) and name.endswith(".so") and "/" not in name[len(native):]:
                base = name[len(native):]
                if base in SKIP_LIBS:
                    continue
                target = root / "bin" / PROGRAMS[base] if base in PROGRAMS else lib / base
                target.write_bytes(archive.read(name))
        assets = "assets/chaquopy/"
        if f"{assets}stdlib-common.imy" not in names:
            sys.exit(f"{aar}: non ha le risorse di Chaquopy ({assets})")
        # La libreria standard resta compressa: Python la legge da lib/python313.zip.
        (lib / f"python{PYTHON.replace('.', '')}.zip").write_bytes(archive.read(f"{assets}stdlib-common.imy"))
        _extract(archive.read(f"{assets}stdlib-{abi}.imy"), dynload)
        boot_native = f"{assets}bootstrap-native/{abi}/"
        for name in names:
            rest = name[len(boot_native):]
            if name.startswith(boot_native) and name.endswith(".so") and "/" not in rest:
                (dynload / rest).write_bytes(archive.read(name))
        # Dal bootstrap di Chaquopy solo pkg_resources (lo usa qualche pacchetto); java/ è il suo ponte.
        _extract(archive.read(f"{assets}bootstrap.imy"), site, skip=("java/",))
        for part in ("requirements-common", f"requirements-{abi}"):
            if f"{assets}{part}.imy" in names:
                _extract(archive.read(f"{assets}{part}.imy"), site)
        _extract(archive.read(f"{assets}app.imy"), root / "app")
        (lib / "cacert.pem").write_bytes(archive.read(f"{assets}cacert.pem"))
    if not (lib / "libpython3.13.so").exists():
        sys.exit(f"{aar}: manca libpython3.13.so per {abi}")
    missing = [name for name in PROGRAMS.values() if not (root / "bin" / name).exists()]
    if missing:
        sys.exit(f"{aar}: mancano {', '.join(missing)} per {abi}")
    info = {"version": version, "abi": abi, "python": PYTHON}
    (root / "payload.json").write_text(json.dumps(info, indent=2) + "\n")


def add_rootfs(root: Path, abi: str, cache: Path) -> list[tuple[str, str]]:
    """rootfs.tar con il sistema Linux di Soloist (rootfs.py); i pacchetti di Ubuntu usati, con la versione."""
    with tempfile.TemporaryDirectory() as tmp:
        tree = Path(tmp) / "rootfs"
        result = subprocess.run(
            [sys.executable, str(HERE / "rootfs.py"), "--arch", UBUNTU_ARCH[abi], "--out", str(tree), "--cache", str(cache)],
            check=True,
            capture_output=True,
            text=True,
        )
        print(result.stdout.strip().splitlines()[-1] if result.stdout.strip() else "rootfs.py: niente da dire")
        used = next((line for line in result.stdout.splitlines() if line.startswith("usati: ")), "")
        packages = [tuple(item.split("=", 1)) for item in used.removeprefix("usati: ").split()]
        write_tar(tree, root / "rootfs.tar")
    return [(name, version.replace("%3a", ":")) for name, version in packages]


def write_tar(tree: Path, out: Path) -> None:
    """Un tar sempre uguale a parità di contenuto: voci in ordine, date e proprietari fissi, collegamenti com'erano."""
    entries = []
    for folder, dirs, files in os.walk(tree):
        for name in dirs + files:
            entries.append(Path(folder) / name)
    entries.sort(key=lambda path: path.relative_to(tree).as_posix())
    with tarfile.open(out, "w", format=tarfile.GNU_FORMAT) as archive:
        for path in entries:
            info = archive.gettarinfo(str(path), arcname=path.relative_to(tree).as_posix())
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mtime = 315532800  # 1980-01-01, come le voci dello zip
            if info.isreg():
                with path.open("rb") as file:
                    archive.addfile(info, file)
            else:
                archive.addfile(info)


def write_notice(root: Path, version: str, abi: str, ubuntu: list[tuple[str, str]]) -> None:
    """NOTICE.txt e licenses/: di chi è ogni parte del pacchetto, con quale licenza e dove sta il sorgente."""
    gradle = (SERVER / "build.gradle.kts").read_text()
    ffmpeg_script = (SERVER / "ffmpeg" / "build.sh").read_text()
    music_assistant = _value(gradle, r'musicAssistantVersion = "([^"]+)"')
    chaquopy = _value(gradle, r'id\("com\.chaquo\.python"\) version "([^"]+)"')
    ffmpeg = _value(ffmpeg_script, r"FFMPEG_VERSION=(\S+)")
    mbedtls = _value(ffmpeg_script, r"MBEDTLS_VERSION=(\S+)")
    librespot = _value((SERVER / "librespot" / "build.sh").read_text(), r"LIBRESPOT_COMMIT=(\S+)")
    licenses = root / "licenses"
    shutil.copytree(HERE / "licenses", licenses)
    shutil.copyfile(SERVER.parent / "LICENSE", licenses / "Xonos-AGPL-3.0.txt")

    unknown = sorted(
        path.name for path in (root / "lib").glob("*.so") if not any(path.name.startswith(prefix) for prefix in NATIVE)
    )
    if unknown:
        print(f"ATTENZIONE: librerie senza una voce in NOTICE.txt: {', '.join(unknown)}")
    parts = [
        (
            "Xonos's own files: bin/python3, app/xonos_server.py, app/av, app/modern_colorthief.py,\n"
            "app/music_assistant_frontend, and the orjson and aiodns stand-ins in site-packages (+xonos)",
            "AGPL-3.0-or-later (licenses/Xonos-AGPL-3.0.txt)",
            REPO_URL,
        ),
        (
            f"Music Assistant {music_assistant} (app/music_assistant), changed for the phone by server/ma/prepare.py",
            "Apache-2.0 (licenses/Apache-2.0.txt)",
            f"https://github.com/music-assistant/server/tree/{music_assistant}",
        ),
        (
            f"Python {PYTHON} for Android (lib/libpython3*.so, lib/python313.zip, lib/python{PYTHON}/lib-dynload),\n"
            f"as built by Chaquopy {chaquopy}. Python's standard library includes other software (expat, libffi,\n"
            "libmpdec, HACL*, bzip2, xz, zlib...): https://docs.python.org/3.13/license.html",
            "PSF-2.0 (licenses/Python.txt); Chaquopy: MIT (licenses/Chaquopy.txt)",
            f"https://github.com/chaquo/chaquopy/tree/{chaquopy}, https://www.python.org/downloads/source/",
        ),
        (
            "OpenSSL 3, for Python and the Python packages (lib/libssl_*.so, lib/libcrypto_*.so)",
            "Apache-2.0 (licenses/Apache-2.0.txt)",
            "https://github.com/openssl/openssl, as built by Chaquopy",
        ),
        ("SQLite, for Python (lib/libsqlite3_*.so)", "public domain", "https://sqlite.org/src, as built by Chaquopy"),
        (
            f"FFmpeg {ffmpeg} (bin/ffmpeg, bin/ffprobe, lib/libav*.so, lib/libsw*.so), built by server/ffmpeg/build.sh\n"
            "with --enable-version3, without GPL parts",
            "LGPL-3.0-or-later (licenses/LGPL-3.0.txt and licenses/GPL-3.0.txt)",
            f"https://ffmpeg.org/releases/ffmpeg-{ffmpeg}.tar.xz",
        ),
        (
            f"Mbed TLS {mbedtls}, inside FFmpeg for https",
            "Apache-2.0 or GPL-2.0-or-later (licenses/Mbed-TLS.txt)",
            f"https://github.com/Mbed-TLS/mbedtls/releases/tag/mbedtls-{mbedtls}",
        ),
        (
            "pkg_resources (site-packages/pkg_resources), from setuptools through Chaquopy's bootstrap",
            "MIT (https://github.com/pypa/setuptools/blob/main/LICENSE)",
            "https://github.com/pypa/setuptools",
        ),
        (
            "librespot, Music Assistant's fork (bin/librespot), built by server/librespot/build.sh",
            "MIT (licenses/librespot.txt)",
            f"https://github.com/music-assistant/librespot/tree/{librespot}",
        ),
        (
            "LLVM libc++ from the Android NDK (lib/libc++_shared.so), if present",
            "Apache-2.0 WITH LLVM-exception (licenses/Apache-2.0.txt)",
            "https://github.com/llvm/llvm-project",
        ),
        (
            "A small Ubuntu 24.04 system (rootfs.tar) where Spotify's Soloist runs inside proot: glibc, PulseAudio\n"
            "and the libraries they need, unchanged from these Ubuntu packages: "
            + ", ".join(f"{name} {package_version}" for name, package_version in ubuntu),
            "each package's own (GPL, LGPL, MIT, BSD and others): its copyright file is inside the archive, in\n"
            "usr/share/doc/<package>/copyright",
            "https://launchpad.net/ubuntu/+source/<package> (the source packages of Ubuntu 24.04 'noble')",
        ),
    ]
    lines = [
        f"Music Assistant for Xonos: package {version} for {abi}",
        "",
        'Xonos downloads this package only when you tap "Make Xonos your Music Assistant server". It is built',
        f"from source by the workflows of {REPO_URL} (folder server/,",
        "with the exact versions and the build scripts). Its parts, their licenses and their sources:",
        "",
    ]
    for what, license_name, source in parts:
        lines += [what, f"    License: {license_name}", f"    Source: {source}", ""]
    packages = _python_packages(root / "site-packages")
    lines += [
        "The Python packages in site-packages, each with its license in its .dist-info folder. Those built for",
        "Android by Chaquopy (numpy, pillow, cryptography, cffi, pycryptodome and the chaquopy-* libraries) have",
        f"their recipes at https://github.com/chaquo/chaquopy/tree/{chaquopy}/server/pypi/packages.",
        "",
    ]
    for name, package_version, license_name in packages:
        # Quelli scritti per Xonos (+xonos) stanno nel suo repository, gli altri su PyPI.
        source = f"{REPO_URL}/tree/main/server/wheels" if package_version.endswith("+xonos") else f"https://pypi.org/project/{name}/{package_version}/"
        lines.append(f"    {name} {package_version}: {license_name}. {source}")
    (root / "NOTICE.txt").write_text("\n".join(lines) + "\n")
    print(f"NOTICE.txt: {len(parts)} parti, {len(packages)} pacchetti Python")


def _value(text: str, pattern: str) -> str:
    match = re.search(pattern, text)
    if not match:
        sys.exit(f"Non trovo {pattern}")
    return match.group(1)


def _python_packages(site: Path) -> list[tuple[str, str, str]]:
    """Nome, versione e licenza dei pacchetti di site-packages, dai loro METADATA."""
    packages = []
    for info in sorted(site.glob("*.dist-info")):
        metadata = info / "METADATA"
        if not metadata.is_file():
            continue
        fields = HeaderParser().parsestr(metadata.read_text(errors="replace"))
        name, package_version = fields.get("Name", info.name), fields.get("Version", "?")
        if package_version.endswith("+xonos"):
            license_name = "AGPL-3.0-or-later, written for Xonos"
        else:
            license_name = _license(fields, info)
        packages.append((name, package_version, license_name))
    if not packages:
        print("ATTENZIONE: in site-packages non ci sono .dist-info: NOTICE.txt non elenca i pacchetti")
    return packages


def _license(fields, info: Path) -> str:
    expression = (fields.get("License-Expression") or "").strip()
    if expression:
        return expression
    text = " ".join((fields.get("License") or "").split())
    if 0 < len(text) <= 60:
        return text
    classifiers = [line.split(" :: ")[-1] for line in fields.get_all("Classifier") or [] if line.startswith("License ::")]
    if classifiers:
        return ", ".join(classifiers)
    files = [path.name for path in info.rglob("*") if path.is_file() and re.match(r"(LICEN[CS]E|COPYING|NOTICE)", path.name, re.I)]
    return f"see {', '.join(sorted(files))} in its .dist-info folder" if files else "see its source"


def report_alignment(root: Path) -> None:
    """I programmi e le librerie allineati a meno di 16 KB: sui telefoni con pagine da 16 KB non si caricano."""
    short = []
    elves = 0
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        align = _elf_alignment(path)
        if align is None:
            continue
        elves += 1
        if align < PAGE_16K:
            short.append(f"{path.relative_to(root)} ({align})")
    print(f"ELF: {elves}, allineati a meno di 16 KB: {len(short)}")
    for line in short[:60]:
        print(f"    {line}")


def _elf_alignment(path: Path) -> int | None:
    """Il più piccolo allineamento dei segmenti PT_LOAD di un ELF a 64 bit; None se non è un ELF."""
    with path.open("rb") as file:
        header = file.read(64)
        if len(header) < 64 or header[:4] != b"\x7fELF" or header[4] != 2:
            return None
        phoff = int.from_bytes(header[32:40], "little")
        phentsize = int.from_bytes(header[54:56], "little")
        phnum = int.from_bytes(header[56:58], "little")
        file.seek(phoff)
        table = file.read(phentsize * phnum)
    aligns = []
    for index in range(phnum):
        entry = table[index * phentsize:(index + 1) * phentsize]
        if int.from_bytes(entry[0:4], "little") == 1:  # PT_LOAD
            aligns.append(int.from_bytes(entry[48:56], "little"))
    return min(aligns) if aligns else None


def compile_launcher(ndk: Path, abi: str, root: Path, out: Path) -> None:
    tools = ndk / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "bin"
    source = Path(__file__).with_name("python3.c")
    subprocess.run(
        [
            str(tools / "clang"),
            f"--target={TRIPLES[abi]}{MIN_SDK}",
            "-O2",
            "-fPIE",
            "-pie",
            "-Wl,--build-id=none",
            # Si carica anche sui telefoni con pagine da 16 KB.
            "-Wl,-z,max-page-size=16384",
            "-o",
            str(out),
            str(source),
            f"-L{root / 'lib'}",
            "-lpython3.13",
        ],
        check=True,
    )
    subprocess.run([str(tools / "llvm-strip"), "--strip-all", str(out)], check=True)


def write_zip(root: Path, out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    files = sorted(path for path in root.rglob("*") if path.is_file())
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            name = path.relative_to(root).as_posix()
            entry = zipfile.ZipInfo(name, FIXED_TIME)
            entry.compress_type = zipfile.ZIP_DEFLATED
            # Chi estrae (Xonos) rende eseguibili i file di bin/; il permesso resta scritto anche qui.
            mode = 0o755 if name.startswith("bin/") else 0o644
            entry.external_attr = (0o100000 | mode) << 16
            archive.writestr(entry, path.read_bytes())


def _native_prefix(names: list[str], abi: str) -> str:
    for prefix in (f"jni/{abi}/", f"lib/{abi}/"):
        if any(name.startswith(prefix) for name in names):
            return prefix
    sys.exit(f"Non ci sono librerie per {abi}")


def _extract(data: bytes, target: Path, skip: tuple[str, ...] = ()) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for entry in archive.infolist():
            name = entry.filename
            if entry.is_dir() or name.startswith(skip):
                continue
            path = (target / name).resolve()
            if not str(path).startswith(str(target.resolve()) + os.sep):
                sys.exit(f"Voce fuori dalla cartella: {name}")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(archive.read(entry))


if __name__ == "__main__":
    main()
