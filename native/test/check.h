#ifndef THORCUTS_TEST_CHECK_H
#define THORCUTS_TEST_CHECK_H

/* The two macros every host test binary uses. One header, included by each test .c,
   which defines its own counters: there is one test file per binary. */

#include <stdio.h>

static int g_checks = 0;
static int g_failures = 0;

#define CHECK(cond)                                                       \
    do {                                                                  \
        g_checks++;                                                       \
        if (!(cond)) {                                                    \
            g_failures++;                                                 \
            printf("    FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);    \
        }                                                                 \
    } while (0)

#define RUN(fn)                    \
    do {                           \
        printf("  %s\n", #fn);     \
        fn();                      \
    } while (0)

#endif /* THORCUTS_TEST_CHECK_H */
