cmake_minimum_required(VERSION 3.22.1)
project("wayland_bridge")

add_library(
    wayland_bridge
    SHARED
    wayland_bridge.cpp
    audio_bridge.cpp
    pty_bridge.cpp
)

find_library(
    android-lib
    android
)

find_library(
    log-lib
    log
)

find_library(
    aaudio-lib
    aaudio
)

find_library(
    egl-lib
    EGL
)

find_library(
    gles-lib
    GLESv2
)

target_link_libraries(
    wayland_bridge
    ${android-lib}
    ${log-lib}
    ${aaudio-lib}
    ${egl-lib}
    ${gles-lib}
)
