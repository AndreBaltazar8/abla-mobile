/* Simulator presentation fixture only. This supplies a fake application entry
 * to the real host/bridge; it does not claim Abla compiler/runtime execution. */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
extern int64_t abla_mobile_host_call(int64_t);
static int publish(const char *text) {
    if (abla_mobile_host_call(1) < 0) return 0;
    for (const unsigned char *at = (const unsigned char *)text; *at; at++)
        if (abla_mobile_host_call(256 + *at) < 0) return 0;
    return abla_mobile_host_call(2) >= 0;
}
int64_t abla_mobile_run(void) {
    char value[64] = {0}, tree[4096];
    const char *status = "ready";
    int64_t revision = 0;
    for (;;) {
        revision++;
        const int size = snprintf(tree, sizeof(tree),
            "{\"protocol\":1,\"revision\":%lld,\"darkTheme\":false,\"title\":\"Host fixture\",\"root\":"
            "{\"type\":\"one.column\",\"key\":\"root\",\"properties\":{},\"children\":["
            "{\"type\":\"one.input\",\"key\":\"secure\",\"properties\":{\"a.secure\":\"true\",\"a.value\":\"%s\",\"a.placeholder\":\"Fixture password\",\"a.onChange\":\"4\"},\"children\":[]},"
            "{\"type\":\"one.pressable\",\"key\":\"accept\",\"properties\":{\"a.onPress\":\"7\",\"a.text\":\"Accept\"},\"children\":[]},"
            "{\"type\":\"one.pressable\",\"key\":\"blocked\",\"properties\":{\"a.onPress\":\"9\",\"a.text\":\"Blocked\",\"a.enabled\":\"false\"},\"children\":[]},"
            "{\"type\":\"one.text\",\"key\":\"status\",\"properties\":{\"a.text\":\"%s\"},\"children\":[]}]}}",
            (long long)revision, value, status);
        if (size <= 0 || size >= (int)sizeof(tree) || !publish(tree)) return 0;
        const int64_t event = abla_mobile_host_call(3);
        if (event == 0) return 0;
        const int64_t from = abla_mobile_host_call(4);
        const int64_t bytes = abla_mobile_host_call(5);
        char payload[64] = {0};
        for (int64_t index = 0; index < bytes; index++) {
            const int64_t byte = abla_mobile_host_call(6);
            if (index < 63 && byte >= 32 && byte <= 126 && byte != '"' && byte != '\\') payload[index] = (char)byte;
        }
        if (from <= 0 || from > revision) continue;
        if (event == 4 && bytes < 64) { memcpy(value, payload, sizeof(value)); status = "editing"; }
        if (event == 7) {
            status = strcmp(value, "s3crEt") == 0 ? "accepted_exact" : "refused";
            memset(value, 0, sizeof(value));
        }
        if (event == 9) status = "disabled_button_sent";
        memset(payload, 0, sizeof(payload));
    }
}
