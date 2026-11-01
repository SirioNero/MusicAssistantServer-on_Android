#!/bin/bash
# La prova di Soloist dentro Xonos, la parte sul runner (Linux x86_64). Scarica Soloist da Spotify solo per la
# prova (non va nel repository, negli artefatti né nei log: Spotify non lo lascia ridistribuire), guarda di cosa
# ha bisogno, prepara un piccolo sistema Linux con le sue librerie e prende proot di Termux per Android, che va
# nella build di prova come libproot.so (vedi app/build.gradle.kts). Il riassunto va in $OUT/runner.txt.
set -euo pipefail
OUT=${OUT:-$PWD/soloist-out}
WORK=${WORK:-/tmp/soloist}
ABI=${ABI:-x86_64}
mkdir -p "$OUT" "$WORK"
REPORT="$OUT/runner.txt"
: > "$REPORT"
say() { echo "$@" | tee -a "$REPORT"; }

# 1. Soloist per le due architetture dei telefoni e dell'emulatore.
for arch in x86_64 arm64; do
  say "== Soloist $arch"
  curl -sSfL -o "$WORK/soloist-$arch.tar.gz" "https://soloist-builds.spotifycdn.com/soloist_release_$arch.tar.gz"
  say "archivio: $(stat -c %s "$WORK/soloist-$arch.tar.gz") byte"
  mkdir -p "$WORK/$arch"
  tar -xzf "$WORK/soloist-$arch.tar.gz" -C "$WORK/$arch"
  (cd "$WORK/$arch" && find . -type f -printf '%s %p\n' | sort -k2) | tee -a "$REPORT"
  bin=$(find "$WORK/$arch" -type f -name soloist | head -n 1)
  [ -n "$bin" ] || { say "nessun programma soloist nell'archivio"; exit 1; }
  file "$bin" | sed "s#$WORK/##" | tee -a "$REPORT"
  readelf -l "$bin" | grep -i 'interpreter' | tee -a "$REPORT" || true
  readelf -d "$bin" | grep -E 'NEEDED|RUNPATH|RPATH' | tee -a "$REPORT" || true
  say "glibc più nuova chiesta: $(objdump -T "$bin" | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -n 1)"
  # Le librerie che non sono tra le NEEDED le apre da sé (dlopen): l'audio, per esempio.
  say "librerie nominate dentro il programma: $(strings -n 6 "$bin" | grep -oE 'lib[A-Za-z0-9_+-]+\.so(\.[0-9]+)*' | sort -u | tr '\n' ' ')"
done

# 2. Soloist x86_64 sul runner: versione, opzioni, librerie che trova e le parole che dicono cosa usa ancora
# (audio, mDNS, file di sistema).
BIN=$(find "$WORK/x86_64" -type f -name soloist | head -n 1)
say "== Soloist sul runner"
timeout 20 "$BIN" --version 2>&1 | tee -a "$REPORT" || say "(--version: uscita $?)"
timeout 20 "$BIN" --help 2>&1 | head -n 80 | tee -a "$REPORT" || true
timeout 20 "$BIN" ctl --help 2>&1 | head -n 80 | tee -a "$REPORT" || true
ldd "$BIN" | tee -a "$REPORT"
say "== parole dentro Soloist"
strings -n 5 "$BIN" | grep -iE 'pulse|pipewire|alsa|avahi|mdns|zeroconf|dbus|XDG_|PULSE_|PIPEWIRE_|SOLOIST_|^/(etc|proc|sys|dev|run|var|usr)/' \
  | sort -u | head -n 150 | tee -a "$REPORT" || true

# 3. Il sistema Linux di prova: Soloist e le librerie che ldd ha trovato, ai loro posti, più busybox (statico)
# per guardarci dentro e i file che i programmi glibc si aspettano. Il DNS è quello dell'emulatore.
ROOT="$WORK/root"
rm -rf "$ROOT"
mkdir -p "$ROOT/opt/soloist" "$ROOT/tmp" "$ROOT/root" "$ROOT/bin" "$ROOT/etc/ssl/certs"
cp -a "$WORK/x86_64/." "$ROOT/opt/soloist/"
for lib in $(ldd "$BIN" | awk '/=> \// {print $3} /^\t\/.*ld-linux/ {print $1}'); do
  cp --parents -L "$lib" "$ROOT"
