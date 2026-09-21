@echo off
call C:\sfa\env.cmd
set AND="%ANDROID_HOME%\cmdline-tools\latest\bin\android.exe"
echo === platform-tools ===
%AND% sdk install --sdk %ANDROID_HOME% --no-metrics platform-tools
echo === platforms/android-37.1 ===
%AND% sdk install --sdk %ANDROID_HOME% --no-metrics platforms/android-37.1
echo === build-tools/37.0.0 ===
%AND% sdk install --sdk %ANDROID_HOME% --no-metrics build-tools/37.0.0
echo === ndk/28.0.13004108 (big) ===
%AND% sdk install --sdk %ANDROID_HOME% --no-metrics ndk/28.0.13004108
echo === installed ===
%AND% sdk list --sdk %ANDROID_HOME% --no-metrics --installed
