@echo off
setlocal
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "JDK=%ROOT%\..\bcu-crazy-patch\tools\jdk-17.0.13+11"
if not exist "%JDK%\bin\javac.exe" set "JDK=%JAVA_HOME%"
if not exist "%JDK%\bin\javac.exe" (
  echo [ERROR] Set JAVA_HOME to your JDK 17 folder, then try again.
  exit /b 1
)
set "BUILD=%ROOT%\build"
set "SOURCE=%ROOT%\installer-src\bcu\attackld\install\AttackLdInstaller.java"
set "PAYLOAD=%ROOT%\payload\attack-ld-editor-agent.jar"
set "OUTPUT=%ROOT%\Install-BCU-Attack-LD-Editor.jar"
if not exist "%PAYLOAD%" (
  echo [ERROR] Missing payload: %PAYLOAD%
  exit /b 1
)
if exist "%BUILD%" rmdir /s /q "%BUILD%"
mkdir "%BUILD%\classes\payload"
"%JDK%\bin\javac.exe" --release 8 -encoding UTF-8 -d "%BUILD%\classes" "%SOURCE%"
if errorlevel 1 exit /b 1
copy /y "%PAYLOAD%" "%BUILD%\classes\payload\attack-ld-editor-agent.jar" >nul
"%JDK%\bin\jar.exe" cfe "%OUTPUT%" bcu.attackld.install.AttackLdInstaller -C "%BUILD%\classes" .
if errorlevel 1 exit /b 1
rmdir /s /q "%BUILD%"
echo [OK] %OUTPUT%
endlocal
