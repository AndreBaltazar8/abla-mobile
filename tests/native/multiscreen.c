#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef int64_t (*AblaMobileCallback)(void *context, int64_t value);

extern int64_t abla_mobile_platform_attach(
    AblaMobileCallback callback,
    void *context
);
extern int64_t abla_mobile_run(void);

typedef struct NavigationEvent {
    int64_t identifier;
    const char *payload;
} NavigationEvent;

typedef struct NavigationHost {
    char *tree;
    size_t tree_size;
    size_t tree_capacity;
    size_t event_index;
    size_t payload_offset;
    const char *payload;
    int published;
} NavigationHost;

static const NavigationEvent navigation_events[] = {
    {2, ""},
    {4, ""},
    {5, ""},
    {6, "Navigator"},
    {7, "true"},
    {8, ""},
    {0, ""},
};

static int64_t navigation_callback(void *opaque, int64_t value) {
    NavigationHost *host = opaque;
    if (value == 1) {
        host->tree_size = 0;
        return 0;
    }
    if (value >= 256 && value <= 511) {
        if (host->tree_size + 1 >= host->tree_capacity) {
            const size_t capacity = host->tree_capacity == 0
                ? 4096
                : host->tree_capacity * 2;
            char *next = realloc(host->tree, capacity);
            if (next == NULL) return -1;
            host->tree = next;
            host->tree_capacity = capacity;
        }
        host->tree[host->tree_size++] = (char)(value - 256);
        host->tree[host->tree_size] = '\0';
        return 0;
    }
    if (value == 2) {
        host->published += 1;
        return 0;
    }
    if (value == 3) {
        if (host->event_index >=
            sizeof(navigation_events) / sizeof(navigation_events[0])) return 0;
        const NavigationEvent event = navigation_events[host->event_index++];
        host->payload = event.payload;
        host->payload_offset = 0;
        return event.identifier;
    }
    if (value == 4) return host->published;
    if (value == 5) return (int64_t)strlen(host->payload);
    if (value == 6) {
        return (unsigned char)host->payload[host->payload_offset++];
    }
    return 0;
}

static int contains(const NavigationHost *host, const char *text) {
    return host->tree != NULL && strstr(host->tree, text) != NULL;
}

int main(void) {
    NavigationHost host = {0};
    host.payload = "";
    if (abla_mobile_platform_attach(navigation_callback, &host) != 1) {
        fputs("could not attach navigation proof host\n", stderr);
        return 1;
    }

    const int64_t result = abla_mobile_run();
    const int valid = result == 0 && host.published == 7 &&
        contains(&host, "\"title\":\"Journeys\"") &&
        contains(&host, "\"darkTheme\":true") &&
        contains(&host, "Hello, Navigator") &&
        contains(&host, "Preferences saved") &&
        contains(&host, "Open journey") &&
        !contains(&host, "Visited 2 time(s)") &&
        !contains(&host, "Display name");

    if (!valid) {
        fprintf(
            stderr,
            "navigation proof failed: result=%lld renders=%d tree=%s\n",
            (long long)result,
            host.published,
            host.tree == NULL ? "(null)" : host.tree
        );
        free(host.tree);
        return 1;
    }
    printf(
        "multi-screen proof: %d renders across three Abla-owned screens\n",
        host.published
    );
    free(host.tree);
    return 0;
}
