/*
 * Solo per la prova di Soloist (tools/soloist): proot ferma il programma solo per gli ioctl che tratta (il filtro di
 * seccomp di app/native/proot.sh). Le fermate si contano con le commutazioni volontarie del processo (getrusage,
 * ru_nvcsw): un programma fermato in proot cede il processore, all'entrata della chiamata e all'uscita.
 *   - FIONREAD su un tubo, come PulseAudio a ogni domanda sui tempi: non deve fermarsi.
 *   - TCGETS2 su un tubo: proot la tratta (su Android la cambia in TCGETS), quindi si ferma ancora. Prima del filtro
 *     anche FIONREAD si fermava così: è il "prima" da confrontare.
 *   - SIOCGIFINDEX ("lo"): proot la risolve da sé (Android la nega per le interfacce vere). Deve fermarsi e dare 1.
 *   - FICLONE tra due tubi: proot guarda il risultato all'uscita. Deve fermarsi.
 * In fondo "ioctl: filtro ok" e uscita 0, oppure "ioctl: PROBLEMA ..." e uscita 1. Fuori da proot nessuna chiamata
 * si ferma: la prova ha senso solo dentro proot.
 */
#define _GNU_SOURCE
#include <asm/termbits.h> /* struct termios2, per TCGETS2 */
#include <errno.h>
#include <net/if.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#ifndef FICLONE
#define FICLONE _IOW(0x94, 9, int)
#endif

#define MANY 20000
#define FEW 2000

struct result {
    double stops;  /* fermate in proot per chiamata */
    double micros; /* microsecondi per chiamata */
    int error;     /* errno dell'ultima chiamata, 0 se è andata */
};

static int problems = 0;

static struct result measure(const char *name, int fd, unsigned long command, void *arg, int count) {
    struct result result = {0, 0, 0};
    struct rusage before, after;
    struct timespec start, end;
    int last = 0;
    /* Le prime chiamate possono trovare pagine da caricare: non contano. */
    for (int i = 0; i < 10; i++) ioctl(fd, command, arg);
    getrusage(RUSAGE_SELF, &before);
    clock_gettime(CLOCK_MONOTONIC, &start);
    for (int i = 0; i < count; i++) last = ioctl(fd, command, arg);
    result.error = last < 0 ? errno : 0;
    clock_gettime(CLOCK_MONOTONIC, &end);
    getrusage(RUSAGE_SELF, &after);
    result.stops = (double)(after.ru_nvcsw - before.ru_nvcsw) / count;
    result.micros = ((end.tv_sec - start.tv_sec) * 1e6 + (end.tv_nsec - start.tv_nsec) / 1e3) / count;
    printf("ioctl %s: %d chiamate, %.2f µs l'una, %.2f fermate in proot l'una", name, count, result.micros,
           result.stops);
    if (result.error != 0) printf(" (errore %d, %s)", result.error, strerror(result.error));
    printf("\n");
    return result;
}

static void expect(int ok, const char *what) {
    printf("%s: %s\n", ok ? "ok" : "PROBLEMA", what);
    if (!ok) problems++;
}

int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    int pipe_a[2], pipe_b[2];
    if (pipe(pipe_a) != 0 || pipe(pipe_b) != 0) {
        printf("ioctl: PROBLEMA, tubi non creati: %s\n", strerror(errno));
        return 1;
    }
    if (write(pipe_a[1], "xonos", 5) != 5) printf("scrittura nel tubo non riuscita: %s\n", strerror(errno));

    int queued = -1;
    struct result fionread = measure("FIONREAD (tubo)", pipe_a[0], FIONREAD, &queued, MANY);
    expect(fionread.error == 0 && queued == 5, "FIONREAD dà i 5 byte nel tubo");
    expect(fionread.stops < 0.1, "FIONREAD non si ferma in proot");

    struct termios2 terminal;
    memset(&terminal, 0, sizeof terminal);
    struct result tcgets2 = measure("TCGETS2 (tubo)", pipe_a[0], TCGETS2, &terminal, MANY);
    expect(tcgets2.stops >= 1.5, "TCGETS2 si ferma ancora in proot, all'entrata e all'uscita");

    int sock = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    struct ifreq request;
    memset(&request, 0, sizeof request);
    strncpy(request.ifr_name, "lo", IFNAMSIZ - 1);
    struct result ifindex = measure("SIOCGIFINDEX (lo)", sock, SIOCGIFINDEX, &request, FEW);
    printf("SIOCGIFINDEX: lo = %d\n", request.ifr_ifindex);
    expect(ifindex.error == 0 && request.ifr_ifindex == 1, "SIOCGIFINDEX dà l'indice di lo");
    expect(ifindex.stops >= 1.5, "SIOCGIFINDEX si ferma ancora in proot");

    struct result ficlone = measure("FICLONE (tubi)", pipe_b[1], FICLONE, (void *)(long)pipe_a[0], FEW);
    expect(ficlone.error != 0, "FICLONE tra due tubi dà un errore");
    expect(ficlone.stops >= 1.5, "FICLONE si ferma ancora in proot, anche all'uscita");

    if (fionread.micros > 0)
        printf("FIONREAD %.2f µs, TCGETS2 %.2f µs: senza il filtro FIONREAD costava come TCGETS2\n", fionread.micros,
               tcgets2.micros);
    if (problems == 0) {
        printf("ioctl: filtro ok\n");
        return 0;
    }
    printf("ioctl: PROBLEMA, %d controlli non passati\n", problems);
    return 1;
}
