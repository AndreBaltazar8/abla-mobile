cmake_minimum_required(VERSION 3.22.1)
project(abla_mobile_showcase C)

if(NOT EXISTS "${ABLA_MOBILE_ROOT}/runtime/native/CMakeLists.txt")
    message(FATAL_ERROR "ABLA_MOBILE_ROOT must name the selected mobile SDK")
endif()

add_subdirectory(
    "${ABLA_MOBILE_ROOT}/runtime/native"
    "${CMAKE_CURRENT_BINARY_DIR}/abla-mobile-native"
)

abla_mobile_add_android_application(abla_app
    APPLICATION_OBJECT_ROOT "${ABLA_APP_OBJECT_ROOT}"
    ABLA_COMPILER_ROOT "${ABLA_COMPILER_ROOT}"
)
