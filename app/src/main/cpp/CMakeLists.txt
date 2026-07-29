cmake_minimum_required(VERSION 3.22.1)
project(pravka_whisper)

# whisper.cpp is pulled at configure time (CI has network; the dev sandbox
# does not, which is why native code is only ever compiled on CI). Pinned to
# a release tag so builds are reproducible.
include(FetchContent)

set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)   # no OpenMP in the NDK
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)   # statically link into our .so

# CRITICAL for speed: the app is a debug APK, so Gradle drives this native
# build as CMAKE_BUILD_TYPE=Debug (-O0). whisper.cpp/ggml is compute-bound and
# runs 10-20x slower without optimization - a 30s clip took minutes. Force
# -O3 + NDEBUG on every target added below (add_compile_options propagates
# into the FetchContent subdirectory), regardless of the APK variant. Placed
# before FetchContent_MakeAvailable so whisper and ggml inherit it.
add_compile_options(-O3 -DNDEBUG)

FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.7.4
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(pravka_whisper SHARED whisper_jni.cpp)

target_link_libraries(pravka_whisper
    whisper
    android
    log
)
