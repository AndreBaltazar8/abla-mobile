#include <stdint.h>
#include <stdio.h>
#include <string.h>

extern int64_t abla_mobile_http_request(
    const char *method,
    const char *url,
    const char *body
);
extern int64_t abla_mobile_platform_request_next_id(void);
extern int64_t abla_mobile_platform_request_method_size(void);
extern int64_t abla_mobile_platform_request_method_byte(int64_t index);
extern int64_t abla_mobile_platform_request_url_size(void);
extern int64_t abla_mobile_platform_request_url_byte(int64_t index);
extern int64_t abla_mobile_platform_request_body_size(void);
extern int64_t abla_mobile_platform_request_body_byte(int64_t index);
extern void abla_mobile_platform_request_pop(void);

static int copied_equals(
    const char *expected,
    int64_t size,
    int64_t (*byte_at)(int64_t)
) {
    if ((size_t)size != strlen(expected)) return 0;
    for (int64_t index = 0; index < size; ++index) {
        if (byte_at(index) != (unsigned char)expected[index]) return 0;
    }
    return 1;
}

int main(void) {
    const char *method = "POST";
    const char *url = "https://abla-svc.oxente.pt/rpc/greet";
    const char *body = "Abla";
    const int64_t first = abla_mobile_http_request(method, url, body);
    const int64_t second = abla_mobile_http_request("GET", url, "");
    if (first != 1 || second != 2 ||
        abla_mobile_platform_request_next_id() != first ||
        !copied_equals(
            method,
            abla_mobile_platform_request_method_size(),
            abla_mobile_platform_request_method_byte
        ) ||
        !copied_equals(
            url,
            abla_mobile_platform_request_url_size(),
            abla_mobile_platform_request_url_byte
        ) ||
        !copied_equals(
            body,
            abla_mobile_platform_request_body_size(),
            abla_mobile_platform_request_body_byte
        )) {
        fputs("platform request copy failed\n", stderr);
        return 1;
    }
    abla_mobile_platform_request_pop();
    if (abla_mobile_platform_request_next_id() != second) {
        fputs("platform request FIFO failed\n", stderr);
        return 1;
    }
    abla_mobile_platform_request_pop();
    if (abla_mobile_platform_request_next_id() != 0 ||
        abla_mobile_http_request("METHOD-TOO-LONG", url, body) != 0 ||
        abla_mobile_http_request("POST", "", body) != 0) {
        fputs("platform request bounds failed\n", stderr);
        return 1;
    }
    puts("platform request queue: copied IDs and bounded FIFO passed");
    return 0;
}
