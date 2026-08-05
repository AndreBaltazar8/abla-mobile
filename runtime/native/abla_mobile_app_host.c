#include <stdint.h>
#include <stddef.h>

#define ABLA_MOBILE_EXPORT __attribute__((visibility("default")))

typedef int64_t (*AblaMobileHostCall)(void *context, int64_t value);

static AblaMobileHostCall mobile_host_call;
static void *mobile_host_context;

// This installs a platform-owned transport function. Neither argument points
// at Abla memory, and the Abla program never observes either value.
ABLA_MOBILE_EXPORT int64_t abla_mobile_platform_attach(
    AblaMobileHostCall call,
    void *context
) {
    if (call == NULL || mobile_host_call != NULL) return 0;
    mobile_host_context = context;
    mobile_host_call = call;
    return 1;
}

int64_t abla_mobile_host_call(int64_t value) {
    if (mobile_host_call == NULL) return -1;
    return mobile_host_call(mobile_host_context, value);
}
