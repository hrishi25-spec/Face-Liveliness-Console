@echo off
rem ============================================================================
rem  MOSSIP Face Liveness & PAD - one-command launcher for Windows.
rem
rem    start.bat                 build if needed, start the service, open console
rem    start.bat --no-build      skip Maven and launch the existing jar (fast)
rem    start.bat --rebuild       clean build + full test suite
rem    start.bat --port 9000     serve on a different port
rem    start.bat --no-browser    do not open a browser
rem    start.bat --profile prod  run against PostgreSQL instead of H2
rem ============================================================================
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "PORT=8000"
set "PROFILE=dev"
set "DO_BUILD=1"
set "DO_CLEAN=0"
set "RUN_TESTS=0"
set "OPEN_BROWSER=1"
set "JAR=target\pad-liveness-backend-1.0.0-SNAPSHOT.jar"
set "LOG=target\mossip-startup.log"
set "JAVA_BIN="

rem ------------------------------------------------------------------ arguments
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--help"   goto usage
if /i "%~1"=="-h"       goto usage
if /i "%~1"=="--port" (
  if "%~2"=="" ( echo [X] --port needs a value & exit /b 2 )
  set "PORT=%~2"
  shift
  shift
  goto parse
)
if /i "%~1"=="--profile" (
  if "%~2"=="" ( echo [X] --profile needs a value & exit /b 2 )
  set "PROFILE=%~2"
  shift
  shift
  goto parse
)
if /i "%~1"=="--no-build" ( set "DO_BUILD=0" & shift & goto parse )
if /i "%~1"=="--rebuild"  ( set "DO_BUILD=1" & set "DO_CLEAN=1" & set "RUN_TESTS=1" & shift & goto parse )
if /i "%~1"=="--test"     ( set "RUN_TESTS=1" & shift & goto parse )
if /i "%~1"=="--no-browser" ( set "OPEN_BROWSER=0" & shift & goto parse )
echo [X] Unknown option: %~1  (try start.bat --help)
exit /b 2
:parsed

echo.
echo  MOSIP Face Liveness ^& PAD Service - launcher
echo  ------------------------------------------------------------------
echo  port %PORT%   profile %PROFILE%
echo.

if not exist "target" mkdir "target"

rem ------------------------------------------------------------------ 1. find JDK
echo [.] Looking for a JDK 25 or newer...
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_BIN (
  for %%i in (java.exe) do if not "%%~$PATH:i"=="" set "JAVA_BIN=%%~$PATH:i"
)

if not defined JAVA_BIN (
  echo [X] No Java found on PATH and JAVA_HOME is not set.
  echo     Install a JDK 25+ then reopen this window:
  echo       winget install EclipseAdoptium.Temurin.25.JDK
  exit /b 1
)

set "JVER="
for /f "tokens=3" %%v in ('"%JAVA_BIN%" -version 2^>^&1 ^| findstr /i "version"') do if not defined JVER set "JVER=%%v"
if defined JVER set "JVER=!JVER:"=!"
if not defined JVER (
  echo [X] Could not determine the Java version from "%JAVA_BIN%".
  exit /b 1
)
set "JMAJOR="
for /f "tokens=1,2 delims=._" %%a in ("!JVER!") do (
  if "%%a"=="1" ( set "JMAJOR=%%b" ) else ( set "JMAJOR=%%a" )
)
if not defined JMAJOR set "JMAJOR=0"
if !JMAJOR! LSS 25 (
  echo [X] Java !JVER! is too old - this project needs JDK 25 or newer.
  echo     Found: %JAVA_BIN%
  exit /b 1
)
echo [ok] JDK !JMAJOR! -^> %JAVA_BIN%

rem ---------------------------------------------------------------- 2. find Maven
set "MVN="
if exist "mvnw.cmd" set "MVN=mvnw.cmd"
if not defined MVN for %%i in (mvn.cmd) do if not "%%~$PATH:i"=="" set "MVN=%%~$PATH:i"

