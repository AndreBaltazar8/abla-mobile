#include <stdint.h>
#include <stddef.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>

#define ABLA_MOBILE_REQUEST_LIMIT 32U
#define ABLA_MOBILE_REQUEST_METHOD_LIMIT 8U
#define ABLA_MOBILE_REQUEST_URL_LIMIT 2048U
/* Bodies are heap-allocated per request (large uploads, JSON envelopes). */
#define ABLA_MOBILE_REQUEST_BODY_LIMIT (8U * 1024U * 1024U)
#define ABLA_MOBILE_EXPORT __attribute__((visibility("default")))

typedef struct AblaMobilePlatformRequest {
    int64_t identifier;
    size_t method_size;
    size_t url_size;
    size_t body_size;
    unsigned char method[ABLA_MOBILE_REQUEST_METHOD_LIMIT];
    unsigned char url[ABLA_MOBILE_REQUEST_URL_LIMIT];
    unsigned char *body;
} AblaMobilePlatformRequest;

static AblaMobilePlatformRequest requests[ABLA_MOBILE_REQUEST_LIMIT];
static size_t request_head;
static size_t request_size;
static int64_t next_request_identifier = 1;

ABLA_MOBILE_EXPORT int64_t abla_mobile_http_request(
    const char *method,
    const char *url,
    const char *body
) {
    if (method == NULL || url == NULL || body == NULL ||
        request_size >= ABLA_MOBILE_REQUEST_LIMIT ||
        next_request_identifier <= 0) return 0;
    const size_t method_size = strnlen(
        method,
        ABLA_MOBILE_REQUEST_METHOD_LIMIT + 1U
    );
    const size_t url_size = strnlen(url, ABLA_MOBILE_REQUEST_URL_LIMIT + 1U);
    const size_t body_size = strnlen(
        body,
        ABLA_MOBILE_REQUEST_BODY_LIMIT + 1U
    );
    if (method_size == 0 || method_size > ABLA_MOBILE_REQUEST_METHOD_LIMIT ||
        url_size == 0 || url_size > ABLA_MOBILE_REQUEST_URL_LIMIT ||
        body_size > ABLA_MOBILE_REQUEST_BODY_LIMIT) return 0;

    unsigned char *copy = NULL;
    if (body_size > 0) {
        copy = (unsigned char *)malloc(body_size);
        if (copy == NULL) return 0;
        memcpy(copy, body, body_size);
    }
    const size_t tail = (request_head + request_size) %
        ABLA_MOBILE_REQUEST_LIMIT;
    AblaMobilePlatformRequest *request = &requests[tail];
    request->identifier = next_request_identifier;
    next_request_identifier = next_request_identifier == INT64_MAX
        ? 0
        : next_request_identifier + 1;
    request->method_size = method_size;
    request->url_size = url_size;
    request->body_size = body_size;
    memcpy(request->method, method, method_size);
    memcpy(request->url, url, url_size);
    request->body = copy;
    request_size += 1U;
    return request->identifier;
}

ABLA_MOBILE_EXPORT int64_t abla_mobile_platform_request_next_id(void) {
    return request_size == 0 ? 0 : requests[request_head].identifier;
}

ABLA_MOBILE_EXPORT int64_t abla_mobile_platform_request_method_size(void) {
    return request_size == 0 ? 0 : (int64_t)requests[request_head].method_size;
}

ABLA_MOBILE_EXPORT int64_t abla_mobile_platform_request_method_byte(
    int64_t index
) {
    if (request_size == 0 || index < 0 ||
        (uint64_t)index >= requests[request_head].method_size) return -1;
    return requests[request_head].method[index];
}

ABLA_MOBILE_EXPORT int64_t abla_mobile_platform_request_url_size(void) {
    return request_size == 0 ? 0 : (int64_t)requests[request_head].url_size;
}

ABLA_MOBILE_EXPORT int64_t abla_mobile_platform_request_url_byte(
    int64_t index
) {
    if (request_size == 0 || index < 0 ||
        (uint64_t)index >= requests[request_head].url_size) return -1;
    return requests[request_head].url[index];
}

ABLA_MOBILE_EXPORT int64_t abla_mobile_platform_request_body_size(void) {
    return request_size == 0 ? 0 : (int64_t)requests[request_head].body_size;
}

ABLA_MOBILE_EXPORT int64_t abla_mobile_platform_request_body_byte(
    int64_t index
) {
    if (request_size == 0 || index < 0 ||
        (uint64_t)index >= requests[request_head].body_size) return -1;
    return requests[request_head].body[index];
}

// Copies the whole body of the next request in one call; returns its size, or
// -1 if `capacity` is too small.
ABLA_MOBILE_EXPORT int64_t abla_mobile_platform_request_body_copy(
    unsigned char *out,
    int64_t capacity
) {
    if (request_size == 0) return 0;
    const AblaMobilePlatformRequest *request = &requests[request_head];
    if (out == NULL || capacity < 0 || (uint64_t)capacity < request->body_size) return -1;
    if (request->body_size > 0) memcpy(out, request->body, request->body_size);
    return (int64_t)request->body_size;
}

ABLA_MOBILE_EXPORT void abla_mobile_platform_request_pop(void) {
    if (request_size == 0) return;
    free(requests[request_head].body);
    requests[request_head].body = NULL;
    request_head = (request_head + 1U) % ABLA_MOBILE_REQUEST_LIMIT;
    request_size -= 1U;
}
