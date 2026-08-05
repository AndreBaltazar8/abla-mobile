#include <stdint.h>
#include <stddef.h>
#include <string.h>

#define ABLA_MOBILE_EFFECT_LIMIT 64U
#define ABLA_MOBILE_EFFECT_PAYLOAD_LIMIT 4096U
#define ABLA_MOBILE_EFFECT_KIND_LIMIT 4
#define ABLA_MOBILE_EXPORT __attribute__((visibility("default")))

typedef struct AblaMobilePlatformEffect {
    int64_t kind;
    size_t size;
    unsigned char payload[ABLA_MOBILE_EFFECT_PAYLOAD_LIMIT];
} AblaMobilePlatformEffect;

static AblaMobilePlatformEffect effects[ABLA_MOBILE_EFFECT_LIMIT];
static size_t effect_head;
static size_t effect_size;

ABLA_MOBILE_EXPORT int64_t abla_mobile_platform_effect(
    int64_t kind,
    const char *payload
) {
    if (kind < 1 || kind > ABLA_MOBILE_EFFECT_KIND_LIMIT || payload == NULL ||
        effect_size >= ABLA_MOBILE_EFFECT_LIMIT) return 0;
    const size_t size = strnlen(
        payload,
        ABLA_MOBILE_EFFECT_PAYLOAD_LIMIT + 1U
    );
    if (size > ABLA_MOBILE_EFFECT_PAYLOAD_LIMIT) return 0;
    const size_t tail = (effect_head + effect_size) % ABLA_MOBILE_EFFECT_LIMIT;
    effects[tail].kind = kind;
    effects[tail].size = size;
    if (size > 0) memcpy(effects[tail].payload, payload, size);
    effect_size += 1U;
    return 1;
}

ABLA_MOBILE_EXPORT int64_t abla_mobile_platform_effect_next_kind(void) {
    return effect_size == 0 ? 0 : effects[effect_head].kind;
}

ABLA_MOBILE_EXPORT int64_t abla_mobile_platform_effect_next_size(void) {
    return effect_size == 0 ? 0 : (int64_t)effects[effect_head].size;
}

ABLA_MOBILE_EXPORT int64_t abla_mobile_platform_effect_next_byte(
    int64_t index
) {
    if (effect_size == 0 || index < 0 ||
        (uint64_t)index >= effects[effect_head].size) return -1;
    return effects[effect_head].payload[index];
}

ABLA_MOBILE_EXPORT void abla_mobile_platform_effect_pop(void) {
    if (effect_size == 0) return;
    effect_head = (effect_head + 1U) % ABLA_MOBILE_EFFECT_LIMIT;
    effect_size -= 1U;
}
