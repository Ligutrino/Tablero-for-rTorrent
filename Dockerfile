# syntax=docker/dockerfile:1.7
#
# Compila rtorrent para Android y el APK de prueba en un único `docker build`.
#
#   docker build -o out .
#
# Deja en ./out:  tablero-debug.apk (app con Tablero), rtorrent-demo-debug.apk (prueba mínima)
#                y native/<abi>/librtorrent.so
# ABIs por defecto: arm64-v8a. Para más:  --build-arg ABIS="arm64-v8a armeabi-v7a x86_64"

############################ 1. SDK + NDK ############################
FROM ubuntu:24.04 AS sdk
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl unzip patch file perl python3 make pkg-config \
      autoconf automake libtool openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
ARG CMDLINE_TOOLS_BUILD=11076708
ARG CMDLINE_TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
RUN curl -fL --retry 3 -o /tmp/clt.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip" \
    && echo "${CMDLINE_TOOLS_SHA256}  /tmp/clt.zip" | sha256sum -c - \
    && mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && unzip -q /tmp/clt.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/clt.zip
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

# NDK r27c. sdkmanager verifica las sumas de cada paquete.
ARG NDK_VERSION=27.2.12479018
ARG COMPILE_SDK=35
ARG BUILD_TOOLS=35.0.0
RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install "platforms;android-${COMPILE_SDK}" "build-tools;${BUILD_TOOLS}" "ndk;${NDK_VERSION}" > /dev/null
ENV ANDROID_NDK_HOME=$ANDROID_HOME/ndk/${NDK_VERSION}

############################ 2. Binario nativo ############################
FROM sdk AS native
WORKDIR /src/native
# Primero solo lo necesario para descargar, así la caché de capas guarda las fuentes.
COPY native/sources.lock native/fetch.sh ./
COPY native/downloads/ ./downloads/
RUN ./fetch.sh
COPY native/ ./
ARG ABIS="arm64-v8a"
ARG ANDROID_API=28
RUN ANDROID_API=${ANDROID_API} ./build.sh ${ABIS} \
    && file out/*/librtorrent.so

############################ 3. APK ############################
FROM sdk AS apk
WORKDIR /src/android
COPY android/gradlew android/gradle.properties android/settings.gradle.kts android/build.gradle.kts ./
COPY android/gradle/ ./gradle/
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --version
COPY android/ ./
COPY --from=native /src/native/out/ ./engine/src/main/jniLibs/
ARG ABIS="arm64-v8a"
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -Pabis="$(echo ${ABIS} | tr ' ' ',')" :app:assembleDebug :tablero:assembleDebug \
    && cp app/build/outputs/apk/debug/app-debug.apk /rtorrent-demo-debug.apk \
    && cp tablero/build/outputs/apk/debug/tablero-debug.apk /tablero-debug.apk

############################ Opcional: prueba en Linux ############################
# docker build --target hosttest .
# Compila rtorrent para Linux y prueba contra él el motor Kotlin y la API de Tablero (tablero.py --android).
FROM sdk AS hosttest
RUN apt-get update && apt-get install -y --no-install-recommends g++ zlib1g-dev && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle tools/host-test.sh

############################ 4. Salida ############################
FROM scratch AS out
COPY --from=apk /rtorrent-demo-debug.apk /tablero-debug.apk /
COPY --from=native /src/native/out/ /native/
