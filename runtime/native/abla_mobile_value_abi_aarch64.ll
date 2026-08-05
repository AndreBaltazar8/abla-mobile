; The Abla LLVM backend's internal value calls carry sret/byval attributes.
; This target-side definition matches that existing ABI exactly, then crosses
; into Abla Mobile's ordinary pointer-only C bridge. It lets the mobile runtime
; remain in this repository without changing the compiler or its C runtime.

target triple = "aarch64-linux-android24"

%AblaValue = type { i32, %AblaPayload }
%AblaPayload = type { i64, i64, i64, i64 }

declare void @abla_mobile_value_void(ptr)
declare void @abla_mobile_value_null(ptr)
declare void @abla_mobile_value_negate(ptr, ptr)
declare void @abla_mobile_value_not(ptr, ptr)
declare void @abla_mobile_value_cell_create(ptr, ptr)
declare void @abla_mobile_value_cell_get(ptr, ptr)
declare void @abla_mobile_value_shared_create(ptr, ptr)
declare void @abla_mobile_value_shared_clone(ptr, ptr)
declare void @abla_mobile_value_shared_get(ptr, ptr)
declare void @abla_mobile_value_shared_lock(ptr, ptr)
declare void @abla_mobile_value_shared_release(ptr, ptr)
declare void @abla_mobile_value_weak_create(ptr, ptr)
declare void @abla_mobile_value_weak_clone(ptr, ptr)
declare void @abla_mobile_value_weak_upgrade(ptr, ptr)
declare void @abla_mobile_value_weak_alive(ptr, ptr)
declare void @abla_mobile_value_weak_release(ptr, ptr)
declare void @abla_mobile_value_to_string(ptr, ptr)
declare void @abla_mobile_value_length(ptr, ptr)
declare void @abla_mobile_value_add(ptr, ptr, ptr)
declare void @abla_mobile_value_subtract(ptr, ptr, ptr)
declare void @abla_mobile_value_multiply(ptr, ptr, ptr)
declare void @abla_mobile_value_divide(ptr, ptr, ptr)
declare void @abla_mobile_value_equal(ptr, ptr, ptr)
declare void @abla_mobile_value_not_equal(ptr, ptr, ptr)
declare void @abla_mobile_value_less(ptr, ptr, ptr)
declare void @abla_mobile_value_less_equal(ptr, ptr, ptr)
declare void @abla_mobile_value_greater(ptr, ptr, ptr)
declare void @abla_mobile_value_greater_equal(ptr, ptr, ptr)
declare void @abla_mobile_value_cell_set(ptr, ptr, ptr)
declare void @abla_mobile_value_string_concat(ptr, ptr, ptr)
declare void @abla_mobile_value_index_get(ptr, ptr, ptr)
declare void @abla_mobile_value_array_append(ptr, ptr, ptr)
declare void @abla_mobile_value_string_slice(ptr, ptr, ptr, ptr)
declare void @abla_mobile_value_i64(ptr, i64)
declare void @abla_mobile_value_bool(ptr, i1)
declare void @abla_mobile_value_string_static(ptr, ptr, i64)
declare void @abla_mobile_value_function(ptr, i32)
declare void @abla_mobile_value_closure(ptr, i32, ptr, i64)
declare void @abla_mobile_value_array_create(ptr, ptr, i64)
declare void @abla_mobile_value_object_create(ptr, i32)
declare void @abla_mobile_value_field_get(ptr, ptr, i32)
declare void @abla_mobile_value_field_set(ptr, i32, ptr)
declare void @abla_mobile_value_array_set(ptr, ptr, ptr)
declare i64 @abla_mobile_value_as_i64(ptr)
declare i1 @abla_mobile_value_as_bool(ptr)
declare ptr @abla_mobile_value_as_cstring(ptr)
declare i32 @abla_mobile_value_as_function(ptr)
declare i64 @abla_mobile_value_function_capture_count(ptr)
declare ptr @abla_mobile_value_function_capture_pointer(ptr)
declare void @abla_mobile_value_function_capture(ptr, ptr, i64)
declare void @abla_mobile_value_shared_unlock(ptr)
declare void @abla_mobile_value_closure_release(ptr)
declare ptr @abla_mobile_value_owned_bytes_from_value(ptr)
declare void @abla_mobile_memory_checkpoint(ptr)
declare void @abla_mobile_memory_reset(ptr, ptr)
declare void @abla_mobile_memory_live_bytes(ptr)
declare void @abla_mobile_memory_limit(ptr)
declare void @abla_mobile_memory_set_limit(ptr, ptr)
declare void @abla_mobile_memory_collect(ptr)

