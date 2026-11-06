#include "abla_ios_host.h"
#include "abla_mobile_app.h"
#include <limits.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#define EVENT_COUNT 64
#define MESSAGE_COUNT 32
#define QUEUE_BYTES (8U * 1024U * 1024U)

typedef struct {
    int64_t revision, identifier;
    uint8_t *data;
    size_t size;
} Event;
typedef struct {
    AblaIosMessageV1 info;
    uint8_t method[ABLA_IOS_METHOD_LIMIT], url[ABLA_IOS_URL_LIMIT];
    uint8_t *body;
} Message;
static struct {
    pthread_mutex_t mutex;
    pthread_cond_t available;
    bool started, accepting, needs_pump;
    _Atomic bool closing;
    _Atomic bool application_ready;
    _Atomic int state;
    _Atomic int failure;
    Event events[EVENT_COUNT], current;
    size_t event_head, event_count, event_bytes, payload_offset;
    Message messages[MESSAGE_COUNT];
    size_t message_head, message_count, message_bytes;
    int64_t message_sequence;
    uint8_t working[ABLA_IOS_TREE_LIMIT], published[ABLA_IOS_TREE_LIMIT];
    size_t working_size, published_size;
    bool working_valid;
    int64_t tree_sequence;
} host = { .mutex = PTHREAD_MUTEX_INITIALIZER, .available = PTHREAD_COND_INITIALIZER };

static void wipe(void *data, size_t size) {
    volatile uint8_t *at = data;
    while (size--) *at++ = 0;
}
static void free_bytes(uint8_t *data, size_t size) {
    if (data) { wipe(data, size); free(data); }
}
static void protocol_failure(void) {
    atomic_store(&host.failure, 2);
    atomic_store(&host.closing, true);
    pthread_mutex_lock(&host.mutex);
    pthread_cond_broadcast(&host.available);
    pthread_mutex_unlock(&host.mutex);
}
static int64_t queue_event(int64_t revision, int64_t identifier,
                            const uint8_t *data, int64_t length, bool replace) {
    if (length < 0 || length > ABLA_IOS_EVENT_LIMIT || (length && !data)) return -1;
    const size_t size = (size_t)length;
    pthread_mutex_lock(&host.mutex);
    if (!host.accepting || atomic_load(&host.closing)) {
        pthread_mutex_unlock(&host.mutex); return -2;
    }
    Event *last = NULL;
    if (replace && host.event_count) {
        last = &host.events[(host.event_head + host.event_count - 1) % EVENT_COUNT];
        if (last->revision != revision || last->identifier != identifier) last = NULL;
    }
    const size_t previous = last ? last->size : 0;
    if ((!last && host.event_count == EVENT_COUNT) || size > QUEUE_BYTES - (host.event_bytes - previous)) {
        pthread_mutex_unlock(&host.mutex); return 0;
    }
    uint8_t *copy = size ? malloc(size) : NULL;
    if (size && !copy) { pthread_mutex_unlock(&host.mutex); return 0; }
    if (size) memcpy(copy, data, size);
    Event *item = last ? last : &host.events[(host.event_head + host.event_count) % EVENT_COUNT];
    if (last) free_bytes(last->data, last->size); else host.event_count++;
    *item = (Event){ revision, identifier, copy, size };
    host.event_bytes = host.event_bytes - previous + size;
    pthread_cond_signal(&host.available);
    pthread_mutex_unlock(&host.mutex);
    return 1;
}
int64_t abla_ios_event_v1(int64_t revision, int64_t identifier,
                         const uint8_t *payload, int64_t size, int64_t change) {
    if (revision <= 0 || identifier <= 0 || (change != 0 && change != 1)) return -1;
    return queue_event(revision, identifier, payload, size, change == 1);
}
int64_t abla_ios_completion_v1(const uint8_t *payload, int64_t size) {
    /* Matches Android's revision-independent completion envelope. Revision1 is
     * valid in mobileRun; zero would be rejected before dispatch. */
    return queue_event(1, -2, payload, size, false);
}
/* Called only on the serialized runtime thread. It copies each platform-owned
 * record before popping it. No Abla pointers or Swift objects cross the queue. */
