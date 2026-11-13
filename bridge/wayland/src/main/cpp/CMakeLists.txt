cmake_minimum_required(VERSION 3.22.1)
project("wayland_bridge")

add_library(
    wayland_bridge
    SHARED
    wayland_bridge.cpp
)

find_library(
    android-lib
    android
)

find_library(
    log-lib
    log
)

target_link_libraries(
    wayland_bridge
    ${android-lib}
    ${log-lib}
)
