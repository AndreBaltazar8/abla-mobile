#define _POSIX_C_SOURCE 200809L
#include "abla_ios_host.h"
#include "abla_mobile_app.h"
#include <assert.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
extern int64_t abla_mobile_host_call(int64_t);
extern int64_t abla_mobile_platform_effect(int64_t, const char *);
extern int64_t abla_mobile_http_request(const char *, const char *, const char *);
static _Atomic int stage;
static int bad_frame;
static int bad_version;
int64_t abla_mobile_application_protocol_version(void) { return bad_version ? 2 : 1; }
static void pause_briefly(void) { struct timespec wait = {0, 1000000}; nanosleep(&wait, NULL); }
static void await_stage(int wanted) {
    for (int attempt = 0; attempt < 5000 && atomic_load(&stage) < wanted; attempt++) pause_briefly();
    assert(atomic_load(&stage) >= wanted);
}
/* A fake application producer exercises the actual bridge/support queues. This
 * deliberately does not claim to execute the compiler's Abla runtime. */
int64_t abla_mobile_run(void) {
    if (bad_frame) { assert(abla_mobile_host_call(ABLA_MOBILE_TREE_END) == -1); return 0; }
    assert(abla_mobile_platform_effect(2, "ação") == 1);
    assert(abla_mobile_http_request("POST", "https://example.invalid/rpc", "{\"x\":1}") == 1);
    assert(abla_mobile_host_call(ABLA_MOBILE_TREE_BEGIN) == 0);
    const unsigned char tree[] = "{\"revision\":1}";
    for (size_t i = 0; i < sizeof(tree) - 1; i++)
        assert(abla_mobile_host_call(ABLA_MOBILE_TREE_BYTE_BASE + tree[i]) == 0);
    assert(abla_mobile_host_call(ABLA_MOBILE_TREE_END) == 0);
    atomic_store(&stage, 1);
    await_stage(2);
    assert(abla_mobile_host_call(ABLA_MOBILE_WAIT_EVENT) == 7);
    assert(abla_mobile_host_call(ABLA_MOBILE_EVENT_REVISION) == 1);
    const unsigned char expected[] = "ação";
    assert(abla_mobile_host_call(ABLA_MOBILE_EVENT_PAYLOAD_SIZE) == sizeof(expected) - 1);
    for (size_t i = 0; i < sizeof(expected) - 1; i++)
        assert(abla_mobile_host_call(ABLA_MOBILE_EVENT_PAYLOAD_BYTE) == expected[i]);
    assert(abla_mobile_host_call(ABLA_MOBILE_WAIT_EVENT) == 8);
    assert(abla_mobile_host_call(ABLA_MOBILE_WAIT_EVENT) == 7);
    assert(abla_mobile_host_call(ABLA_MOBILE_EVENT_PAYLOAD_SIZE) == 1);
    assert(abla_mobile_host_call(ABLA_MOBILE_EVENT_PAYLOAD_BYTE) == 'z');
    /* Queue depth is bounded: exactly 64 accepted events were filled by UI. */
    for (int i = 0; i < 64; i++) assert(abla_mobile_host_call(ABLA_MOBILE_WAIT_EVENT) == 9);
    assert(abla_mobile_host_call(ABLA_MOBILE_WAIT_EVENT) == -2);
    assert(abla_mobile_host_call(ABLA_MOBILE_EVENT_REVISION) == 1);
    assert(abla_mobile_host_call(ABLA_MOBILE_EVENT_PAYLOAD_SIZE) == 4);
    atomic_store(&stage, 3);
    assert(abla_mobile_host_call(ABLA_MOBILE_WAIT_EVENT) == 0);
    return 0;
}
int main(int argc, char **argv) {
    (void)argv;
    if (argc > 1) {
        if (strcmp(argv[1], "--bad-version") == 0) {
            bad_version = 1;
            assert(abla_ios_start_v1() == -1);
            assert(abla_ios_state_v1() == ABLA_IOS_IDLE);
            puts("PASS native bridge: mismatched application protocol refused before runtime start");
            return 0;
        }
        bad_frame = 1;
        assert(abla_ios_start_v1() == 1);
        for (int i = 0; i < 5000 && abla_ios_state_v1() != ABLA_IOS_FAILED; i++) pause_briefly();
        assert(abla_ios_state_v1() == ABLA_IOS_FAILED && abla_ios_failure_v1() == 2);
        puts("PASS native bridge: unframed publication fails closed without raw diagnostics");
        return 0;
    }
    assert(abla_ios_event_v1(1, 7, NULL, 0, 0) == -2);
    assert(abla_ios_event_v1(0, 7, NULL, 0, 0) == -1);
    assert(abla_ios_start_v1() == 1);
    assert(abla_ios_start_v1() == 0);
    await_stage(1);
    unsigned char tree[64] = {0}; int64_t sequence = 0;
    assert(abla_ios_tree_copy_v1(0, tree, 2, &sequence) == -1);
    assert(sequence == 0);
    assert(abla_ios_tree_copy_v1(0, tree, sizeof(tree), &sequence) == 14);
    assert(sequence == 1 && memcmp(tree, "{\"revision\":1}", 14) == 0);
    assert(abla_ios_tree_copy_v1(sequence, tree, sizeof(tree), &sequence) == 0);
    AblaIosMessageV1 message;
    assert(abla_ios_message_peek_v1(&message) == 1 && message.kind == 1 && message.identifier == 2);
    unsigned char method[8], url[128], body[128];
    assert(abla_ios_message_copy_pop_v1(message.sequence + 1, method, 8, url, 128, body, 128) == -1);
    assert(abla_ios_message_copy_pop_v1(message.sequence, method, 8, url, 128, body, 1) == -1);
    assert(abla_ios_message_copy_pop_v1(message.sequence, method, 8, url, 128, body, 128) == 1);
    assert(message.body_size == 6 && memcmp(body, "ação", 6) == 0);
    assert(abla_ios_message_peek_v1(&message) == 1 && message.kind == 2 && message.identifier == 1);
    assert(abla_ios_message_copy_pop_v1(message.sequence, method, 8, url, 128, body, 128) == 1);
    assert(message.method_size == 4 && memcmp(method, "POST", 4) == 0);
    assert(message.body_size == 7 && memcmp(body, "{\"x\":1}", 7) == 0);
    assert(abla_ios_message_peek_v1(&message) == 0);
    assert(abla_ios_event_v1(1, 7, (const unsigned char *)"x", 1, 1) == 1);
    assert(abla_ios_event_v1(1, 7, (const unsigned char *)"ação", 6, 1) == 1);
    assert(abla_ios_event_v1(1, 8, NULL, 0, 0) == 1);
    assert(abla_ios_event_v1(1, 7, (const unsigned char *)"z", 1, 1) == 1);
    for (int i = 0; i < 61; i++) assert(abla_ios_event_v1(1, 9, NULL, 0, 0) == 1);
    assert(abla_ios_event_v1(1, 9, NULL, 0, 0) == 0);
    atomic_store(&stage, 2);
    /* Runtime consumes 61 initial ID9s, allowing the last three to be queued. */
    for (int i = 0; i < 3; i++) {
        int64_t accepted = 0;
        for (int attempt = 0; attempt < 5000 && accepted == 0; attempt++) {
            accepted = abla_ios_event_v1(1, 9, NULL, 0, 0); if (!accepted) pause_briefly();
        }
        assert(accepted == 1);
    }
    int64_t completion = 0;
    for (int i = 0; i < 5000 && !completion; i++) {
        completion = abla_ios_completion_v1((const unsigned char *)"done", 4);
        if (!completion) pause_briefly();
    }
    assert(completion == 1);
    await_stage(3);
    abla_ios_shutdown_v1();
    for (int i = 0; i < 5000 && abla_ios_state_v1() == ABLA_IOS_STOPPING; i++) pause_briefly();
    assert(abla_ios_state_v1() == ABLA_IOS_STOPPED && abla_ios_failure_v1() == 0);
    assert(abla_ios_event_v1(1, 7, NULL, 0, 0) == -2);
    assert(abla_ios_message_peek_v1(&message) == 0);
    assert(abla_ios_tree_copy_v1(0, tree, sizeof(tree), &sequence) == 0);
    puts("PASS native bridge: copied UTF-8/tree/requests, stale/capacity refusal, change order, 64-event bound, shutdown");
    return 0;
}
