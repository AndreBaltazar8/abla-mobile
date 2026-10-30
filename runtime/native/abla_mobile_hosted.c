#include <android/log.h>
#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

/* Support for applications compiled by Abla releases whose runtime is written
 * in Abla (v0.2.22 and later). Their objects carry the runtime and need only
 * libc, so this file fills the few gaps between that runtime and Android:
 *
 * - A runtime panic writes "panic: <message>" to standard error and calls
 *   _exit(101). Android discards standard error, and exiting would hide the
 *   failure, so `write(2, ...)` and `_exit` resolve to the hidden definitions
 *   below inside this library: standard error goes to logcat and the failure
 *   buffer, and _exit returns control to abla_mobile_run_checked. The program
 *   is not resumed afterwards, so runtime state left by the panic is never
 *   reused.
 * - backtrace() is a glibc extension that Android only provides from API 33.
 * - The runtime reads errno through glibc's __errno_location; bionic calls it
 *   __errno.
 */

#define ABLA_MOBILE_HIDDEN __attribute__((visibility("hidden")))

void abla_mobile_capture_failure(const char *message, size_t length);
_Noreturn void abla_mobile_exit_failure(int status);

ABLA_MOBILE_HIDDEN ssize_t write(int descriptor, const void *data, size_t length) {
    if (descriptor == 2 && data != NULL) {
        abla_mobile_capture_failure((const char *)data, length);
        char line[512];
        size_t size = length < sizeof(line) - 1 ? length : sizeof(line) - 1;
        memcpy(line, data, size);
        line[size] = '\0';
        __android_log_write(ANDROID_LOG_ERROR, "Abla", line);
        return (ssize_t)length;
    }
    return (ssize_t)syscall(SYS_write, descriptor, data, length);
}

ABLA_MOBILE_HIDDEN _Noreturn void _exit(int status) {
    abla_mobile_exit_failure(status);
}

ABLA_MOBILE_HIDDEN int backtrace(void **buffer, int size) {
    (void)buffer;
    (void)size;
    return 0;
}

ABLA_MOBILE_HIDDEN void backtrace_symbols_fd(void *const *buffer, int size, int descriptor) {
    (void)buffer;
    (void)size;
    (void)descriptor;
}

ABLA_MOBILE_HIDDEN int *__errno_location(void) {
    return __errno();
}
