#include <stdint.h>
#include <stdio.h>
#include <string.h>

typedef int64_t (*abla_mobile_callback)(void *context, int64_t value);

extern int64_t abla_mobile_run(
    abla_mobile_callback callback,
    void *context
);

typedef struct MobileProofHost {
    char tree[1024];
    size_t tree_size;
    int64_t revision;
    int event_index;
    int published;
} MobileProofHost;

static int64_t mobile_host_callback(void *opaque, int64_t value) {
    MobileProofHost *host = (MobileProofHost *)opaque;
    if (value == 1) {
        host->tree_size = 0;
        memset(host->tree, 0, sizeof(host->tree));
        return 0;
    }
    if (value >= 256 && value <= 511) {
        if (host->tree_size + 1 < sizeof(host->tree)) {
            host->tree[host->tree_size++] = (char)(value - 256);
        }
        return 0;
    }
    if (value == 2) {
        host->published += 1;
        host->revision = host->published;
        return 0;
    }
    if (value == 3) {
        const int64_t events[] = {1, 1, 0};
        return events[host->event_index++];
    }
    if (value == 4) return host->revision;
    return 0;
}

int main(void) {
    MobileProofHost host = {0};
    const int64_t result = abla_mobile_run(mobile_host_callback, &host);
    const int valid = result == 42 && host.published == 3 &&
        strstr(host.tree, "\"count\":2") != NULL &&
        strstr(host.tree, "\"status\":\"incremented\"") != NULL;
    if (!valid) {
        fprintf(
            stderr,
            "mobile loop proof failed: result=%lld renders=%d tree=%s\n",
            (long long)result,
            host.published,
            host.tree
        );
        return 1;
    }
    puts("mobile loop proof: 3 renders, 2 events, no app handle");
    return 0;
}

