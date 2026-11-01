/*
 * Xonos: al posto della libreria "replace" di Samba, per compilare solo talloc.c (la usa proot, vedi proot.sh)
 * con l'NDK, senza waf. Le definizioni sono quelle che la configurazione di Samba darebbe su Linux/Android.
 */
#ifndef XONOS_TALLOC_REPLACE_H
#define XONOS_TALLOC_REPLACE_H

#include <errno.h>
#include <limits.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 5
#define TALLOC_BUILD_VERSION_RELEASE 0

#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_INTPTR_T 1
#define HAVE_VA_COPY 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1

#ifndef _PUBLIC_
#define _PUBLIC_ __attribute__((visibility("default")))
#endif
#ifndef PRINTF_ATTRIBUTE
#define PRINTF_ATTRIBUTE(a1, a2) __attribute__((format(__printf__, a1, a2)))
#endif
#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif
#ifndef MAX
#define MAX(a, b) ((a) > (b) ? (a) : (b))
#endif

/* memset che il compilatore non toglie (C23): bionic non lo ha. */
static inline void *xonos_memset_explicit(void *s, int c, size_t n)
{
	volatile unsigned char *p = s;
	while (n--) {
		*p++ = (unsigned char)c;
	}
	return s;
}
#define memset_explicit xonos_memset_explicit

#endif
