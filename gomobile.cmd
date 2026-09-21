@echo off
call C:\sfa\env.cmd
echo === installing gomobile/gobind v0.1.12 (matching sing-box v1.13.21) ===
go install -v github.com/sagernet/gomobile/cmd/gomobile@v0.1.12
go install -v github.com/sagernet/gomobile/cmd/gobind@v0.1.12
echo === done ===
dir "%USERPROFILE%\go\bin"
