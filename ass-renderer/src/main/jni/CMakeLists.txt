cmake_minimum_required(VERSION 3.22.1)
project(assJNI C CXX)

# Static libass, HarfBuzz, FriBidi and FreeType from tools/build-libass.sh, per ABI.
set(LIBS ${CMAKE_CURRENT_SOURCE_DIR}/libass/android-libs/${ANDROID_ABI})
add_library(assJNI SHARED ass_jni.c)
target_include_directories(assJNI PRIVATE ${LIBS}/include)
target_link_libraries(assJNI
    ${LIBS}/lib/libass.a
    ${LIBS}/lib/libharfbuzz.a
    ${LIBS}/lib/libfribidi.a
    ${LIBS}/lib/libfreetype.a
    jnigraphics log m)
# HarfBuzz is C++: its runtime comes from the NDK's static libc++.
set_target_properties(assJNI PROPERTIES LINKER_LANGUAGE CXX)
target_link_options(assJNI PRIVATE -static-libstdc++)
