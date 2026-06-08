@echo off
cd /d "d:\Eink_ASProject\FirmwareManagement\test_server"
echo Starting HTTP server on port 8080...
python -m http.server 8080
pause