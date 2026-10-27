/*
 * BugCapsule NDK crash handler: core logic (no JNI, see bugcapsule_ndk_jni.c).
 *
 * On a fatal signal the handler writes a small text crash record to the path
 * given at install time, using only open/write/fsync/close and hand-rolled
 * integer formatting (no stdio, no malloc). It then restores the previous
 * handlers and chains to them (or re-raises with the default action) so the
 * system crash reporting (tombstones, Play vitals) and other SDKs still see
 * the crash. The Kotlin core parses the record on the next launch.
 *
 * Record format (UTF-8, '\n' line endings):
 *
 *   bugcapsule-native-crash 1
 *   signal <decimal signo>
 *   code <decimal si_code>
 *   address 0x<hex fault address>
 *   tid <decimal>
 *   thread <thread name, may be empty>
 *   timestamp <decimal epoch ms>
 *   abi <arm64-v8a|armeabi-v7a|x86_64|x86>
 *   scope <one line of JSON set with bc_ndk_set_scope, when set>
 *   frame 0x<pc> 0x<image base or 0> 0x<symbol address or 0> <image path or -> <symbol or ->
 *   ...   (crashing frame first; later frames are return addresses)
 *   end
 *
 * Async-signal-safety notes:
 *  - dladdr() is not formally async-signal-safe (it may take the linker lock).
 *    It is used best-effort, as most native crash reporters do; a crash while
 *    the dynamic linker lock is held could deadlock the handler.
 *  - _Unwind_Backtrace() is not formally async-signal-safe either, but the
 *    LLVM libunwind shipped with the NDK does not allocate on this path.
 *  - The alternate signal stack installed here is only for the thread calling
 *    bc_ndk_install(). Other threads rely on the per-thread alternate stack
 *    bionic allocates for every pthread; a stack overflow on a thread without
 *    any alternate stack cannot run the handler (the kernel kills the process).
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include "bugcapsule_ndk.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

#if defined(__ANDROID__) || defined(__linux__)
#define BC_LINUX 1
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#else
#define BC_LINUX 0
#endif

#if defined(__aarch64__)
#define BC_ABI "arm64-v8a"
#elif defined(__arm__)
#define BC_ABI "armeabi-v7a"
#elif defined(__x86_64__)
#define BC_ABI "x86_64"
#elif defined(__i386__)
#define BC_ABI "x86"
#else
#define BC_ABI "unknown"
#endif

#define BC_EXPORT __attribute__((visibility("default")))

#define BC_MAX_FRAMES 64
/* Raw unwinder frames: room for the handler's own frames, which are skipped. */
#define BC_MAX_RAW_FRAMES (BC_MAX_FRAMES + 16)
#define BC_MAX_SYMBOL 256
#define BC_ALT_STACK_SIZE (64 * 1024)

/* Largest scope (JSON) bc_ndk_set_scope accepts, in bytes. */
#define BC_MAX_SCOPE (64 * 1024)

/* Values of g_handling_tid besides a thread id. */
#define BC_TID_NONE 0L
#define BC_TID_DONE (-1L)

static const int k_signals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP};
#define BC_SIGNAL_COUNT (sizeof(k_signals) / sizeof(k_signals[0]))

static char g_record_path[BC_NDK_MAX_PATH];
static struct sigaction g_previous[BC_SIGNAL_COUNT];
static atomic_int g_installed;
/* Thread currently writing the record (re-entrance / concurrent crash guard). */
static atomic_long g_handling_tid;

/*
 * Scope mirror: the app's breadcrumbs, user, tags... as JSON, double-buffered
 * so the handler always reads a complete copy. Writers fill the inactive
 * buffer under g_scope_lock and publish it by switching g_scope_active; once
 * the handler sets g_scope_reading no writer touches the buffers again.
 */
static char g_scope[2][BC_MAX_SCOPE];
static size_t g_scope_len[2];
static atomic_int g_scope_active = -1;
static atomic_int g_scope_reading;
static pthread_mutex_t g_scope_lock = PTHREAD_MUTEX_INITIALIZER;

