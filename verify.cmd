@echo off
call C:\sfa\env.cmd
set APK=C:\sfa\app\build\outputs\apk\other\debug\Aurora-1.13.21-arm64-v8a-debug.apk
echo === application id ===
call apkanalyzer.bat manifest application-id "%APK%"
echo === min/target sdk ===
call apkanalyzer.bat manifest min-sdk "%APK%"
call apkanalyzer.bat manifest target-sdk "%APK%"
echo === app label ===
call apkanalyzer.bat manifest print "%APK%" | findstr /i "android:label"
echo === native libs ===
call apkanalyzer.bat files list "%APK%" | findstr /i ".so"
