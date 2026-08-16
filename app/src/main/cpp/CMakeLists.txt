cmake_minimum_required(VERSION 3.22)
project(kenjinxjni)
set(CMAKE_CXX_STANDARD 20)

# Official Kenji JNI (window, vulkan surface, debug_break).
add_library(kenjinxjni SHARED kenjinx.cpp vulkan_wrapper.cpp)
target_link_libraries(kenjinxjni PRIVATE log android vulkan)

# Tiny bridge kept so older call sites still resolve.
add_library(symbiosis_kenji SHARED kenji_bridge.cpp)
target_link_libraries(symbiosis_kenji PRIVATE log dl android)

# OpenAL: the C# core constructs OpenALHardwareDeviceDriver in deviceInitialize.
include(FetchContent)
set(ALSOFT_UTILS OFF CACHE BOOL "" FORCE)
set(ALSOFT_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ALSOFT_TESTS OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL OFF CACHE BOOL "" FORCE)
set(LIBTYPE SHARED)
FetchContent_Declare(
    openal
    GIT_REPOSITORY https://github.com/kcat/openal-soft.git
    GIT_TAG 1.23.1
)
FetchContent_MakeAvailable(openal)
