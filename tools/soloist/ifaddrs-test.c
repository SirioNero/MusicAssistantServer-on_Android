/*
 * Solo per la prova di Soloist (tools/soloist): come un programma glibc dentro proot, dentro Xonos, vede le
 * interfacce di rete. Soloist le elenca per l'annuncio mDNS (l'abbinamento con l'app Spotify); da Android 11 le
 * app non possono fare bind() su un socket netlink né chiedere RTM_GETLINK, e getifaddrs() di glibc fa tutte e
 * due le cose. Prova: getifaddrs(), netlink con e senza bind, RTM_GETADDR da solo (come fa Android stesso),
 * l'ioctl SIOCGIFCONF.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <ifaddrs.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

static void test_getifaddrs(void) {
    struct ifaddrs *list = NULL;
    if (getifaddrs(&list) != 0) {
        printf("getifaddrs: errore %d (%s)\n", errno, strerror(errno));
        return;
    }
    int count = 0;
    for (struct ifaddrs *item = list; item != NULL; item = item->ifa_next) {
        count++;
        if (item->ifa_addr != NULL && item->ifa_addr->sa_family == AF_INET) {
            char text[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, &((struct sockaddr_in *)item->ifa_addr)->sin_addr, text, sizeof text);
            printf("getifaddrs: %s %s flags=0x%x\n", item->ifa_name, text, item->ifa_flags);
        }
    }
    printf("getifaddrs: %d voci\n", count);
    freeifaddrs(list);
}

static int netlink_dump(int type, int do_bind) {
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
    if (fd < 0) {
        printf("netlink socket: errore %d (%s)\n", errno, strerror(errno));
        return -1;
    }
    if (do_bind) {
        struct sockaddr_nl local = {.nl_family = AF_NETLINK};
        if (bind(fd, (struct sockaddr *)&local, sizeof local) != 0) {
            printf("netlink bind: errore %d (%s)\n", errno, strerror(errno));
            close(fd);
            return -1;
        }
    }
    struct {
        struct nlmsghdr header;
        struct rtgenmsg message;
    } request = {
        .header = {.nlmsg_len = sizeof request, .nlmsg_type = type, .nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP, .nlmsg_seq = 1},
        .message = {.rtgen_family = AF_UNSPEC},
    };
    struct sockaddr_nl kernel = {.nl_family = AF_NETLINK};
    if (sendto(fd, &request, sizeof request, 0, (struct sockaddr *)&kernel, sizeof kernel) < 0) {
        printf("netlink %s sendto: errore %d (%s)\n", type == RTM_GETLINK ? "RTM_GETLINK" : "RTM_GETADDR", errno,
               strerror(errno));
        close(fd);
        return -1;
    }
    char buffer[16384];
    int messages = 0, done = 0;
    while (!done) {
        ssize_t got = recv(fd, buffer, sizeof buffer, 0);
        if (got < 0) {
            printf("netlink recv: errore %d (%s)\n", errno, strerror(errno));
            break;
        }
        for (struct nlmsghdr *header = (struct nlmsghdr *)buffer; NLMSG_OK(header, (size_t)got);
             header = NLMSG_NEXT(header, got)) {
            if (header->nlmsg_type == NLMSG_DONE) {
                done = 1;
                break;
            }
            if (header->nlmsg_type == NLMSG_ERROR) {
                struct nlmsgerr *error = NLMSG_DATA(header);
                printf("netlink: il kernel risponde errore %d (%s)\n", -error->error, strerror(-error->error));
                done = 1;
                break;
            }
            messages++;
        }
    }
    close(fd);
    return messages;
}

static void test_ioctl(void) {
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    char buffer[4096];
    struct ifconf conf = {.ifc_len = sizeof buffer, .ifc_buf = buffer};
    if (ioctl(fd, SIOCGIFCONF, &conf) != 0) {
        printf("SIOCGIFCONF: errore %d (%s)\n", errno, strerror(errno));
        close(fd);
        return;
    }
    for (struct ifreq *item = conf.ifc_req; (char *)item < buffer + conf.ifc_len; item++) {
        char text[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &((struct sockaddr_in *)&item->ifr_addr)->sin_addr, text, sizeof text);
        struct ifreq flags = {0};
        strncpy(flags.ifr_name, item->ifr_name, IFNAMSIZ - 1);
        int ok = ioctl(fd, SIOCGIFFLAGS, &flags);
        printf("SIOCGIFCONF: %s %s flags=%s0x%x\n", item->ifr_name, text, ok == 0 ? "" : "(errore) ",
               ok == 0 ? flags.ifr_flags : errno);
    }
    close(fd);
}

int main(void) {
    test_getifaddrs();
    printf("RTM_GETLINK con bind: %d messaggi\n", netlink_dump(RTM_GETLINK, 1));
    printf("RTM_GETLINK senza bind: %d messaggi\n", netlink_dump(RTM_GETLINK, 0));
    printf("RTM_GETADDR senza bind: %d messaggi\n", netlink_dump(RTM_GETADDR, 0));
    test_ioctl();
    return 0;
}