static bool append_message(Message *message) {
    const size_t size = (size_t)message->info.body_size;
    pthread_mutex_lock(&host.mutex);
    if (host.message_count == MESSAGE_COUNT || size > QUEUE_BYTES - host.message_bytes ||
        host.message_sequence == INT64_MAX || atomic_load(&host.closing)) {
        pthread_mutex_unlock(&host.mutex); return false;
    }
    message->info.sequence = ++host.message_sequence;
    host.messages[(host.message_head + host.message_count) % MESSAGE_COUNT] = *message;
    host.message_count++;
    host.message_bytes += size;
    pthread_mutex_unlock(&host.mutex);
    return true;
}
static void pump_platform(void) {
    for (;;) {
        const int64_t kind = abla_mobile_platform_effect_next_kind();
        if (!kind) break;
        const int64_t size = abla_mobile_platform_effect_next_size();
        if (kind < 1 || kind > 9 || size < 0 || size > ABLA_IOS_EVENT_LIMIT) { protocol_failure(); return; }
        Message message = { .info = { .kind = 1, .identifier = kind, .body_size = size } };
        message.body = size ? malloc((size_t)size) : NULL;
        if (size && !message.body) return;
        bool valid = true;
        for (int64_t index = 0; index < size; index++) {
            const int64_t byte = abla_mobile_platform_effect_next_byte(index);
            if (byte < 0 || byte > 255) { valid = false; break; }
            message.body[index] = (uint8_t)byte;
        }
        if (!valid) { free_bytes(message.body, (size_t)size); protocol_failure(); return; }
        if (!append_message(&message)) { free_bytes(message.body, (size_t)size); return; }
        abla_mobile_platform_effect_pop();
    }
    for (;;) {
        const int64_t identifier = abla_mobile_platform_request_next_id();
        if (!identifier) break;
        const int64_t method_size = abla_mobile_platform_request_method_size();
        const int64_t url_size = abla_mobile_platform_request_url_size();
        const int64_t body_size = abla_mobile_platform_request_body_size();
        if (identifier < 0 || method_size < 1 || method_size > ABLA_IOS_METHOD_LIMIT ||
            url_size < 0 || url_size > ABLA_IOS_URL_LIMIT || body_size < 0 || body_size > ABLA_IOS_BODY_LIMIT) {
            protocol_failure(); return;
        }
        Message message = { .info = { .kind = 2, .identifier = identifier,
            .method_size = method_size, .url_size = url_size, .body_size = body_size } };
        bool valid = true;
        for (int64_t index = 0; index < method_size; index++) {
            const int64_t byte = abla_mobile_platform_request_method_byte(index);
            if (byte < 0 || byte > 255) { valid = false; break; }
            message.method[index] = (uint8_t)byte;
        }
        for (int64_t index = 0; valid && index < url_size; index++) {
            const int64_t byte = abla_mobile_platform_request_url_byte(index);
            if (byte < 0 || byte > 255) { valid = false; break; }
            message.url[index] = (uint8_t)byte;
        }
        message.body = body_size ? malloc((size_t)body_size) : NULL;
        if (body_size && !message.body) return;
        if (body_size && abla_mobile_platform_request_body_copy(message.body, body_size) != body_size) valid = false;
        if (!valid) { free_bytes(message.body, (size_t)body_size); protocol_failure(); return; }
        if (!append_message(&message)) { free_bytes(message.body, (size_t)body_size); return; }
        abla_mobile_platform_request_pop();
    }
}
static int64_t scalar_call(void *context, int64_t value) {
    (void)context;
    if (!atomic_load(&host.application_ready)) { protocol_failure(); return -1; }
    if (value == ABLA_MOBILE_TREE_BEGIN) {
        wipe(host.working, host.working_size); host.working_size = 0;
        host.working_valid = !atomic_load(&host.closing);
        return host.working_valid ? 0 : -1;
    }
    if (value >= ABLA_MOBILE_TREE_BYTE_BASE && value < ABLA_MOBILE_TREE_BYTE_BASE + 256) {
        if (!host.working_valid || host.working_size == ABLA_IOS_TREE_LIMIT) {
            host.working_valid = false; protocol_failure(); return -1;
        }
        host.working[host.working_size++] = (uint8_t)(value - ABLA_MOBILE_TREE_BYTE_BASE);
        return 0;
    }
    if (value == ABLA_MOBILE_TREE_END) {
        if (!host.working_valid) { protocol_failure(); return -1; }
        if (atomic_load(&host.closing)) return -1;
        pthread_mutex_lock(&host.mutex);
        if (host.tree_sequence == INT64_MAX) {
            pthread_mutex_unlock(&host.mutex); protocol_failure(); return -1;
        }
        wipe(host.published, host.published_size);
        memcpy(host.published, host.working, host.working_size);
        host.published_size = host.working_size; host.tree_sequence++;
        pthread_mutex_unlock(&host.mutex);
        wipe(host.working, host.working_size); host.working_size = 0;
        host.working_valid = false;
        pump_platform();
        return 0;
    }
    if (value == ABLA_MOBILE_WAIT_EVENT) {
        for (;;) {
            pump_platform();
            pthread_mutex_lock(&host.mutex);
            while (!host.event_count && !atomic_load(&host.closing) && !host.needs_pump)
                pthread_cond_wait(&host.available, &host.mutex);
            host.needs_pump = false;
            if (atomic_load(&host.closing)) {
                free_bytes(host.current.data, host.current.size); host.current = (Event){0};
                pthread_mutex_unlock(&host.mutex); return 0;
            }
            if (host.event_count) {
                free_bytes(host.current.data, host.current.size);
                host.current = host.events[host.event_head];
                host.events[host.event_head] = (Event){0};
                host.event_head = (host.event_head + 1) % EVENT_COUNT;
                host.event_count--; host.event_bytes -= host.current.size; host.payload_offset = 0;
                const int64_t identifier = host.current.identifier;
                pthread_mutex_unlock(&host.mutex); return identifier;
            }
            pthread_mutex_unlock(&host.mutex);
        }
    }
    if (value == ABLA_MOBILE_EVENT_REVISION) return host.current.revision;
    if (value == ABLA_MOBILE_EVENT_PAYLOAD_SIZE) return (int64_t)host.current.size;
    if (value == ABLA_MOBILE_EVENT_PAYLOAD_BYTE)
        return host.payload_offset < host.current.size ? host.current.data[host.payload_offset++] : 0;
    protocol_failure(); return -1;
}
static void *runtime_thread(void *unused) {
    (void)unused;
    const int64_t result = abla_mobile_run_checked();
    pthread_mutex_lock(&host.mutex);
    const bool expected = atomic_load(&host.closing);
    host.accepting = false;
    if (!expected && !atomic_load(&host.failure)) atomic_store(&host.failure, result == 1 ? 3 : 4);
    for (size_t index = 0; index < EVENT_COUNT; index++) {
        free_bytes(host.events[index].data, host.events[index].size); host.events[index] = (Event){0};
    }
    free_bytes(host.current.data, host.current.size); host.current = (Event){0};
    host.event_count = 0; host.event_bytes = 0;
    wipe(host.working, host.working_size); host.working_size = 0;
    wipe(host.published, host.published_size); host.published_size = 0;
    for (size_t index = 0; index < MESSAGE_COUNT; index++) {
        Message *message = &host.messages[index];
        free_bytes(message->body, (size_t)message->info.body_size);
        wipe(message, sizeof(*message));
    }
    host.message_count = 0; host.message_bytes = 0;
    while (abla_mobile_platform_effect_next_kind()) abla_mobile_platform_effect_pop();
    while (abla_mobile_platform_request_next_id()) abla_mobile_platform_request_pop();
    pthread_cond_broadcast(&host.available);
    pthread_mutex_unlock(&host.mutex);
    atomic_store(&host.state, expected && !atomic_load(&host.failure) ? ABLA_IOS_STOPPED : ABLA_IOS_FAILED);
    return NULL;
}
/* The iOS-only panic shim calls this entry inside its checked boundary. Even
 * a pure exported Abla query may initialize application globals, so it MUST
 * run on the same runtime thread as the application, never on UIKit's thread. */
