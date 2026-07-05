#!/usr/bin/env bash
#
# Cross-compiles libass and its dependencies (fribidi, freetype) as static
# libraries for Android, into app/src/main/cpp/prebuilt/<abi>/. The CMake build
# (app/src/main/cpp/CMakeLists.txt) links these into libjellyfin_ass.so.
#
# HarfBuzz and fontconfig are intentionally left out for a lean first pass:
# libass still renders ASS styling/positioning/karaoke without them, using
# embedded (attached) fonts plus a bundled fallback font.
#
# Requires: ANDROID_NDK_HOME, autotools, make, pkg-config, curl, tar.
# Usage: scripts/build-libass.sh [abi ...]   (default: arm64-v8a)
set -euo pipefail

FRIBIDI_VERSION="1.0.13"
FREETYPE_VERSION="2.13.2"
HARFBUZZ_VERSION="7.3.0" # last HarfBuzz series that still ships an autotools configure
LIBASS_VERSION="0.17.1"
API="${ANDROID_API:-21}"

ABIS=("${@:-arm64-v8a}")
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${ROOT}/.libass-build"
NDK="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
HOST_TAG="linux-x86_64"
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}"

fetch() {
    local url="$1" out="$2"
    [ -f "$out" ] || curl -fsSL -o "$out" "$url"
}

abi_triple() {
    case "$1" in
        arm64-v8a) echo "aarch64-linux-android" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi" ;;
        x86_64) echo "x86_64-linux-android" ;;
        x86) echo "i686-linux-android" ;;
        *) echo "unsupported abi: $1" >&2; exit 1 ;;
    esac
}

mkdir -p "$WORK"
cd "$WORK"
fetch "https://github.com/fribidi/fribidi/releases/download/v${FRIBIDI_VERSION}/fribidi-${FRIBIDI_VERSION}.tar.xz" "fribidi.tar.xz"
fetch "https://download.savannah.gnu.org/releases/freetype/freetype-${FREETYPE_VERSION}.tar.xz" "freetype.tar.xz"
fetch "https://github.com/harfbuzz/harfbuzz/releases/download/${HARFBUZZ_VERSION}/harfbuzz-${HARFBUZZ_VERSION}.tar.xz" "harfbuzz.tar.xz"
fetch "https://github.com/libass/libass/releases/download/${LIBASS_VERSION}/libass-${LIBASS_VERSION}.tar.xz" "libass.tar.xz"

for ABI in "${ABIS[@]}"; do
    TRIPLE="$(abi_triple "$ABI")"
    PREFIX="${ROOT}/app/src/main/cpp/prebuilt/${ABI}"
    BUILD="${WORK}/${ABI}"
    rm -rf "$BUILD"; mkdir -p "$BUILD" "$PREFIX"

    export AR="${TOOLCHAIN}/bin/llvm-ar"
    export AS="${TOOLCHAIN}/bin/${TRIPLE}${API}-clang"
    export CC="${TOOLCHAIN}/bin/${TRIPLE}${API}-clang"
    export CXX="${TOOLCHAIN}/bin/${TRIPLE}${API}-clang++"
    export LD="${TOOLCHAIN}/bin/ld"
    export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
    export STRIP="${TOOLCHAIN}/bin/llvm-strip"
    export PKG_CONFIG_PATH="${PREFIX}/lib/pkgconfig"
    export CFLAGS="-fPIC -O2 -I${PREFIX}/include"
    export CXXFLAGS="-fPIC -O2 -I${PREFIX}/include"
    export LDFLAGS="-L${PREFIX}/lib"

    # --- fribidi ---
    tar -C "$BUILD" -xf "${WORK}/fribidi.tar.xz"
    ( cd "${BUILD}/fribidi-${FRIBIDI_VERSION}" && \
        ./configure --host="$TRIPLE" --prefix="$PREFIX" --enable-static --disable-shared \
            --disable-bin --disable-docs && \
        make -j"$(nproc)" && make install )

    # --- freetype (no harfbuzz to avoid the circular dependency) ---
    tar -C "$BUILD" -xf "${WORK}/freetype.tar.xz"
    ( cd "${BUILD}/freetype-${FREETYPE_VERSION}" && \
        ./configure --host="$TRIPLE" --prefix="$PREFIX" --enable-static --disable-shared \
            --without-harfbuzz --without-png --without-brotli --without-zlib && \
        make -j"$(nproc)" && make install )

    # --- harfbuzz (needs freetype; required by libass 0.17.x) ---
    tar -C "$BUILD" -xf "${WORK}/harfbuzz.tar.xz"
    ( cd "${BUILD}/harfbuzz-${HARFBUZZ_VERSION}" && \
        ./configure --host="$TRIPLE" --prefix="$PREFIX" --enable-static --disable-shared \
            --with-freetype --without-glib --without-gobject --without-cairo \
            --without-icu --without-fontconfig --without-chafa && \
        make -j"$(nproc)" && make install )

    # --- libass (picks up freetype, fribidi and harfbuzz via PKG_CONFIG_PATH) ---
    tar -C "$BUILD" -xf "${WORK}/libass.tar.xz"
    ( cd "${BUILD}/libass-${LIBASS_VERSION}" && \
        ./configure --host="$TRIPLE" --prefix="$PREFIX" --enable-static --disable-shared \
            --disable-fontconfig --disable-require-system-font-provider && \
        make -j"$(nproc)" && make install )

    echo "== built libass for ${ABI} -> ${PREFIX} =="
done
