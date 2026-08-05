#include "abla_mobile_core_rename.h"
#include "abla_runtime.h"

#define MOBILE_VALUE0(name) \
    void abla_mobile_value_##name(AblaValue *out) { \
        *out = abla_mobile_core_##name(); \
    }
#define MOBILE_VALUE1(name) \
    void abla_mobile_value_##name(AblaValue *out, const AblaValue *a) { \
        *out = abla_mobile_core_##name(*a); \
    }
#define MOBILE_VALUE2(name) \
    void abla_mobile_value_##name(AblaValue *out, const AblaValue *a, \
                                  const AblaValue *b) { \
        *out = abla_mobile_core_##name(*a, *b); \
    }

MOBILE_VALUE0(void)
MOBILE_VALUE0(null)
MOBILE_VALUE1(negate)
MOBILE_VALUE1(not)
MOBILE_VALUE1(cell_create)
MOBILE_VALUE1(cell_get)
MOBILE_VALUE1(shared_create)
MOBILE_VALUE1(shared_clone)
MOBILE_VALUE1(shared_get)
MOBILE_VALUE1(shared_lock)
MOBILE_VALUE1(shared_release)
MOBILE_VALUE1(weak_create)
MOBILE_VALUE1(weak_clone)
MOBILE_VALUE1(weak_upgrade)
MOBILE_VALUE1(weak_alive)
MOBILE_VALUE1(weak_release)
MOBILE_VALUE1(to_string)
MOBILE_VALUE1(length)
MOBILE_VALUE2(add)
MOBILE_VALUE2(subtract)
MOBILE_VALUE2(multiply)
MOBILE_VALUE2(divide)
MOBILE_VALUE2(equal)
MOBILE_VALUE2(not_equal)
MOBILE_VALUE2(less)
MOBILE_VALUE2(less_equal)
MOBILE_VALUE2(greater)
MOBILE_VALUE2(greater_equal)
MOBILE_VALUE2(cell_set)
MOBILE_VALUE2(string_concat)
MOBILE_VALUE2(index_get)
MOBILE_VALUE2(array_append)

void abla_mobile_value_string_slice(
    AblaValue *out,
    const AblaValue *value,
    const AblaValue *begin,
    const AblaValue *end
) {
    *out = abla_mobile_core_string_slice(*value, *begin, *end);
}

void abla_mobile_value_i64(AblaValue *out, int64_t value) {
    *out = abla_mobile_core_i64(value);
}

void abla_mobile_value_bool(AblaValue *out, bool value) {
    *out = abla_mobile_core_bool(value);
}

void abla_mobile_value_string_static(
    AblaValue *out,
    const char *data,
    size_t length
) {
    *out = abla_mobile_core_string_static(data, length);
}

void abla_mobile_value_function(AblaValue *out, uint32_t function) {
    *out = abla_mobile_core_function(function);
}

void abla_mobile_value_closure(
    AblaValue *out,
    uint32_t function,
    const AblaValue *captures,
    size_t capture_count
) {
    *out = abla_mobile_core_closure(function, captures, capture_count);
}

void abla_mobile_value_array_create(
    AblaValue *out,
    const AblaValue *values,
    size_t count
) {
    *out = abla_mobile_core_array_create(values, count);
}

void abla_mobile_value_object_create(AblaValue *out, uint32_t type_symbol) {
    *out = abla_mobile_core_object_create(type_symbol);
}

void abla_mobile_value_field_get(
    AblaValue *out,
    const AblaValue *object,
    uint32_t field_symbol
) {
    *out = abla_mobile_core_field_get(*object, field_symbol);
}

void abla_mobile_value_field_set(
    const AblaValue *object,
    uint32_t field_symbol,
    const AblaValue *value
) {
    abla_mobile_core_field_set(*object, field_symbol, *value);
}

void abla_mobile_value_array_set(
    const AblaValue *array,
    const AblaValue *index,
    const AblaValue *value
) {
    abla_mobile_core_array_set(*array, *index, *value);
}

int64_t abla_mobile_value_as_i64(const AblaValue *value) {
    return abla_mobile_core_as_i64(*value);
}

bool abla_mobile_value_as_bool(const AblaValue *value) {
    return abla_mobile_core_as_bool(*value);
}

const char *abla_mobile_value_as_cstring(const AblaValue *value) {
    return abla_mobile_core_as_cstring(*value);
}

uint32_t abla_mobile_value_as_function(const AblaValue *value) {
    return abla_mobile_core_as_function(*value);
}

size_t abla_mobile_value_function_capture_count(const AblaValue *value) {
    return abla_mobile_core_function_capture_count(*value);
}

AblaValue *abla_mobile_value_function_capture_pointer(
    const AblaValue *value
) {
    return abla_mobile_core_function_capture_pointer(*value);
}

void abla_mobile_value_function_capture(
    AblaValue *out,
    const AblaValue *value,
    size_t index
) {
    *out = abla_mobile_core_function_capture(*value, index);
}

void abla_mobile_value_shared_unlock(const AblaValue *value) {
    abla_mobile_core_shared_unlock(*value);
}

void abla_mobile_value_closure_release(const AblaValue *value) {
    abla_mobile_core_closure_release(*value);
}

void *abla_mobile_value_owned_bytes_from_value(const AblaValue *value) {
    return abla_mobile_core_owned_bytes_from_value(*value);
}