define void @abla_void(ptr sret(%AblaValue) %out) {
  call void @abla_mobile_value_void(ptr %out)
  ret void
}

define void @abla_null(ptr sret(%AblaValue) %out) {
  call void @abla_mobile_value_null(ptr %out)
  ret void
}

define void @abla_negate(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_negate(ptr %out, ptr %a)
  ret void
}

define void @abla_not(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_not(ptr %out, ptr %a)
  ret void
}

define void @abla_cell_create(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_cell_create(ptr %out, ptr %a)
  ret void
}

define void @abla_cell_get(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_cell_get(ptr %out, ptr %a)
  ret void
}

define void @abla_shared_create(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_shared_create(ptr %out, ptr %a)
  ret void
}

define void @abla_shared_clone(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_shared_clone(ptr %out, ptr %a)
  ret void
}

define void @abla_shared_get(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_shared_get(ptr %out, ptr %a)
  ret void
}

define void @abla_shared_lock(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_shared_lock(ptr %out, ptr %a)
  ret void
}

define void @abla_shared_release(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_shared_release(ptr %out, ptr %a)
  ret void
}

define void @abla_weak_create(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_weak_create(ptr %out, ptr %a)
  ret void
}

define void @abla_weak_clone(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_weak_clone(ptr %out, ptr %a)
  ret void
}

define void @abla_weak_upgrade(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_weak_upgrade(ptr %out, ptr %a)
  ret void
}

define void @abla_weak_alive(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_weak_alive(ptr %out, ptr %a)
  ret void
}

define void @abla_weak_release(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_weak_release(ptr %out, ptr %a)
  ret void
}

define void @abla_to_string(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_to_string(ptr %out, ptr %a)
  ret void
}

define void @abla_length(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a) {
  call void @abla_mobile_value_length(ptr %out, ptr %a)
  ret void
}

define void @abla_add(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b) {
  call void @abla_mobile_value_add(ptr %out, ptr %a, ptr %b)
  ret void
}

define void @abla_subtract(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b) {
  call void @abla_mobile_value_subtract(ptr %out, ptr %a, ptr %b)
  ret void
}

define void @abla_multiply(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b) {
  call void @abla_mobile_value_multiply(ptr %out, ptr %a, ptr %b)
  ret void
}

define void @abla_divide(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b) {
  call void @abla_mobile_value_divide(ptr %out, ptr %a, ptr %b)
  ret void
}

define void @abla_equal(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b) {
  call void @abla_mobile_value_equal(ptr %out, ptr %a, ptr %b)
  ret void
}

define void @abla_not_equal(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b) {
  call void @abla_mobile_value_not_equal(ptr %out, ptr %a, ptr %b)
  ret void
}

define void @abla_less(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b) {
  call void @abla_mobile_value_less(ptr %out, ptr %a, ptr %b)
  ret void
}

define void @abla_less_equal(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b) {
  call void @abla_mobile_value_less_equal(ptr %out, ptr %a, ptr %b)
  ret void
}

define void @abla_greater(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b) {
  call void @abla_mobile_value_greater(ptr %out, ptr %a, ptr %b)
  ret void
}

define void @abla_greater_equal(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b) {
  call void @abla_mobile_value_greater_equal(ptr %out, ptr %a, ptr %b)
  ret void
}

define void @abla_cell_set(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b) {
  call void @abla_mobile_value_cell_set(ptr %out, ptr %a, ptr %b)
  ret void
}

define void @abla_string_concat(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b) {
  call void @abla_mobile_value_string_concat(ptr %out, ptr %a, ptr %b)
  ret void
}

define void @abla_index_get(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b) {
  call void @abla_mobile_value_index_get(ptr %out, ptr %a, ptr %b)
  ret void
}

define void @abla_array_append(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b) {
  call void @abla_mobile_value_array_append(ptr %out, ptr %a, ptr %b)
  ret void
}

define void @abla_string_slice(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %a, ptr byval(%AblaValue) %b, ptr byval(%AblaValue) %c) {
  call void @abla_mobile_value_string_slice(ptr %out, ptr %a, ptr %b, ptr %c)
  ret void
}

define void @abla_i64(ptr sret(%AblaValue) %out, i64 %value) {
  call void @abla_mobile_value_i64(ptr %out, i64 %value)
  ret void
}

define void @abla_bool(ptr sret(%AblaValue) %out, i1 %value) {
  call void @abla_mobile_value_bool(ptr %out, i1 %value)
  ret void
}

