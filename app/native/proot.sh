#!/usr/bin/env bash
# proot (il fork di Termux) e la sua libreria talloc, compilati dal sorgente con l'NDK per le librerie dell'app.
#
#   app/native/proot.sh <cartella dell'NDK> <cartella di uscita> <abi>...
#   app/native/proot.sh --correzioni <cartella src di proot>
#
# proot fa girare dentro Xonos i programmi per Linux con glibc (Soloist, il motore ufficiale di Spotify, e
# PulseAudio, a cui Soloist dà l'audio), con un piccolo sistema Linux tutto loro: vedi tools/soloist e il server
# di Music Assistant sul telefono. Da Android 10 un'app può avviare un programma solo dalla cartella delle sue
# librerie, dove Android installa i file lib*.so dell'APK: per questo escono <uscita>/<abi>/libproot.so e
# libproot-loader.so (il caricatore che proot avvia al posto di ogni programma).
#
# I sorgenti sono quelli fissati qui sotto, con lo SHA-256 (gli stessi del pacchetto di Termux), più le correzioni
# di patch_sources. Il risultato è lo stesso byte per byte su GitHub e su F-Droid: stesso NDK (ndkVersion in
# app/build.gradle.kts), percorsi tolti dal codice, niente build-id. talloc si compila da solo (talloc.c con
# talloc-replace.h al posto della libreria "replace" di Samba) e va dentro proot, statica.
#
# La seconda forma fa solo le correzioni, su una cartella src di proot già estratta: per guardarle (con un diff)
# anche dove l'NDK non c'è.
set -euo pipefail

PROOT_VERSION=5.1.107.101
PROOT_SHA256=a66743f50c7b0bac1913b83bf55e139d679c80a0ca2808eec76432a65640017f
TALLOC_VERSION=2.5.0
TALLOC_SHA256=912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007
API=26

HERE=$(cd "$(dirname "$0")" && pwd)

