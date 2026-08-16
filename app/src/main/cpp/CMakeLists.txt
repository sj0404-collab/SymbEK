cmake_minimum_required(VERSION 3.22)
project(symbiosis_kenji)
set(CMAKE_CXX_STANDARD 20)

# Only our tiny bridge. Every other .so (libkenjinx, official jni,
# OpenAL, FFmpeg, OpenSSL, SDL2, Skia, adrenotools hooks) comes from
# the official Kenji-NX 2.1.0-pr.2 APK and is copied into jniLibs.
# Building kenjinxjni/openal here would overwrite those files.
add_library(symbiosis_kenji SHARED kenji_bridge.cpp)
target_link_libraries(symbiosis_kenji PRIVATE log dl android)
