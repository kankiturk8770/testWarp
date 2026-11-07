# Kanki Warp JNI bridge: loads libaether.so (WARP/MASQUE core) and
# libhev-socks5-tunnel.so (TUN -> SOCKS5) at runtime and forwards calls.
# Both are built in CI and copied into jniLibs; dlopen keeps a missing one
# a readable error instead of a crash at System.loadLibrary.
cmake_minimum_required(VERSION 3.22.1)
project(kwarp C)

add_library(kwarp SHARED bridge.c)
target_compile_options(kwarp PRIVATE -O2 -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter)
find_library(LOG_LIB log)
target_link_libraries(kwarp PRIVATE ${LOG_LIB} dl)
