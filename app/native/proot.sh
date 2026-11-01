#!/usr/bin/env bash
# proot (il fork di Termux) e la sua libreria talloc, compilati dal sorgente con l'NDK per le librerie dell'app.
#
#   app/native/proot.sh <cartella dell'NDK> <cartella di uscita> <abi>...
#
# proot fa girare dentro Xonos i programmi per Linux con glibc (Soloist, il motore ufficiale di Spotify, e
# PulseAudio, a cui Soloist dà l'audio), con un piccolo sistema Linux tutto loro: vedi tools/soloist e il server
# di Music Assistant sul telefono. Da Android 10 un'app può avviare un programma solo dalla cartella delle sue
# librerie, dove Android installa i file lib*.so dell'APK: per questo escono <uscita>/<abi>/libproot.so e
# libproot-loader.so (il caricatore che proot avvia al posto di ogni programma).
#
# I sorgenti sono quelli fissati qui sotto, con lo SHA-256 (gli stessi del pacchetto di Termux). Il risultato è
# lo stesso byte per byte su GitHub e su F-Droid: stesso NDK (ndkVersion in app/build.gradle.kts), percorsi
# tolti dal codice, niente build-id. talloc si compila da solo (talloc.c con talloc-replace.h al posto della
# libreria "replace" di Samba) e va dentro proot, statica.
set -euo pipefail

PROOT_VERSION=5.1.107.101
PROOT_SHA256=a66743f50c7b0bac1913b83bf55e139d679c80a0ca2808eec76432a65640017f
TALLOC_VERSION=2.5.0
TALLOC_SHA256=912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007
API=26

NDK=${1:?cartella dell\'NDK}
OUT=$(mkdir -p "${2:?cartella di uscita}" && cd "$2" && pwd)
shift 2
HERE=$(cd "$(dirname "$0")" && pwd)
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -x "$TOOLCHAIN/bin/clang" ] || { echo "NDK senza clang: $NDK"; exit 1; }
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

fetch() {
    local url=$1 sum=$2 file=$3
    curl -fsSL --retry 3 -o "$file" "$url"
    echo "$sum  $file" | sha256sum -c --quiet - || { echo "SHA-256 diverso per $url"; exit 1; }
}
fetch "https://github.com/termux/proot/archive/v$PROOT_VERSION.zip" "$PROOT_SHA256" "$WORK/proot.zip"
fetch "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" "$TALLOC_SHA256" "$WORK/talloc.tar.gz"

for ABI in "$@"; do
    case "$ABI" in
        arm64-v8a) TRIPLE=aarch64-linux-android ;;
        x86_64) TRIPLE=x86_64-linux-android ;;
        *) echo "ABI non prevista: $ABI"; exit 1 ;;
    esac
    BUILD="$WORK/$ABI"
    mkdir -p "$BUILD/talloc"
    unzip -q "$WORK/proot.zip" -d "$BUILD"
    tar -xzf "$WORK/talloc.tar.gz" -C "$BUILD"
    cp "$BUILD/talloc-$TALLOC_VERSION/talloc.c" "$BUILD/talloc-$TALLOC_VERSION/talloc.h" "$BUILD/talloc/"
    cp "$HERE/talloc-replace.h" "$BUILD/talloc/replace.h"
    CC="$TOOLCHAIN/bin/$TRIPLE$API-clang"
    # I percorsi della macchina che compila non restano nei programmi (assert, __FILE__).
    MAP="-ffile-prefix-map=$BUILD=."
    echo "talloc $TALLOC_VERSION per $ABI"
    "$CC" -std=gnu11 -O2 -fPIC -g0 $MAP -I"$BUILD/talloc" -c "$BUILD/talloc/talloc.c" -o "$BUILD/talloc/talloc.o"
    "$TOOLCHAIN/bin/llvm-ar" rcsD "$BUILD/talloc/libtalloc.a" "$BUILD/talloc/talloc.o"
    echo "proot $PROOT_VERSION per $ABI"
    # ashmem_memfd.c usa strcmp e memset senza includere string.h: il clang dell'NDK 27 lo tratta come un errore.
    sed -i '1i #include <string.h>' "$BUILD/proot-$PROOT_VERSION/src/extension/ashmem_memfd/ashmem_memfd.c"
    # Le opzioni passano dall'ambiente: il GNUmakefile di proot ci aggiunge le sue (-ltalloc...). Il caricatore
    # resta un file a sé (PROOT_UNBUNDLE_LOADER; Xonos lo indica con PROOT_LOADER); niente caricatore a 32 bit,
    # perché i programmi da avviare sono a 64; GIT=false: la versione è quella scritta qui, non quella del
    # repository che compila.
    env CC="$CC" LD="$CC" \
        STRIP="$TOOLCHAIN/bin/llvm-strip" OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy" OBJDUMP="$TOOLCHAIN/bin/llvm-objdump" \
        CPPFLAGS="-DARG_MAX=131072 -DVERSION=\\\"$PROOT_VERSION\\\" -I$BUILD/talloc" \
        CFLAGS="-g0 $MAP" \
        LDFLAGS="-L$BUILD/talloc -Wl,--build-id=none -Wl,-z,max-page-size=16384" \
        PROOT_UNBUNDLE_LOADER=/xonos \
        make -s -C "$BUILD/proot-$PROOT_VERSION/src" -j"$(nproc)" HAS_LOADER_32BIT= GIT=false proot loader/loader
    mkdir -p "$OUT/$ABI"
    "$TOOLCHAIN/bin/llvm-strip" --strip-all -o "$OUT/$ABI/libproot.so" "$BUILD/proot-$PROOT_VERSION/src/proot"
    "$TOOLCHAIN/bin/llvm-strip" --strip-all -o "$OUT/$ABI/libproot-loader.so" "$BUILD/proot-$PROOT_VERSION/src/loader/loader"
    ls -l "$OUT/$ABI"
done