define void @abla_string_static(ptr sret(%AblaValue) %out, ptr %data, i64 %length) {
  call void @abla_mobile_value_string_static(ptr %out, ptr %data, i64 %length)
  ret void
}

define void @abla_function(ptr sret(%AblaValue) %out, i32 %function) {
  call void @abla_mobile_value_function(ptr %out, i32 %function)
  ret void
}

define void @abla_closure(ptr sret(%AblaValue) %out, i32 %function, ptr %captures, i64 %count) {
  call void @abla_mobile_value_closure(ptr %out, i32 %function, ptr %captures, i64 %count)
  ret void
}

define void @abla_array_create(ptr sret(%AblaValue) %out, ptr %values, i64 %count) {
  call void @abla_mobile_value_array_create(ptr %out, ptr %values, i64 %count)
  ret void
}

define void @abla_object_create(ptr sret(%AblaValue) %out, i32 %type) {
  call void @abla_mobile_value_object_create(ptr %out, i32 %type)
  ret void
}

define void @abla_field_get(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %object, i32 %field) {
  call void @abla_mobile_value_field_get(ptr %out, ptr %object, i32 %field)
  ret void
}

define void @abla_field_set(ptr byval(%AblaValue) %object, i32 %field, ptr byval(%AblaValue) %value) {
  call void @abla_mobile_value_field_set(ptr %object, i32 %field, ptr %value)
  ret void
}

define void @abla_array_set(ptr byval(%AblaValue) %array, ptr byval(%AblaValue) %index, ptr byval(%AblaValue) %value) {
  call void @abla_mobile_value_array_set(ptr %array, ptr %index, ptr %value)
  ret void
}

define i64 @abla_as_i64(ptr byval(%AblaValue) %value) {
  %result = call i64 @abla_mobile_value_as_i64(ptr %value)
  ret i64 %result
}

define i1 @abla_as_bool(ptr byval(%AblaValue) %value) {
  %result = call i1 @abla_mobile_value_as_bool(ptr %value)
  ret i1 %result
}

define ptr @abla_as_cstring(ptr byval(%AblaValue) %value) {
  %result = call ptr @abla_mobile_value_as_cstring(ptr %value)
  ret ptr %result
}

define i32 @abla_as_function(ptr byval(%AblaValue) %value) {
  %result = call i32 @abla_mobile_value_as_function(ptr %value)
  ret i32 %result
}

define i64 @abla_function_capture_count(ptr byval(%AblaValue) %value) {
  %result = call i64 @abla_mobile_value_function_capture_count(ptr %value)
  ret i64 %result
}

define ptr @abla_function_capture_pointer(ptr byval(%AblaValue) %value) {
  %result = call ptr @abla_mobile_value_function_capture_pointer(ptr %value)
  ret ptr %result
}

define void @abla_function_capture(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %value, i64 %index) {
  call void @abla_mobile_value_function_capture(ptr %out, ptr %value, i64 %index)
  ret void
}

define void @abla_shared_unlock(ptr byval(%AblaValue) %value) {
  call void @abla_mobile_value_shared_unlock(ptr %value)
  ret void
}

define void @abla_closure_release(ptr byval(%AblaValue) %value) {
  call void @abla_mobile_value_closure_release(ptr %value)
  ret void
}

define ptr @abla_owned_bytes_from_value(ptr byval(%AblaValue) %value) {
  %result = call ptr @abla_mobile_value_owned_bytes_from_value(ptr %value)
  ret ptr %result
}

define void @ablaRuntimeMemoryCheckpoint(ptr sret(%AblaValue) %out) {
  call void @abla_mobile_memory_checkpoint(ptr %out)
  ret void
}

define void @ablaRuntimeMemoryReset(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %checkpoint) {
  call void @abla_mobile_memory_reset(ptr %out, ptr %checkpoint)
  ret void
}

define void @ablaRuntimeMemoryLiveBytes(ptr sret(%AblaValue) %out) {
  call void @abla_mobile_memory_live_bytes(ptr %out)
  ret void
}

define void @ablaRuntimeMemoryLimit(ptr sret(%AblaValue) %out) {
  call void @abla_mobile_memory_limit(ptr %out)
  ret void
}

define void @ablaRuntimeMemorySetLimit(ptr sret(%AblaValue) %out, ptr byval(%AblaValue) %limit) {
  call void @abla_mobile_memory_set_limit(ptr %out, ptr %limit)
  ret void
}

define void @ablaRuntimeMemoryCollect(ptr sret(%AblaValue) %out) {
  call void @abla_mobile_memory_collect(ptr %out)
  ret void
}
