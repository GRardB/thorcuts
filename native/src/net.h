#ifndef THORCUTS_NET_H
#define THORCUTS_NET_H

#include <stddef.h>

/* Connect to an abstract-namespace unix socket (Android's LocalServerSocket).
   Returns an fd, or -1. */
int tc_connect_abstract(const char *name);

/* Send one line; a '\n' is appended. Returns 0 on success, -1 on failure. */
int tc_send_line(int fd, const char *line);

/* Buffered line reader over a stream fd. */
typedef struct {
    int    fd;
    char   buf[1024];
    size_t len;
} tc_lines;

void tc_lines_init(tc_lines *r, int fd);

/* Pull one complete line into out (NUL-terminated, newline stripped).
   Returns 1 if a line was produced, 0 if more data is needed, -1 on EOF/error. */
int tc_read_line(tc_lines *r, char *out, size_t out_sz);

#endif /* THORCUTS_NET_H */
