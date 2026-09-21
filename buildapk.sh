#!/usr/bin/env bash
set -e

export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
export ANDROID_HOME="C:/Android/Sdk"
export ANDROID_SDK_ROOT="C:/Android/Sdk"
export ANDROID_NDK_HOME="C:/ndk/android-ndk-r28"
export PATH="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin:$PATH"

cd /c/sfa
echo "=== java ==="
java -version
echo "=== assembling Aurora (flavor: other, debug) ==="
./gradlew assembleOtherDebug --no-daemon --stacktrace

echo "=== output ==="
find /c/sfa/app/build/outputs/apk -name "*.apk" -exec ls -la {} \;
