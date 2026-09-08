@echo off
rem P2PCall signaling server — запуск на Windows 7 двойным кликом.
rem Использование: start-server.bat [порт]
rem Пример: start-server.bat 8081
chcp 65001 >nul 2>&1
cd /d "%~dp0"
title P2PCall - signaling server

set PORT=8080
if not "%~1"=="" set PORT=%~1

set PY=
where python >nul 2>&1
if %errorlevel%==0 set PY=python
if "%PY%"=="" where py >nul 2>&1
if "%PY%"=="" if %errorlevel%==0 set PY=py -3
if "%PY%"=="" if exist C:\Python38\python.exe set PY=C:\Python38\python.exe
if "%PY%"=="" if exist "C:\Program Files\Python38\python.exe" set PY=C:\Python38\python.exe

if "%PY%"=="" (
  echo.
  echo [ОШИБКА] Python не найден!
  echo Установите Python 3.8.10 ^(последняя версия для Windows 7^):
  echo   https://www.python.org/downloads/release/python-3810/
  echo При установке поставьте галочку "Add Python to PATH".
  echo Подробнее — в файле WIN7_SERVER.md
  echo.
  pause
  exit /b 1
)

echo Запуск сервера на порту %PORT% ...
echo Остановка — Ctrl+C или закрыть окно.
echo.
%PY% signaling_server.py --port %PORT%
echo.
echo Сервер остановлен.
pause