rem ------------------------------------------------------------------- 3. build
if "%DO_BUILD%"=="1" (
  if not defined MVN (
    echo [X] Neither mvnw.cmd nor mvn.cmd is available, so the jar cannot be built.
    echo     Install Maven ^(https://maven.apache.org^) or run "start.bat --no-build"
    echo     once a prebuilt %JAR% exists.
    exit /b 1
  )
  if "%DO_CLEAN%"=="1" (
    echo [.] Clean build ^(this also runs the full test suite^)...
    call "%MVN%" clean package
  ) else if "%RUN_TESTS%"=="1" (
    echo [.] Building and running tests...
    call "%MVN%" package
  ) else (
    echo [.] Building ^(skipping tests - use --test to include them^)...
    call "%MVN%" -q package -DskipTests
  )
  if errorlevel 1 (
    echo [X] Build failed. Fix the errors above and try again.
    exit /b 1
  )
  if not exist "%JAR%" (
    echo [X] Build finished but %JAR% is missing.
    exit /b 1
  )
  echo [ok] Build complete
)

if not exist "%JAR%" (
  echo [X] %JAR% not found. Run "start.bat" without --no-build first.
  exit /b 1
)

rem ---------------------------------------------------------------- 4. check port
set "PORTBUSY="
for /f "delims=" %%L in ('netstat -ano ^| findstr /i "LISTENING" ^| findstr /c:":%PORT% "') do set "PORTBUSY=1"
if defined PORTBUSY (
  echo [X] Port %PORT% is already in use.
  echo     Stop the other process, or start on a different port:  start.bat --port 8001
  exit /b 1
)
echo [ok] Port %PORT% is free

rem --------------------------------------------------------------------- 5. run
echo [.] Starting service ^(log -^> %LOG%^)...
start "MOSIP Liveness Service" "%JAVA_BIN%" -Dfile.encoding=UTF-8 -Djava.awt.headless=true -jar "%JAR%" --spring.profiles.active=%PROFILE% --server.port=%PORT% --logging.file.name="%LOG%"

set "HAVECURL="
where curl >nul 2>&1 && set "HAVECURL=1"

set "READY=0"
for /l %%i in (1,1,120) do (
  if "!READY!"=="0" (
    if defined HAVECURL (
      curl -s -f -m 2 "http://127.0.0.1:%PORT%/health" >nul 2>&1
      if not errorlevel 1 set "READY=1"
    ) else (
      rem No curl (pre-1803 Windows): fall back to a fixed wait.
      timeout /t 1 /nobreak >nul 2>&1
      set /a WAITED+=1
      if !WAITED! GEQ 45 set "READY=1"
    )
    if "!READY!"=="0" timeout /t 1 /nobreak >nul 2>&1
  )
)

if not "!READY!"=="1" (
  echo.
  echo [!] The service did not become healthy in time.
  if exist "%LOG%" (
    echo     Last lines of %LOG%:
    echo     ------------------------------------------------------------
    powershell -NoProfile -Command "Get-Content -Tail 25 -Path '%LOG%' | ForEach-Object { '     ' + $_ }" 2>nul
    echo     ------------------------------------------------------------
  )
  call :stopservice
  exit /b 1
)

set "ENGINE=unknown"
if defined HAVECURL (
  for /f "delims=" %%E in ('curl -s -f -m 2 "http://127.0.0.1:%PORT%/health" 2^>nul') do set "HEALTHJSON=%%E"
  echo !HEALTHJSON! | findstr /c:"\"engine\":\"available\"" >nul 2>&1 && set "ENGINE=available"
  echo !HEALTHJSON! | findstr /c:"\"engine\":\"unavailable\"" >nul 2>&1 && set "ENGINE=unavailable"
)

echo.
echo [ok] Service is up
echo    Console      http://localhost:%PORT%/
echo    API docs     http://localhost:%PORT%/swagger-ui/index.html
echo    Health       http://localhost:%PORT%/health
if "!ENGINE!"=="available" (
  echo    Engine       available ^(OpenCV native library loaded^)
) else (
  echo    Engine       !ENGINE! - frame endpoints may return 503 on this platform
)
echo.

if "%OPEN_BROWSER%"=="1" start "" "http://localhost:%PORT%/"

echo  The service runs in a separate "MOSIP Liveness Service" window.
echo  Press any key here to STOP it, or just close that window.
echo.
pause >nul
call :stopservice
echo [ok] Stopped
exit /b 0

rem ---------------------------------------------------------------- subroutines
:stopservice
set "APPPID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /i "LISTENING" ^| findstr /c:":%PORT% "') do set "APPPID=%%p"
if defined APPPID (
  taskkill /F /PID !APPPID! >nul 2>&1
  echo [.] Stopped process !APPPID! listening on port %PORT%
) else (
  echo [.] Nothing was listening on port %PORT%
)
rem Close the service console window if it is still around.
taskkill /F /FI "WINDOWTITLE eq MOSIP Liveness Service*" >nul 2>&1
exit /b 0

:usage
echo.
echo  Usage: start.bat [options]
echo.
echo    --no-build      skip Maven, launch the existing jar
echo    --rebuild       clean build + full test suite
echo    --test          run tests during the build
echo    --port N        serve on port N (default 8000)
echo    --profile NAME  spring profile: dev (H2, default) or default (PostgreSQL)
echo    --no-browser    do not open a browser
echo    --help          show this help
echo.
exit /b 0
