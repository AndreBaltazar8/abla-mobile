/* Simulator presentation fixture only. This supplies a fake application entry
 * to the real host/bridge; it does not claim Abla compiler/runtime execution. */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
extern int64_t abla_mobile_host_call(int64_t);
extern int64_t abla_mobile_http_request(const char *, const char *, const char *);
int64_t abla_mobile_application_protocol_version(void) { return 1; }
static int publish(const char *text) {
    if (abla_mobile_host_call(1) < 0) return 0;
    for (const unsigned char *at = (const unsigned char *)text; *at; at++)
        if (abla_mobile_host_call(256 + *at) < 0) return 0;
    return abla_mobile_host_call(2) >= 0;
}
static int64_t number_field(const char *json, const char *key) {
    const char *at = strstr(json, key);
    long long value = -1;
    if (at) (void)sscanf(at + strlen(key), ":%lld", &value);
    return (int64_t)value;
}
int64_t abla_mobile_run(void) {
    char value[64] = {0}, plain_value[64] = {0}, tree[4096];
    const char *status = "ready";
    int64_t revision = 0;
    int64_t entry = 1;
    int ignore = 0;
    int secure_phase = 0;
    int64_t secure_request = 0;
    int http_phase = 0;
    int64_t http_request = 0;
    int64_t picker_request = 0;
    if (abla_mobile_http_request("TIMER", "100", "") <= 0) return 0;
    for (;;) {
        revision++;
        const int size = snprintf(tree, sizeof(tree),
            "{\"protocol\":1,\"revision\":%lld,\"darkTheme\":false,\"title\":\"Host fixture\",\"root\":"
            "{\"type\":\"one.column\",\"key\":\"root\",\"properties\":{\"a.one.entry\":\"%lld\"},\"children\":["
            "{\"type\":\"one.input\",\"key\":\"secure\",\"properties\":{\"a.secure\":\"true\",\"a.value\":\"%s\",\"a.placeholder\":\"Fixture password\",\"a.onChange\":\"4\"},\"children\":[]},"
            "{\"type\":\"one.input\",\"key\":\"ordinary\",\"properties\":{\"a.value\":\"%s\",\"a.placeholder\":\"Public fixture text\",\"a.onChange\":\"22\"},\"children\":[]},"
            "{\"type\":\"one.pressable\",\"key\":\"accept\",\"properties\":{\"a.onPress\":\"7\",\"a.text\":\"Accept\"},\"children\":[]},"
            "{\"type\":\"one.pressable\",\"key\":\"blocked\",\"properties\":{\"a.onPress\":\"9\",\"a.text\":\"Blocked\",\"a.enabled\":\"false\"},\"children\":[]},"
            "{\"type\":\"one.pressable\",\"key\":\"ignore\",\"properties\":{\"a.onPress\":\"10\",\"a.text\":\"Ignore echoes\"},\"children\":[]},"
            "{\"type\":\"one.pressable\",\"key\":\"route\",\"properties\":{\"a.onPress\":\"11\",\"a.text\":\"New route\"},\"children\":[]},"
            "{\"type\":\"one.pressable\",\"key\":\"nfc\",\"properties\":{\"a.onPress\":\"15\",\"a.text\":\"Set NFC\"},\"children\":[]},"
            "{\"type\":\"one.pressable\",\"key\":\"nfd\",\"properties\":{\"a.onPress\":\"16\",\"a.text\":\"Set NFD\"},\"children\":[]},"
            "{\"type\":\"one.pressable\",\"key\":\"keychain\",\"properties\":{\"a.onPress\":\"17\",\"a.text\":\"Check Keychain\"},\"children\":[]},"
            "{\"type\":\"one.pressable\",\"key\":\"keychain-refusal\",\"properties\":{\"a.onPress\":\"21\",\"a.text\":\"Check unavailable storage\"},\"children\":[]},"
            "{\"type\":\"one.pressable\",\"key\":\"https\",\"properties\":{\"a.onPress\":\"18\",\"a.text\":\"Check HTTPS\"},\"children\":[]},"
            "{\"type\":\"one.pressable\",\"key\":\"pick\",\"properties\":{\"a.onPress\":\"19\",\"a.text\":\"Pick any file\"},\"children\":[]},"
            "{\"type\":\"one.text\",\"key\":\"status\",\"properties\":{\"a.text\":\"%s\"},\"children\":[]}]}}",
            (long long)revision, (long long)entry, value, plain_value, status);
        if (size <= 0 || size >= (int)sizeof(tree) || !publish(tree)) return 0;
        const int64_t event = abla_mobile_host_call(3);
        if (event == 0) return 0;
        const int64_t from = abla_mobile_host_call(4);
        const int64_t bytes = abla_mobile_host_call(5);
        char payload[1024] = {0};
        for (int64_t index = 0; index < bytes; index++) {
            const int64_t byte = abla_mobile_host_call(6);
            if (index < 1023 && byte >= 0 && byte <= 255) payload[index] = (char)byte;
        }
        if (from <= 0 || from > revision) continue;
        if (event == -2 && !secure_phase && !http_phase) status = "timer_ready";
        if (event == -2 && picker_request && number_field(payload, "\"requestId\"") == picker_request) {
            status = number_field(payload, "\"status\"") == 200 && strstr(payload, "\"body\":\"[]\"")
                ? "picker_cancel_passed" : "picker_refused";
            picker_request = 0;
        }
        if (event == -2 && http_phase && number_field(payload, "\"requestId\"") == http_request) {
            const int64_t code = number_field(payload, "\"status\"");
            const int unknown = strstr(payload, "network_failed_outcome_unknown") != NULL;
            if (http_phase == 1 && getenv("ABLA_HOST_TEST_UNTRUSTED")) {
                status = code == 0 && unknown ? "https_untrusted_refused" : "https_untrusted_accepted"; http_phase = 0;
            } else if (http_phase == 1 && code == 200 && strstr(payload, "fixture-ok")) {
                http_phase = 2; http_request = abla_mobile_http_request("GET", "https://localhost:19443/redirect", "");
            } else if (http_phase == 2 && code == 302) {
                http_phase = 3; http_request = abla_mobile_http_request("POST", "https://localhost:19443/lost", "synthetic-fixture-body");
            } else if (http_phase == 3 && code == 0 && unknown) {
                status = "https_system_trust_roundtrip_passed"; http_phase = 0;
            } else { status = "https_contract_failed"; http_phase = 0; }
        }
        if (event == -2 && secure_phase && number_field(payload, "\"requestId\"") == secure_request) {
            const int64_t code = number_field(payload, "\"status\"");
            if ((secure_phase == 1 || secure_phase == 2 || secure_phase == 4) && code != 204) {
                status = strstr(payload, "secure_entitlement_missing") ? "keychain_needs_entitlement" : "keychain_refused";
                secure_phase = 0;
            } else if (secure_phase == 3 && (code != 200 || !strstr(payload, "\"body\":\"fixture-only-value\""))) {
                status = "keychain_wrong_value"; secure_phase = 0;
            } else if (secure_phase == 5) {
                status = code == 404 ? "keychain_roundtrip_passed" : "keychain_delete_failed";
                secure_phase = 0;
            } else {
                secure_phase++;
                if (secure_phase == 2) secure_request = abla_mobile_http_request("STORESET", "host-smoke-isolated-key", "fixture-only-value");
                if (secure_phase == 3 || secure_phase == 5) secure_request = abla_mobile_http_request("STOREGET", "host-smoke-isolated-key", "");
                if (secure_phase == 4) secure_request = abla_mobile_http_request("STOREDEL", "host-smoke-isolated-key", "");
                if (secure_request <= 0) { secure_phase = 0; status = "keychain_queue_refused"; }
            }
        }
        if ((event == 4 || event == 22) && bytes < 64) {
            int safe = 1;
            for (int64_t i = 0; i < bytes; i++) if ((unsigned char)payload[i] < 32 || payload[i] == '"' || payload[i] == '\\') safe = 0;
            if (safe && (event == 22 || !ignore)) memcpy(event == 22 ? plain_value : value, payload, sizeof(value));
            status = "editing";
        }
        if (event == 7) {
            status = strcmp(value, "s3crEt") == 0 ? "accepted_exact" : "refused";
            memset(value, 0, sizeof(value));
        }
        if (event == 9) status = "disabled_button_sent";
        if (event == 10) { ignore = 1; memset(value, 0, sizeof(value)); status = "ignore_mode"; }
        if (event == 11) { entry++; ignore = 0; memset(value, 0, sizeof(value)); status = "new_route"; }
        if (event == 15) { strcpy(value, "\xc3\xa9"); strcpy(plain_value, "\xc3\xa9"); status = "unicode_nfc"; }
        if (event == 16) { strcpy(value, "e\xcc\x81"); strcpy(plain_value, "e\xcc\x81"); status = "unicode_nfd"; }
        if (event == 17 && !secure_phase) {
            secure_phase = 1; status = "keychain_in_progress";
            secure_request = abla_mobile_http_request("STOREDEL", "host-smoke-isolated-key", "");
            if (secure_request <= 0) { secure_phase = 0; status = "keychain_queue_refused"; }
        }
        if (event == 21 && !secure_phase) {
            secure_phase = 2; status = "keychain_in_progress";
            secure_request = abla_mobile_http_request("STORESET", "host-smoke-isolated-key", "fixture-only-value");
            if (secure_request <= 0) { secure_phase = 0; status = "keychain_queue_refused"; }
        }
        if (event == 18 && !http_phase) {
            http_phase = 1; status = "https_in_progress";
            http_request = abla_mobile_http_request("GET", "https://localhost:19443/ok", "");
            if (http_request <= 0) { http_phase = 0; status = "https_queue_refused"; }
        }
        if (event == 19 && !picker_request) {
            status = "picker_in_progress";
            picker_request = abla_mobile_http_request("PICKFILE", "{\"mime\":[],\"multiple\":false}", "");
            if (picker_request <= 0) status = "picker_queue_refused";
        }
        memset(payload, 0, sizeof(payload));
    }
}
