#ifndef THORCUTS_NET_H
#define THORCUTS_NET_H

#include <stddef.h>

/* Connect to an abstract-namespace unix socket (Android's LocalServerSocket).
   Returns an fd, or -1. */
int tc_connect_abstract(const char *name);

/* Send one line; a '\n' is appended. Returns 0 on success, -1 on failure. */
int tc_send_line(int fd, const char *line);

/* The longest line either side writes. A rule table is at most 2060 bytes = 4120 hex
   characters on a "T <gen> <hex>" line of at most 4133 characters; this is that with
   headroom. One constant, so the reader and every line buffer in main.c agree. */
#define TC_LINE_MAX 8192

/* Buffered line reader over a stream fd. */
typedef struct {
    int      fd;
    char     buf[TC_LINE_MAX];
    size_t   len;
    /* Lines discarded for overflowing buf since the caller last reset this. The reader
       cannot report a drop through its return value (0 means "need more data"), and a
       silently discarded command is a protocol failure nobody would see. The caller
       reads it, says so, and zeroes it. */
    unsigned dropped;
} tc_lines;

void tc_lines_init(tc_lines *r, int fd);

/* Pull one complete line into out (NUL-terminated, newline stripped).
   Returns 1 if a line was produced, 0 if more data is needed, -1 on EOF/error. */
int tc_read_line(tc_lines *r, char *out, size_t out_sz);

#endif /* THORCUTS_NET_H */
