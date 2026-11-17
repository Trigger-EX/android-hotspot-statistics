/*
 * hscapture — minimal packet capture helper for Hotspot Statistics.
 *
 * Runs as root (via su). Opens an AF_PACKET socket on one interface, keeps only frames
 * to/from the selected hosts, and writes a libpcap file to stdout. Human-readable status
 * goes to stderr, one line per record:
 *
 *   I <linktype> <iface>                         capture started
 *   S <elapsed_ms> <packets> <bytes> <drops> <seen>   stats, once per second
 *   P <epoch_ms> <length> <summary...>            packet summary (rate limited)
 *   E <message>                                   error
 *   X <reason> <packets> <bytes> <drops>          capture ended
 *
 * It stops when stdin delivers 'q' or EOF, when stdout breaks, when the interface goes down,
 * or on -d / -c / -C limits, so the caller never has to kill a root process.
 *
 * Usage: hscapture -i IFACE [-a IPV4]... [-m MAC]... [-s SNAPLEN] [-d SECONDS]
 *                  [-c COUNT] [-C MAX_FILE_BYTES] [-r SUMMARIES_PER_SEC] [-n]
 *
 * -C stops before a record that would push the file past the limit, so the file never
 * ends in a partial packet.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <linux/if_packet.h>
#include <net/ethernet.h>
#include <net/if.h>
#include <net/if_arp.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#ifndef ARPHRD_RAWIP
#define ARPHRD_RAWIP 519
#endif

#define MAX_HOSTS 64
#define BUF_SIZE 65536
#define LINKTYPE_ETHERNET 1
#define LINKTYPE_RAW 101

static uint32_t g_ips[MAX_HOSTS];
static int g_nips;
static uint8_t g_macs[MAX_HOSTS][6];
static int g_nmacs;
static volatile sig_atomic_t g_stop;

static void on_signal(int sig) {
    (void) sig;
    g_stop = 1;
}

static int64_t now_ms(clockid_t clock) {
    struct timespec ts;
    clock_gettime(clock, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int parse_mac(const char *s, uint8_t out[6]) {
    unsigned v[6];
    if (sscanf(s, "%2x:%2x:%2x:%2x:%2x:%2x", &v[0], &v[1], &v[2], &v[3], &v[4], &v[5]) != 6) return -1;
    for (int i = 0; i < 6; i++) out[i] = (uint8_t) v[i];
    return 0;
}

static int has_filter(void) { return g_nips > 0 || g_nmacs > 0; }

static int ip_selected(const uint8_t *p) {
    uint32_t ip;
    memcpy(&ip, p, 4);
    for (int i = 0; i < g_nips; i++)
        if (g_ips[i] == ip) return 1;
    return 0;
}

static int mac_selected(const uint8_t *p) {
    for (int i = 0; i < g_nmacs; i++)
        if (memcmp(g_macs[i], p, 6) == 0) return 1;
    return 0;
}

/* Returns the offset of the network-layer header and its ethertype. */
static int l3_offset(const uint8_t *pkt, int len, int linktype, uint16_t *ethertype) {
    if (linktype == LINKTYPE_RAW) {
        if (len < 1) return -1;
        *ethertype = (pkt[0] >> 4) == 6 ? 0x86dd : 0x0800;
        return 0;
    }
    if (len < 14) return -1;
    int off = 12;
    uint16_t type = (uint16_t) (pkt[off] << 8 | pkt[off + 1]);
    while ((type == 0x8100 || type == 0x88a8) && off + 6 <= len) {
        off += 4;
        type = (uint16_t) (pkt[off] << 8 | pkt[off + 1]);
    }
    *ethertype = type;
    return off + 2;
}

static int matches(const uint8_t *pkt, int len, int linktype) {
    if (!has_filter()) return 1;
    if (linktype == LINKTYPE_ETHERNET && len >= 12 && (mac_selected(pkt) || mac_selected(pkt + 6))) return 1;
    uint16_t type;
    int off = l3_offset(pkt, len, linktype, &type);
    if (off < 0) return 0;
    if (type == 0x0800 && off + 20 <= len) return ip_selected(pkt + off + 12) || ip_selected(pkt + off + 16);
    if (type == 0x0806 && off + 28 <= len) return ip_selected(pkt + off + 14) || ip_selected(pkt + off + 24);
    return 0;
}

/* Reads the first question name of a DNS message into out. */
static void dns_name(const uint8_t *p, int len, char *out, size_t cap) {
    out[0] = 0;
    if (len < 13 || (p[4] << 8 | p[5]) == 0) return;
    int i = 12;
    size_t o = 0;
    while (i < len && p[i] != 0) {
        int l = p[i];
        if ((l & 0xc0) || i + 1 + l > len || o + l + 2 >= cap) return;
        if (o) out[o++] = '.';
        for (int k = 0; k < l; k++) {
            char c = (char) p[i + 1 + k];
            out[o++] = (c > 32 && c < 127) ? c : '?';
        }
        i += l + 1;
    }
    out[o] = 0;
}

