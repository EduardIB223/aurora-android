@echo off
call C:\sfa\env.cmd
cd /d C:\sbcore
echo === env ===
echo JAVA_HOME=%JAVA_HOME%
echo ANDROID_HOME=%ANDROID_HOME%
echo ANDROID_NDK_HOME=%ANDROID_NDK_HOME%
go version
echo === building libbox.aar (android) ===
go run ./cmd/internal/build_libbox -target android
echo === exit code: %ERRORLEVEL% ===
dir /b *.aar