# Nel file $1 cambia il testo $3 (esatto, anche di più righe, tab compresi) in $4, ogni volta che c'è. Deve esserci
# $2 volte: se il sorgente di proot non è quello atteso, si ferma. Il risultato è lo stesso con ogni awk (mawk, gawk).
replace() {
    local file=$1 times=$2 found
    found=$(OLD=$3 NEW=$4 LC_ALL=C awk -v out="$file.xonos" '
        BEGIN { RS = "\001"; old = ENVIRON["OLD"]; new = ENVIRON["NEW"] }
        { text = text $0 }
        END {
            n = 0
            done = ""
            while (old != "" && (i = index(text, old)) > 0) {
                done = done substr(text, 1, i - 1) new
                text = substr(text, i + length(old))
                n++
            }
            printf "%s%s", done, text > out
            print n
        }' "$file")
    if [ "$found" != "$times" ]; then
        rm -f "$file.xonos"
        echo "$(basename "$file") di proot diverso da quello atteso: «${3%%$'\n'*}» c'è $found volte invece di $times"
        exit 1
    fi
    mv "$file.xonos" "$file"
}

# ioctl nel filtro di seccomp. Su Android proot ferma il programma a ogni ioctl, all'entrata e all'uscita
# (syscall/seccomp.c), ma ne tratta pochi: SIOCGIFINDEX e i comandi dei terminali (TCSETS+2, TCGETS2, TCSETS2,
# TCSETSW2, TCSETSF2) all'entrata (syscall/enter.c), FICLONE all'uscita (syscall/exit.c). PulseAudio fa un ioctl
# FIONREAD sul tubo del pipe sink a ogni domanda sui tempi: con Soloist circa 130 al secondo, ognuno con due fermate
# in proot che non servono (sul telefono proot[pulseaudio] a 230-280 risvegli al secondo). Qui il filtro guarda anche
# il comando e ferma il programma solo per quelli della lista: gli altri vanno dritti al kernel. Per ioctl il filtro
# ha più istruzioni, quindi le sezioni per architettura contano le istruzioni e non più le chiamate; il controllo
# di proot sulla loro lunghezza (end_arch_section) resta. Le estensioni di proot (Xonos non ne usa) non hanno ioctl
# nel loro elenco.
filter_ioctl() {
    local file=$1
    # Prima il cambio che su un sorgente già corretto non trova niente: così lì si ferma senza toccarlo.
    replace "$file" 12 nb_traced_syscalls nb_traced_statements
    replace "$file" 1 $'#include <assert.h>        /* assert(3), */\n' \
        $'#include <assert.h>        /* assert(3), */\n#include <sys/ioctl.h>     /* _IOW, TCSETS, TCGETS2... (Xonos) */\n#include <linux/sockios.h> /* SIOCGIFINDEX (Xonos) */\n'
    replace "$file" 2 'nb_traced_statements * LENGTH_TRACE_SYSCALL' nb_traced_statements
    replace "$file" 1 '/* Pre-compute the number of traced syscalls for this architecture.  */' \
        '/* Xonos: quante istruzioni seguono le chiamate per questa architettura (ioctl ne ha di più).  */'
    replace "$file" 1 'nb_traced_statements++;' 'nb_traced_statements += trace_length(sysnums[k].value);'
    replace "$file" 1 'status = add_trace_syscall(&program, syscall, sysnums[k].flags);' \
        'status = add_trace(&program, sysnums[k].value, syscall, sysnums[k].flags);'
    # Le funzioni nuove vanno dopo add_trace_syscall(), che definisce LENGTH_TRACE_SYSCALL.
    local end block
    end=$'\tstatus = add_statements(program, LENGTH_TRACE_SYSCALL, statements);\n\tif (status < 0)\n\t\treturn status;\n\n\treturn 0;\n}\n'
    block=$(cat <<'EOF'
/*
 * Xonos (app/native/proot.sh): i comandi di ioctl che proot tratta, gli unici per cui il filtro
 * gli ferma il programma. All'entrata SIOCGIFINDEX e, su Android, i comandi dei terminali
 * (syscall/enter.c); all'uscita FICLONE (syscall/exit.c). Se proot comincia a trattarne un
 * altro, va aggiunto qui.
 */
static const uint32_t ioctl_commands[] = {
	SIOCGIFINDEX,
	_IOW(0x94, 9, int), /* FICLONE */
#ifdef __ANDROID__
	TCSETS + 2, TCGETS2, TCSETS2, TCSETSW2, TCSETSF2,
#endif
};
#define NB_IOCTL_COMMANDS (sizeof(ioctl_commands) / sizeof(ioctl_commands[0]))
#define LENGTH_TRACE_IOCTL (NB_IOCTL_COMMANDS + 4)

/* In BPF un salto condizionato arriva al massimo a 255 istruzioni.  */
_Static_assert(LENGTH_TRACE_IOCTL - 1 <= UINT8_MAX, "filtro di ioctl troppo lungo");

/* Il comando è il secondo argomento: il kernel ne legge 32 bit, quelli bassi.  */
#if __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
#define IOCTL_COMMAND_OFFSET (offsetof(struct seccomp_data, args[1]) + 4)
#else
#define IOCTL_COMMAND_OFFSET (offsetof(struct seccomp_data, args[1]))
#endif

/*
 * Xonos: come add_trace_syscall(), ma per ioctl il filtro guarda anche il comando. Il
 * programma si ferma in proot solo per i comandi di ioctl_commands; gli altri, come il
 * FIONREAD che PulseAudio fa a ogni domanda sui tempi, vanno dritti al kernel. Le
 * istruzioni, con N comandi:
 *
 *     0            non è ioctl: salta le altre N + 3 istruzioni del blocco
 *     1            mette il comando nell'accumulatore, al posto del numero della chiamata
 *     2 ... N + 1  è il comando i: salta all'ultima istruzione
 *     N + 2        non è nessuno dei comandi: lascia passare
 *     N + 3        ferma il programma per proot, con @flag come per le altre chiamate
 *
 * Quando la chiamata è ioctl il blocco finisce sempre con un return: le istruzioni dopo,
 * che confrontano il numero della chiamata, non vedono mai l'accumulatore cambiato.
 * Tutti i comandi fermati hanno lo stesso @flag (FILTER_SYSEXIT): tracee/event.c lo
 * confronta con quello di filtered_sysnum_flags() per riconoscere i kernel che lo perdono.
 */
static int add_trace_ioctl(struct sock_fprog *program, word_t syscall, int flag)
{
	struct sock_filter statements[LENGTH_TRACE_IOCTL];
	size_t n = 0;
	size_t i;

	if (syscall > UINT32_MAX)
		return -ERANGE;

	statements[n++] = (struct sock_filter)
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, syscall, 0, LENGTH_TRACE_IOCTL - 1);
	statements[n++] = (struct sock_filter)
		BPF_STMT(BPF_LD + BPF_W + BPF_ABS, IOCTL_COMMAND_OFFSET);
	for (i = 0; i < NB_IOCTL_COMMANDS; i++)
		statements[n++] = (struct sock_filter)
			BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, ioctl_commands[i], NB_IOCTL_COMMANDS - i, 0);
	statements[n++] = (struct sock_filter) BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW);
	statements[n++] = (struct sock_filter) BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRACE + flag);

	return add_statements(program, n, statements);
}

/* Xonos: quante istruzioni mette add_trace() per @sysnum.  */
static size_t trace_length(Sysnum sysnum)
{
	return sysnum == PR_ioctl ? LENGTH_TRACE_IOCTL : (size_t) LENGTH_TRACE_SYSCALL;
}