done
ln -s usr/lib "$ROOT/lib"
BUSYBOX=$(command -v busybox)
file -L "$BUSYBOX" | tee -a "$REPORT"
cp -L "$BUSYBOX" "$ROOT/bin/busybox"
ln -s busybox "$ROOT/bin/sh"
printf 'nameserver 10.0.2.3\nnameserver 8.8.8.8\n' > "$ROOT/etc/resolv.conf"
printf '127.0.0.1 localhost\n::1 localhost\n' > "$ROOT/etc/hosts"
printf 'hosts: files dns\n' > "$ROOT/etc/nsswitch.conf"
printf 'root:x:0:0:root:/root:/bin/sh\n' > "$ROOT/etc/passwd"
printf 'root:x:0:\n' > "$ROOT/etc/group"
cp -L /etc/ssl/certs/ca-certificates.crt "$ROOT/etc/ssl/certs/"
REL=$(cd "$WORK/x86_64" && find . -type f -name soloist | head -n 1)
echo "/opt/soloist/${REL#./}" > "$WORK/soloist-path"
say "== sistema di prova: $(du -sh "$ROOT" | cut -f1), Soloist in $(cat "$WORK/soloist-path")"
tar -C "$ROOT" -cf "$WORK/root.tar" .

# 4. proot di Termux per Android, con le sue librerie, nella build di prova come librerie dell'app. Il RUNPATH
# diventa $ORIGIN: proot trova le sue librerie accanto a sé senza LD_LIBRARY_PATH, che passerebbe anche ai
# programmi glibc dentro proot.
BASE=https://packages.termux.dev/apt/termux-main
curl -sSfL -o "$WORK/Packages" "$BASE/dists/stable/main/binary-$ABI/Packages"
rm -rf "$WORK/termux"
mkdir -p "$WORK/termux"
for pkg in proot libtalloc libandroid-shmem; do
  file=$(awk -v p="$pkg" '$1 == "Package:" {name = $2} $1 == "Filename:" && name == p {print $2; exit}' "$WORK/Packages")
  version=$(awk -v p="$pkg" '$1 == "Package:" {name = $2} $1 == "Version:" && name == p {print $2; exit}' "$WORK/Packages")
  say "== Termux: $pkg $version"
  curl -sSfL -o "$WORK/$pkg.deb" "$BASE/$file"
  dpkg-deb -x "$WORK/$pkg.deb" "$WORK/termux"
done
T="$WORK/termux/data/data/com.termux/files/usr"
JNI="app/src/debug/jniLibs/$ABI"
rm -rf "$JNI"
mkdir -p "$JNI"
cp "$T/bin/proot" "$JNI/libproot.so"
cp "$T/libexec/proot/loader" "$JNI/libproot-loader.so"
cp -L "$T/lib/libtalloc.so.2" "$JNI/libtalloc.so"
cp -L "$(find "$T/lib" -maxdepth 1 -name 'libandroid-shmem.so*' | sort | head -n 1)" "$JNI/libandroid-shmem.so"
patchelf --replace-needed libtalloc.so.2 libtalloc.so "$JNI/libproot.so"
patchelf --set-soname libtalloc.so "$JNI/libtalloc.so"
patchelf --set-rpath '$ORIGIN' "$JNI/libproot.so"
for f in "$JNI"/*.so; do
  say "$(basename "$f"): $(readelf -d "$f" | grep -E 'NEEDED|RUNPATH|SONAME' | sed -E 's/.*\((NEEDED|RUNPATH|SONAME)\).*\[(.*)\]/\1=\2/' | tr '\n' ' ')"
done
ls -l "$JNI" | tee -a "$REPORT"
