#include "abla_mobile_core_rename.h"
#include "abla_runtime.h"

#include <android/log.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* Android allocation, collection, root tracking, and panic policy owned by
 * Abla Mobile and shared by application packaging templates. */

typedef union AblaAndroidAllocation AblaAndroidAllocation;
union AblaAndroidAllocation {
    struct {
        AblaAndroidAllocation* previous;
        AblaAndroidAllocation* next;
        uint64_t generation;
        size_t size;
        size_t scan_size;
        uint8_t layout;
        void* cache_owner;
    } allocation;
    max_align_t alignment;
};

static AblaAndroidAllocation* allocation_head;
static AblaAndroidAllocation* allocation_tail;
static uint64_t allocation_generation;
static size_t allocation_live_bytes;
static size_t allocation_limit = (size_t)256 * 1024 * 1024;
static size_t collection_threshold = (size_t)1024 * 1024;
static _Thread_local AblaRuntimeRootFrame* root_frame;
static AblaAndroidAllocation** collection_index;
static size_t collection_index_count;
static AblaAndroidAllocation** mark_worklist;
static size_t mark_worklist_count;

_Noreturn void abla_mobile_raise_panic(const char* message, size_t length);

void* abla_platform_alloc(size_t size) {
    const size_t measured = size == 0 ? 1 : size;
    if (measured > SIZE_MAX - sizeof(AblaAndroidAllocation) ||
        measured > allocation_limit - allocation_live_bytes ||
        allocation_generation >= (uint64_t)INT64_MAX) {
        abla_platform_panic("mobile memory limit exceeded", 28);
    }
    AblaAndroidAllocation* header = malloc(sizeof(*header) + measured);
    if (header == NULL) abla_platform_panic("out of memory", 13);
    header->allocation.previous = allocation_tail;
    header->allocation.next = NULL;
    header->allocation.generation = ++allocation_generation;
    header->allocation.size = measured;
    header->allocation.scan_size = measured;
    header->allocation.layout = 1;
    header->allocation.cache_owner = NULL;
    if (allocation_tail == NULL) allocation_head = header;
    else allocation_tail->allocation.next = header;
    allocation_tail = header;
    allocation_live_bytes += measured;
    void* result = header + 1;
    memset(result, 0, measured);
    return result;
}

void abla_platform_free(void* pointer) {
    if (pointer == NULL) return;
    AblaAndroidAllocation* header = ((AblaAndroidAllocation*)pointer) - 1;
    if (header->allocation.previous == NULL) {
        allocation_head = header->allocation.next;
    } else {
        header->allocation.previous->allocation.next = header->allocation.next;
    }
    if (header->allocation.next == NULL) {
        allocation_tail = header->allocation.previous;
    } else {
        header->allocation.next->allocation.previous = header->allocation.previous;
    }
    allocation_live_bytes -= header->allocation.size;
    free(header);
}

_Noreturn void abla_platform_panic(const char* message, size_t length) {
    __android_log_print(
        ANDROID_LOG_FATAL,
        "AblaMobile",
        "Abla panic: %.*s",
        (int)(length > (size_t)INT_MAX ? INT_MAX : length),
        message
    );
    abla_mobile_raise_panic(message, length);
}

int64_t abla_platform_memory_checkpoint(void) {
    return (int64_t)allocation_generation;
}

void abla_platform_memory_reset(int64_t checkpoint) {
    if (checkpoint < 0 || (uint64_t)checkpoint > allocation_generation) {
        abla_platform_panic("invalid memory checkpoint", 25);
    }
    while (allocation_tail != NULL &&
           allocation_tail->allocation.generation > (uint64_t)checkpoint) {
        abla_platform_free(allocation_tail + 1);
    }
}

int64_t abla_platform_memory_live_bytes(void) {
    return (int64_t)allocation_live_bytes;
}

int64_t abla_platform_memory_limit(void) {
    return (int64_t)allocation_limit;
}

void abla_platform_memory_set_limit(int64_t limit) {
    if (limit < 0 || (uint64_t)limit < allocation_live_bytes) {
        abla_platform_panic("invalid memory limit", 20);
    }
    allocation_limit = (size_t)limit;
}

void abla_platform_memory_set_scan(void* pointer, int64_t scan_size) {
    if (pointer == NULL || scan_size < 0) {
        abla_platform_panic("invalid memory scan size", 24);
    }
    AblaAndroidAllocation* header = ((AblaAndroidAllocation*)pointer) - 1;
    if ((uint64_t)scan_size > header->allocation.size) {
        abla_platform_panic("invalid memory scan size", 24);
    }
    header->allocation.scan_size = (size_t)scan_size;
}

void abla_platform_memory_set_layout(void* pointer, int64_t layout) {
    if (pointer == NULL || layout < 0 || layout > 12) {
        abla_platform_panic("invalid memory scan layout", 26);
    }
    (((AblaAndroidAllocation*)pointer) - 1)->allocation.layout = (uint8_t)layout;
}

void abla_platform_memory_set_cache_owner(void* pointer, void* owner) {
    if (pointer == NULL || owner == NULL) {
        abla_platform_panic("invalid cache owner", 19);
    }
    (((AblaAndroidAllocation*)pointer) - 1)->allocation.cache_owner = owner;
}

static int compare_allocation_payloads(const void* left, const void* right) {
    const uintptr_t left_payload =
        (uintptr_t)(*(AblaAndroidAllocation* const*)left + 1);
    const uintptr_t right_payload =
        (uintptr_t)(*(AblaAndroidAllocation* const*)right + 1);
    return left_payload < right_payload ? -1 : left_payload > right_payload;
}