int64_t abla_ios_checked_entry(void) {
    if (abla_mobile_application_protocol_version() != ABLA_MOBILE_PROTOCOL_VERSION) {
        atomic_store(&host.failure, 5);
        return 0;
    }
    pthread_mutex_lock(&host.mutex);
    if (atomic_load(&host.closing)) { pthread_mutex_unlock(&host.mutex); return 0; }
    atomic_store(&host.application_ready, true);
    host.accepting = true;
    pthread_mutex_unlock(&host.mutex);
    return abla_mobile_run();
}
int64_t abla_ios_start_v1(void) {
    if (abla_mobile_ios_abi_version() != ABLA_MOBILE_IOS_ABI_VERSION) return -1;
    pthread_mutex_lock(&host.mutex);
    if (host.started || atomic_load(&host.closing)) { pthread_mutex_unlock(&host.mutex); return 0; }
    host.started = true;
    if (abla_mobile_platform_attach(scalar_call, &host) != 1) {
        atomic_store(&host.failure, 1); atomic_store(&host.state, ABLA_IOS_FAILED);
        pthread_mutex_unlock(&host.mutex); return -1;
    }
    atomic_store(&host.state, ABLA_IOS_RUNNING);
    pthread_t thread;
    if (pthread_create(&thread, NULL, runtime_thread, NULL) != 0) {
        host.accepting = false; atomic_store(&host.failure, 1); atomic_store(&host.state, ABLA_IOS_FAILED);
        pthread_mutex_unlock(&host.mutex); return -1;
    }
    pthread_detach(thread); pthread_mutex_unlock(&host.mutex); return 1;
}
void abla_ios_shutdown_v1(void) {
    atomic_store(&host.closing, true);
    pthread_mutex_lock(&host.mutex);
    if (atomic_load(&host.state) == ABLA_IOS_RUNNING) atomic_store(&host.state, ABLA_IOS_STOPPING);
    pthread_cond_broadcast(&host.available); pthread_mutex_unlock(&host.mutex);
}
int64_t abla_ios_state_v1(void) { return atomic_load(&host.state); }
int64_t abla_ios_failure_v1(void) { return atomic_load(&host.failure); }
int64_t abla_ios_tree_copy_v1(int64_t after, uint8_t *destination, int64_t capacity, int64_t *sequence) {
    if (after < 0 || capacity < 0 || !sequence) return -1;
    pthread_mutex_lock(&host.mutex);
    if (host.tree_sequence <= after) { pthread_mutex_unlock(&host.mutex); return 0; }
    if (!destination || (uint64_t)capacity < host.published_size) { pthread_mutex_unlock(&host.mutex); return -1; }
    memcpy(destination, host.published, host.published_size); *sequence = host.tree_sequence;
    const int64_t size = (int64_t)host.published_size;
    pthread_mutex_unlock(&host.mutex); return size;
}
int64_t abla_ios_message_peek_v1(AblaIosMessageV1 *message) {
    if (!message) return -1;
    pthread_mutex_lock(&host.mutex);
    if (!host.message_count) { pthread_mutex_unlock(&host.mutex); return 0; }
    *message = host.messages[host.message_head].info;
    pthread_mutex_unlock(&host.mutex); return 1;
}
int64_t abla_ios_message_copy_pop_v1(int64_t sequence,
    uint8_t *method, int64_t method_capacity, uint8_t *url, int64_t url_capacity,
    uint8_t *body, int64_t body_capacity) {
    pthread_mutex_lock(&host.mutex);
    if (!host.message_count) { pthread_mutex_unlock(&host.mutex); return 0; }
    Message *message = &host.messages[host.message_head];
    const AblaIosMessageV1 info = message->info;
    if (info.sequence != sequence || method_capacity < info.method_size || url_capacity < info.url_size ||
        body_capacity < info.body_size || (info.method_size && !method) || (info.url_size && !url) ||
        (info.body_size && !body)) { pthread_mutex_unlock(&host.mutex); return -1; }
    if (info.method_size) memcpy(method, message->method, (size_t)info.method_size);
    if (info.url_size) memcpy(url, message->url, (size_t)info.url_size);
    if (info.body_size) memcpy(body, message->body, (size_t)info.body_size);
    free_bytes(message->body, (size_t)info.body_size); *message = (Message){0};
    host.message_head = (host.message_head + 1) % MESSAGE_COUNT;
    host.message_count--; host.message_bytes -= (size_t)info.body_size;
    host.needs_pump = true; pthread_cond_signal(&host.available);
    pthread_mutex_unlock(&host.mutex); return 1;
}
