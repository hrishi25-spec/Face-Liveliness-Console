@echo off
REM MOSSIP Face Liveness & PAD — minimal Windows developer runner (jar only)
REM Usage: run.bat [--port N] [--profile dev^|default] [--no-browser]

setlocal enabledelayedexpansion
set PORT=8000
set PROFILE=dev
set OPEN_BROWSER=1

:parse_args
if "%~1"=="" goto :start
if "%~1"=="-p" if not "%~2"=="" set PORT=%~2 & shift & shift & goto :parse_args
if "%~1"=="--port" if not "%~2"=="" set PORT=%~2 & shift & shift & goto :parse_args
if "%~1"=="--port=%~2" set PORT=%~2 & shift & goto :parse_args
if "%~1"=="--profile" if not "%~2"=="" set PROFILE=%~2 & shift & shift & goto :parse_args
if "%~1"=="--no-browser" set OPEN_BROWSER=0 & shift & goto :parse_args
if "%~1"=="-h" if "%~2"=="" goto :help
if "%~1"=="--help" if "%~2"=="" goto :help
echo Unknown option: %~1
exit /b 1

:help
echo Usage: run.bat [--port N] [--profile dev^|default] [--no-browser]
exit /b 0

:start
REM Find JDK 17+
set "JAVA_BIN="
if defined JAVA_HOME (
  if "%JAVA_HOME:~-1%"=="\" set "JAVA_HOME=%JAVA_HOME:~0,-1%"
  if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
)
if not defined JAVA_BIN for %%i in (java.exe) do if not "%%~$PATH:i"=="" set "JAVA_BIN=%%~$PATH:i"

REM Last resort: the usual install folders. The wildcard has to be the final path
REM element to expand, so enumerate the JDK folders and look inside each one.
if not defined JAVA_BIN for /d %%d in ("%ProgramFiles%\Eclipse Adoptium\jdk*") do (
  if not defined JAVA_BIN if exist "%%~fd\bin\java.exe" set "JAVA_BIN=%%~fd\bin\java.exe"
)
if not defined JAVA_BIN for /d %%d in ("%ProgramFiles%\Java\jdk*") do (
  if not defined JAVA_BIN if exist "%%~fd\bin\java.exe" set "JAVA_BIN=%%~fd\bin\java.exe"
)
if not defined JAVA_BIN if exist "C:\openjdk\bin\java.exe" set "JAVA_BIN=C:\openjdk\bin\java.exe"

if not defined JAVA_BIN (
  echo Need JDK 17+. Install: winget install EclipseAdoptium.Temurin.21.JDK
  exit /b 1
)

REM java prints its version banner on stderr. Probe it through a file rather than a
REM pipe inside for /f: cmd.exe strips the quotes around the executable when a
REM for /f command contains a pipe, so a JDK under "C:\Program Files" is read as
REM the path "C:\Program" and the version cannot be determined.
set "JVER="
set "JVERFILE=%TEMP%\mossip-run-java-version.txt"
"%JAVA_BIN%" -version > "%JVERFILE%" 2>&1
for /f "tokens=3" %%v in ('findstr /i "version" "%JVERFILE%"') do if not defined JVER set "JVER=%%v"
del "%JVERFILE%" >nul 2>&1
if defined JVER set "JVER=%JVER:"=%"
if not defined JVER (
  echo Could not determine the Java version from "%JAVA_BIN%".
  exit /b 1
)
REM Accept any JDK 17 or newer: "17.0.1" -^> 17, legacy "1.8.0_382" -^> 8.
set "JMAJOR="
for /f "tokens=1,2 delims=._" %%a in ("%JVER%") do (
  if "%%a"=="1" ( set "JMAJOR=%%b" ) else ( set "JMAJOR=%%a" )
)
if not defined JMAJOR set "JMAJOR=0"
if %JMAJOR% LSS 17 (
  echo Java %JVER% is too old - this project needs JDK 17 or newer.
  echo   Found: %JAVA_BIN%
  exit /b 1
)
echo Using JDK %JMAJOR%: %JAVA_BIN%

REM Build if needed.
REM Skip both installer profiles: this is a developer launcher and only needs
REM the jar. Without these flags `package` would build the WiX-backed .exe (or
REM a .deb/.rpm on a unix-like host) as well.
set JAR=target\pad-liveness-backend-1.0.0-SNAPSHOT.jar
set SKIP_INSTALLERS=-Dlinux.package.skip=true -Dwindows.package.skip=true
if not exist "%JAR%" (
  echo Building...
  if exist "mvnw.cmd" (
    call mvnw.cmd -q package -DskipTests %SKIP_INSTALLERS%
  ) else (
    call mvn -q package -DskipTests %SKIP_INSTALLERS%
  )
)

REM Check port
powershell -Command "try { $tcp = New-Object Net.Sockets.TcpClient; $tcp.Connect('127.0.0.1', %PORT%); $tcp.Close(); exit 1 } catch { exit 0 }" || (echo Port %PORT% in use & exit /b 1)

REM Start
echo Starting service on port %PORT% with profile %PROFILE%...
start /B "" "%JAVA_BIN%" -Dfile.encoding=UTF-8 -Djava.awt.headless=true -jar "%JAR%" --spring.profiles.active=%PROFILE% --server.port=%PORT%

REM Wait for health. Sleep with ping, not timeout: Git for Windows ships its own
REM timeout.exe ahead of System32, so "timeout /t 1" fails with "invalid time
REM interval '/t'" when this script is launched from Git Bash.
echo Waiting for service...
for /l %%i in (1,1,60) do (
  curl --noproxy "*" -fs http://127.0.0.1:%PORT%/health >nul 2>&1 && goto :running
  ping -n 2 127.0.0.1 >nul 2>&1
)
echo Service failed to start
exit /b 1

:running
echo.
echo ✓ Service running on http://localhost:%PORT%
echo   Console:     http://localhost:%PORT%/
echo   API docs:    http://localhost:%PORT%/swagger-ui/index.html
echo   Health:      http://localhost:%PORT%/health
if /i "%PROFILE%"=="dev" (
  echo   Sign in:     supervisor / supervisor123
)
if %OPEN_BROWSER%==1 start "" http://localhost:%PORT%/
echo.
echo Press Ctrl+C to stop.
pause
