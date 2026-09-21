@echo off
call C:\sfa\env.cmd
echo === installing packages ===
call sdkmanager.bat --sdk_root=%ANDROID_HOME% "platform-tools" "platforms/android-37.1" "build-tools/37.0.0" "ndk/28.0.13004108"
echo === installed list ===
call sdkmanager.bat --sdk_root=%ANDROID_HOME% --list_installed
