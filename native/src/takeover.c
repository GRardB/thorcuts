#include "takeover.h"

#include <dirent.h>
#include <errno.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/*
 * The ONE setting that matters. Measured 2026-10-02 by writing each candidate
 * alone: persist.sys.gamepad.type and temp_abxy_layout_mode change when AYN's
 * "Controller style" tile is used but have NO effect on whether the pad exists.
 * This one does. Its name says so.
 */
#define TC_TRIGGER_KEY "no_create_gamepad_button_layout"

/*
 * How long we are willing to wait for AYN's daemon to act on a trigger write.
 *
 * This is NOT a race bound: while the pad is deregistered nothing competes with
 * us for number 1. It only stops the helper hanging forever if AYN never
 * responds, and a timeout here degrades safely — the clone is destroyed, the pad
 * is restored, and the user keeps a working controller without a takeover.
 *
 * The recon's own waits (scratch/m2-recon/run-single-key.sh) were 3 s after the
 * deregister write and 5 s after the re-register write, both chosen
 * conservatively rather than measured as minima. 3 s is therefore comfortable
 * for the disappearance and at the edge for the reappearance.
 */
#define TC_WAIT_MS 3000
#define TC_POLL_MS 10

/*
 * The gap between the deregister write and the re-register write of a restore
 * cycle. 3000 ms, because that is the ONLY measured value anyone has for this
 * cycle: scratch/m2-recon/run-single-key.sh STEP 5 sleeps 3 s between the two
 * writes and the pad comes back at 1.
 *
 * It was 300 ms, justified by the "3–19 ms" figure. THAT NUMBER DOES NOT APPLY
 * HERE: it is the borrowed figure for a node's disappear→reappear during one
 * rebuild, not the latency from a `settings put` to AYN's daemon acting on it.
 * If the 0 write arrives before the daemon has destroyed the pad, it can
 * coalesce the pair and never rebuild — and then the restore silently does
 * nothing, which is the one outcome this function exists to prevent.
 *
 * ControllerNumbers.kt uses 1 s for the same cycle and also works; 3 s is the
 * figure the sequence was actually proven with, and this is the copy that runs
 * on the teardown path, so it takes the proven one.
 */
#define TC_RESTORE_GAP_MS 3000

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* Sleep [ms], RESUMING after a signal.
   nanosleep returns early with EINTR on any signal, and main.c installs
   SIGTERM/SIGINT/SIGHUP handlers; an unretried nap would collapse the restore
   gap to near zero at exactly the moment the gap has to be honoured — a signal
   during teardown is the normal case, not the exotic one. */
static void nap(int ms) {
    struct timespec ts = { .tv_sec = ms / 1000, .tv_nsec = (long)(ms % 1000) * 1000000L };
    struct timespec rem = {0, 0};
    while (nanosleep(&ts, &rem) != 0) {
        if (errno != EINTR) break;
        ts = rem;                      /* resume with what is left, not from the top */
    }
}

/* Runs as uid 0 (pservice launched us), so the settings write is permitted.
   Returns 0 on success. */
static int write_trigger(const char *value) {
    char cmd[128];
    snprintf(cmd, sizeof cmd, "settings put system " TC_TRIGGER_KEY " %s", value);
    int rc = system(cmd);
    if (rc == -1) return -1;
    /* WIFEXITED first: WEXITSTATUS is only meaningful for a child that exited
       normally. A shell killed by a signal encodes the signal in the low byte and
       leaves the high byte zero, so testing WEXITSTATUS alone reported a
       signal-killed `settings` as SUCCESS — on the restore path that would mean
       believing the user's pad was back when it was not. */
    return (WIFEXITED(rc) && WEXITSTATUS(rc) == 0) ? 0 : -1;
}

/*
 * Write the 0 that brings AYN's pad back, retrying once.
 *
 * Every caller reaches this with the pad already deregistered, so a failure here
 * is the one failure in this file that leaves the user with no controller at all.
 * One cheap retry is worth more than the tidiness of a single call.
 *
 * Returns 0 if the write landed, non-zero if the pad may still be deregistered.
 */
