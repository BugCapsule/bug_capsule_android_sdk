/*
 * Host smoke test for the core crash handler (no JNI): installs the handler in
 * a forked child, crashes it, and validates the crash record. Run smoke.sh.
 */
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include "bugcapsule_ndk.h"

__attribute__((noinline)) static void smoke_null_write(void) {
    volatile int *volatile target = NULL;
    *target = 7;
}

static int has_line_prefix(const char *text, const char *prefix) {
    size_t n = strlen(prefix);
    const char *line = text;
    while (line && *line) {
        if (strncmp(line, prefix, n) == 0) return 1;
        line = strchr(line, '\n');
        if (line) line++;
    }
    return 0;
}

/* kind: 0 = null write in this file, 1 = bc_ndk_crash(1) (abort). */
static int run_case(const char *dir, int kind) {
    char path[1024], expected_signal[32], *text;
    FILE *f;
    long size;
    int status, failures = 0, frames = 0;
    const char *line;
    pid_t pid;

    snprintf(path, sizeof(path), "%s/case%d.crash", dir, kind);
    fflush(stdout);
    fflush(stderr);
    pid = fork();
    if (pid < 0) return 1;
    if (pid == 0) {
        if (!bc_ndk_install(path)) _exit(100);
        if (!bc_ndk_is_installed()) _exit(101);
        if (kind == 0) smoke_null_write(); else bc_ndk_crash(1);
        _exit(102);
    }
    if (waitpid(pid, &status, 0) != pid) return 1;
    if (!WIFSIGNALED(status)) {
        fprintf(stderr, "case %d: child was not killed by a signal (status %d)\n", kind, status);
        return 1;
    }
    printf("case %d: child terminated by signal %d (chained to default action)\n", kind, WTERMSIG(status));

    f = fopen(path, "r");
    if (f == NULL) {
        fprintf(stderr, "case %d: no record at %s\n", kind, path);
        return 1;
    }
    fseek(f, 0, SEEK_END);
    size = ftell(f);
    fseek(f, 0, SEEK_SET);
    text = calloc(1, (size_t) size + 1);
    fread(text, 1, (size_t) size, f);
    fclose(f);
    printf("---- %s ----\n%s----\n", path, text);

    if (strncmp(text, "bugcapsule-native-crash 1\n", 26) != 0) { fprintf(stderr, "missing header\n"); failures++; }
    if (kind == 0) {
        if (!has_line_prefix(text, "signal 11\n") && !has_line_prefix(text, "signal 10\n")) {
            fprintf(stderr, "expected signal 11 (or 10)\n");
            failures++;
        }
    } else if (!has_line_prefix(text, "signal 6\n")) {
        fprintf(stderr, "expected signal 6\n");
        failures++;
    }
    snprintf(expected_signal, sizeof(expected_signal), "signal %d\n", WTERMSIG(status));
    if (!has_line_prefix(text, expected_signal)) { fprintf(stderr, "record signal != termination signal\n"); failures++; }
    const char *keys[] = {"code ", "address 0x", "tid ", "thread", "timestamp ", "abi "};
    for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); i++) {
        if (!has_line_prefix(text, keys[i])) { fprintf(stderr, "missing '%s'\n", keys[i]); failures++; }
    }
    for (line = text; line && *line; line = strchr(line, '\n') ? strchr(line, '\n') + 1 : NULL) {
        if (strncmp(line, "frame 0x", 8) == 0) frames++;
    }
    if (frames < 1) { fprintf(stderr, "no frame lines\n"); failures++; }
    if (kind == 0 && strstr(text, "smoke_null_write") == NULL && strstr(text, "main") == NULL) {
        fprintf(stderr, "warning: no recognizable symbol in frames (static functions are not in dladdr's view)\n");
    }
    if (size < 4 || strcmp(text + size - 4, "end\n") != 0) { fprintf(stderr, "missing trailing 'end'\n"); failures++; }
    free(text);
    return failures;
}

int main(int argc, char **argv) {
    int failures;
    if (argc < 2) {
        fprintf(stderr, "usage: %s <output dir>\n", argv[0]);
        return 2;
    }
    failures = run_case(argv[1], 0) + run_case(argv[1], 1);
    printf(failures == 0 ? "SMOKE TEST PASSED\n" : "SMOKE TEST FAILED (%d)\n", failures);
    return failures == 0 ? 0 : 1;
}
