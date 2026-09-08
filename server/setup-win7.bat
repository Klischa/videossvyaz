@echo off
rem =====================================================================
rem P2PCall — АВТОУСТАНОВКА сигнального сервера на Windows 7.
rem
rem Двойной клик — и скрипт сам:
rem   1. проверит Python (подойдёт любой 2.7/3.x, сервер без зависимостей);
rem   2. если Python нет — скачает и тихо установит Python 3.8.10;
rem   3. откроет порт в брандмауэре Windows;
rem   4. добавит сервер в автозапуск;
rem   5. запустит сервер.
rem
rem Использование: setup-win7.bat [порт] [noauto]
rem   порт   — по умолчанию 8080
rem   noauto — не добавлять в автозапуск
rem Пример: setup-win7.bat 8081
rem =====================================================================
setlocal EnableExtensions
chcp 65001 >nul 2>&1
cd /d "%~dp0"
title P2PCall - автоустановка сервера

set PORT=8080
if not "%~1"=="" set PORT=%~1
set NOAUTO=0
if /i "%~2"=="noauto" set NOAUTO=1

rem --- 0. Права администратора (нужны для установки Python и фаервола) ---
net session >nul 2>&1
if not %errorlevel%==0 (
  echo Запрашиваю права администратора — подтвердите в окне UAC...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -ArgumentList '%PORT% %~2' -Verb RunAs" >nul 2>&1
  exit /b
)

echo =====================================================================
echo  P2PCall — автоустановка сигнального сервера
echo  Порт: %PORT%
echo =====================================================================
echo.

rem --- 1. Поиск Python --------------------------------------------------
echo [1/5] Ищу Python...
set PY=
where python >nul 2>&1
if %errorlevel%==0 set PY=python
if "%PY%"=="" if exist C:\Python38\python.exe set PY=C:\Python38\python.exe
if "%PY%"=="" if exist "C:\Program Files\Python38\python.exe" set PY=C:\Python38\python.exe
if "%PY%"=="" if exist "%LocalAppData%\Programs\Python\Python38\python.exe" set PY=%LocalAppData%\Programs\Python\Python38\python.exe
if not "%PY%"=="" goto have_python

rem --- 2. Python нет — скачиваем установщик 3.8.10 ------------------------
if defined ProgramFiles(x86) (
  set PYURL=https://www.python.org/ftp/python/3.8.10/python-3.8.10-amd64.exe
) else (
  set PYURL=https://www.python.org/ftp/python/3.8.10/python-3.8.10.exe
)
set INSTALLER=%TEMP%\python-3.8.10-setup.exe
echo [2/5] Python не найден. Скачиваю Python 3.8.10 ~27 МБ ...
echo       %PYURL%
if exist "%INSTALLER%" del "%INSTALLER%" >nul 2>&1
powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('%PYURL%', '%INSTALLER%')" >nul 2>&1
if not exist "%INSTALLER%" certutil -urlcache -split -f "%PYURL%" "%INSTALLER%" >nul 2>&1
if not exist "%INSTALLER%" bitsadmin /transfer P2PCallPyDL /priority normal "%PYURL%" "%INSTALLER%" >nul 2>&1
if not exist "%INSTALLER%" goto dl_fail
set SIZE=0
for %%F in ("%INSTALLER%") do set SIZE=%%~zF
if %SIZE% LSS 10000000 goto dl_fail
echo       Скачано: %INSTALLER% (%SIZE% байт)

rem --- 3. Тихая установка Python ------------------------------------------
echo [3/5] Тихо устанавливаю Python 3.8.10, это займёт 1-2 минуты...
"%INSTALLER%" /quiet InstallAllUsers=1 TargetDir=C:\Python38 PrependPath=1 Include_test=0 >nul 2>&1
del "%INSTALLER%" >nul 2>&1
set "PATH=C:\Python38;C:\Python38\Scripts;%PATH%"
set PY=
where python >nul 2>&1
if %errorlevel%==0 set PY=python
if "%PY%"=="" if exist C:\Python38\python.exe set PY=C:\Python38\python.exe
if "%PY%"=="" if exist "C:\Program Files\Python38\python.exe" set PY=C:\Python38\python.exe
if "%PY%"=="" goto py_fail

:have_python
echo       Python: %PY%
%PY% --version 2>&1
echo.

rem --- 4. Брандмауэр -------------------------------------------------------
echo [4/5] Открываю TCP-порт %PORT% в брандмауэре Windows...
netsh advfirewall firewall delete rule name="P2PCall signaling" >nul 2>&1
netsh advfirewall firewall add rule name="P2PCall signaling" dir=in action=allow protocol=TCP localport=%PORT% >nul 2>&1
if %errorlevel%==0 ( echo       Готово. ) else ( echo       Не удалось — откройте порт вручную, см. WIN7_SERVER.md )
echo.

rem --- 5. Автозапуск -------------------------------------------------------
if "%NOAUTO%"=="1" (
  echo [5/5] Автозапуск пропущен (noauto).
) else (
  echo [5/5] Добавляю сервер в автозапуск Windows...
  powershell -NoProfile -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut([IO.Path]::Combine([Environment]::GetFolderPath('Startup'),'P2PCall signaling.lnk')); $s.TargetPath='%~dp0start-server.bat'; $s.WorkingDirectory='%~dp0'; $s.WindowStyle=7; $s.Save()" >nul 2>&1
  echo       Готово. Убрать можно удалением ярлыка из папки shell:startup.
)
echo.
echo =====================================================================
echo  Установка завершена. Запускаю сервер на порту %PORT%...
echo  Остановка — Ctrl+C или закрыть окно.
echo =====================================================================
echo.
"%PY%" signaling_server.py --port %PORT%
echo.
echo Сервер остановлен.
pause
exit /b 0

:dl_fail
echo.
echo [ОШИБКА] Не удалось скачать установщик Python.
echo Проверьте интернет и TLS 1.2 на Windows 7, либо скачайте вручную:
echo   64-bit: https://www.python.org/ftp/python/3.8.10/python-3.8.10-amd64.exe
echo   32-bit: https://www.python.org/ftp/python/3.8.10/python-3.8.10.exe
echo Установите с галочкой "Add Python to PATH" и запустите setup-win7.bat снова.
echo Подробнее — в WIN7_SERVER.md
echo.
pause
exit /b 1

:py_fail
echo.
echo [ОШИБКА] Python установился, но не найден в PATH.
echo Перезагрузите компьютер и запустите setup-win7.bat снова,
echo либо укажите путь вручную — см. WIN7_SERVER.md
echo.
pause
exit /b 1
