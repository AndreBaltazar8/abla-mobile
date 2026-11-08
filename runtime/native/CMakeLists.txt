function(abla_mobile_add_android_application target)
    set(one_value_arguments
        APPLICATION_OBJECT
        APPLICATION_OBJECT_ROOT
        ABLA_COMPILER_ROOT
    )
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
    set(application_object "${ABLA_MOBILE_APPLICATION_OBJECT}")
    if(ABLA_MOBILE_APPLICATION_OBJECT_ROOT)
        if(NOT ANDROID_ABI STREQUAL "arm64-v8a" AND
           NOT ANDROID_ABI STREQUAL "x86_64")
            message(FATAL_ERROR "Unsupported Abla Mobile ABI: ${ANDROID_ABI}")
        endif()
        set(application_object
            "${ABLA_MOBILE_APPLICATION_OBJECT_ROOT}/${ANDROID_ABI}/abla_app.o"
        )
    endif()
    if(NOT application_object OR NOT EXISTS "${application_object}")
        message(FATAL_ERROR "Build the Abla application object before Gradle")
    endif()

    set_source_files_properties(
        "${application_object}"
        PROPERTIES EXTERNAL_OBJECT TRUE GENERATED TRUE
    )

    # The runtime is written in Abla and compiled into the application object,
    # which then needs only libc.
    add_library(${target} SHARED
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/abla_mobile_app_host.c"
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/abla_mobile_hosted.c"
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/abla_mobile_panic.c"
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/abla_mobile_platform_effects.c"
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/abla_mobile_platform_requests.c"
        "${application_object}"
    )
    find_library(abla_mobile_log_library log)
    target_include_directories(${target} PRIVATE "${CMAKE_CURRENT_FUNCTION_LIST_DIR}")
    target_compile_features(${target} PRIVATE c_std_11)
    target_compile_options(${target} PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
    target_link_options(${target} PRIVATE
        "-Wl,--version-script=${CMAKE_CURRENT_FUNCTION_LIST_DIR}/abla_mobile_exports.map"
        "-Wl,--no-undefined"
    )
    target_link_libraries(${target} PRIVATE ${abla_mobile_log_library})
endfunction()
