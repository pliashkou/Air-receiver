# Air-receiver

An open-source AirPlay receiver for Android TV devices. Mirror your Mac or iOS screen to any Android TV box (tested on MiBox S).

## Features

- AirPlay screen mirroring (H.264 video stream)
- Audio playback (AAC, ALAC)
- mDNS/Bonjour service discovery via jmdns
- FairPlay decryption (Curve25519 ECDH, AES-CTR, RSA)
- Hardware-accelerated video decoding via MediaCodec
- Runs as a foreground service on Android TV

## Architecture

```
mdns/        - Bonjour service advertisement
rtsp/        - RTSP server & session handling (AirPlay control channel)
crypto/      - FairPlay pairing, Curve25519, AES decryption
media/       - VideoDecoder (MediaCodec), AudioPlayer
service/     - AirPlayService (foreground service)
cpp/         - Native PlayFair library (JNI)
```

## Requirements

- Android device running API 23+ (Android 6.0)
- Android Studio with NDK and CMake
- Sender: macOS or iOS device on the same network

## Build

```bash
git clone https://github.com/pliashkou/Air-receiver.git
cd Air-receiver
./gradlew assembleDebug
```

Install on your Android TV:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Launch the app on your Android TV
2. On your Mac/iPhone, open Screen Mirroring and select "Remote Play"
3. The mirrored screen appears on the TV

## Supported ABIs

- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`

## License

MIT