static char g_alt_stack[BC_ALT_STACK_SIZE] __attribute__((aligned(16)));
static stack_t g_previous_alt_stack;
static int g_alt_stack_installed;

/* ------------------------------------------------------------------------ */
/* Async-signal-safe output                                                  */
/* ------------------------------------------------------------------------ */

typedef struct {
    int fd;
    size_t len;
    char buf[512];
} bc_writer;

static void bc_flush(bc_writer *w) {
    size_t off = 0;
    while (off < w->len) {
        ssize_t n = write(w->fd, w->buf + off, w->len - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (n == 0) break;
        off += (size_t) n;
    }
    w->len = 0;
}

static void bc_put_char(bc_writer *w, char c) {
    if (w->len == sizeof(w->buf)) bc_flush(w);
    w->buf[w->len++] = c;
}

static void bc_put_str(bc_writer *w, const char *s) {
    while (*s) bc_put_char(w, *s++);
}

static void bc_put_dec(bc_writer *w, long long value) {
    char tmp[24];
    int i = 0;
    unsigned long long u;
    if (value < 0) {
        bc_put_char(w, '-');
        u = (unsigned long long) (-(value + 1)) + 1u;
    } else {
        u = (unsigned long long) value;
    }
    do {
        tmp[i++] = (char) ('0' + (u % 10u));
        u /= 10u;
    } while (u != 0);
    while (i > 0) bc_put_char(w, tmp[--i]);
}

static void bc_put_hex(bc_writer *w, uintptr_t value) {
    static const char digits[] = "0123456789abcdef";
    char tmp[2 * sizeof(uintptr_t)];
    int i = 0;
    bc_put_char(w, '0');
    bc_put_char(w, 'x');
    do {
        tmp[i++] = digits[value & 0xfu];
        value >>= 4;
    } while (value != 0);
    while (i > 0) bc_put_char(w, tmp[--i]);
}

/* A whitespace-free token: spaces, tabs and newlines become '_'; empty -> "-". */
static void bc_put_token(bc_writer *w, const char *s, size_t max_len) {
    size_t i;
    if (s == NULL || s[0] == '\0') {
        bc_put_char(w, '-');
        return;
    }
    for (i = 0; s[i] != '\0' && i < max_len; i++) {
        char c = s[i];
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') c = '_';
        bc_put_char(w, c);
    }
}

/* Rest-of-line value: only line breaks are replaced. */
static void bc_put_line_value(bc_writer *w, const char *s) {
    for (; *s; s++) bc_put_char(w, (*s == '\n' || *s == '\r') ? '_' : *s);
}

/* Like bc_put_line_value for len bytes (JSON: raw line breaks only occur as whitespace). */
static void bc_put_line_bytes(bc_writer *w, const char *s, size_t len) {
    size_t i;
    for (i = 0; i < len; i++) bc_put_char(w, (s[i] == '\n' || s[i] == '\r') ? ' ' : s[i]);
}

/* ------------------------------------------------------------------------ */
/* Thread / context helpers                                                  */
/* ------------------------------------------------------------------------ */

static long bc_gettid(void) {
#if BC_LINUX
    return (long) syscall(SYS_gettid);
#elif defined(__APPLE__)
    uint64_t tid = 0;
    pthread_threadid_np(NULL, &tid);
    return (long) tid;
#else
    return 0;
#endif
}

static void bc_thread_name(char *out, size_t size) {
    out[0] = '\0';
#if BC_LINUX
    if (size >= 17) {
        char name[17];
        memset(name, 0, sizeof(name));
        if (prctl(PR_GET_NAME, (unsigned long) name, 0, 0, 0) == 0) {
            name[16] = '\0';
            memcpy(out, name, sizeof(name));
        }
    }
#else
    (void) size; /* No async-signal-safe way on Apple hosts: leave empty. */
#endif
}

/* Registers of the interrupted context. Returns 0 when unavailable. */
static int bc_context_regs(void *ucontext, uintptr_t *pc, uintptr_t *sp, uintptr_t *lr) {
    *pc = 0;
    *sp = 0;
    *lr = 0;
    if (ucontext == NULL) return 0;
#if BC_LINUX
    const ucontext_t *uc = (const ucontext_t *) ucontext;
#if defined(__aarch64__)
    *pc = (uintptr_t) uc->uc_mcontext.pc;
    *sp = (uintptr_t) uc->uc_mcontext.sp;
    *lr = (uintptr_t) uc->uc_mcontext.regs[30];
    return 1;
#elif defined(__arm__)
    *pc = (uintptr_t) uc->uc_mcontext.arm_pc;
    *sp = (uintptr_t) uc->uc_mcontext.arm_sp;
    *lr = (uintptr_t) uc->uc_mcontext.arm_lr;
    return 1;
#elif defined(__x86_64__)
    *pc = (uintptr_t) uc->uc_mcontext.gregs[REG_RIP];
    *sp = (uintptr_t) uc->uc_mcontext.gregs[REG_RSP];
    return 1;
#elif defined(__i386__)
    *pc = (uintptr_t) uc->uc_mcontext.gregs[REG_EIP];
    *sp = (uintptr_t) uc->uc_mcontext.gregs[REG_ESP];
    return 1;
#else
    return 0;
#endif
#elif defined(__APPLE__)
    const ucontext_t *uc = (const ucontext_t *) ucontext;
    if (uc->uc_mcontext == NULL) return 0;
#if defined(__aarch64__) || defined(__arm64__)
    *pc = (uintptr_t) __darwin_arm_thread_state64_get_pc(uc->uc_mcontext->__ss);
    *sp = (uintptr_t) __darwin_arm_thread_state64_get_sp(uc->uc_mcontext->__ss);
    *lr = (uintptr_t) __darwin_arm_thread_state64_get_lr(uc->uc_mcontext->__ss);
    return 1;
#elif defined(__x86_64__)
    *pc = (uintptr_t) uc->uc_mcontext->__ss.__rip;
    *sp = (uintptr_t) uc->uc_mcontext->__ss.__rsp;
    return 1;
#else
    return 0;
#endif
#else
    return 0;
#endif
}

/* ------------------------------------------------------------------------ */
/* Backtrace                                                                 */
/* ------------------------------------------------------------------------ */

typedef struct {
    uintptr_t pc;
    uintptr_t sp;
} bc_raw_frame;

typedef struct {
    bc_raw_frame *frames;
    size_t count;
    size_t max;
} bc_unwind_state;

/* Static so the handler needs little (alternate) stack; one writer at a time. */
static bc_raw_frame g_raw_frames[BC_MAX_RAW_FRAMES];
static uintptr_t g_frames[BC_MAX_FRAMES];
static bc_writer g_writer;

static _Unwind_Reason_Code bc_unwind_cb(struct _Unwind_Context *ctx, void *arg) {
    bc_unwind_state *state = (bc_unwind_state *) arg;
    uintptr_t pc;
    if (state->count >= state->max) return _URC_END_OF_STACK;
    pc = (uintptr_t) _Unwind_GetIP(ctx);
    if (pc == 0) return _URC_END_OF_STACK;
    state->frames[state->count].pc = pc;
    /* LLVM libunwind returns the frame's SP here; enough to tell stacks apart. */
    state->frames[state->count].sp = (uintptr_t) _Unwind_GetCFA(ctx);
    state->count++;
    return _URC_NO_REASON;
}

/*
 * Fills g_frames with the crashing frame first. Frames that belong to the
 * handler itself are recognized by their stack pointer: they live on the
 * alternate signal stack, or (without one) below the interrupted SP.
 */
static size_t bc_collect_frames(void *ucontext) {
    bc_unwind_state state = {g_raw_frames, 0, BC_MAX_RAW_FRAMES};
    uintptr_t uc_pc, uc_sp, uc_lr;
    int have_regs = bc_context_regs(ucontext, &uc_pc, &uc_sp, &uc_lr);
    uintptr_t alt_lo = 0, alt_hi = 0;
    stack_t ss;
    size_t first = 0, out = 0, i;

    _Unwind_Backtrace(bc_unwind_cb, &state);

    if (sigaltstack(NULL, &ss) == 0 && (ss.ss_flags & SS_ONSTACK)) {
        alt_lo = (uintptr_t) ss.ss_sp;
        alt_hi = alt_lo + (uintptr_t) ss.ss_size;
    }
    if (have_regs || alt_hi != 0) {
        while (first < state.count) {
            uintptr_t sp = g_raw_frames[first].sp;
            int on_alt = alt_hi != 0 && sp >= alt_lo && sp < alt_hi;
            int below = have_regs && uc_sp != 0 && sp < uc_sp;
            if (!on_alt && !below) break;
            first++;
        }
    }

    if (have_regs) {
        g_frames[out++] = uc_pc;
        if (first < state.count && g_raw_frames[first].pc == uc_pc) first++;
        /* Unwinder could not cross the signal frame: LR is the best caller guess. */
        if (first >= state.count && uc_lr != 0 && uc_lr != uc_pc) g_frames[out++] = uc_lr;
    } else if (first >= state.count) {
        first = 0; /* Could not tell handler frames apart: keep everything. */
    }
    for (i = first; i < state.count && out < BC_MAX_FRAMES; i++) {
        g_frames[out++] = g_raw_frames[i].pc;
    }
    return out;
}

/* ------------------------------------------------------------------------ */
/* Record                                                                    */
/* ------------------------------------------------------------------------ */

static void bc_write_frame(bc_writer *w, uintptr_t pc, int is_return_address) {
    Dl_info info;
    uintptr_t base = 0, sym_addr = 0;
    const char *path = NULL, *sym = NULL;
    /* A return address may point past the end of the calling function. */
    uintptr_t lookup = (is_return_address && pc > 0) ? pc - 1 : pc;

    memset(&info, 0, sizeof(info));
    if (pc != 0 && dladdr((const void *) lookup, &info) != 0) {
        base = (uintptr_t) info.dli_fbase;
        sym_addr = (uintptr_t) info.dli_saddr;
        path = info.dli_fname;
        sym = info.dli_sname;
    }
    bc_put_str(w, "frame ");
    bc_put_hex(w, pc);
    bc_put_char(w, ' ');
    bc_put_hex(w, base);
    bc_put_char(w, ' ');
    bc_put_hex(w, sym_addr);
    bc_put_char(w, ' ');
    bc_put_token(w, path, (size_t) -1);
    bc_put_char(w, ' ');
    bc_put_token(w, sym, BC_MAX_SYMBOL);
    bc_put_char(w, '\n');
}

static void bc_write_record(int sig, siginfo_t *info, void *ucontext, long tid) {
    bc_writer *w = &g_writer;
    struct timespec now;
    long long epoch_ms = 0;
    char thread_name[32];
    size_t count, i;
    int fd = open(g_record_path, O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return;

    if (clock_gettime(CLOCK_REALTIME, &now) == 0) {
        epoch_ms = (long long) now.tv_sec * 1000LL + (long long) (now.tv_nsec / 1000000L);
    }
    bc_thread_name(thread_name, sizeof(thread_name));

    w->fd = fd;
    w->len = 0;
    bc_put_str(w, "bugcapsule-native-crash 1\n");
    bc_put_str(w, "signal ");
    bc_put_dec(w, sig);
    bc_put_str(w, "\ncode ");
    bc_put_dec(w, info != NULL ? info->si_code : 0);
    bc_put_str(w, "\naddress ");
    bc_put_hex(w, info != NULL ? (uintptr_t) info->si_addr : 0);
    bc_put_str(w, "\ntid ");
    bc_put_dec(w, tid);
    bc_put_str(w, "\nthread ");
    bc_put_line_value(w, thread_name);
    bc_put_str(w, "\ntimestamp ");
    bc_put_dec(w, epoch_ms);
    bc_put_str(w, "\nabi " BC_ABI "\n");
    /* Flush the header first so it survives a crash inside the unwinder. */
    bc_flush(w);

    atomic_store(&g_scope_reading, 1);
    {
        int active = atomic_load(&g_scope_active);
        if (active == 0 || active == 1) {
            bc_put_str(w, "scope ");
            bc_put_line_bytes(w, g_scope[active], g_scope_len[active]);
            bc_put_char(w, '\n');
            bc_flush(w);
        }
    }

    count = bc_collect_frames(ucontext);
    for (i = 0; i < count; i++) bc_write_frame(w, g_frames[i], i > 0);
    bc_put_str(w, "end\n");
    bc_flush(w);
    fsync(fd);
    close(fd);
}

/* ------------------------------------------------------------------------ */
/* Signal handling                                                           */
/* ------------------------------------------------------------------------ */

static void bc_reraise(int sig) {
#if BC_LINUX
    syscall(SYS_tgkill, (long) getpid(), bc_gettid(), (long) sig);
#else
    raise(sig);
#endif
}

static void bc_set_default_and_reraise(int sig) {
    struct sigaction dfl;
    memset(&dfl, 0, sizeof(dfl));
    sigemptyset(&dfl.sa_mask);
    dfl.sa_handler = SIG_DFL;
    sigaction(sig, &dfl, NULL);
    /* Blocked while this handler runs: delivered with the default action on return. */
    bc_reraise(sig);
}

/* Restores the previous handlers and hands the signal over to them. */
static void bc_chain(int sig, siginfo_t *info, void *ucontext) {
    const struct sigaction *prev = NULL;
    size_t i;
    for (i = 0; i < BC_SIGNAL_COUNT; i++) {
        if (k_signals[i] == sig) prev = &g_previous[i];
        sigaction(k_signals[i], &g_previous[i], NULL);
    }
    atomic_store(&g_installed, 0);

    if (prev == NULL) {
        bc_set_default_and_reraise(sig);
        return;
    }
    if (prev->sa_flags & SA_SIGINFO) {
        void (*action)(int, siginfo_t *, void *) = prev->sa_sigaction;
        if (action == NULL || (void *) action == (void *) SIG_DFL || (void *) action == (void *) SIG_IGN) {
            bc_set_default_and_reraise(sig);
            return;
        }
        action(sig, info, ucontext);
    } else {
        void (*handler)(int) = prev->sa_handler;
        if (handler == SIG_DFL || handler == SIG_IGN) {
            bc_set_default_and_reraise(sig);
            return;
        }
        handler(sig);
    }
    /*
     * The previous handler returned. A hardware fault re-executes the faulting
     * instruction and reaches the (now restored) previous handler again; a
     * signal sent with kill/tgkill/abort (si_code <= 0) must be sent again.
     */
    if (info == NULL || info->si_code <= 0) bc_reraise(sig);
}

static void bc_signal_handler(int sig, siginfo_t *info, void *ucontext) {
    int saved_errno = errno;
    long tid = bc_gettid();
    long expected = BC_TID_NONE;

    if (!atomic_load(&g_installed)) {
        bc_chain(sig, info, ucontext);
    } else if (atomic_compare_exchange_strong(&g_handling_tid, &expected, tid)) {
        if (g_record_path[0] != '\0') bc_write_record(sig, info, ucontext, tid);
        atomic_store(&g_handling_tid, BC_TID_DONE);
        bc_chain(sig, info, ucontext);
    } else {
        if (expected != tid && expected != BC_TID_DONE) {
            /* Another thread is writing the record; give it up to ~3 s to finish. */
            struct timespec pause = {0, 10L * 1000L * 1000L};
            int i;
            for (i = 0; i < 300 && atomic_load(&g_handling_tid) != BC_TID_DONE; i++) {
                nanosleep(&pause, NULL);
            }
        }
        /* Recursive crash inside the handler, or a second crashing thread. */
        bc_chain(sig, info, ucontext);
    }
    errno = saved_errno;
}

/* ------------------------------------------------------------------------ */
/* Public API                                                                */
/* ------------------------------------------------------------------------ */

static void bc_restore_alt_stack(void) {
    stack_t current;
    if (!g_alt_stack_installed) return;
    if (sigaltstack(NULL, &current) == 0 && current.ss_sp == (void *) g_alt_stack &&
        !(current.ss_flags & SS_ONSTACK)) {
        sigaltstack(&g_previous_alt_stack, NULL);
        g_alt_stack_installed = 0;
    }
    /* Otherwise installed on another thread: the static buffer stays valid. */
}

int bc_ndk_install(const char *record_path) {
    struct sigaction action;
    size_t len, i;

    if (atomic_load(&g_installed)) return 1;
    if (record_path == NULL) return 0;
    len = strlen(record_path);
    if (len == 0 || len >= sizeof(g_record_path)) return 0;
    memcpy(g_record_path, record_path, len + 1);
    atomic_store(&g_handling_tid, BC_TID_NONE);

    /* Alternate stack for this thread, unless it already has a big enough one. */
    if (!g_alt_stack_installed) {
        stack_t current;
        if (sigaltstack(NULL, &current) == 0 && !(current.ss_flags & SS_ONSTACK) &&
            ((current.ss_flags & SS_DISABLE) || current.ss_size < BC_ALT_STACK_SIZE)) {
            stack_t ss;
            memset(&ss, 0, sizeof(ss));
            ss.ss_sp = g_alt_stack;
            ss.ss_size = sizeof(g_alt_stack);
            ss.ss_flags = 0;
            if (sigaltstack(&ss, &g_previous_alt_stack) == 0) g_alt_stack_installed = 1;
        }
    }

    memset(&action, 0, sizeof(action));
    sigemptyset(&action.sa_mask);
    action.sa_sigaction = bc_signal_handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    for (i = 0; i < BC_SIGNAL_COUNT; i++) {
        if (sigaction(k_signals[i], &action, &g_previous[i]) != 0) {
            while (i > 0) {
                i--;
                sigaction(k_signals[i], &g_previous[i], NULL);
            }
            bc_restore_alt_stack();
            return 0;
        }
    }
    atomic_store(&g_installed, 1);
    return 1;
}

void bc_ndk_uninstall(void) {
    struct sigaction current;
    size_t i;
    if (!atomic_exchange(&g_installed, 0)) return;
    for (i = 0; i < BC_SIGNAL_COUNT; i++) {
        /* Leave handlers installed after ours alone (they may chain to us). */
        if (sigaction(k_signals[i], NULL, &current) == 0 && (current.sa_flags & SA_SIGINFO) &&
            current.sa_sigaction == bc_signal_handler) {
            sigaction(k_signals[i], &g_previous[i], NULL);
        }
    }
    bc_restore_alt_stack();
}

int bc_ndk_is_installed(void) {
    return atomic_load(&g_installed);
}

int bc_ndk_set_scope(const char *json, size_t len) {
    int next;
    if (json == NULL || len > BC_MAX_SCOPE) return 0;
    pthread_mutex_lock(&g_scope_lock);
    if (atomic_load(&g_scope_reading)) {
        /* The process is crashing: keep the copy the handler is reading. */
        pthread_mutex_unlock(&g_scope_lock);
        return 0;
    }
    next = atomic_load(&g_scope_active) == 0 ? 1 : 0;
    memcpy(g_scope[next], json, len);
    g_scope_len[next] = len;
    atomic_store(&g_scope_active, next);
    pthread_mutex_unlock(&g_scope_lock);
    return 1;
}

BC_EXPORT __attribute__((noinline)) void bugcapsule_ndk_crash_segv(void) {
    volatile int *volatile target = NULL;
    *target = 0x42;
}

BC_EXPORT __attribute__((noinline)) void bugcapsule_ndk_crash_abort(void) {
    abort();
}

void bc_ndk_crash(int kind) {
    if (kind == 1) {
        bugcapsule_ndk_crash_abort();
    } else {
        bugcapsule_ndk_crash_segv();
    }
}
