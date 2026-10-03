#ifndef THORCUTS_TAKEOVER_H
#define THORCUTS_TAKEOVER_H

#include "evdev.h"
#include "uinput.h"

/*
 * Taking controller number 1 away from AYN's pad.
 *
 * Android assigns a gamepad its controller number when it appears, lowest free
 * number first, and frees the number when the pad goes. Emulators key saved
 * mappings on that number — Dolphin stores "Android/1/Odin Controller" verbatim
 * (measured) — so a twin created alongside AYN's pad gets 2 and misses every
 * mapping the user has.
 *
 * MEASURED, and it is why none of this is a race: writing 1 to the setting below
 * makes AYN deregister its pad ENTIRELY. Android then reports no gamepad at all,
 * and number 1 stays free for as long as the setting says so. The clone is
 * created at leisure, takes 1 together with the real pad's own descriptor, and
 * AYN's pad returns at 2 when the setting is put back.
 *
 * Everything here runs as uid 0 and deliberately puts the handheld into a state
 * where the user has NO gamepad, then takes it out again. That is why the
 * failure reporting below is finer-grained than the amount of code suggests: a
 * caller that cannot tell "nothing happened" from "the pad may still be
 * deregistered" cannot tell the user what to do, and the second state is only
 * escapable through AYN's own Quick Settings tile or a reboot.
 */

typedef enum {
    TC_TAKEOVER_OK = 0,

    TC_TAKEOVER_ARM_FAILED,

    /* The FIRST trigger write failed. Benign: nothing was changed on the device,
       the pad is exactly as it was, and [src] is still open on it. Not to be
       confused with TC_TAKEOVER_RESTORE_FAILED, which is its opposite. */
    TC_TAKEOVER_DEREGISTER_FAILED,

    TC_TAKEOVER_SOURCE_NEVER_WENT,
    TC_TAKEOVER_CREATE_FAILED,
    TC_TAKEOVER_REPLACEMENT_NEVER_CAME,

    /* The two below are APPENDED rather than inserted in sequence order, so the
       values above keep the numbers they already had. */

    /*
     * Our own clone's event node could not be resolved, so the scan for AYN's
     * replacement would have to run with NO exclusion. It is not run.
     *
     * The clone copies the pad's capability signature EXACTLY, so an unexcluded
     * capability scan can match our own clone — which certainly exists at that
     * moment, while AYN's replacement may not yet. The caller would then grab
     * its own clone: an echo loop, nothing from controller 1 reaching Android,
     * and the panic combo unreachable because the real pad was never opened.
     * Failing the takeover is strictly better than any of that.
     */
    TC_TAKEOVER_SELF_UNRESOLVED,

    /*
     * THE SERIOUS ONE. The pad was deregistered and the write that brings it
     * back failed, retry included. A last-resort full cycle may also have been
     * attempted, so the pad's state is UNKNOWN and possibly absent: the user may
     * have no controller at all.
     *
     * The caller must say so loudly rather than degrading quietly, and must name
     * AYN's "Controller style" tile or a reboot as the way out.
     */
    TC_TAKEOVER_RESTORE_FAILED,
} tc_takeover_result;

/*
 * Take controller number 1.
 *
 * [src] must be an OPEN pad on entry — its capabilities and identity are read
 * while it still exists, which is the whole reason this cannot be driven from
 * the app side.
 *
 * On TC_TAKEOVER_OK: [clone] is live and holds number 1, and [src] has been
 * RE-OPENED on AYN's replacement pad (a different node; the original fd is
 * closed). The caller grabs [src] afterwards — this function never grabs.
 *
 * On every other result [clone] has been destroyed and AYN's pad has been
 * restored — except on TC_TAKEOVER_RESTORE_FAILED, where the restore is the very
 * thing that failed. The caller should carry on without a takeover rather than
 * refusing to start.
 *
 * WHAT HAPPENS TO [src] ON FAILURE, stated per result because an earlier
 * revision of this comment claimed [src] was "left open on whatever pad
 * currently exists, or closed if none does", which was wrong on every path but
 * the first two:
 *
 *   TC_TAKEOVER_ARM_FAILED, TC_TAKEOVER_DEREGISTER_FAILED
 *       Nothing was changed on the device. [src] is still open on the original
 *       pad and still usable.
 *
 *   every other failure
 *       [src] is CLOSED, and is never reopened. tc_close(src) runs
 *       unconditionally once the deregister write has landed, because the fd is
 *       stale from that moment on. The caller must reopen the pad itself; this
 *       function deliberately does not, because the caller is better placed to
 *       decide whether to wait, retry, or carry on without a pad.
 *
 * The caller does not have to branch on the result to tell those apart:
 * tc_close sets the fd to -1, so [src] needs reopening exactly when
 * src->fd < 0. Do NOT read src->path or src->name on those paths — tc_close
 * leaves both holding the stale, already-unlinked node.
 */
tc_takeover_result tc_takeover(tc_device *src, tc_uinput *clone);

/*
 * Cycle the trigger so AYN's pad is destroyed and recreated, reclaiming the
 * lowest free number. Call AFTER our clone is destroyed, or AYN's pad will come
 * back at 2 again.
 *
 * NOT "safe to call at any time", as an earlier revision of this comment said.
 * It is DESTRUCTIVE THEN RESTORATIVE: the first write deregisters the pad, and
 * from then until the second write lands the user has no controller at all. It
 * is safe only if the second write lands — which is why it reports whether it
 * did, and why the result must be checked.
 *
 *   0   the full cycle completed; the pad should exist again, at number 1.
 *   -1  the FIRST write never landed. Nothing was changed: the pad is however it
 *       was, which for a self-heal call means still stranded, and for a teardown
 *       call means still at 2. Bad, but not dangerous.
 *   1   the pad was deregistered and could NOT be brought back, retry included.
 *       THE USER MAY HAVE NO CONTROLLER AT ALL. Say so loudly.
 *
 * Blocking: the measured 3 s gap between the two writes, plus two `settings put`
 * invocations. It was described as "roughly a second" when the gap was 300 ms.
 */
int tc_pad_restore(void);

const char *tc_takeover_describe(tc_takeover_result r);

/*
 * Where a uinput device of ours sits in /dev/input.
 *
 * UI_GET_SYSNAME answers with a sysfs name like "input78", not an event node, so
 * the event* child has to be read out of /sys/class/input/. Writes
 * "/dev/input/eventN" into [out]; returns 0 on success.
 *
 * Exported rather than kept private because main.c needs it too: once the twin
 * carries its source's NAME as well as its ids, the node path is the only thing
 * that tells the twin and AYN's pad apart in a bug report.
 */
int tc_clone_node_path(const tc_uinput *u, char *out, size_t out_sz);

#endif /* THORCUTS_TAKEOVER_H */
