# Building rtorrent-android

This branch builds **Tablero**, an Android torrent client that runs
[rTorrent + libtorrent (rakshasa) 0.16.17](https://github.com/rakshasa/rtorrent) as a daemon and controls it
over XML-RPC/SCGI on a private Unix socket. The UI is Jetpack Compose; the Tablero backend is Kotlin.

Everything is built inside Docker, so the only thing you need on your machine is Docker.

## Requirements

- Docker 23 or newer (BuildKit, the default).
- About 15 GB of free disk space.
- Network access to `dl.google.com`, `archive.ubuntu.com`, `services.gradle.org`, Maven Central and Google Maven.
- To install on a phone: `adb` and USB debugging enabled. Android 9 (API 28) or newer.

## Build the APK

```bash
git clone -b android https://github.com/Ligutrino/rtorrent rtorrent-android
cd rtorrent-android

# arm64-v8a APK (almost every current phone). The first build takes about 15–30 minutes.
docker build -o out .

# Optional: more ABIs (x86_64 emulator, older 32-bit phones)
docker build --build-arg ABIS="arm64-v8a armeabi-v7a x86_64" -o out .
```

The build downloads the Android SDK and NDK r27c, cross-compiles OpenSSL 3.5.5, curl 8.23.0,
libtorrent and rtorrent 0.16.17 (versions and sha256 sums are pinned in `native/sources.lock`,
local patches are in `native/patches/`), and then builds the APKs with Gradle.

`out/` ends up with:

| File | What it is |
|---|---|
| `tablero.apk` | The Tablero app (`cl.tablero.android`) |
| `rtorrent-demo-debug.apk` | A minimal test app for the engine (`io.github.ligutrino.rtorrent.demo`) |
| `native/<abi>/librtorrent.so` | The rtorrent binary for each ABI |

## Install

```bash
adb install -r out/tablero.apk
adb install -r out/rtorrent-demo-debug.apk   # optional
```

## Signing

The APK is a debug build. If `android/debug.keystore` exists (it is not in git; keep your own copy there),
Gradle signs with it, so every new build installs over the previous one with `adb install -r`.
If it does not exist, Gradle uses your machine's own debug key (`~/.android/debug.keystore`).
Android only lets you update an app with an APK signed by the same key, so an APK you build yourself
cannot be installed over the release APK: uninstall first.

## Test without a phone

```bash
docker build --target hosttest .
```

This builds rtorrent for Linux with the same recipes and runs the engine's integration test
(`tools/host-test`), the Tablero API tests (`tools/tablero-test.sh`) and the sequential download and
streaming test (`tools/sequential-test.py`) against a real rtorrent.

## Licenses

rtorrent and libtorrent are GPL-2.0-or-later (see `COPYING`), curl is MIT/curl and OpenSSL 3 is
Apache-2.0. The APK is distributed under the GPL; this repository is its complete source.

More detail (in Spanish) is in `README.md`.
