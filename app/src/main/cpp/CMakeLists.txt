# Builds libjellyfin_ass.so — a thin JNI bridge that statically links libass and
# its dependencies (freetype, fribidi) cross-compiled by scripts/build-libass.sh
# into app/src/main/cpp/prebuilt/<abi>/.
cmake_minimum_required(VERSION 3.22.1)
project(jellyfin_ass)

set(PREBUILT "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}")

if(NOT EXISTS "${PREBUILT}/lib/libass.a")
    message(FATAL_ERROR
        "Prebuilt libass not found for ${ANDROID_ABI} at ${PREBUILT}. "
        "Run scripts/build-libass.sh ${ANDROID_ABI} first.")
endif()

foreach(lib ass freetype fribidi)
    add_library(${lib} STATIC IMPORTED)
    set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION "${PREBUILT}/lib/lib${lib}.a")
endforeach()

add_library(jellyfin_ass SHARED libass_jni.cpp)

target_include_directories(jellyfin_ass PRIVATE "${PREBUILT}/include")

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

# Link order matters for static libs: ass depends on freetype and fribidi.
target_link_libraries(jellyfin_ass
    ass freetype fribidi
    ${log-lib} ${jnigraphics-lib} m z)
