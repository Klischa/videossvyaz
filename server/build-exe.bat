@echo off
rem Сборка одиночного p2pcall-signaling.exe БЕЗ необходимости ставить Python
rem на целевой машине. Запускать ОДИН раз на компьютере с Windows 7 + Python 3.8.
rem Готовый exe появится в папке dist\ и будет работать на любой Win7.
chcp 65001 >nul 2>&1
cd /d "%~dp0"
title P2PCall - сборка exe

where python >nul 2>&1
if not %errorlevel%==0 (
  echo [ОШИБКА] Python не найден. Установите Python 3.8.10:
  echo   https://www.python.org/downloads/release/python-3810/
  pause
  exit /b 1
)

echo Установка PyInstaller...
python -m pip install --upgrade pyinstaller
if not %errorlevel%==0 (
  echo [ОШИБКА] Не удалось установить PyInstaller. Проверьте интернет.
  pause
  exit /b 1
)

echo Сборка exe...
python -m PyInstaller --onefile --console --name p2pcall-signaling signaling_server.py
echo.
if exist dist\p2pcall-signaling.exe (
  echo ГОТОВО: dist\p2pcall-signaling.exe
  echo Скопируйте его на Windows 7 машину и запускайте двойным кликом.
) else (
  echo [ОШИБКА] Сборка не удалась, смотрите лог выше.
)
pause
