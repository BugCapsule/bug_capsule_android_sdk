cmake_minimum_required(VERSION 3.22.1)
project(bugcapsule_ndk LANGUAGES C)

add_library(bugcapsule-ndk SHARED
        bugcapsule_ndk.c
        bugcapsule_ndk_jni.c)

set_target_properties(bugcapsule-ndk PROPERTIES
        C_STANDARD 11
        C_EXTENSIONS ON
        C_VISIBILITY_PRESET hidden)

target_compile_options(bugcapsule-ndk PRIVATE
        -Wall
        -Wextra
        -funwind-tables
        -fno-omit-frame-pointer)

target_link_options(bugcapsule-ndk PRIVATE
        # 16 KB page size devices (Android 15+).
        "-Wl,-z,max-page-size=16384"
        "-Wl,--build-id=sha1")

target_link_libraries(bugcapsule-ndk PRIVATE dl)