/* Xonos: add_trace_ioctl() per ioctl, add_trace_syscall() per le altre chiamate.  */
static int add_trace(struct sock_fprog *program, Sysnum sysnum, word_t syscall, int flag)
{
	if (sysnum == PR_ioctl)
		return add_trace_ioctl(program, syscall, flag);
	return add_trace_syscall(program, syscall, flag);
}
EOF
)
    replace "$file" 1 "$end" "$end"$'\n'"$block"$'\n'
}

# Le correzioni ai sorgenti di proot (cartella src). Ognuna si ferma se il sorgente non è quello atteso.
patch_sources() {
    local src=$1
    # ashmem_memfd.c usa strcmp e memset senza includere string.h: il clang dell'NDK 27 lo tratta come un errore.
    sed -i '1i #include <string.h>' "$src/extension/ashmem_memfd/ashmem_memfd.c"
    # Per arm64 il GNUmakefile scrive loader/loader-info.c con "readelf -s loader | awk -f loader-info.awk": il
    # readelf del sistema (binutils) e uno script per gawk. Senza readelf non si ferma, e proot esce sbagliato
    # (l'offset di pokedata_workaround diventa 0). Qui il readelf dell'NDK e loader-info.awk di Xonos, che va con
    # ogni awk e si ferma se mancano i simboli: stesso file, stesso proot.
    sed -i 's/^\treadelf -s \$</\t$(READELF) -s $</' "$src/GNUmakefile"
    grep -q '^	$(READELF) -s ' "$src/GNUmakefile" || { echo "GNUmakefile di proot diverso: readelf non trovato"; exit 1; }
    cp "$HERE/loader-info.awk" "$src/loader/loader-info.awk"
    filter_ioctl "$src/syscall/seccomp.c"
}

if [ "${1:-}" = --correzioni ]; then
    patch_sources "${2:?cartella src di proot}"
    exit 0
fi

NDK=${1:?cartella dell\'NDK}
OUT=$(mkdir -p "${2:?cartella di uscita}" && cd "$2" && pwd)
shift 2
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
    SRC="$BUILD/proot-$PROOT_VERSION/src"
    # I percorsi della macchina che compila non restano nei programmi (assert, __FILE__).
    MAP="-ffile-prefix-map=$BUILD=."
    echo "talloc $TALLOC_VERSION per $ABI"
    "$CC" -std=gnu11 -O2 -fPIC -g0 $MAP -I"$BUILD/talloc" -c "$BUILD/talloc/talloc.c" -o "$BUILD/talloc/talloc.o"
    "$TOOLCHAIN/bin/llvm-ar" rcsD "$BUILD/talloc/libtalloc.a" "$BUILD/talloc/talloc.o"
    echo "proot $PROOT_VERSION per $ABI"
    patch_sources "$SRC"
    # Le opzioni passano dall'ambiente: il GNUmakefile di proot ci aggiunge le sue (-ltalloc...). Il caricatore
    # resta un file a sé (PROOT_UNBUNDLE_LOADER; Xonos lo indica con PROOT_LOADER); niente caricatore a 32 bit,
    # perché i programmi da avviare sono a 64; GIT=false: la versione è quella scritta qui, non quella del
    # repository che compila.
    env CC="$CC" LD="$CC" READELF="$TOOLCHAIN/bin/llvm-readelf" \
        STRIP="$TOOLCHAIN/bin/llvm-strip" OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy" OBJDUMP="$TOOLCHAIN/bin/llvm-objdump" \
        CPPFLAGS="-DARG_MAX=131072 -DVERSION=\\\"$PROOT_VERSION\\\" -I$BUILD/talloc" \
        CFLAGS="-g0 $MAP" \
        LDFLAGS="-L$BUILD/talloc -Wl,--build-id=none -Wl,-z,max-page-size=16384" \
        PROOT_UNBUNDLE_LOADER=/xonos \
        make -s -C "$SRC" -j"$(nproc)" HAS_LOADER_32BIT= GIT=false proot loader/loader
    if [ "$ABI" = arm64-v8a ]; then
        grep -Eq '^const ssize_t offset_to_pokedata_workaround=-?[1-9][0-9]*;$' "$SRC/loader/loader-info.c" \
            || { echo "loader-info.c senza l'offset di pokedata_workaround:"; cat "$SRC/loader/loader-info.c"; exit 1; }
        sed -n 's/^const ssize_t //p' "$SRC/loader/loader-info.c"
    fi
    mkdir -p "$OUT/$ABI"
    "$TOOLCHAIN/bin/llvm-strip" --strip-all -o "$OUT/$ABI/libproot.so" "$SRC/proot"
    "$TOOLCHAIN/bin/llvm-strip" --strip-all -o "$OUT/$ABI/libproot-loader.so" "$SRC/loader/loader"
    # Le impronte, da confrontare con le librerie di una release (lib/<abi>/ nell'APK).
    (cd "$OUT" && sha256sum "$ABI"/*)
done