static void tcp_flags(uint8_t f, char *out) {
    int o = 0;
    if (f & 0x02) out[o++] = 'S';
    if (f & 0x01) out[o++] = 'F';
    if (f & 0x04) out[o++] = 'R';
    if (f & 0x08) out[o++] = 'P';
    if (f & 0x10) out[o++] = '.';
    if (!o) out[o++] = '-';
    out[o] = 0;
}

static void l4_summary(char *out, size_t cap, uint8_t proto, const uint8_t *l4, int l4len,
                       const char *src, const char *dst, int v6) {
    if ((proto == 6 || proto == 17) && l4len >= 8) {
        unsigned sp = l4[0] << 8 | l4[1], dp = l4[2] << 8 | l4[3];
        const char *fmt = v6 ? "%s [%s]:%u > [%s]:%u" : "%s %s:%u > %s:%u";
        int n = snprintf(out, cap, fmt, proto == 6 ? "TCP" : "UDP", src, sp, dst, dp);
        if (n < 0 || (size_t) n >= cap) return;
        if (proto == 6 && l4len >= 14) {
            char f[8];
            tcp_flags(l4[13], f);
            snprintf(out + n, cap - n, " [%s]", f);
        } else if (proto == 17 && (sp == 53 || dp == 53 || sp == 5353 || dp == 5353) && l4len > 8) {
            char name[256];
            dns_name(l4 + 8, l4len - 8, name, sizeof name);
            if (name[0]) snprintf(out + n, cap - n, " DNS%s %s", (l4len > 10 && (l4[10] & 0x80)) ? "" : "?", name);
        }
    } else if ((proto == 1 || proto == 58) && l4len >= 1) {
        snprintf(out, cap, "%s %s > %s type %u", proto == 1 ? "ICMP" : "ICMPv6", src, dst, l4[0]);
    } else {
        snprintf(out, cap, "IP proto %u %s > %s", proto, src, dst);
    }
}

static void summarize(const uint8_t *pkt, int len, int linktype, char *out, size_t cap) {
    uint16_t type;
    int off = l3_offset(pkt, len, linktype, &type);
    char a[INET6_ADDRSTRLEN], b[INET6_ADDRSTRLEN];
    snprintf(out, cap, "frame");
    if (off < 0) return;
    const uint8_t *l3 = pkt + off;
    int l3len = len - off;
    if (type == 0x0800 && l3len >= 20) {
        int ihl = (l3[0] & 0x0f) * 4;
        inet_ntop(AF_INET, l3 + 12, a, sizeof a);
        inet_ntop(AF_INET, l3 + 16, b, sizeof b);
        if (ihl < 20 || ihl > l3len) ihl = l3len;
        l4_summary(out, cap, l3[9], l3 + ihl, l3len - ihl, a, b, 0);
    } else if (type == 0x86dd && l3len >= 40) {
        inet_ntop(AF_INET6, l3 + 8, a, sizeof a);
        inet_ntop(AF_INET6, l3 + 24, b, sizeof b);
        l4_summary(out, cap, l3[6], l3 + 40, l3len - 40, a, b, 1);
    } else if (type == 0x0806 && l3len >= 28) {
        inet_ntop(AF_INET, l3 + 14, a, sizeof a);
        inet_ntop(AF_INET, l3 + 24, b, sizeof b);
        unsigned op = l3[6] << 8 | l3[7];
        if (op == 1) {
            snprintf(out, cap, "ARP who-has %s tell %s", b, a);
        } else {
            const uint8_t *m = l3 + 8;
            snprintf(out, cap, "ARP %s is-at %02x:%02x:%02x:%02x:%02x:%02x", a, m[0], m[1], m[2], m[3], m[4], m[5]);
        }
    } else if (linktype == LINKTYPE_ETHERNET) {
        snprintf(out, cap, "ETH type 0x%04x %02x:%02x:%02x:%02x:%02x:%02x > %02x:%02x:%02x:%02x:%02x:%02x", type,
                 pkt[6], pkt[7], pkt[8], pkt[9], pkt[10], pkt[11], pkt[0], pkt[1], pkt[2], pkt[3], pkt[4], pkt[5]);
    }
}

static int write_all(FILE *f, const void *data, size_t len) {
    return fwrite(data, 1, len, f) == len ? 0 : -1;
}

