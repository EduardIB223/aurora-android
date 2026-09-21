@echo off
call C:\sfa\env.cmd
"%ANDROID_HOME%\cmdline-tools\latest\bin\android.exe" sdk install --sdk %ANDROID_HOME% --no-metrics platforms/android-35
echo === platforms now ===
dir /b "%ANDROID_HOME%\platforms"
