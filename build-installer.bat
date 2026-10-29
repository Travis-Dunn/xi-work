@echo off
cd /d "%~dp0"
call mvn clean package -Pinstaller
exit /b %errorlevel%
