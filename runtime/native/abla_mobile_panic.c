#include <setjmp.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define ABLA_MOBILE_EXPORT __attribute__((visibility("default")))
#define ABLA_MOBILE_FAILURE_LIMIT 1024U

extern int64_t abla_mobile_run(void);

static _Thread_local jmp_buf panic_target;
static _Thread_local bool panic_target_active;
static _Thread_local unsigned char panic_message[ABLA_MOBILE_FAILURE_LIMIT];
static _Thread_local size_t panic_message_size;

// This boundary contains explicit Abla runtime panics on the serialized mobile
// program thread. It deliberately does not claim to recover memory faults,
// signals, or failures raised by unrelated native threads.
ABLA_MOBILE_EXPORT int64_t abla_mobile_run_checked(void) {
    if (panic_target_active) return -1;
    panic_message_size = 0;
    panic_target_active = true;
    if (setjmp(panic_target) == 0) {
        (void)abla_mobile_run();
        panic_target_active = false;
        return 0;
    }
    panic_target_active = false;
    return 1;
}

ABLA_MOBILE_EXPORT int64_t abla_mobile_failure_size(void) {
    return (int64_t)panic_message_size;
}

ABLA_MOBILE_EXPORT int64_t abla_mobile_failure_byte(int64_t index) {
    if (index < 0 || (uint64_t)index >= panic_message_size) return -1;
    return panic_message[index];
}

_Noreturn void abla_mobile_raise_panic(const char *message, size_t length) {
    const char *source = message == NULL ? "Abla runtime panic" : message;
    size_t size = message == NULL ? strlen(source) : length;
    if (size > ABLA_MOBILE_FAILURE_LIMIT) size = ABLA_MOBILE_FAILURE_LIMIT;
    if (size > 0) memcpy(panic_message, source, size);
    panic_message_size = size;
    if (panic_target_active) longjmp(panic_target, 1);
    abort();
}

// Self-hosted runtimes (abla_mobile_hosted.c) report a panic by writing to
// standard error and then exiting. Collect what they write while a checked run
// is active, then turn the exit into the same failure return.
void abla_mobile_capture_failure(const char *message, size_t length) {
    if (!panic_target_active || message == NULL) return;
    size_t room = ABLA_MOBILE_FAILURE_LIMIT - panic_message_size;
    size_t size = length < room ? length : room;
    if (size > 0) memcpy(panic_message + panic_message_size, message, size);
    panic_message_size += size;
}

_Noreturn void abla_mobile_exit_failure(int status) {
    if (panic_target_active) longjmp(panic_target, 1);
    (void)status;
    abort();
}
