#!/usr/bin/env python3
"""
Il piccolo sistema Linux (glibc) in cui gira Soloist dentro Xonos, per il pacchetto del server sul telefono
(make_payload.py lo mette in rootfs.tar) e per la prova di tools/soloist.

Dentro: glibc, PulseAudio (il demone, pactl, pacat e i due moduli che usa Music Assistant: il socket e il
"pipe sink" che scrive l'audio in un tubo) e libpulse, che Soloist apre da sé per suonare. I pacchetti sono
quelli di Ubuntu 24.04 (noble) per l'architettura chiesta, con le firme dell'archivio controllate da apt; si
tengono solo i programmi, le librerie che chiedono (NEEDED, cercate come fa il caricatore) e i testi delle
licenze dei pacchetti usati. Niente script dei pacchetti, documentazione o configurazioni: quelle che servono le
scrive questo programma.

Uso: rootfs.py --arch arm64|amd64 --out <cartella> [--cache <cartella dei pacchetti>]
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ARCHIVE = {
    "amd64": "http://archive.ubuntu.com/ubuntu",
    "arm64": "http://ports.ubuntu.com/ubuntu-ports",
}
TRIPLET = {"amd64": "x86_64-linux-gnu", "arm64": "aarch64-linux-gnu"}
# Le chiavi dell'archivio di Ubuntu, per il controllo delle firme (sui runner c'è il pacchetto ubuntu-keyring).
KEYRINGS = ["/usr/share/keyrings/ubuntu-archive-keyring.gpg", "/usr/share/keyrings/ubuntu-archive-keyring.pgp"]

# I pacchetti da cui si parte e, dentro, i file che servono (percorsi dentro il pacchetto, * vale come in glob).
ROOTS = ["libc6", "libgcc-s1", "libatomic1", "libpulse0", "pulseaudio", "pulseaudio-utils"]
WANTED = [
    "usr/lib/{t}/ld-linux-*.so.*",
    "usr/lib/{t}/libc.so.6",
    "usr/lib/{t}/libm.so.6",
    "usr/lib/{t}/libpthread.so.0",
    "usr/lib/{t}/libdl.so.2",
    "usr/lib/{t}/librt.so.1",
    "usr/lib/{t}/libresolv.so.2",
    "usr/lib/{t}/libnss_dns.so.2",
    "usr/lib/{t}/libnss_files.so.2",
    "usr/lib/{t}/libgcc_s.so.1",
    "usr/lib/{t}/libatomic.so.1",
    "usr/lib/{t}/libpulse.so.0",
    "usr/lib/{t}/libpulse-simple.so.0",
    "usr/bin/pulseaudio",
    "usr/bin/pactl",
    "usr/bin/pacat",
    "usr/lib/pulse-*/modules/module-native-protocol-unix.so",
    "usr/lib/pulse-*/modules/module-cli-protocol-unix.so",  # i comandi a righe di testo (niente pactl)
    "usr/lib/pulse-*/modules/module-pipe-sink.so",
    "usr/lib/pulse-*/modules/module-null-sink.so",
]
# Il caricatore dove lo cerca il kernel (PT_INTERP, attraverso /lib e /lib64 che puntano dentro /usr).
WANTED_ARCH = {"amd64": ["usr/lib64/ld-linux-x86-64.so.2"], "arm64": ["usr/lib/ld-linux-aarch64.so.1"]}
# Il caricatore del programma: dove lo cerca il kernel (PT_INTERP), relativo alla radice.
INTERP = {"amd64": "lib64/ld-linux-x86-64.so.2", "arm64": "lib/ld-linux-aarch64.so.1"}
# Pacchetti che non hanno librerie da prendere: script, dati, demoni di sistema.
SKIP = {
    "adduser", "lsb-base", "debconf", "init-system-helpers", "dpkg", "perl-base", "passwd", "systemd",
    "systemd-sysv", "dbus", "dbus-bin", "dbus-daemon", "dbus-session-bus-common", "dbus-system-bus-common",
    "dbus-user-session", "libasound2-data", "libasound2-plugins", "x11-common", "libx11-data", "pulseaudio-utils",
    "gcc-14-base", "libpam-systemd",
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    parser.add_argument("--arch", choices=sorted(ARCHIVE), required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=None)
    args = parser.parse_args()
    triplet = TRIPLET[args.arch]
    cache = (args.cache or args.out.parent / f".rootfs-cache-{args.arch}").resolve()
    apt = Apt(args.arch, cache)
    pool = cache / "pool"
    shutil.rmtree(pool, ignore_errors=True)
    pool.mkdir(parents=True)

    fetched: dict[str, Path] = {}
    fetch(apt, ROOTS, fetched, pool)
    files = index(pool)
    wanted: set[str] = set()
    for pattern in WANTED + WANTED_ARCH[args.arch]:
        pattern = pattern.format(t=triplet)
        matches = [rel for rel in files if Path(rel).match(pattern) and rel.count("/") == pattern.count("/")]
        if not matches:
            raise SystemExit(f"nessun file per {pattern}")
        wanted.update(matches)

    while True:
        needed, missing = closure(wanted, files, triplet)
        if not missing:
            break
        # Le librerie mancanti stanno nelle dipendenze dirette dei pacchetti che danno file usati.
        used = {package_of(files[rel], pool) for rel in needed}
        candidates = sorted({
            dep for package in used for dep in apt.depends(package)
            if dep not in fetched and dep not in SKIP
        })
        if not candidates:
            raise SystemExit(f"librerie che non si trovano: {', '.join(sorted(missing))}")
        print(f"mancano {', '.join(sorted(missing))}: prendo {', '.join(candidates)}", flush=True)
        fetch(apt, candidates, fetched, pool)
        files = index(pool)

    out = args.out.resolve()
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    # Ubuntu ha /lib, /lib64 e /bin dentro /usr: gli stessi collegamenti qui.
    for name in ("lib", "lib64", "bin", "sbin"):
        (out / name).symlink_to(f"usr/{name}")
    (out / "usr" / "lib64").mkdir(parents=True)
    used = set()
    for rel in sorted(needed):
        copy(rel, files[rel], out)
        used.add(package_of(files[rel], pool))
    interp = out / INTERP[args.arch]
    if not interp.exists():
        raise SystemExit(f"manca il caricatore {INTERP[args.arch]}")
    write_config(out)
    licenses(used, pool, out)
    report(out, used, fetched, needed)
    return 0


class Apt:
    """apt con un indice tutto suo (nessun permesso di root, nessuna modifica al sistema)."""

    def __init__(self, arch: str, cache: Path) -> None:
        self.cache = cache
        state = cache / "apt"
        for sub in ("lists/partial", "archives/partial", "etc/preferences.d", "etc/apt.conf.d"):
            (state / sub).mkdir(parents=True, exist_ok=True)
        (state / "status").touch()
        keyring = next((k for k in KEYRINGS if Path(k).exists()), None)
        if keyring is None:
            raise SystemExit("mancano le chiavi dell'archivio di Ubuntu (pacchetto ubuntu-keyring)")
        sources = state / "etc" / "sources.list"
        sources.write_text(
            "".join(
                f"deb [arch={arch} signed-by={keyring}] {ARCHIVE[arch]} {suite} main universe\n"
                for suite in ("noble", "noble-updates", "noble-security")
            )
        )
        self.options = [
            f"-oDir::Etc={state / 'etc'}",
            f"-oDir::Etc::sourcelist={sources}",
            "-oDir::Etc::sourceparts=-",
            f"-oDir::State={state}",
            f"-oDir::State::Lists={state / 'lists'}",
            f"-oDir::State::status={state / 'status'}",
            f"-oDir::Cache={state}",
            f"-oDir::Cache::archives={state / 'archives'}",
            f"-oAPT::Architecture={arch}",
            f"-oAPT::Architectures::={arch}",
            "-oDebug::NoLocking=1",
        ]
        if os.geteuid() == 0:
            # Da root apt scaricherebbe come _apt, che non può scrivere in queste cartelle.
            self.options.append("-oAPT::Sandbox::User=root")
        subprocess.run(["apt-get", *self.options, "-qq", "update"], check=True)

    def download(self, packages: list[str], into: Path) -> None:
        into.mkdir(parents=True, exist_ok=True)
        subprocess.run(["apt-get", *self.options, "-qq", "download", *packages], cwd=into, check=True)

    def depends(self, package: str) -> list[str]:
        result = subprocess.run(
            ["apt-cache", *self.options, "depends", "--no-recommends", "--no-suggests", "--no-conflicts",
             "--no-breaks", "--no-replaces", "--no-enhances", package],
            capture_output=True, text=True, check=True,
        )
        deps = []
        for line in result.stdout.splitlines():
            line = line.strip().lstrip("|")
            if line.startswith(("Depends:", "PreDepends:")):
                name = line.split(":", 1)[1].strip()
                if not name.startswith("<"):
                    deps.append(name.split(":")[0])
        return deps


def fetch(apt: Apt, packages: list[str], fetched: dict[str, Path], pool: Path) -> None:
    debs = apt.cache / "debs"
    apt.download(packages, debs)
    for package in packages:
        found = sorted(debs.glob(f"{package}_*.deb"))
        if not found:
            raise SystemExit(f"pacchetto non scaricato: {package}")
        target = pool / package
        subprocess.run(["dpkg-deb", "-x", str(found[-1]), str(target)], check=True)
        fetched[package] = found[-1]


def package_of(path: Path, pool: Path) -> str:
    return path.relative_to(pool).parts[0]


def index(pool: Path) -> dict[str, Path]:
    """Ogni file dei pacchetti, per percorso dentro la radice (il primo pacchetto che lo ha)."""
    files: dict[str, Path] = {}
    for package in sorted(pool.iterdir()):
        for path in package.rglob("*"):
            if path.is_symlink() or path.is_file():
                files.setdefault(path.relative_to(package).as_posix(), path)
    return files


def target_of(rel: str, files: dict[str, Path]) -> str | None:
    """Dove porta un collegamento (un passo), come percorso dentro la radice."""
    link = os.readlink(files[rel])
    if link.startswith("/"):
        return os.path.normpath(link.lstrip("/"))
    return os.path.normpath(os.path.join(os.path.dirname(rel), link))


def elf_info(path: Path) -> tuple[list[str], list[str]] | None:
    """NEEDED e RUNPATH/RPATH di un file ELF, o None se non lo è."""
    with path.open("rb") as file:
        if file.read(4) != b"\x7fELF":
            return None
    result = subprocess.run(["readelf", "-d", "-W", str(path)], capture_output=True, text=True)
    needed, runpath = [], []
    for line in result.stdout.splitlines():
        if "(NEEDED)" in line:
            needed.append(line.split("[", 1)[1].rstrip("]"))
        elif "(RUNPATH)" in line or "(RPATH)" in line:
            runpath.extend(line.split("[", 1)[1].rstrip("]").split(":"))
    return needed, runpath


def closure(start: set[str], files: dict[str, Path], triplet: str) -> tuple[set[str], set[str]]:
    """I file da tenere (con le librerie che chiedono, cercate come fa il caricatore) e le librerie che mancano."""
    defaults = [f"usr/lib/{triplet}", "usr/lib", f"lib/{triplet}", "lib"]
    seen: set[str] = set()
    missing: set[str] = set()
    todo = list(start)
    while todo:
        rel = todo.pop()
        if rel in seen:
            continue
        if rel not in files:
            missing.add(rel)
            continue
        seen.add(rel)
        path = files[rel]
        if path.is_symlink():
            todo.append(target_of(rel, files))
            continue
        info = elf_info(path)
        if info is None:
            continue
        needed, runpath = info
        origin = os.path.dirname(rel)
        dirs = [entry.replace("${ORIGIN}", origin).replace("$ORIGIN", origin).lstrip("/") for entry in runpath]
        for name in needed:
            hit = next((f"{d}/{name}" for d in dirs + defaults if f"{d}/{name}" in files), None)
            if hit is None:
                missing.add(name)
            else:
                todo.append(os.path.normpath(hit))
    return seen, missing


def copy(rel: str, source: Path, out: Path) -> None:
    """Un file del pacchetto al suo posto, collegamenti compresi (relativi: si estraggono anche altrove)."""
    target = out / rel
    if target.exists() or target.is_symlink():
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.is_symlink():
        link = os.readlink(source)
        if link.startswith("/"):
            link = os.path.relpath(link.lstrip("/"), os.path.dirname(rel) or ".")
        target.symlink_to(link)
    else:
        shutil.copy2(source, target)


def write_config(out: Path) -> None:
    """I file di configurazione che i programmi glibc e PulseAudio si aspettano."""
    etc = out / "etc"
    (etc / "pulse").mkdir(parents=True, exist_ok=True)
    (etc / "ssl" / "certs").mkdir(parents=True, exist_ok=True)
    for name in ("tmp", "root", "run", "var/lib"):
        (out / name).mkdir(parents=True, exist_ok=True)
    (etc / "passwd").write_text("root:x:0:0:root:/root:/bin/sh\n")
    (etc / "group").write_text("root:x:0:\n")
    (etc / "nsswitch.conf").write_text("passwd: files\ngroup: files\nhosts: files dns\n")
    (etc / "hosts").write_text("127.0.0.1 localhost\n::1 localhost\n")
    # Il DNS e l'identità della macchina li mette Xonos quando avvia (qui ci sono solo per le prove).
    (etc / "resolv.conf").write_text("nameserver 10.0.2.3\nnameserver 8.8.8.8\n")
    (etc / "os-release").write_text('NAME="Xonos"\nID=xonos\nPRETTY_NAME="Xonos (Android)"\n')
    # Sul telefono non c'è /dev/shm: l'audio passa dal socket.
    (etc / "pulse" / "client.conf").write_text("autospawn = no\nenable-shm = no\nenable-memfd = no\n")
    (etc / "pulse" / "daemon.conf").write_text(
        # Niente D-Bus né rtkit nell'app: senza queste due righe il demone li cerca e lo scrive a ogni avvio.
        "enable-shm = no\nenable-memfd = no\nflat-volumes = no\nexit-idle-time = -1\n"
        "realtime-scheduling = no\nhigh-priority = no\n"
    )
    bundle = Path("/etc/ssl/certs/ca-certificates.crt")
    if bundle.exists():
        shutil.copy2(bundle, etc / "ssl" / "certs" / "ca-certificates.crt")


def licenses(used: set[str], pool: Path, out: Path) -> None:
    """I testi delle licenze dei pacchetti da cui vengono i file."""
    for package in sorted(used):
        source = pool / package / "usr" / "share" / "doc" / package / "copyright"
        if source.exists():
            target = out / "usr" / "share" / "doc" / package / "copyright"
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)


def report(out: Path, used: set[str], fetched: dict[str, Path], needed: set[str]) -> None:
    size = sum(p.stat().st_size for p in out.rglob("*") if p.is_file() and not p.is_symlink())
    print(f"pacchetti scaricati: {len(fetched)}, usati: {len(used)}")
    print("usati: " + " ".join(f"{name}={fetched[name].name.split('_')[1]}" for name in sorted(used)))
    print(f"file: {len(needed)}, peso: {size / 1e6:.1f} MB in {out}")


if __name__ == "__main__":
    sys.exit(main())
