/*
 * thorcutsd — the Thorcuts input helper. Runs as uid 0, launched by the app
 * through AYN's pservice daemon.
 *
 * This build does NOT grab and does NOT emit: devices are opened read-only, so
 * nothing here can affect the user's controller. Grabbing arrives in a later
 * task, once the watchdog and panic path below are proven.
 *
 * The invariant every exit path serves: never leave a device grabbed without a
 * live matcher.
 */
#include "evdev.h"
#include "matcher.h"
#include "net.h"

#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define PING_TIMEOUT_MS 3000
#define MAX_BATCH 64

static tc_device g_pad;
static tc_device g_gpio;
static int       g_sock = -1;
static volatile sig_atomic_t g_signalled = 0;

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* Release everything. Safe to call twice; runs from atexit and from signals. */
static void release_all(void) {
    tc_close(&g_pad);
    tc_close(&g_gpio);
}

static void on_signal(int sig) {
    (void)sig;
    g_signalled = 1;
}

static void say(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void say(const char *fmt, ...) {
    if (g_sock < 0) return;
    char line[480];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof line, fmt, ap);
    va_end(ap);
    tc_send_line(g_sock, line);
}

static void leave(const char *reason) {
    say("X %s", reason);
    release_all();
    if (g_sock >= 0) close(g_sock);
    _exit(0);
}

/* Execute one matcher decision. Without a grab, FORWARD is a no-op: the kernel
   already delivered the event to Android, because we never took it away. */
static void apply(tc_decision d, bool *out_panic) {
    switch (d.kind) {
        case TC_FORWARD:
        case TC_SWALLOW:
        case TC_SYNTH:
            break;
        case TC_FIRE:
            say("F %u", (unsigned)d.rule_id);
            break;
        case TC_PANIC:
            *out_panic = true;
            break;
    }
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: thorcutsd <abstract-socket-name>\n");
        return 2;
    }

    tc_device_init(&g_pad);
    tc_device_init(&g_gpio);
    atexit(release_all);
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    signal(SIGHUP, on_signal);
    signal(SIGPIPE, SIG_IGN);

    g_sock = tc_connect_abstract(argv[1]);
    if (g_sock < 0) {
        fprintf(stderr, "cannot connect to %s\n", argv[1]);
        return 3;
    }

    tc_lines reader;
    tc_lines_init(&reader, g_sock);

    /* Handshake: the app sends "H" once it is ready for us. A helper launched
       while another is still connected never receives it and simply leaves. */
    {
        struct pollfd p = {.fd = g_sock, .events = POLLIN};
        char line[512];
        bool shaken = false;
        int64_t deadline = now_ms() + 5000;
        while (!shaken && now_ms() < deadline) {
            if (poll(&p, 1, 500) <= 0) continue;
            int r;
            while ((r = tc_read_line(&reader, line, sizeof line)) == 1) {
                if (line[0] == 'H') shaken = true;
            }
            if (r < 0) return 4;
        }
        if (!shaken) return 5;
    }

    int opened = 0;
    if (tc_open_pad(&g_pad) == 0) {
        opened++;
        say("L pad %s at %s", g_pad.name, g_pad.path);
    } else {
        say("L pad NOT FOUND");
    }
    if (tc_open_named("gpio-keys", &g_gpio) == 0) {
        opened++;
        say("L gpio %s at %s", g_gpio.name, g_gpio.path);
    } else {
        say("L gpio-keys NOT FOUND");
    }
    if (opened == 0) leave("no input devices");

    say("R %d", opened);

    tc_state state;
    tc_init(&state);

    int64_t last_ping = now_ms();
    int64_t deadline = -1;
    bool capture = true;  /* M1 streams events so the diagnostics screen shows them */

    for (;;) {
        if (g_signalled) leave("signal");

        /* Wake for whichever comes first: a matcher deadline, or the watchdog. */
        int64_t now = now_ms();
        int64_t wake = last_ping + PING_TIMEOUT_MS;
        if (deadline >= 0 && deadline < wake) wake = deadline;
        int timeout = (int)(wake - now);
        if (timeout < 0) timeout = 0;
        if (timeout > 1000) timeout = 1000;

        struct pollfd fds[3];
        int nfds = 0;
        int sock_i = nfds;
        fds[nfds].fd = g_sock;
        fds[nfds].events = POLLIN;
        nfds++;
        int pad_i = -1, gpio_i = -1;
        if (g_pad.fd >= 0) {
            pad_i = nfds;
            fds[nfds].fd = g_pad.fd;
            fds[nfds].events = POLLIN;
            nfds++;
        }
        if (g_gpio.fd >= 0) {
            gpio_i = nfds;
            fds[nfds].fd = g_gpio.fd;
            fds[nfds].events = POLLIN;
            nfds++;
        }

        if (poll(fds, (nfds_t)nfds, timeout) < 0 && g_signalled) leave("signal");

        /* --- the app's side of the channel --- */
        if (fds[sock_i].revents & (POLLHUP | POLLERR)) leave("socket closed");
        if (fds[sock_i].revents & POLLIN) {
            char line[512];
            int r;
            while ((r = tc_read_line(&reader, line, sizeof line)) == 1) {
                if (line[0] == 'P') {
                    last_ping = now_ms();
                } else if (line[0] == 'S') {
                    leave("stop requested");
                } else if (line[0] == 'M') {
                    capture = strstr(line, "capture") != NULL;
                    say("L mode %s", capture ? "capture" : "match");
                }
            }
            /* The socket IS the lifeline. Losing it means the matcher is gone,
               so the devices must not stay grabbed. */
            if (r < 0) leave("socket eof");
        }

        /* --- devices --- */
        struct input_event evs[MAX_BATCH];
        bool panic = false;
        for (int pass = 0; pass < 2; pass++) {
            int idx = pass == 0 ? pad_i : gpio_i;
            if (idx < 0 || !(fds[idx].revents & POLLIN)) continue;
            tc_device *dev = pass == 0 ? &g_pad : &g_gpio;
            uint8_t slot = pass == 0 ? TC_DEV_PAD : TC_DEV_GPIO;

            int n = tc_read_events(dev, evs, MAX_BATCH);
            if (n < 0) {
                say("L read error on %s", dev->path);
                continue;
            }
            for (int i = 0; i < n; i++) {
                if (evs[i].type == EV_SYN) continue;

                tc_event ev;
                ev.dev = slot;
                ev.type = (uint16_t)evs[i].type;
                ev.code = (uint16_t)evs[i].code;
                ev.value = evs[i].value;

                if (capture) {
                    say("E %u %u %u %d", (unsigned)ev.dev, (unsigned)ev.type,
                        (unsigned)ev.code, ev.value);
                }

                tc_out out = tc_step(&state, ev, now_ms());
                deadline = out.deadline_ms;
                for (uint8_t k = 0; k < out.n; k++) apply(out.items[k], &panic);
            }
        }
        if (panic) leave("panic combo");

        /* --- deadlines --- */
        if (deadline >= 0 && now_ms() >= deadline) {
            tc_event tick;
            tick.dev = 0;
            tick.type = TC_EV_TICK;
            tick.code = 0;
            tick.value = 0;
            tc_out out = tc_step(&state, tick, now_ms());
            deadline = out.deadline_ms;
            for (uint8_t k = 0; k < out.n; k++) apply(out.items[k], &panic);
            if (panic) leave("panic combo");
        }

        /* --- the watchdog: fail OPEN, always --- */
        if (now_ms() - last_ping > PING_TIMEOUT_MS) leave("watchdog: no ping");
    }
}
