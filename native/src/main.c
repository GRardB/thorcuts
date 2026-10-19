/*
 * thorcutsd — the Thorcuts input helper. Runs as uid 0, launched by the app
 * through AYN's pservice daemon.
 *
 * It takes the gamepad and gpio-keys EXCLUSIVELY (EVIOCGRAB) and re-emits
 * through virtual uinput twins, so Android sees only what the matcher allows
 * through. From the grab onward, a missing FORWARD is a button that no longer
 * works, and a crash while holding the grab is a handheld with no controller.
 *
 * Ordering is load-bearing: the twins are created BEFORE the grab, because
 * grabbing with nowhere to re-emit leaves the controller dead with no route
 * back. Teardown runs in reverse — release the real devices, then destroy the
 * twins — so the controller is alive again even if tearing down a twin stalls.
 *
 * The invariant every exit path serves: never leave a device grabbed without a
 * live matcher. Three things enforce it — the panic combo (checked in the
 * matcher before any rule), the watchdog on the app socket, and an explicit
 * stop.
 */
#include "evdev.h"
#include "matcher.h"
#include "net.h"
#include "ruletable.h"
#include "takeover.h"
#include "uinput.h"

#include <dirent.h>
#include <errno.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define PING_TIMEOUT_MS 3000
#define MAX_BATCH 64

static tc_device g_pad;
static tc_device g_gpio;
static tc_uinput g_vpad;
static tc_uinput g_vgpio;
static int       g_sock = -1;
static volatile sig_atomic_t g_signalled = 0;

/* The rule table the app last pushed, as parsed. The MATCHER keeps its own copies —
   the one it matches from, plus a pending one if a push arrived while a session was
   open — so this is the record of what was accepted, not what is live. handle_table
   hands it to tc_matcher_set_table right after accepting it, and the "T ok" line says
   which of the two happened. Replaced wholesale, inside the socket branch of the main
   loop; the matcher's own swap rule (install at the session's close) is what makes a
   mid-chord push safe. */
static tc_table g_table;
static tc_state g_matcher;

/* Set once the numbering has been DISTURBED — which is NOT the same as "the
   takeover succeeded", and conflating the two was a defect. This flag was
   `g_took_over` and was set for TC_TAKEOVER_OK alone, so the three OTHER results
   that can hand the caller a pad already moved off number 1 never got their
   restore and could leave AYN's pad at 2 for the rest of the boot.

   What it means now: the trigger write landed, so the numbering is ours and we
   owe the user a restore on the way out. See the per-result switch in main() for
   which results set it and why. */
static bool g_numbering_disturbed = false;

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* say() is defined below, after the socket is set up. release_all needs it to
   report a failed restore, which is the one teardown outcome the user must be
   told about. */
