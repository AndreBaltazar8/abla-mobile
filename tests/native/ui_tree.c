#include <stdint.h>
#include <stdio.h>
#include <string.h>

extern void *abla_mobile_test_tree(void);
extern const uint8_t *abla_owned_bytes_data(void *handle);
extern uint64_t abla_owned_bytes_length(void *handle);
extern void abla_owned_bytes_release(void *handle);

int main(void) {
    void *tree = abla_mobile_test_tree();
    if (tree == NULL) return 1;
    const uint8_t *data = abla_owned_bytes_data(tree);
    const uint64_t size = abla_owned_bytes_length(tree);
    const int valid = size > 0 &&
        strstr((const char *)data, "\"protocol\":1") != NULL &&
        strstr((const char *)data, "\"type\":\"textField\"") != NULL &&
        strstr((const char *)data, "\"type\":\"slider\"") != NULL;
    if (!valid) fprintf(stderr, "invalid UI tree: %.*s\n", (int)size, data);
    abla_owned_bytes_release(tree);
    return valid ? 0 : 1;
}

