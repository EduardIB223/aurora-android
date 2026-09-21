#!/usr/bin/env bash
set -e

export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
export ANDROID_HOME="C:/Android/Sdk"
export ANDROID_SDK_ROOT="C:/Android/Sdk"
export ANDROID_NDK_HOME="C:/ndk/android-ndk-r28"
export PATH="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin:/c/Program Files/Go/bin:/c/Users/addyo/go/bin:$PATH"

echo "=== env ==="
echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
go version
gomobile version || true

cd /c/sbcore
echo "=== building libbox.aar (android) ==="
go run ./cmd/internal/build_libbox -target android

echo "=== result ==="
ls -la /c/sbcore/*.aar
