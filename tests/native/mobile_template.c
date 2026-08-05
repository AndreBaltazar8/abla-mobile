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

typedef struct TemplateEvent {
    int64_t identifier;
    const char *payload;
} TemplateEvent;

typedef struct TemplateHost {
    char *tree;
    size_t tree_size;
    size_t tree_capacity;
    size_t event_index;
    size_t payload_offset;
    const char *payload;
    int published;
} TemplateHost;

static const TemplateEvent template_events[] = {
    {1, ""},
    {2, "Ada Lovelace"},
    {3, "false"},
    {4, "true"},
    {5, "80"},
    {6, "selection"},
    {7, ""},
    {0, ""},
};

static int64_t template_callback(void *opaque, int64_t value) {
    TemplateHost *host = opaque;
    if (value == 1) {
        host->tree_size = 0;
        return 0;
    }
    if (value >= 256 && value <= 511) {
        if (host->tree_size + 1 >= host->tree_capacity) {
            size_t capacity = host->tree_capacity == 0
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
            sizeof(template_events) / sizeof(template_events[0])) return 0;
        const TemplateEvent event = template_events[host->event_index++];
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

static int contains(const TemplateHost *host, const char *text) {
    return host->tree != NULL && strstr(host->tree, text) != NULL;
}

int main(void) {
    TemplateHost host = {0};
    host.payload = "";
    if (abla_mobile_platform_attach(template_callback, &host) != 1) {
        fputs("could not attach template proof host\n", stderr);
        return 1;
    }

    const int64_t result = abla_mobile_run();
    const int valid = result == 0 && host.published == 8 &&
        contains(&host, "\"title\":\"Template Ada Lovelace\"") &&
        contains(&host, "\"darkTheme\":true") &&
        contains(&host, "\"text\":\"Hello Ada Lovelace\"") &&
        contains(&host, "\"text\":\"Count: 2\"") &&
        contains(&host, "\"text\":\"Incremented\"") &&
        contains(&host, "\"value\":\"Ada Lovelace\"") &&
        contains(&host, "\"checked\":false") &&
        contains(&host, "\"checked\":true") &&
        contains(&host, "\"value\":80") &&
        contains(&host, "\"selected\":true") &&
        contains(&host, "Selected state is visible") &&
        contains(&host, "Dynamic child group") &&
        !contains(&host, "Conditional detail");

    if (!valid) {
        fprintf(
            stderr,
            "mobile template proof failed: result=%lld renders=%d tree=%s\n",
            (long long)result,
            host.published,
            host.tree == NULL ? "(null)" : host.tree
        );
        free(host.tree);
        return 1;
    }
    printf(
        "mobile template proof: %d reactive renders, %zu-byte final tree\n",
        host.published,
        host.tree_size
    );
    free(host.tree);
    return 0;
}
