#include "net.h"

#include <errno.h>
#include <poll.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

/* Bounded total wait for a blocked (EAGAIN) write to become writable again.
   A dead or stuck peer must never hang the helper -- a few hundred ms is
   ample for a local abstract-namespace socket that is actually alive. */
#define SEND_TIMEOUT_MS 300

static int64_t send_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

int tc_connect_abstract(const char *name) {
    /* Non-blocking: tc_read_line()'s EAGAIN/EWOULDBLOCK branch (its "0 = need
       more data" return) and main.c's whole poll()-driven loop only make sense
       on a non-blocking fd. A blocking fd here makes the "drain what's already
       buffered" pattern (`while (tc_read_line(...) == 1)`) block inside read()
       the moment the buffer runs dry — in the handshake loop that traps the
       process reading one Ping line at a time forever, past its 5s deadline,
       and in the main loop it would stall device/deadline/watchdog handling on
       every socket wakeup. See task-8-report.md for how this was found. */
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK, 0);
    if (fd < 0) return -1;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;

    /* Abstract namespace: a leading NUL, then the name, and NO trailing NUL.
       The address length carries the name length instead. */
    size_t n = strlen(name);
    if (n + 1 > sizeof addr.sun_path) {
        close(fd);
        return -1;
    }
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, name, n);

    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);
    if (connect(fd, (struct sockaddr *)&addr, len) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* Set once a send has left a PARTIAL line in the socket buffer. After that the
   stream is corrupt: the stranded bytes have no trailing '\n', so the next line
   would be concatenated onto them and arrive at the reader as one garbled
   message — and the line most likely to be stranded is the final "X <reason>"
   written just before exit. Rather than silently corrupt the protocol, refuse
   every later send; the caller already treats -1 as "the channel is gone". */
static int g_poisoned = 0;

int tc_send_line(int fd, const char *line) {
    if (g_poisoned) return -1;

    size_t n = strlen(line);
    char out[512];
    if (n + 2 > sizeof out) return -1;
    memcpy(out, line, n);
    out[n] = '\n';

    size_t total = n + 1;
    size_t sent = 0;
    /* The socket is created SOCK_NONBLOCK (see tc_connect_abstract), so a full
       send buffer shows up here as EAGAIN/EWOULDBLOCK rather than write()
       blocking until space frees up. That is not failure, just "not right
       now" -- and the line most likely to land here is the final "X <reason>"
       written right before the process exits, which is the one line we can
       least afford to drop silently. So on EAGAIN, wait for POLLOUT and
       retry, bounded by SEND_TIMEOUT_MS total so a dead/stuck peer can never
       hang the helper. */
    int64_t deadline = -1;
    while (sent < total) {
        ssize_t w = write(fd, out + sent, total - sent);
        if (w < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                if (deadline < 0) deadline = send_now_ms() + SEND_TIMEOUT_MS;
                int64_t left = deadline - send_now_ms();
                if (left <= 0) {
                    if (sent > 0) g_poisoned = 1;
                    return -1;
                }
                struct pollfd p = {.fd = fd, .events = POLLOUT};
                int pr = poll(&p, 1, (int)left);
                if (pr < 0) {
                    if (errno == EINTR) continue;
                    if (sent > 0) g_poisoned = 1;
                    return -1;
                }
                if (pr == 0) {
                    /* Waited out the bound and still blocked. If part of this
                       line already went out, the stream can no longer be framed. */
                    if (sent > 0) g_poisoned = 1;
                    return -1;
                }
                continue;
            }
            if (sent > 0) g_poisoned = 1;
            return -1;
        }
        sent += (size_t)w;
    }
    return 0;
}

void tc_lines_init(tc_lines *r, int fd) {
    r->fd = fd;
    r->len = 0;
}

int tc_read_line(tc_lines *r, char *out, size_t out_sz) {
    for (;;) {
        /* A complete line already buffered? */
        for (size_t i = 0; i < r->len; i++) {
            if (r->buf[i] != '\n') continue;
            size_t copy = i < out_sz - 1 ? i : out_sz - 1;
            memcpy(out, r->buf, copy);
            out[copy] = '\0';
            memmove(r->buf, r->buf + i + 1, r->len - i - 1);
            r->len -= i + 1;
            return 1;
        }
        if (r->len == sizeof r->buf) {
            /* An overlong line: drop it rather than grow without bound. */
            r->len = 0;
            return 0;
        }
        ssize_t got = read(r->fd, r->buf + r->len, sizeof r->buf - r->len);
        if (got == 0) return -1;
        if (got < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
            return -1;
        }
        r->len += (size_t)got;
    }
}
