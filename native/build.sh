#!/usr/bin/env bash
# Compila rtorrent (daemon, sin ncurses, XML-RPC con tinyxml2) y sus dependencias
# (openssl, curl, libtorrent) como un único ejecutable estático respecto a todo
# salvo bionic (libc, libm, libdl, libz del sistema).
#
# Uso:  native/build.sh <abi> [<abi>...]
#   abi: arm64-v8a | armeabi-v7a | x86_64 | x86 | host
#   "host" compila para la máquina actual (sirve para probar el pipeline sin NDK).
#
# Variables:
#   ANDROID_NDK_HOME  ruta al NDK (obligatoria salvo para "host")
#   ANDROID_API       nivel de API mínimo del binario (por defecto 28: posix_spawn)
#   OUT_DIR           salida (por defecto native/out); deja <abi>/librtorrent.so
#   WORK_DIR          directorio de compilación (por defecto native/work)
#   JOBS              paralelismo de make
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DL="${DOWNLOAD_DIR:-$HERE/downloads}"
OUT_DIR="${OUT_DIR:-$HERE/out}"
WORK_DIR="${WORK_DIR:-$HERE/work}"
API="${ANDROID_API:-28}"
JOBS="${JOBS:-$(nproc)}"

ver() { awk -v n="$1" '$1==n {print $2}' "$HERE/sources.lock"; }
OPENSSL_V="$(ver openssl)"; CURL_V="$(ver curl)"
LIBTORRENT_V="$(ver libtorrent)"; RTORRENT_V="$(ver rtorrent)"

log() { printf '\n\033[1;34m[%s] %s\033[0m\n' "$ABI" "$*"; }

unpack() { # unpack <nombre> <versión> -> imprime el directorio de fuentes
  local name="$1" v="$2" dir="$SRC/$1-$2"
  if [[ ! -d "$dir" ]]; then
    tar -xzf "$DL/$name-$v.tar.gz" -C "$SRC"
    shopt -s nullglob
    for p in "$HERE/patches/$name"/*.patch; do
      echo "[patch] $name: $(basename "$p")" >&2
      patch -d "$dir" -p1 < "$p" >&2
    done
    shopt -u nullglob
  fi
  echo "$dir"
}

setup_toolchain() {
  case "$ABI" in
    arm64-v8a)   TRIPLE=aarch64-linux-android;     CLANG_TRIPLE=aarch64-linux-android;    OSSL_TARGET=android-arm64 ;;
    armeabi-v7a) TRIPLE=arm-linux-androideabi;     CLANG_TRIPLE=armv7a-linux-androideabi; OSSL_TARGET=android-arm ;;
    x86_64)      TRIPLE=x86_64-linux-android;      CLANG_TRIPLE=x86_64-linux-android;     OSSL_TARGET=android-x86_64 ;;
    x86)         TRIPLE=i686-linux-android;        CLANG_TRIPLE=i686-linux-android;       OSSL_TARGET=android-x86 ;;
    host)        TRIPLE=""; OSSL_TARGET="" ;;
    *) echo "ABI desconocida: $ABI" >&2; exit 2 ;;
  esac

  COMMON_FLAGS="-O2 -fPIC -ffunction-sections -fdata-sections"
  if [[ "$ABI" == host ]]; then
    export CC="${CC:-cc}" CXX="${CXX:-c++}" AR="${AR:-ar}" RANLIB="${RANLIB:-ranlib}" STRIP="${STRIP:-strip}"
    HOST_ARGS=()
    export LDFLAGS="-Wl,--gc-sections -static-libstdc++ -static-libgcc"
  else
    : "${ANDROID_NDK_HOME:?Define ANDROID_NDK_HOME}"
    local tc="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
    export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
    export PATH="$tc/bin:$PATH"
    export CC="$tc/bin/${CLANG_TRIPLE}${API}-clang"
    export CXX="$tc/bin/${CLANG_TRIPLE}${API}-clang++"
    export AR="$tc/bin/llvm-ar" RANLIB="$tc/bin/llvm-ranlib" STRIP="$tc/bin/llvm-strip"
    export NM="$tc/bin/llvm-nm" LD="$tc/bin/ld.lld"
    HOST_ARGS=(--host="$TRIPLE")
    SYSROOT="$tc/sysroot"
    # Páginas de 16 KB: obligatorio para apps nuevas en Google Play desde nov. 2025.
    export LDFLAGS="-Wl,--gc-sections -Wl,-z,max-page-size=16384 -static-libstdc++"
  fi
  export CFLAGS="$COMMON_FLAGS" CXXFLAGS="$COMMON_FLAGS"
  export CPPFLAGS="-I$PREFIX/include"
  # off_t de 64 bits en ABIs de 32 bits: archivos de más de 2 GiB.
  case "$ABI" in armeabi-v7a|x86) CPPFLAGS="$CPPFLAGS -D_FILE_OFFSET_BITS=64" ;; esac
  LDFLAGS="-L$PREFIX/lib $LDFLAGS"
  # Solo nuestro prefix; --static para que arrastre las dependencias privadas (ssl, crypto, z).
  export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
  [[ "$ABI" == host ]] && PKG_CONFIG_LIBDIR="$PKG_CONFIG_LIBDIR:$(pkg-config --variable pc_path pkg-config)"
  export PKG_CONFIG="pkg-config --static"
}

zlib_pc() {
  # El NDK trae zlib (libz.so del sistema) pero no zlib.pc; libtorrent y rtorrent lo piden por pkg-config.
  [[ "$ABI" == host ]] && return 0
  mkdir -p "$PREFIX/lib/pkgconfig"
  local zver
  zver="$(sed -n 's/^#define ZLIB_VERSION "\(.*\)".*/\1/p' "$SYSROOT/usr/include/zlib.h")"
  cat > "$PREFIX/lib/pkgconfig/zlib.pc" <<EOF
Name: zlib
Description: zlib del sistema (NDK)
Version: ${zver:-1.2.13}
Libs: -lz
Cflags:
EOF
}

