cmake_minimum_required(VERSION 3.22.1)
project(cutviet_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# whisper.cpp is fetched at build time (GitHub Actions) at a fixed release tag.
include(FetchContent)

# Tuned for the owner's phone (Snapdragon 8 Elite, Armv8.7): dot-product, int8 matmul and fp16
# instructions. The app only ships arm64-v8a, and every arm64 phone from the last years has these.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.3
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(cutviet_whisper SHARED whisper_jni.cpp)
target_compile_options(cutviet_whisper PRIVATE -O3 -fvisibility=hidden)
target_link_libraries(cutviet_whisper PRIVATE whisper log android)
