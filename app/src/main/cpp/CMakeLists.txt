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
