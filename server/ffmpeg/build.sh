#!/usr/bin/env bash
# ffmpeg e ffprobe per il server di Music Assistant dentro Xonos, compilati per Android dal sorgente.
#
#   ANDROID_NDK=<cartella dell'NDK> server/ffmpeg/build.sh <abi> <cartella di uscita>
#
# Music Assistant li chiama per leggere, convertire e analizzare l'audio (e le copertine dentro i
# file): senza, il server non parte. Si compilano come librerie condivise (libavcodec.so...) più i due
# programmi, chiamati libffmpeg.so e libffprobe.so: Android installa solo file lib*.so, nella cartella
# delle librerie dell'app, che è l'unico posto da cui un'app può eseguire un programma.
# Solo l'audio e le immagini delle copertine; https con mbedTLS (radio e podcast); niente librerie
# GPL. Se l'uscita c'è già, compilata da questa stessa versione dello script, non rifà niente.
set -euo pipefail

FFMPEG_VERSION=7.1.5
MBEDTLS_VERSION=3.6.5
API=26

ABI=${1:?abi}
OUT=$(mkdir -p "${2:?cartella di uscita}" && cd "$2" && pwd)/$ABI
NDK=${ANDROID_NDK:?serve ANDROID_NDK con la cartella dell’NDK}
# Fuori dalla cartella delle librerie, che finisce nell'APK così com'è.
STAMP="$(dirname "$OUT")/.build-$ABI"
ME=$(sha256sum "$0" | cut -d' ' -f1)
if [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$ME" ] && [ -f "$OUT/libffmpeg.so" ]; then
    echo "ffmpeg per $ABI già pronto in $OUT"
    exit 0
fi

case "$ABI" in
    arm64-v8a) TRIPLE=aarch64-linux-android; ARCH=aarch64; CPU=armv8-a; ASM=() ;;
    # Per l'emulatore delle prove: senza nasm, le parti in assembly x86 si lasciano fuori.
    x86_64) TRIPLE=x86_64-linux-android; ARCH=x86_64; CPU=x86-64; ASM=(--disable-x86asm) ;;
    *) echo "ABI non prevista: $ABI"; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
PREFIX="$WORK/prefix"
JOBS=$(nproc)

echo "mbedTLS $MBEDTLS_VERSION per $ABI"
curl -fsSL "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-$MBEDTLS_VERSION/mbedtls-$MBEDTLS_VERSION.tar.bz2" \
    | tar xj -C "$WORK"
cmake -S "$WORK/mbedtls-$MBEDTLS_VERSION" -B "$WORK/mbedtls-build" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DENABLE_PROGRAMS=OFF -DENABLE_TESTING=OFF -DGEN_FILES=OFF \
    -DUSE_SHARED_MBEDTLS_LIBRARY=OFF -DUSE_STATIC_MBEDTLS_LIBRARY=ON >/dev/null
cmake --build "$WORK/mbedtls-build" -j "$JOBS" >/dev/null
cmake --install "$WORK/mbedtls-build" >/dev/null

echo "ffmpeg $FFMPEG_VERSION per $ABI"
# ffmpeg.org a volte non risponde ai runner di GitHub (4 novembre 2026: «Could not connect to server»):
# si riprova, poi si prende lo stesso tag dal mirror di FFmpeg su GitHub. Lì manca il file VERSION delle
# release, che si scrive: Music Assistant legge la versione da «ffmpeg -version» e vuole la 7.
SRC="$WORK/ffmpeg-$FFMPEG_VERSION"
mkdir -p "$SRC"
if curl -fsSL --retry 3 --retry-all-errors --retry-delay 10 --connect-timeout 30 \
        "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" -o "$WORK/ffmpeg.tar.xz"; then
    tar xJf "$WORK/ffmpeg.tar.xz" --strip-components=1 -C "$SRC"
else
    echo "ffmpeg.org non risponde: prendo n$FFMPEG_VERSION da github.com/FFmpeg/FFmpeg"
    curl -fsSL --retry 3 --retry-all-errors --retry-delay 10 --connect-timeout 30 \
        "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n$FFMPEG_VERSION.tar.gz" \
        | tar xz --strip-components=1 -C "$SRC"
    echo "$FFMPEG_VERSION" > "$SRC/VERSION"
fi
rm -f "$WORK/ffmpeg.tar.xz"
cd "$SRC"
# Le decodifiche: tutto l'audio, più le immagini delle copertine.
DECODERS='aac*,ac3*,eac3*,alac,als,ape,atrac*,cook,dca,dsd_*,flac,mlp,truehd,mp1*,mp2*,mp3*,mpc7,mpc8'
DECODERS+=',opus,vorbis,wavpack,wma*,tta,tak,speex,gsm*,amr*,qdm2,qdmc,ra_*,ralf,sipr,twinvq,adpcm_*'
DECODERS+=',pcm_*,s302m,shorten,comfortnoise,dfpwm,dolby_e,mjpeg,png,bmp,gif,webp,tiff'
# Le codifiche che Music Assistant chiede: PCM, FLAC e AAC per l'audio, JPEG per le copertine.
ENCODERS='pcm_*,flac,aac,alac,wavpack,mjpeg,png'
PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" ./configure \
    --prefix="$PREFIX" --target-os=android --arch="$ARCH" --cpu="$CPU" --enable-cross-compile \
    --cc="$TOOLCHAIN/bin/$TRIPLE$API-clang" --cxx="$TOOLCHAIN/bin/$TRIPLE$API-clang++" \
    --ar="$TOOLCHAIN/bin/llvm-ar" --nm="$TOOLCHAIN/bin/llvm-nm" --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
    --strip="$TOOLCHAIN/bin/llvm-strip" --sysroot="$TOOLCHAIN/sysroot" --pkg-config=pkg-config \
    --extra-cflags="-I$PREFIX/include -O2" --extra-ldflags="-L$PREFIX/lib" \
    --enable-shared --disable-static --enable-pic "${ASM[@]}" \
    --disable-doc --disable-debug --disable-avdevice --disable-ffplay \
    --disable-hwaccels --disable-mediacodec --disable-jni --disable-vulkan \
    --enable-zlib --enable-mbedtls --enable-version3 \
    --disable-decoders --enable-decoder="$DECODERS" \
    --disable-encoders --enable-encoder="$ENCODERS" >/dev/null
make -j "$JOBS" >/dev/null
make install >/dev/null

rm -rf "$OUT"
mkdir -p "$OUT"
for lib in avutil swresample swscale avcodec avformat avfilter; do
    cp "$PREFIX/lib/lib$lib.so" "$OUT/"
done
cp "$PREFIX/bin/ffmpeg" "$OUT/libffmpeg.so"
cp "$PREFIX/bin/ffprobe" "$OUT/libffprobe.so"
"$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$OUT"/*.so
echo "$ME" > "$STAMP"
ls -la "$OUT"