static int reregister(void) {
    if (write_trigger("0") == 0) return 0;
    nap(TC_POLL_MS);
    return write_trigger("0");
}

/* True once [path] no longer exists. The node is unlinked when AYN destroys the
   device, which is also what makes Android's EventHub drop it. */
static bool gone(const char *path) {
    return access(path, F_OK) != 0;
}

/*
 * Where our own clone's event node is.
 *
 * Needed because the clone copies the pad's capability signature EXACTLY, so the
 * capability match that finds a pad cannot tell our clone from AYN's — only the
 * node path can. UI_GET_SYSNAME answers with a sysfs name like "input78", not an
 * event node, so the event* child has to be read out of sysfs.
 *
 * Writes "/dev/input/eventN" into [out]. Returns 0 on success.
 */
static int clone_node_path(const tc_uinput *u, char *out, size_t out_sz) {
    char sysname[64] = {0};
    if (ioctl(u->fd, UI_GET_SYSNAME(sizeof sysname - 1), sysname) < 0) return -1;

    char dir[128];
    snprintf(dir, sizeof dir, "/sys/class/input/%s", sysname);
    DIR *d = opendir(dir);
    if (!d) return -1;

    int found = -1;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        snprintf(out, out_sz, "/dev/input/%s", e->d_name);
        found = 0;
        break;
    }
    closedir(d);
    return found;
}

int tc_pad_restore(void) {
    /* Deregister then re-register. The pad is recreated and takes the lowest free
       number, which is 1 once our clone is gone. */
    if (write_trigger("1") != 0) {
        /* Nothing landed, so nothing changed. The pad is however it was. */
        return -1;
    }
    nap(TC_RESTORE_GAP_MS);
    /* 1, not -1: the two failures are not interchangeable. Above, the pad is
       untouched; here, it is deregistered and did not come back. */
    return reregister() != 0 ? 1 : 0;
}

const char *tc_takeover_describe(tc_takeover_result r) {
    switch (r) {
        case TC_TAKEOVER_OK:                       return "ok";
        case TC_TAKEOVER_ARM_FAILED:               return "could not arm the clone";
        case TC_TAKEOVER_DEREGISTER_FAILED:        return "could not write the trigger setting (nothing changed)";
        case TC_TAKEOVER_SOURCE_NEVER_WENT:        return "AYN's pad did not go away";
        case TC_TAKEOVER_CREATE_FAILED:            return "could not create the clone";
        case TC_TAKEOVER_REPLACEMENT_NEVER_CAME:   return "AYN's pad did not come back";
        case TC_TAKEOVER_SELF_UNRESOLVED:          return "could not find our own clone's node, so the pad scan was unsafe";
        case TC_TAKEOVER_RESTORE_FAILED:           return "RESTORE FAILED: AYN's pad may still be deregistered and the user may have NO controller — use AYN's Controller style tile, or reboot";
    }
    return "unknown";
}