int main(int argc, char **argv) {
    const char *iface = NULL;
    int snaplen = BUF_SIZE, duration = 0, rate = 50, watch_stdin = 1;
    long long max_count = 0, max_bytes = 0;
    int opt;

    while ((opt = getopt(argc, argv, "i:a:m:s:d:c:C:r:n")) != -1) {
        switch (opt) {
            case 'i': iface = optarg; break;
            case 'a':
                if (g_nips >= MAX_HOSTS || inet_pton(AF_INET, optarg, &g_ips[g_nips]) != 1) {
                    fprintf(stderr, "E bad or too many IPv4 addresses: %s\n", optarg);
                    return 2;
                }
                g_nips++;
                break;
            case 'm':
                if (g_nmacs >= MAX_HOSTS || parse_mac(optarg, g_macs[g_nmacs]) != 0) {
                    fprintf(stderr, "E bad or too many MAC addresses: %s\n", optarg);
                    return 2;
                }
                g_nmacs++;
                break;
            case 's': snaplen = atoi(optarg); break;
            case 'd': duration = atoi(optarg); break;
            case 'c': max_count = atoll(optarg); break;
            case 'C': max_bytes = atoll(optarg); break;
            case 'r': rate = atoi(optarg); break;
            case 'n': watch_stdin = 0; break;
            default:
                fprintf(stderr, "E usage: hscapture -i IFACE [-a IP]... [-m MAC]... [-s SNAPLEN] [-d SEC] [-c COUNT] [-C BYTES] [-r RATE] [-n]\n");
                return 2;
        }
    }
    if (!iface) {
        fprintf(stderr, "E missing -i IFACE\n");
        return 2;
    }
    if (snaplen < 64 || snaplen > BUF_SIZE) snaplen = BUF_SIZE;

    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    signal(SIGHUP, on_signal);

    unsigned ifindex = if_nametoindex(iface);
    if (!ifindex) {
        fprintf(stderr, "E no such interface %s\n", iface);
        return 3;
    }

    /* Ethernet-style devices (Wi-Fi AP, loopback) give full frames; raw-IP devices do not. */
    int linktype = LINKTYPE_ETHERNET, sock_type = SOCK_RAW, is_loopback = 0;
    {
        int s = socket(AF_INET, SOCK_DGRAM, 0);
        struct ifreq ifr;
        memset(&ifr, 0, sizeof ifr);
        strncpy(ifr.ifr_name, iface, IFNAMSIZ - 1);
        if (s >= 0 && ioctl(s, SIOCGIFHWADDR, &ifr) == 0) {
            unsigned short hw = ifr.ifr_hwaddr.sa_family;
            is_loopback = hw == ARPHRD_LOOPBACK;
            if (hw != ARPHRD_ETHER && hw != ARPHRD_LOOPBACK && hw != ARPHRD_IEEE802) {
                linktype = LINKTYPE_RAW;
                sock_type = SOCK_DGRAM;
            }
        }
        if (s >= 0) close(s);
    }

    /* Protocol 0: receive nothing until bind() picks the interface, otherwise frames from
     * every interface (raw-IP cellular, loopback) would queue up first. */
    int sock = socket(AF_PACKET, sock_type, 0);
    if (sock < 0) {
        fprintf(stderr, "E socket: %s (root required)\n", strerror(errno));
        return 4;
    }
    int rcvbuf = 8 << 20;
    if (setsockopt(sock, SOL_SOCKET, SO_RCVBUFFORCE, &rcvbuf, sizeof rcvbuf) != 0)
        setsockopt(sock, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof rcvbuf);

    struct sockaddr_ll sll;
    memset(&sll, 0, sizeof sll);
    sll.sll_family = AF_PACKET;
    sll.sll_protocol = htons(ETH_P_ALL);
    sll.sll_ifindex = (int) ifindex;
    if (bind(sock, (struct sockaddr *) &sll, sizeof sll) != 0) {
        fprintf(stderr, "E bind %s: %s\n", iface, strerror(errno));
        return 4;
    }

    static char outbuf[1 << 18];
    setvbuf(stdout, outbuf, _IOFBF, sizeof outbuf);
    setvbuf(stderr, NULL, _IOLBF, 0);

    struct {
        uint32_t magic;
        uint16_t major, minor;
        int32_t zone;
        uint32_t sigfigs, snaplen, linktype;
    } hdr = {0xa1b2c3d4, 2, 4, 0, 0, (uint32_t) snaplen, (uint32_t) linktype};
    if (write_all(stdout, &hdr, sizeof hdr) != 0 || fflush(stdout) != 0) {
        fprintf(stderr, "E cannot write output\n");
        return 5;
    }
    fprintf(stderr, "I %d %s\n", linktype, iface);

    static uint8_t buf[BUF_SIZE];
    long long packets = 0, bytes = 0, drops = 0, seen = 0, file_bytes = (long long) sizeof hdr;
    /* BOOTTIME keeps counting through deep sleep, so time limits are wall-clock accurate. */
    int64_t start = now_ms(CLOCK_BOOTTIME), next_stat = start + 1000, window = start;
    int summaries = 0, suppressed = 0;
    const char *reason = "stopped";

    struct pollfd pfd[2] = {{sock, POLLIN, 0}, {STDIN_FILENO, POLLIN, 0}};
    int nfds = watch_stdin ? 2 : 1;

    while (!g_stop) {
        int r = poll(pfd, nfds, 250);
        if (r < 0 && errno != EINTR) {
            reason = "poll-error";
            break;
        }
        int64_t now = now_ms(CLOCK_BOOTTIME);

        if (nfds == 2 && pfd[1].revents) {
            char cmd[64];
            ssize_t n = read(STDIN_FILENO, cmd, sizeof cmd);
            if (n <= 0 || memchr(cmd, 'q', (size_t) n)) {
                reason = "stopped";
                break;
            }
        }

        if (r > 0 && (pfd[0].revents & (POLLERR | POLLHUP | POLLNVAL))) {
            /* Interface went down or was removed (hotspot turned off): the socket now only
             * reports an error, which poll() would return forever. */
            int soerr = 0;
            socklen_t el = sizeof soerr;
            getsockopt(sock, SOL_SOCKET, SO_ERROR, &soerr, &el);
            fprintf(stderr, "E capture interface stopped: %s\n", soerr ? strerror(soerr) : "socket error");
            reason = "interface-down";
            break;
        }

        if (r > 0 && (pfd[0].revents & POLLIN)) {
            for (int burst = 0; burst < 512; burst++) {
                struct sockaddr_ll from;
                socklen_t fromlen = sizeof from;
                ssize_t n = recvfrom(sock, buf, sizeof buf, MSG_DONTWAIT | MSG_TRUNC, (struct sockaddr *) &from, &fromlen);
                if (n < 0) {
                    if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                        fprintf(stderr, "E capture interface stopped: %s\n", strerror(errno));
                        reason = "interface-down";
                        g_stop = 1;
                    }
                    break;
                }
                /* Loopback shows every packet twice (out and in); keep one, as tcpdump does. */
                if (is_loopback && from.sll_pkttype == PACKET_OUTGOING) continue;
                seen++;
                int avail = n > (ssize_t) sizeof buf ? (int) sizeof buf : (int) n;
                if (!matches(buf, avail, linktype)) continue;

                struct timespec ts;
                clock_gettime(CLOCK_REALTIME, &ts);
                uint32_t caplen = (uint32_t) (avail < snaplen ? avail : snaplen);
                if (max_bytes && file_bytes + 16 + caplen > max_bytes) {
                    reason = "size-limit";
                    g_stop = 1;
                    break;
                }
                uint32_t rec[4] = {(uint32_t) ts.tv_sec, (uint32_t) (ts.tv_nsec / 1000), caplen, (uint32_t) n};
                if (write_all(stdout, rec, sizeof rec) != 0 || write_all(stdout, buf, caplen) != 0) {
                    reason = "output-closed";
                    g_stop = 1;
                    break;
                }
                packets++;
                bytes += n;
                file_bytes += 16 + caplen;

                if (now - window >= 1000) {
                    if (suppressed) fprintf(stderr, "P %lld 0 (%d more packets not listed)\n",
                                            (long long) (ts.tv_sec * 1000LL + ts.tv_nsec / 1000000), suppressed);
                    window = now;
                    summaries = 0;
                    suppressed = 0;
                }
                if (summaries < rate) {
                    char text[384];
                    summarize(buf, avail, linktype, text, sizeof text);
                    fprintf(stderr, "P %lld %zd %s\n", (long long) (ts.tv_sec * 1000LL + ts.tv_nsec / 1000000), n, text);
                    summaries++;
                } else {
                    suppressed++;
                }
                if (max_count && packets >= max_count) {
                    reason = "count-limit";
                    g_stop = 1;
                    break;
                }
            }
        }

        if (now >= next_stat || g_stop) {
            struct tpacket_stats st;
            socklen_t sl = sizeof st;
            if (getsockopt(sock, SOL_PACKET, PACKET_STATISTICS, &st, &sl) == 0) drops += st.tp_drops;
            if (fflush(stdout) != 0) {
                reason = "output-closed";
                break;
            }
            fprintf(stderr, "S %lld %lld %lld %lld %lld\n", (long long) (now - start), packets, bytes, drops, seen);
            next_stat = now + 1000;
        } else if (r == 0 && fflush(stdout) != 0) {
            reason = "output-closed";
            break;
        }

        if (duration > 0 && now - start >= (int64_t) duration * 1000) {
            reason = "time-limit";
            break;
        }
    }

    fflush(stdout);
    fprintf(stderr, "X %s %lld %lld %lld\n", reason, packets, bytes, drops);
    close(sock);
    return 0;
}
