#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>
extern int64_t abla_mobile_platform_effect(int64_t, const char *);
extern int64_t abla_mobile_platform_effect_next_kind(void);
extern int64_t abla_mobile_platform_effect_next_size(void);
extern int64_t abla_mobile_platform_effect_next_byte(int64_t);
extern void abla_mobile_platform_effect_pop(void);
int main(void) {
    const char *payload = "{\"handle\":\"file-test\",\"name\":\"contabilidade.xml\",\"mime\":\"application/xml\"}";
    assert(abla_mobile_platform_effect(8, payload) == 1);
    assert(abla_mobile_platform_effect(9, payload) == 1);
    for (int kind=8; kind<=9; kind++) {
        assert(abla_mobile_platform_effect_next_kind() == kind);
        assert(abla_mobile_platform_effect_next_size() == (int64_t)strlen(payload));
        for (size_t i=0; i<strlen(payload); i++) assert(abla_mobile_platform_effect_next_byte((int64_t)i) == payload[i]);
        abla_mobile_platform_effect_pop();
    }
    assert(abla_mobile_platform_effect_next_kind() == 0);
    assert(abla_mobile_platform_effect(10, payload) == 0);
    char oversized[4098]; memset(oversized,'x',sizeof oversized); oversized[4097]=0;
    assert(abla_mobile_platform_effect(8, oversized) == 0);
    assert(abla_mobile_platform_effect(8, NULL) == 0);
    puts("file export effects: save/share payloads copied intact; unsupported/oversized/null rejected");
    return 0;
}