tc_takeover_result tc_takeover(tc_device *src, tc_uinput *clone) {
    char source_path[64];
    snprintf(source_path, sizeof source_path, "%s", src->path);

    /* 1. Arm while the source still exists. The clone carries the source's name
          VERBATIM, because an emulator's saved mapping contains it. */
    if (tc_uinput_arm(src, src->name, clone) != 0) {
        return TC_TAKEOVER_ARM_FAILED;
    }

    /* 2. Deregister AYN's pad. From here on, EVERY failure path restores before
          returning — a pad left deregistered is a handheld with no controller —
          and a restore that itself fails outranks whatever brought us there,
          because it is the only outcome the user has to act on. */
    if (write_trigger("1") != 0) {
        tc_uinput_destroy(clone);
        return TC_TAKEOVER_DEREGISTER_FAILED;
    }

    /* 3. Wait for it to actually go. Our own fd on it is now stale either way. */
    int64_t deadline = now_ms() + TC_WAIT_MS;
    while (!gone(source_path) && now_ms() < deadline) nap(TC_POLL_MS);
    tc_close(src);
    if (!gone(source_path)) {
        tc_uinput_destroy(clone);
        /* The clone never went live, so a single re-register brings the pad back
           at 1 and no full cycle is needed. But the write can fail, and its
           failure is the dangerous outcome, so it is retried and reported rather
           than discarded. */
        if (reregister() != 0) return TC_TAKEOVER_RESTORE_FAILED;
        return TC_TAKEOVER_SOURCE_NEVER_WENT;
    }

    /* 4. Fire. No gamepad exists right now, so number 1 is free and this is not
          a race — nothing is competing for it. */
    if (tc_uinput_fire(clone) != 0) {
        tc_uinput_destroy(clone);
        if (reregister() != 0) return TC_TAKEOVER_RESTORE_FAILED;
        return TC_TAKEOVER_CREATE_FAILED;
    }

    /* 5. Give AYN its pad back. It will take 2, because we hold 1. */
    if (reregister() != 0) {
        /* The clone exists and holds 1, but the user has no real pad and the one
           write that brings it back has now failed twice. Tear the clone down and
           try the full cycle, which is the only lever left. Whether or not that
           cycle works, the pad's state is no longer known, so this reports the
           serious result and lets the caller tell the user. */
        tc_uinput_destroy(clone);
        tc_pad_restore();
        return TC_TAKEOVER_RESTORE_FAILED;
    }

    /* 6. Learn our own node, so the scan in step 7 cannot match it.
          If it cannot be resolved we FAIL THE TAKEOVER. An earlier revision
          passed NULL to the scan and "checked afterwards" that what it opened was
          not the clone — which could never work: clone_node_path writes
          [clone_path] only on its success path, so the check's own condition
          (exclude == NULL && clone_path[0] != '\0') was a contradiction and never
          fired. The scan would then have run with no exclusion and could have
          returned our own clone, which the caller grabs: an echo loop with the
          real pad never opened and the panic combo unreachable. */
    char clone_path[64] = {0};
    /* gone() is the check that matters: a resolved path that does not exist means
       the exclusion below would be VACUOUS, which is the failure C1 was about.
       Unlike comparing paths after the scan (see step 7) this one can actually
       fire — devtmpfs lag, or a sysfs name that no longer maps to a node. */
    if (clone_node_path(clone, clone_path, sizeof clone_path) != 0 || gone(clone_path)) {
        tc_uinput_destroy(clone);
        /* The pad came back at 2 in step 5, so the full cycle is needed to put it
           back at 1 — a single write would do nothing. */
        if (tc_pad_restore() > 0) return TC_TAKEOVER_RESTORE_FAILED;
        return TC_TAKEOVER_SELF_UNRESOLVED;
    }

    /* 7. Wait for the replacement and open it, excluding our own clone. */
    deadline = now_ms() + TC_WAIT_MS;
    while (now_ms() < deadline) {
        if (tc_open_pad_except(clone_path, src) == 0) {
            /* A TRIPWIRE, not protection. It cannot fire as evdev.c stands:
               scan_except skips a candidate with strcmp(path, exclude) == 0 and
               assigns d->path from that same buffer, so a path it returns is
               never the one it was told to exclude — and the mismatch this was
               meant to catch (clone_path naming the wrong node) would not be
               caught by it either, since the paths would then differ. It stays
               because it costs three lines and would fire if that exclusion ever
               changed shape. The real guard is the gone() check in step 6. */
            if (strcmp(src->path, clone_path) == 0) {
                tc_close(src);          /* we found ourselves; keep waiting */
            } else {
                return TC_TAKEOVER_OK;
            }
        }
        nap(TC_POLL_MS);
    }

    tc_uinput_destroy(clone);
    if (tc_pad_restore() > 0) return TC_TAKEOVER_RESTORE_FAILED;
    return TC_TAKEOVER_REPLACEMENT_NEVER_CAME;
}
