#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef int64_t (*AblaMobileCallback)(void* context, int64_t value);
extern int64_t abla_mobile_platform_attach(
    AblaMobileCallback callback,
    void* context
);
extern int64_t abla_mobile_run(void);

typedef struct ShowcaseHost {
    char* tree;
    size_t size;
    size_t capacity;
    int published;
} ShowcaseHost;

static int64_t showcase_callback(void* opaque, int64_t value) {
    ShowcaseHost* host = opaque;
    if (value == 1) {
        host->size = 0;
        return 0;
    }
    if (value >= 256 && value <= 511) {
        if (host->size + 1 >= host->capacity) {
            size_t capacity = host->capacity == 0 ? 4096 : host->capacity * 2;
            char* next = realloc(host->tree, capacity);
            if (next == NULL) return -1;
            host->tree = next;
            host->capacity = capacity;
        }
        host->tree[host->size++] = (char)(value - 256);
        host->tree[host->size] = '\0';
        return 0;
    }
    if (value == 2) {
        host->published += 1;
        return 0;
    }
    if (value == 3) return 0;
    return 0;
}

int main(void) {
    ShowcaseHost host = {0};
    if (abla_mobile_platform_attach(showcase_callback, &host) != 1) return 1;
    const int64_t result = abla_mobile_run();
    const int valid = result == 0 && host.published == 1 &&
        host.tree != NULL &&
        strstr(host.tree, "Abla Mobile Showcase") != NULL &&
        strstr(host.tree, "Reactive counter") != NULL &&
        strstr(host.tree, "textField") != NULL &&
        strstr(host.tree, "slider") != NULL;
    if (!valid) {
        fprintf(stderr, "showcase host proof failed: result=%lld renders=%d\n",
            (long long)result, host.published);
        free(host.tree);
        return 1;
    }
    printf("showcase host proof: %zu-byte semantic tree\n", host.size);
    free(host.tree);
    return 0;
}