build_openssl() {
  [[ -f "$PREFIX/lib/libcrypto.a" ]] && return 0
  log "openssl $OPENSSL_V"
  local src; src="$(unpack openssl "$OPENSSL_V")"
  local b="$BUILD/openssl"; rm -rf "$b"; cp -a "$src" "$b"; cd "$b"
  local target="$OSSL_TARGET"; [[ "$ABI" == host ]] && target=""
  local api_def=(); [[ "$ABI" != host ]] && api_def=(-D__ANDROID_API__="$API")
  # Configure de OpenSSL usa CC tal cual; con el NDK espera el clang "genérico" en PATH.
  ( [[ "$ABI" != host ]] && unset CC CXX
    ./Configure $target "${api_def[@]}" --prefix="$PREFIX" --libdir=lib \
      no-shared no-tests no-docs no-apps no-engine no-dso no-legacy no-module no-comp \
      -fPIC -ffunction-sections -fdata-sections
    make -j"$JOBS" build_libs
    make install_dev )
}

build_curl() {
  [[ -f "$PREFIX/lib/libcurl.a" ]] && return 0
  log "curl $CURL_V"
  local src; src="$(unpack curl "$CURL_V")"
  local b="$BUILD/curl"; rm -rf "$b"; mkdir -p "$b"; cd "$b"
  local ca=(); [[ "$ABI" != host ]] && ca=(--with-ca-path=/system/etc/security/cacerts --without-ca-bundle)
  "$src/configure" "${HOST_ARGS[@]}" --prefix="$PREFIX" \
    --disable-shared --enable-static --with-pic \
    --with-openssl="$PREFIX" --with-zlib "${ca[@]}" \
    --enable-ipv6 --enable-threaded-resolver \
    --without-libpsl --without-brotli --without-zstd --without-libidn2 \
    --without-nghttp2 --without-nghttp3 --without-ngtcp2 --without-libssh2 --without-librtmp \
    --disable-ldap --disable-ldaps --disable-rtsp --disable-dict --disable-telnet \
    --disable-tftp --disable-pop3 --disable-imap --disable-smtp --disable-gopher \
    --disable-mqtt --disable-smb --disable-ftp --disable-file --disable-manual --disable-docs \
    --disable-ntlm --disable-unix-sockets --disable-websockets
  make -j"$JOBS" -C lib
  make -C lib install
  make -C include install
  make install-pkgconfigDATA install-binSCRIPTS
}

build_libtorrent() {
  [[ -f "$PREFIX/lib/libtorrent.a" ]] && return 0
  log "libtorrent $LIBTORRENT_V"
  local src; src="$(unpack libtorrent "$LIBTORRENT_V")"
  [[ -x "$src/configure" ]] || (cd "$src" && autoreconf -fi)
  local b="$BUILD/libtorrent"; rm -rf "$b"; mkdir -p "$b"; cd "$b"
  "$src/configure" "${HOST_ARGS[@]}" --prefix="$PREFIX" \
    --enable-static --disable-shared --with-pic \
    --disable-debug --disable-execinfo --disable-instrumentation
  make -j"$JOBS" -C src
  make -C src install
  make install-pkgconfigDATA
}

build_rtorrent() {
  log "rtorrent $RTORRENT_V"
  local src; src="$(unpack rtorrent "$RTORRENT_V")"
  [[ -x "$src/configure" ]] || (cd "$src" && autoreconf -fi)
  local b="$BUILD/rtorrent"; rm -rf "$b"; mkdir -p "$b"; cd "$b"
  "$src/configure" "${HOST_ARGS[@]}" --prefix="$PREFIX" \
    --without-ncurses --with-xmlrpc-tinyxml2 --without-lua \
    --disable-debug --disable-execinfo
  make -j"$JOBS"
  local bin; bin="$(find . -maxdepth 2 -type f -name rtorrent -perm -u+x | head -1)"
  [[ -n "$bin" ]] || { echo "No se encontró el binario rtorrent" >&2; exit 1; }
  mkdir -p "$OUT_DIR/$ABI"
  # Se empaqueta como "librtorrent.so" para que Android lo instale en nativeLibraryDir
  # (único lugar desde el que una app con targetSdk >= 29 puede ejecutar binarios).
  "$STRIP" -o "$OUT_DIR/$ABI/librtorrent.so" "$bin"
  chmod 755 "$OUT_DIR/$ABI/librtorrent.so"
  log "OK -> $OUT_DIR/$ABI/librtorrent.so ($(du -h "$OUT_DIR/$ABI/librtorrent.so" | cut -f1))"
}

[[ $# -ge 1 ]] || { sed -n '2,15p' "$0"; exit 2; }
"$HERE/fetch.sh"

for ABI in "$@"; do
  BASE="$WORK_DIR/$ABI"; PREFIX="$BASE/prefix"; BUILD="$BASE/build"; SRC="$WORK_DIR/src"
  mkdir -p "$PREFIX" "$BUILD" "$SRC"
  setup_toolchain
  zlib_pc
  build_openssl
  build_curl
  build_libtorrent
  build_rtorrent
done
