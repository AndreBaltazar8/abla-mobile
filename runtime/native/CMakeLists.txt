function(abla_mobile_add_android_application target)
    set(one_value_arguments APPLICATION_OBJECT ABLA_COMPILER_ROOT)
    cmake_parse_arguments(
        ABLA_MOBILE
        ""
        "${one_value_arguments}"
        ""
        ${ARGN}
    )

    if(NOT ABLA_MOBILE_ABLA_COMPILER_ROOT)
        message(FATAL_ERROR "ABLA_COMPILER_ROOT is required")
    endif()
    if(NOT ABLA_MOBILE_APPLICATION_OBJECT OR
       NOT EXISTS "${ABLA_MOBILE_APPLICATION_OBJECT}")
        message(FATAL_ERROR "Build the Abla application object before Gradle")
    endif()

    set_source_files_properties(
        "${ABLA_MOBILE_APPLICATION_OBJECT}"
        PROPERTIES EXTERNAL_OBJECT TRUE GENERATED TRUE
    )

    set(abi_llvm
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/abla_mobile_value_abi_aarch64.ll"
    )
    set(abi_object
        "${CMAKE_CURRENT_BINARY_DIR}/${target}_value_abi_aarch64.o"
    )
    add_custom_command(
        OUTPUT "${abi_object}"
        COMMAND "${CMAKE_C_COMPILER}"
            "--target=${CMAKE_C_COMPILER_TARGET}"
            "--sysroot=${CMAKE_SYSROOT}"
            -c "${abi_llvm}"
            -o "${abi_object}"
        DEPENDS "${abi_llvm}"
        VERBATIM
    )
    set_source_files_properties(
        "${abi_object}"
        PROPERTIES EXTERNAL_OBJECT TRUE GENERATED TRUE
    )

    set(portable_runtime
        "${ABLA_MOBILE_ABLA_COMPILER_ROOT}/runtime/abla_runtime.c"
    )
    add_library(${target} SHARED
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/abla_mobile_android_platform.c"
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/abla_mobile_app_host.c"
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/abla_mobile_platform_effects.c"
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/abla_mobile_platform_requests.c"
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/abla_mobile_value_bridge.c"
        "${abi_object}"
        "${portable_runtime}"
        "${ABLA_MOBILE_APPLICATION_OBJECT}"
    )

    find_library(abla_mobile_log_library log)
    target_include_directories(${target} PRIVATE
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}"
        "${ABLA_MOBILE_ABLA_COMPILER_ROOT}/runtime"
    )
    set_source_files_properties(
        "${portable_runtime}"
        PROPERTIES COMPILE_OPTIONS
        "-include;${CMAKE_CURRENT_FUNCTION_LIST_DIR}/abla_mobile_core_rename.h"
    )
    target_compile_features(${target} PRIVATE c_std_11)
    target_compile_options(${target} PRIVATE
        -Wall
        -Wextra
        -Werror
        -fvisibility=hidden
    )
    target_link_options(${target} PRIVATE
        -Wl,--exclude-libs,ALL
        -Wl,--gc-sections
        -Wl,--no-undefined
        -Wl,--version-script=${CMAKE_CURRENT_FUNCTION_LIST_DIR}/abla_mobile_exports.map
    )
    target_link_libraries(${target} PRIVATE ${abla_mobile_log_library})
endfunction()
