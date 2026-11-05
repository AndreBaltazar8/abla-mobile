#ifndef ABLA_MOBILE_APP_H
#define ABLA_MOBILE_APP_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Statically linked application surface. No JNI, dlopen, Swift object or Abla
 * heap pointer crosses this boundary. Failure readers are thread-local: read
 * them on the runtime thread after checked start returns, then copy for UI. */
#define ABLA_MOBILE_IOS_ABI_VERSION 1
#define ABLA_MOBILE_PROTOCOL_VERSION 1

typedef int64_t (*AblaMobileHostCall)(void *host_context, int64_t value);
int64_t abla_mobile_run(void);
int64_t abla_mobile_run_checked(void);
int64_t abla_mobile_failure_size(void);
int64_t abla_mobile_failure_byte(int64_t index);
int64_t abla_mobile_platform_attach(AblaMobileHostCall call, void *host_context);

/* Provided by the mobile-owned iOS archive support object, not the compiler. */
int64_t abla_mobile_ios_abi_version(void);

int64_t abla_mobile_platform_effect_next_kind(void);
int64_t abla_mobile_platform_effect_next_size(void);
int64_t abla_mobile_platform_effect_next_byte(int64_t index);
void abla_mobile_platform_effect_pop(void);
int64_t abla_mobile_platform_request_next_id(void);
int64_t abla_mobile_platform_request_method_size(void);
int64_t abla_mobile_platform_request_method_byte(int64_t index);
int64_t abla_mobile_platform_request_url_size(void);
int64_t abla_mobile_platform_request_url_byte(int64_t index);
int64_t abla_mobile_platform_request_body_size(void);
int64_t abla_mobile_platform_request_body_byte(int64_t index);
int64_t abla_mobile_platform_request_body_copy(uint8_t *destination, int64_t capacity);
void abla_mobile_platform_request_pop(void);

/* The existing scalar-stream v1. Tree bytes are 256..511. All payloads are
 * copied by the host before returning; no Abla pointer survives a call. */
enum AblaMobileProtocolV1 {
    ABLA_MOBILE_TREE_BEGIN = 1,
    ABLA_MOBILE_TREE_END = 2,
    ABLA_MOBILE_WAIT_EVENT = 3,
    ABLA_MOBILE_EVENT_REVISION = 4,
    ABLA_MOBILE_EVENT_PAYLOAD_SIZE = 5,
    ABLA_MOBILE_EVENT_PAYLOAD_BYTE = 6,
    ABLA_MOBILE_TREE_BYTE_BASE = 256
};
#ifdef __cplusplus
}
#endif
#endif
