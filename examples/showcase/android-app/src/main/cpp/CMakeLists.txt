cmake_minimum_required(VERSION 3.22.1)
project(abla_mobile_showcase C)

add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../../../../../../runtime/native"
    "${CMAKE_CURRENT_BINARY_DIR}/abla-mobile-native"
)

abla_mobile_add_android_application(abla_app
    APPLICATION_OBJECT "${ABLA_APP_OBJECT}"
    ABLA_COMPILER_ROOT "${ABLA_COMPILER_ROOT}"
)
