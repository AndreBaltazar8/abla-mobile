#include <stdint.h>
#include <stdio.h>
#include <string.h>

extern int64_t abla_mobile_run_checked(void);
extern int64_t abla_mobile_failure_size(void);
extern int64_t abla_mobile_failure_byte(int64_t index);
_Noreturn void abla_mobile_raise_panic(const char *message, size_t length);

static int should_panic = 1;

int64_t abla_mobile_run(void) {
    if (should_panic) {
        const char message[] = "contained mobile panic";
        abla_mobile_raise_panic(message, sizeof(message) - 1U);
    }
    return 42;
}

int main(void) {
    if (abla_mobile_run_checked() != 1) {
        fputs("mobile panic was not contained\n", stderr);
        return 1;
    }
    char message[64] = {0};
    const int64_t size = abla_mobile_failure_size();
    if (size <= 0 || (uint64_t)size >= sizeof(message)) {
        fputs("invalid contained panic size\n", stderr);
        return 1;
    }
    for (int64_t index = 0; index < size; ++index) {
        const int64_t byte = abla_mobile_failure_byte(index);
        if (byte < 0 || byte > 255) {
            fputs("invalid contained panic byte\n", stderr);
            return 1;
        }
        message[index] = (char)byte;
    }
    if (strcmp(message, "contained mobile panic") != 0 ||
        abla_mobile_failure_byte(size) != -1) {
        fprintf(stderr, "unexpected contained panic: %s\n", message);
        return 1;
    }

    should_panic = 0;
    if (abla_mobile_run_checked() != 0 || abla_mobile_failure_size() != 0) {
        fputs("checked normal return was not reset\n", stderr);
        return 1;
    }
    puts("mobile panic containment proof passed");
    return 0;
}
