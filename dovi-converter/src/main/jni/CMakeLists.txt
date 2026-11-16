cmake_minimum_required(VERSION 3.22.1)
project(doviJNI C)

# Static libdovi (Rust) from tools/build-libdovi.sh, per ABI.
set(LIBS ${CMAKE_CURRENT_SOURCE_DIR}/libdovi/android-libs/${ANDROID_ABI})
add_library(doviJNI SHARED dovi_jni.c)
target_link_libraries(doviJNI ${LIBS}/libdovi.a log dl m)
# Only the JNI entry point is exported; the Rust library stays inside.
target_link_options(doviJNI PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
