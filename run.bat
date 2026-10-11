@echo off
REM MOSSIP Face Liveness & PAD — minimal Windows runner
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
set JAVA_BIN=
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
  "%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /r "version \"1[7-9]\." >nul && set JAVA_BIN=%JAVA_HOME%\bin\java.exe
)
if not defined JAVA_BIN (
  where java 2>nul | findstr /r "version \"1[7-9]\." >nul 2>&1 && set JAVA_BIN=java
)
if not defined JAVA_BIN (
  for %%p in (
    "C:\Program Files\Java\jdk*\bin\java.exe"
    "C:\Program Files\Eclipse Adoptium\jdk*\bin\java.exe"
    "C:\openjdk\bin\java.exe"
  ) do if not defined JAVA_BIN if exist "%%p" (
    "%%p" -version 2>&1 | findstr /r "version \"1[7-9]\." >nul && set JAVA_BIN=%%p
  )
)
if not defined JAVA_BIN (
  echo Need JDK 17+. Install: winget install EclipseAdoptium.Temurin.21.JDK
  exit /b 1
)

REM Build if needed
set JAR=target\pad-liveness-backend-1.0.0-SNAPSHOT.jar
if not exist "%JAR%" (
  echo Building...
  call mvnw -q package -DskipTests
)

REM Check port
powershell -Command "try { $tcp = New-Object Net.Sockets.TcpClient; $tcp.Connect('127.0.0.1', %PORT%); $tcp.Close(); exit 1 } catch { exit 0 }" || (echo Port %PORT% in use & exit /b 1)

REM Start
echo Starting service on port %PORT% with profile %PROFILE%...
start /B "" "%JAVA_BIN%" -Dfile.encoding=UTF-8 -Djava.awt.headless=true -jar "%JAR%" --spring.profiles.active=%PROFILE% --server.port=%PORT%

REM Wait for health
echo Waiting for service...
for /l %%i in (1,1,60) do (
  curl -fs http://127.0.0.1:%PORT%/health >nul 2>&1 && goto :running
  timeout /t 1 >nul
)
echo Service failed to start
exit /b 1

:running
echo.
echo ✓ Service running on http://localhost:%PORT%
echo   Console:     http://localhost:%PORT%/
echo   API docs:    http://localhost:%PORT%/swagger-ui/index.html
echo   Health:      http://localhost:%PORT%/health
if %OPEN_BROWSER%==1 start "" http://localhost:%PORT%/
echo.
echo Press Ctrl+C to stop.
pause
