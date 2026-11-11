# Standalone CMake project for the packer's native loader.
# Built by the Gradle `compileDexldrSo` task per-ABI (arm64-v8a + armeabi-v7a).
# SO_NAME is patched at build time (lib<SO_NAME>.so).

cmake_minimum_required(VERSION 3.18)
project(dexldr_pack)

set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Aggressive size optimization — stub loader should be small.
set(CMAKE_C_FLAGS_RELEASE "-Os -fvisibility=hidden -fdata-sections -ffunction-sections")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "-Wl,--gc-sections -Wl,--strip-all")

add_library(${DEXLDR_LIBNAME} SHARED dexldr.c)

find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(${DEXLDR_LIBNAME} ${log-lib} ${android-lib})