static void say(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/* Release everything. Safe to call twice; runs from leave() on every exit path.
   Order matters and is not negotiable:
     1. ungrab the real devices, so the controller is alive again immediately;
     2. destroy the twins, freeing controller number 1;
     3. restore AYN's pad to number 1, if we disturbed the numbering.
   Ungrabbing is the time-critical act — the panic combo exists to make the
   controller work again NOW — and must not wait behind the fork/exec in step 3.
   Step 3 must follow step 2, or AYN's pad comes back at 2 all over again.

   BUT TEARDOWN AS A WHOLE IS NOT INSTANT, and the panic path must not be read as
   if it were. Step 3 is tc_pad_restore(), which DEREGISTERS the pad and waits the
   measured 3 s gap before writing it back (see takeover.h). So after a panic the
   user's controller works again at once, then disappears for ~3 s, then returns
   at number 1. That is inherent to the only mechanism that moves the numbering,
   not a bug — and the ungrab still goes first because it is free — but it is a
   surprise in the hand, so it is written down here rather than discovered.

   A pad left at 2 is WORSE than never having run: 2 is exactly what breaks the
   emulators this whole feature exists to fix, and the user has no way to connect
   the two facts. */
static void release_all(void) {
    tc_close(&g_pad);
    tc_close(&g_gpio);
    tc_uinput_destroy(&g_vpad);
    tc_uinput_destroy(&g_vgpio);
    if (g_numbering_disturbed) {
        g_numbering_disturbed = false;
        /* The result is NOT discarded. A return of 1 means the pad was
           deregistered and did not come back, which is the one teardown outcome
           the user has to act on — and the only way out of it is AYN's own
           "Controller style" tile or a reboot. say() is a no-op once the socket
           is gone, so this costs nothing on the paths where nobody is listening. */
        if (tc_pad_restore() > 0) {
            say("L RESTORE FAILED — AYN's pad may still be deregistered. Use "
                "AYN's Controller style tile, or reboot.");
        }
    }
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

/* PRECONDITION: line[0] == 'T' (the caller dispatched on it); parsing starts at line + 1.
   "T <gen> <hex>": decode, validate, hold, acknowledge.
   The line was read by tc_read_line, so it has no newline and fits TC_LINE_MAX.
   Every outcome is answered on the socket: "T ok <gen> <count>" or "T bad <gen> <why>".
   A rejected table changes nothing — g_table stays what it was. */
static void handle_table(const char *line) {
    /* Exactly 'T', one space, decimal digits, one space, hex to the end. */
    const char *p = line + 1;
    if (*p != ' ' || !(p[1] >= '0' && p[1] <= '9')) {
        say("T bad 0 line");
        return;
    }
    p++;
    char *end = NULL;
    errno = 0;
    unsigned long gen = strtoul(p, &end, 10);
    if (errno != 0 || end == p || *end != ' ' || gen > 0xFFFFFFFFul) {
        say("T bad 0 line");
        return;
    }
    const char *hex = end + 1;

    uint8_t bytes[TC_TABLE_MAX_BYTES];
    size_t n = 0;
    if (tc_hex_decode(hex, bytes, sizeof bytes, &n) != 0) {
        say("T bad %lu hex", gen);
        return;
    }

    tc_table parsed;
    int bad_rule = -1;
    tc_table_result r = tc_table_parse(bytes, n, (uint32_t)gen, &parsed, &bad_rule);
    if (r != TC_TABLE_OK) {
        if (bad_rule >= 0) {
            say("T bad %lu %s rule %d", gen, tc_table_reason(r), bad_rule);
        } else {
            say("T bad %lu %s", gen, tc_table_reason(r));
        }
        return;
    }
    g_table = parsed;
    tc_set_result how = tc_matcher_set_table(&g_matcher, &g_table);
    say("T ok %lu %u %s", gen, (unsigned)parsed.count,
        how == TC_SET_INSTALLED ? "installed" : "deferred");
}

static void leave(const char *reason) {
    say("X %s", reason);
    release_all();
    if (g_sock >= 0) close(g_sock);
    _exit(0);
}

/* Report a condition once per run. A failing emit would otherwise either spam
   the channel at input rate or, worse, say nothing at all — and "a button went
   dead with no log" is the worst position to debug a grabbed controller from. */
static void warn_once(bool *flag, const char *what) {
    if (*flag) return;
    *flag = true;
    say("L WARNING %s — a control may be dead; stop the helper if input misbehaves", what);
}

/* What each twin currently holds, by the events apply() emitted: a key's down/up and an
   axis's last value. Consulted by the capture gate for the one release a twin is owed. */
static bool    g_twin_key_down[2][KEY_MAX + 1];
static int32_t g_twin_axis[2][ABS_MAX + 1];

static void note_emitted(tc_event ev) {
    if (ev.dev >= 2) return;
    if (ev.type == TC_EV_KEY && ev.code <= KEY_MAX) {
        g_twin_key_down[ev.dev][ev.code] = ev.value != 0;
    } else if (ev.type == TC_EV_ABS && ev.code <= ABS_MAX) {
        g_twin_axis[ev.dev][ev.code] = ev.value;
    }
}

/* A release the twin is OWED: a key or axis held from before the recording reached its twin
   as a down (the twin's state says so), and dropping its up would leave it held in the game
   until pressed again — a stuck button, the one state the gate must not create. A release
   presses nothing and fires nothing, so letting it out keeps the point of the gate. Only a
   forward or a synthesised up, and only for a control the twin has down: a key pressed during
   the recording never reached it, so its up is dropped like everything else. Those two kinds
   are enough to look at because matcher.c swallows an up only when it swallowed the down
   (button_up's and handle_modifier's fired branches), and the one up it synthesises — a
   tap's, TC_TAP_MS after its down — is owed by construction once that down went out: every
   up a twin is owed arrives as TC_FORWARD or TC_SYNTH. If that ever changes, a key can
   stick. */
static bool owed_release(tc_decision d) {
    if ((d.kind != TC_FORWARD && d.kind != TC_SYNTH) || d.ev.value != 0 || d.ev.dev >= 2) {
        return false;
    }
    if (d.ev.type == TC_EV_KEY) {
        return d.ev.code <= KEY_MAX && g_twin_key_down[d.ev.dev][d.ev.code];
    }
    if (d.ev.type == TC_EV_ABS) {
        return d.ev.code <= ABS_MAX && g_twin_axis[d.ev.dev][d.ev.code] != 0;
    }
    return false;
}

/* Execute one matcher decision against the virtual devices.
   Now that the real devices are grabbed, Android sees ONLY what is emitted here:
   a missing FORWARD is a button that no longer works.

   While the app records a trigger (capture mode) nothing leaves the helper but the panic and
   a release a twin is owed (owed_release): no forward, no synthesised down, no fire. The matcher
   is still fed every event and every tick, so the panic combo keeps working with the gate shut
   and the matcher's picture of what is held stays true to the keys when the mode switches
   back. Three things the gate does not undo, accepted: a key whose down never reached the twin
   and whose up arrives after the switch back is a lone up there (an app generally ignores one);
   an axis is owed its release only at exactly 0, so a stick still away from centre keeps its
   twin there until the axis reads 0 or the mode switches back; and a timed combo armed during
   capture fires if the mode switches back before its deadline while it is still held — the
   normal switch back is on the release, when nothing is held. */
static void apply(tc_decision d, bool capture, bool *out_panic, bool *out_dirty_pad,
                  bool *out_dirty_gpio) {
    if (capture && d.kind != TC_PANIC && !owed_release(d)) return;

    tc_uinput *v = d.ev.dev == TC_DEV_GPIO ? &g_vgpio : &g_vpad;
    bool *dirty = d.ev.dev == TC_DEV_GPIO ? out_dirty_gpio : out_dirty_pad;

    switch (d.kind) {
        case TC_FORWARD:
        case TC_SYNTH:
            if (tc_uinput_emit(v, d.ev.type, d.ev.code, d.ev.value) == 0) {
                *dirty = true;
                note_emitted(d.ev);
            } else {
                static bool warned_emit = false;
                warn_once(&warned_emit, "emit to a virtual device failed");
            }
            break;
        case TC_SWALLOW:
            /* Emit nothing: this is the whole point. The app under us never sees it. */
            break;
        case TC_FIRE:
            say("F %u %u", (unsigned)d.generation, (unsigned)d.index);
            break;
        case TC_PANIC:
            *out_panic = true;
            break;
    }
}


int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: thorcutsd <abstract-socket-name> [takeover]\n");
        return 2;
    }
    const bool want_takeover = (argc > 2 && strcmp(argv[2], "takeover") == 0);

    tc_device_init(&g_pad);
    tc_device_init(&g_gpio);
    tc_uinput_init(&g_vpad);
    tc_uinput_init(&g_vgpio);
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
        char line[TC_LINE_MAX];
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

    /* A mistyped argument — "Takeover", "takover" — used to produce a run with no
       takeover and no message, indistinguishable from a deliberate plain run from
       either side of the socket. Say it once. It cannot be said any earlier: say()
       needs the socket, which is only connected above. */
    if (argc > 2 && !want_takeover) {
        say("L ignoring unrecognised argument \"%s\" — the only one understood is "
            "\"takeover\"; running without one", argv[2]);
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

    /* The takeover, if the app asked for one. It must happen BEFORE anything is
       grabbed: the panic combo is Home + Select and both live on the GAMEPAD, so
       stalling here while holding a grab would leave the user with no controller
       and no way out. On failure we carry on WITHOUT a takeover rather than
       refusing to start — a working controller with one emulator limitation beats
       no Thorcuts at all. */
    if (want_takeover && g_pad.fd >= 0) {
        tc_takeover_result r = tc_takeover(&g_pad, &g_vpad);

        /* One arm per result, because the question teardown has to answer is not
           "did the takeover work" but "did the deregister write land" — i.e. is
           the numbering ours to repair. A switch over the whole enum rather than
           an if-chain, so that -Wswitch fails the build if a result is ever added
           and nobody decides which side of that line it falls on.

           NOT gated on g_pad.fd < 0, tempting though that is: tc_takeover also
           closes the pad on SOURCE_NEVER_WENT and CREATE_FAILED, which are back at
           1 already, so that gate would buy nothing but a pointless 3 s restore
           cycle on every teardown after those two. */
        switch (r) {
            /* By design: the clone holds 1 and AYN's pad is at 2. The whole point
               of the exercise, and the pad must be put back on the way out. */
            case TC_TAKEOVER_OK:
                g_numbering_disturbed = true;
                say("L takeover ok: the twin holds controller 1, AYN's pad is at 2");
                /* The replacement is a DIFFERENT node from the pad we opened at
                   startup, and it now shares its name and ids with our twin, so
                   name it while we know it. */
                say("L AYN's replacement pad is \"%s\" at %s", g_pad.name, g_pad.path);
                break;

            /* The pad is deregistered and the write that brings it back failed,
               tc_takeover's own retry included — and on some paths its last-resort
               full cycle as well (takeover.h). The user may have no controller at
               all. Claim the restore anyway: teardown's cycle is a free further
               attempt and the only lever left, and running the whole session and
               exiting without trying once more would throw it away. Reported on
               its own rather than as a degrade: tc_takeover's other failures all
               leave the user a controller, and a single "takeover failed" line
               would bury the one that does not. */
            case TC_TAKEOVER_RESTORE_FAILED:
                g_numbering_disturbed = true;
                say("L %s", tc_takeover_describe(r));
                break;

            /* Both are reached AFTER tc_takeover's step 5 re-registered the pad
               AT 2, and both then call tc_pad_restore() testing only > 0 — which
               swallows the -1 that takeover.h documents as "nothing was changed …
               for a teardown call means still at 2". So the pad may be sitting at
               2 right now. Worse, the fallback twin created below then takes 1,
               making the end state indistinguishable from a successful takeover
               with nothing left to undo it. Claim the restore: when
               tc_pad_restore did work, the cost is one 3 s cycle on the way out;
               the cost of not claiming it is a pad silently left at 2. */
            case TC_TAKEOVER_SELF_UNRESOLVED:
            case TC_TAKEOVER_REPLACEMENT_NEVER_CAME:
                g_numbering_disturbed = true;
                say("L takeover failed (%s) — continuing without it; AYN's pad may "
                    "have been left at 2, so the numbering is put back on teardown",
                    tc_takeover_describe(r));
                break;

            /* Nothing was written to the device at all (takeover.h: "nothing was
               changed on the device"), so there is nothing to put back and g_pad
               is still open on the original node. */
            case TC_TAKEOVER_ARM_FAILED:
            case TC_TAKEOVER_DEREGISTER_FAILED:
            /* Here the deregister DID land — but the clone never went live and the
               single re-register that follows SUCCEEDED, because anything else
               returns TC_TAKEOVER_RESTORE_FAILED. So AYN's pad is back at 1 and
               the numbering is undisturbed; only g_pad's fd is stale, and it is
               reopened below. */
            case TC_TAKEOVER_SOURCE_NEVER_WENT:
            case TC_TAKEOVER_CREATE_FAILED:
                say("L takeover failed (%s) — continuing without it; the twin will "
                    "be controller 2 and emulators keyed on 1 will not see it",
                    tc_takeover_describe(r));
                break;
        }
    }

    /* A FAILED takeover CLOSES g_pad (see takeover.h) and does not reopen it, so
       the fallback twin has nothing to clone from until the pad is reopened. The
       stale g_pad.path must not be reused: reopen by capability instead.

       Gated on want_takeover, which is what this comment always claimed and the
       code did not do: ungated, the block also ran on a plain run whose first
       tc_open_pad had simply failed, and then logged "reopened AYN's pad" on a
       path where nothing had taken anything over. */
    if (want_takeover && g_pad.fd < 0) {
        /* RETRY, do not try once. A failed takeover runs tc_pad_restore() on its
           way out, which DEREGISTERS the pad and re-registers it — so at the
           moment we arrive here the pad is reliably ABSENT for several seconds,
           and a single attempt always failed. Measured on hardware 2026-10-12:
           the helper came up "ready, 1 devices" with no pad twin and nothing
           grabbed, every time. The bound outlasts a restore (its own gap plus
           AYN's re-enumeration) with margin. */
        const int64_t reopen_deadline = now_ms() + 10000;
        while (tc_open_pad(&g_pad) != 0 && now_ms() < reopen_deadline) {
            struct timespec ts = {.tv_sec = 0, .tv_nsec = 200 * 1000000L};
            nanosleep(&ts, NULL);
        }
        if (g_pad.fd >= 0) {
            say("L reopened AYN's pad as \"%s\" at %s", g_pad.name, g_pad.path);
        } else {
            say("L AYN's pad is not there to reopen; continuing without a pad twin");
        }
    }

    /* The pad's twin carries its SOURCE'S NAME verbatim, because an emulator's
       saved mapping contains it — Dolphin stores "Android/1/Odin Controller".
       When the takeover ran, the twin already exists and is that clone. */
    if (g_pad.fd >= 0 && g_vpad.fd < 0) {
        if (tc_uinput_create(&g_pad, g_pad.name, &g_vpad) == 0) {
            say("L virtual pad created as \"%s\"", g_pad.name);
        } else {
            leave("cannot create virtual pad");
        }
    }
    /* gpio-keys keeps a legible name: nothing keys on it, it is not a gamepad and
       receives no controller number, so legibility in a bug report is worth more
       than consistency here. */
    if (g_gpio.fd >= 0) {
        if (tc_uinput_create(&g_gpio, "Thorcuts Keys", &g_vgpio) == 0) {
            say("L virtual keys created");
        } else {
            leave("cannot create virtual keys");
        }
    }

    /* Which node is ours. The pad twin carries its source's NAME as well as its
       bus, vendor and product — deliberately, because the emulator mapping keys on
       all of them — so from here on two devices are identical in every field
       `dumpsys input` prints except the node path and the controller number. A bug
       report that cannot tell them apart cannot be read, so say it once now that
       the twin exists. */
    if (g_vpad.fd >= 0) {
        char node[64];
        if (tc_clone_node_path(&g_vpad, node, sizeof node) == 0) {
            say("L pad twin \"%s\" at %s", g_vpad.name, node);
        } else {
            /* Not fatal: the twin works and only the log is poorer. takeover.c
               treats the same failure as fatal because it needs the path to avoid
               grabbing its own clone; here nothing depends on it. */
            say("L pad twin \"%s\" — node path unresolved", g_vpad.name);
        }
    }

    /* Grab LAST, once the twins are live. Grabbing before there is somewhere to
       re-emit would leave the controller dead with no route back. */
    if (g_pad.fd >= 0 && tc_grab(&g_pad) != 0) leave("cannot grab pad");
    if (g_gpio.fd >= 0 && tc_grab(&g_gpio) != 0) leave("cannot grab gpio-keys");
    say("L grabbed; Android now sees only the twins");

    /* Count what is actually OPEN at the moment of reporting rather than carrying
       the startup tally forward. `opened` goes stale in both directions once a
       takeover is in play: tc_takeover closes the pad on every failure but its
       first two, and the reopen above may or may not get it back. The app's main
       screen shows this number as its first status line ("ready, N devices"), so
       it has to be the truth. Nothing can be grabbed-but-closed here: a failed
       grab leaves through leave(). */
    say("R %d", (g_pad.fd >= 0 ? 1 : 0) + (g_gpio.fd >= 0 ? 1 : 0));

    tc_init(&g_matcher);

    int64_t last_ping = now_ms();
    int64_t deadline = -1;
    /* Match mode until the app's editor asks for capture (`M capture`): a fresh helper never
       swallows on its own, and outside a recording the socket carries no `E` lines. */
    bool capture = false;

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
            char line[TC_LINE_MAX];
            int r;
            while ((r = tc_read_line(&reader, line, sizeof line)) == 1) {
                if (line[0] == 'P') {
                    last_ping = now_ms();
                } else if (line[0] == 'S') {
                    leave("stop requested");
                } else if (line[0] == 'M') {
                    /* Silent, like `P`: the app logs what it sent, and an `L` here would
                       show over the "ready" line on its screen. */
                    capture = strstr(line, "capture") != NULL;
                } else if (line[0] == 'T') {
                    handle_table(line);
                }
            }
            /* A line that overflowed the reader was discarded without a trace. Our own
               app cannot produce one — the app's rule compiler caps a table well under
               TC_LINE_MAX — which is exactly why it must not be silent if it ever happens. */
            if (reader.dropped != 0) {
                say("L dropped %u overlong line(s) from the app", reader.dropped);
                reader.dropped = 0;
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
            bool dirty_pad = false;
            bool dirty_gpio = false;
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

                tc_out out = tc_step(&g_matcher, ev, now_ms());
                deadline = out.deadline_ms;
                for (uint8_t k = 0; k < out.n; k++) {
                    apply(out.items[k], capture, &panic, &dirty_pad, &dirty_gpio);
                }
            }
            /* One report per batch, per device that actually emitted something. */
            if (dirty_pad && tc_uinput_syn(&g_vpad) != 0) {
                static bool warned = false;
                warn_once(&warned, "SYN_REPORT to the pad twin failed");
            }
            if (dirty_gpio && tc_uinput_syn(&g_vgpio) != 0) {
                static bool warned = false;
                warn_once(&warned, "SYN_REPORT to the keys twin failed");
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
            tc_out out = tc_step(&g_matcher, tick, now_ms());
            deadline = out.deadline_ms;
            bool dirty_pad = false;
            bool dirty_gpio = false;
            for (uint8_t k = 0; k < out.n; k++) {
                apply(out.items[k], capture, &panic, &dirty_pad, &dirty_gpio);
            }
            if (dirty_pad && tc_uinput_syn(&g_vpad) != 0) {
                static bool warned = false;
                warn_once(&warned, "SYN_REPORT to the pad twin failed");
            }
            if (dirty_gpio && tc_uinput_syn(&g_vgpio) != 0) {
                static bool warned = false;
                warn_once(&warned, "SYN_REPORT to the keys twin failed");
            }
            if (panic) leave("panic combo");
        }

        /* --- the watchdog: fail OPEN, always --- */
        if (now_ms() - last_ping > PING_TIMEOUT_MS) leave("watchdog: no ping");
    }
}
