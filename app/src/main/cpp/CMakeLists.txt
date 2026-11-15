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

# espeak-ng turns text into phonemes for the bundled Piper voices (Hoài My, Nam Minh). The commit is
# the one Piper 1.3.0 was built and trained with; its data files (vi + en only) are in
# assets/espeak-ng-data. Only the library is built: no data compile, no audio output.
set(USE_ASYNC OFF CACHE BOOL "" FORCE)
set(USE_MBROLA OFF CACHE BOOL "" FORCE)
set(USE_LIBSONIC OFF CACHE BOOL "" FORCE)
set(USE_LIBPCAUDIO OFF CACHE BOOL "" FORCE)
set(USE_KLATT OFF CACHE BOOL "" FORCE)
set(USE_SPEECHPLAYER OFF CACHE BOOL "" FORCE)
set(COMPILE_INTONATIONS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    espeakng
    GIT_REPOSITORY https://github.com/espeak-ng/espeak-ng.git
    GIT_TAG 212928b394a96e8fd2096616bfd54e17845c48f6
)
FetchContent_GetProperties(espeakng)
if(NOT espeakng_POPULATED)
    FetchContent_Populate(espeakng)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    add_subdirectory(${espeakng_SOURCE_DIR} ${espeakng_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

add_library(cutviet_tts SHARED espeak_jni.cpp)
target_compile_options(cutviet_tts PRIVATE -O2 -fvisibility=hidden)
# espeak-ng's compat headers include ucd/ucd.h (same fix Piper uses).
target_include_directories(cutviet_tts PRIVATE ${espeakng_SOURCE_DIR}/src/ucd-tools/src/include)
target_link_libraries(cutviet_tts PRIVATE espeak-ng log)
