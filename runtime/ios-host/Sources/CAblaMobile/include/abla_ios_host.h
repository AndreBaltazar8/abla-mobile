#ifndef ABLA_IOS_HOST_H
#define ABLA_IOS_HOST_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define ABLA_IOS_TREE_LIMIT (1024 * 1024)
#define ABLA_IOS_EVENT_LIMIT (512 * 1024)
#define ABLA_IOS_BODY_LIMIT (8 * 1024 * 1024)
#define ABLA_IOS_URL_LIMIT 2048
#define ABLA_IOS_METHOD_LIMIT 8

enum AblaIosRuntimeStateV1 { ABLA_IOS_IDLE = 0, ABLA_IOS_RUNNING = 1,
    ABLA_IOS_STOPPING = 2, ABLA_IOS_STOPPED = 3, ABLA_IOS_FAILED = 4 };
typedef struct AblaIosMessageV1 {
    int64_t sequence, kind, identifier, method_size, url_size, body_size;
} AblaIosMessageV1;

int64_t abla_ios_start_v1(void);
void abla_ios_shutdown_v1(void);
int64_t abla_ios_state_v1(void);
int64_t abla_ios_failure_v1(void);
/* Copies into caller memory; never returns an owned/Abla pointer. */
int64_t abla_ios_tree_copy_v1(int64_t after_sequence, uint8_t *destination,
                             int64_t capacity, int64_t *sequence);
/* UI events require a positive rendered revision/identifier. A change may
 * replace only the newest matching queued event, preserving action order. */
int64_t abla_ios_event_v1(int64_t revision, int64_t identifier,
                         const uint8_t *payload, int64_t size, int64_t change);
/* Host-created request completions use reserved event -2, revision0. */
int64_t abla_ios_completion_v1(const uint8_t *payload, int64_t size);
/* kind1=fire-and-forget effect, kind2=result-bearing request. Peek does not pop;
 * copy/pop requires the same sequence and sufficient capacities for all parts. */
int64_t abla_ios_message_peek_v1(AblaIosMessageV1 *message);
int64_t abla_ios_message_copy_pop_v1(int64_t sequence,
    uint8_t *method, int64_t method_capacity,
    uint8_t *url, int64_t url_capacity,
    uint8_t *body, int64_t body_capacity);
#ifdef __cplusplus
}
#endif
#endif
