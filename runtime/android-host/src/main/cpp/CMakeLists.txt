cmake_minimum_required(VERSION 3.22.1)
project(abla_mobile_bridge C)

add_library(abla_mobile_bridge SHARED abla_mobile_bridge.c)

find_library(log_library log)
find_library(android_library android)

target_compile_features(abla_mobile_bridge PRIVATE c_std_11)
target_compile_options(abla_mobile_bridge PRIVATE
    -Wall
    -Wextra
    -Werror
    -fvisibility=hidden
)
target_link_libraries(abla_mobile_bridge PRIVATE
    ${log_library}
    ${android_library}
    dl
)