static bool mark_pointer(uintptr_t candidate) {
    size_t begin = 0;
    size_t end = collection_index_count;
    while (begin < end) {
        const size_t middle = begin + (end - begin) / 2;
        const uintptr_t payload = (uintptr_t)(collection_index[middle] + 1);
        if (payload < candidate) begin = middle + 1;
        else end = middle;
    }
    if (begin >= collection_index_count ||
        (uintptr_t)(collection_index[begin] + 1) != candidate) return false;
    AblaAndroidAllocation* header = collection_index[begin];
    if ((header->allocation.generation >> 63) != 0) return false;
    header->allocation.generation |= UINT64_C(1) << 63;
    if (mark_worklist_count >= collection_index_count) {
        abla_platform_panic("collection worklist overflow", 28);
    }
    mark_worklist[mark_worklist_count++] = header;
    return true;
}

static void mark_words(const void* bytes, size_t size) {
    size_t offset = 0;
    while (offset <= size && sizeof(uintptr_t) <= size - offset) {
        uintptr_t candidate = 0;
        memcpy(&candidate, (const uint8_t*)bytes + offset, sizeof(candidate));
        (void)mark_pointer(candidate);
        offset += sizeof(uintptr_t);
    }
}

static void mark_root_frames(AblaRuntimeRootFrame* frame) {
    while (frame != NULL) {
        for (uint64_t index = 0; index < frame->count; ++index) {
            if (frame->roots[index] != NULL) {
                mark_words(frame->roots[index], sizeof(AblaValue));
            }
        }
        frame = frame->previous;
    }
}

int64_t abla_platform_memory_collect(void* frames) {
    const size_t before = allocation_live_bytes;
    size_t count = 0;
    for (AblaAndroidAllocation* header = allocation_head;
         header != NULL; header = header->allocation.next) ++count;
    if (count > SIZE_MAX / sizeof(*collection_index)) {
        abla_platform_panic("collection index overflow", 25);
    }

    collection_index = count == 0 ? NULL : malloc(count * sizeof(*collection_index));
    mark_worklist = count == 0 ? NULL : malloc(count * sizeof(*mark_worklist));
    if (count != 0 && (collection_index == NULL || mark_worklist == NULL)) {
        free(collection_index);
        free(mark_worklist);
        collection_index = NULL;
        mark_worklist = NULL;
        abla_platform_panic("out of memory", 13);
    }
    collection_index_count = count;
    mark_worklist_count = 0;
    size_t index = 0;
    for (AblaAndroidAllocation* header = allocation_head;
         header != NULL; header = header->allocation.next) {
        collection_index[index++] = header;
    }
    if (count > 1) {
        qsort(collection_index, count, sizeof(*collection_index),
            compare_allocation_payloads);
    }

    mark_root_frames(frames == NULL ? root_frame : frames);
    while (mark_worklist_count != 0) {
        const AblaAndroidAllocation* marked =
            mark_worklist[--mark_worklist_count];
        if (marked->allocation.layout != 1 &&
            marked->allocation.layout != 10) {
            mark_words(marked + 1, marked->allocation.scan_size);
        }
    }

    AblaAndroidAllocation* header = allocation_tail;
    while (header != NULL) {
        AblaAndroidAllocation* previous = header->allocation.previous;
        if ((header->allocation.generation >> 63) != 0 ||
            header->allocation.layout == 10) {
            header->allocation.generation &= INT64_MAX;
        } else {
            abla_platform_free(header + 1);
        }
        header = previous;
    }

    free(collection_index);
    free(mark_worklist);
    collection_index = NULL;
    mark_worklist = NULL;
    collection_index_count = 0;
    const size_t freed = before - allocation_live_bytes;
    if (freed > (size_t)INT64_MAX) {
        abla_platform_panic("collection size overflow", 24);
    }
#ifndef NDEBUG
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "AblaMobile",
        "Abla GC freed %zu bytes; %zu remain",
        freed,
        allocation_live_bytes
    );
#endif
    return (int64_t)freed;
}

void abla_runtime_roots_push(
    AblaRuntimeRootFrame* frame,
    void** roots,
    uint64_t count
) {
    frame->previous = root_frame;
    frame->roots = roots;
    frame->count = count;
    root_frame = frame;
}

void abla_runtime_roots_pop(AblaRuntimeRootFrame* frame) {
    if (root_frame != frame) abla_platform_panic("unbalanced root frame", 21);
    root_frame = frame->previous;
}

void abla_runtime_memory_pressure(void) {
    if (allocation_live_bytes >= collection_threshold) {
        (void)abla_platform_memory_collect(root_frame);
        const size_t live = allocation_live_bytes;
        const size_t growth = live / 2 > (size_t)1024 * 1024
            ? live / 2
            : (size_t)1024 * 1024;
        const size_t latest = growth >= allocation_limit
            ? 0
            : allocation_limit - growth;
        collection_threshold = live > latest
            ? allocation_limit
            : live + growth;
    }
}

void abla_mobile_memory_checkpoint(AblaValue* out) {
    *out = abla_i64(abla_platform_memory_checkpoint());
}

void abla_mobile_memory_reset(
    AblaValue* out,
    const AblaValue* checkpoint
) {
    abla_platform_memory_reset(abla_as_i64(*checkpoint));
    *out = abla_void();
}

void abla_mobile_memory_live_bytes(AblaValue* out) {
    *out = abla_i64(abla_platform_memory_live_bytes());
}

void abla_mobile_memory_limit(AblaValue* out) {
    *out = abla_i64(abla_platform_memory_limit());
}

void abla_mobile_memory_set_limit(
    AblaValue* out,
    const AblaValue* limit
) {
    abla_platform_memory_set_limit(abla_as_i64(*limit));
    *out = abla_void();
}

void abla_mobile_memory_collect(AblaValue* out) {
    *out = abla_i64(abla_platform_memory_collect(root_frame));
}
