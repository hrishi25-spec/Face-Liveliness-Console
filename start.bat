@echo off
rem ============================================================================
rem  MOSSIP Face Liveness & PAD - one-command launcher for Windows.
rem
rem    start.bat                 build if needed, start the service, open console
rem    start.bat --no-build      skip Maven and launch the existing jar (fast)
rem    start.bat --rebuild       clean build + full test suite
rem    start.bat --port 9000     serve on a different port
rem    start.bat --no-browser    do not open a browser
rem    start.bat --doctor        report what would block a start, without starting
rem    start.bat --profile prod  run against PostgreSQL instead of H2
rem    start.bat --lowmem        2-core / 2 GB tuning: capped heap, Serial GC,
rem                              class-data-sharing archive, 'lowmem' profile
rem ============================================================================
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "PORT=8000"
set "PROFILE=dev"
set "DO_BUILD=1"
set "DO_CLEAN=0"
set "RUN_TESTS=0"
set "OPEN_BROWSER=1"
set "DOCTOR=0"
set "LOWMEM=0"
set "JAR=target\pad-liveness-backend-1.0.0-SNAPSHOT.jar"
set "CDS=target\lowmem-cds.jsa"
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
if /i "%~1"=="--doctor"     ( set "DOCTOR=1" & shift & goto parse )
if /i "%~1"=="--lowmem"     ( set "LOWMEM=1" & shift & goto parse )
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
echo [.] Looking for a JDK 17 or newer...
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_BIN (
  for %%i in (java.exe) do if not "%%~$PATH:i"=="" set "JAVA_BIN=%%~$PATH:i"
)

if not defined JAVA_BIN (
  echo [X] No Java found on PATH and JAVA_HOME is not set.
  echo     Install a JDK 17+ then reopen this window:
  echo       winget install EclipseAdoptium.Temurin.21.JDK
  exit /b 1
)

rem java prints its version banner on stderr. Probe it through a file rather than
rem a pipe inside for /f: cmd.exe strips the quotes around the executable when a
rem for /f command contains a pipe, so a JDK under "C:\Program Files" is read as
rem the path "C:\Program" and the version cannot be determined.
set "JVER="
set "JVERFILE=target\java-version.txt"
"%JAVA_BIN%" -version > "%JVERFILE%" 2>&1
for /f "tokens=3" %%v in ('findstr /i "version" "%JVERFILE%"') do if not defined JVER set "JVER=%%v"
del "%JVERFILE%" >nul 2>&1
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
if !JMAJOR! LSS 17 (
  echo [X] Java !JVER! is too old - this project needs JDK 17 or newer.
  echo     Found: %JAVA_BIN%
  exit /b 1
)
echo [ok] JDK !JMAJOR! -^> %JAVA_BIN%

rem The doctor reports and stops here: building or launching is the opposite of
rem what it is for, and a usable JDK is a precondition for the checks below.
if "%DOCTOR%"=="1" goto doctor

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

rem ------------------------------------------------- 5a. lowmem JVM arguments
rem
rem --lowmem exists for the 2-core / 2 GB target: the heap is capped so the JVM
rem cannot grow into the memory the OS, the database and the OpenCV/ONNX natives
rem need, Serial GC keeps the collector's own threads and footprint down, and a
rem class-data-sharing archive skips re-parsing the classes this service loads
rem on every boot. The Spring half of the profile is application-lowmem.yml; each
rem flag's measured effect is in docs/configuration.md. Without --lowmem none of
rem this is applied.
set "JVM_OPTS=-Dfile.encoding=UTF-8 -Djava.awt.headless=true"
set "PROFILE_ARG=%PROFILE%"
set "CDS_NOTE=none"
if "%LOWMEM%"=="1" (
  set "PROFILE_ARG=%PROFILE%,lowmem"
  set "JVM_OPTS=!JVM_OPTS! -Xmx512m -XX:+UseSerialGC"
  rem The archive is only valid for the exact jar it was trained on (the JVM
  rem checks the jar's size and mtime), so a rebuild invalidates it; training it
  rem during a service boot would also need a gentle stop this launcher cannot
  rem guarantee (taskkill /F truncates the dump). It is therefore used when it
  rem exists and left out otherwise, and the one-time training command is in
  rem docs/configuration.md.
  if "%DO_BUILD%"=="1" if exist "%CDS%" del /q "%CDS%" >nul 2>&1
  if exist "%CDS%" (
    set "JVM_OPTS=!JVM_OPTS! -XX:SharedArchiveFile=%CDS%"
    set "CDS_NOTE=active"
  ) else (
    set "CDS_NOTE=not trained - see docs/configuration.md"
  )
)

rem --------------------------------------------------------------------- 5. run
echo [.] Starting service ^(log -^> %LOG%^)...
start "MOSIP Liveness Service" "%JAVA_BIN%" !JVM_OPTS! -jar "%JAR%" --spring.profiles.active=!PROFILE_ARG! --server.port=%PORT% --logging.file.name="%LOG%"

set "HAVECURL="
where curl >nul 2>&1 && set "HAVECURL=1"

set "READY=0"
for /l %%i in (1,1,120) do (
  if "!READY!"=="0" (
    if defined HAVECURL (
      curl --noproxy "*" -s -f -m 2 "http://127.0.0.1:%PORT%/health" >nul 2>&1
      if not errorlevel 1 set "READY=1"
    ) else (
      rem No curl (pre-1803 Windows): fall back to a fixed wait.
      call :sleep1
      set /a WAITED+=1
      if !WAITED! GEQ 45 set "READY=1"
    )
    if "!READY!"=="0" call :sleep1
  )
)

if not "!READY!"=="1" (
  echo.
  echo [X] The service did not become healthy in time.
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
  for /f "delims=" %%E in ('curl --noproxy "*" -s -f -m 2 "http://127.0.0.1:%PORT%/health" 2^>nul') do set "HEALTHJSON=%%E"
  echo !HEALTHJSON! | findstr /c:"\"engine\":\"available\"" >nul 2>&1 && set "ENGINE=available"
  echo !HEALTHJSON! | findstr /c:"\"engine\":\"unavailable\"" >nul 2>&1 && set "ENGINE=unavailable"
)

echo.
echo [ok] Service is up
echo    Console      http://localhost:%PORT%/
echo    API docs     http://localhost:%PORT%/swagger-ui/index.html
echo    Health       http://localhost:%PORT%/health
if /i "%PROFILE%"=="dev" (
  echo    Sign in      supervisor / supervisor123
)
if "%LOWMEM%"=="1" (
  echo    Lowmem       !JVM_OPTS!
  echo    Profile      !PROFILE_ARG!   class-data sharing: !CDS_NOTE!
)
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
rem One-second pause. "timeout /t 1" cannot be used: Git for Windows ships a
rem coreutils timeout.exe ahead of System32 on PATH, which rejects "/t", and
rem System32's own timeout.exe refuses to wait when stdin is redirected.
:sleep1
ping -n 2 127.0.0.1 >nul 2>&1
exit /b 0

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

rem ------------------------------------------------------------------- doctor
rem start.bat --doctor: report whether each of the preconditions to a successful
rem start looks OK - JDK, port, jar and (when nothing is running) the OpenCV
rem engine. When something is already on the port, it reports the live state of
rem that /health endpoint instead of pretending it can inspect the jar. It never
rem builds, launches or kills anything, so it is safe to run while diagnosing.
rem Exit code 0 when nothing would block a start, 1 when something would, which
rem makes it usable from a script as well as by hand.
:doctor
echo  Self-check only - nothing is built, started or stopped
echo  ------------------------------------------------------------------
set "DFAIL=0"

rem ------------------------------------------------------------- port ----
set "PORTPID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /i "LISTENING" ^| findstr /c:":%PORT% "') do if not defined PORTPID set "PORTPID=%%p"
if not defined PORTPID (
  echo [ok] Port     %PORT% is free
  goto doctor_jar
)
rem Something is listening. Ask it whether it is this service: only our own
rem /health answers with an "engine" field, so any other HTTP app holding the
rem port must be reported as a conflict, not as a running instance.
set "HEALTHJSON="
for /f "delims=" %%E in ('curl --noproxy "*" -s -f -m 2 "http://127.0.0.1:%PORT%/health" 2^>nul') do if not defined HEALTHJSON set "HEALTHJSON=%%E"
set "ISOURS="
echo !HEALTHJSON! | findstr /c:"engine" >nul 2>&1 && set "ISOURS=1"
if not defined ISOURS (
  echo [X] Port     %PORT% is in use by PID !PORTPID!, and /health did not answer as this service
  echo        stop that process, or use another port:  start.bat --port 8001
  set /a DFAIL+=1
  goto doctor_jar
)
rem The marker here is "[.]", not the "[!]" this script might otherwise use:
rem with EnableDelayedExpansion on, cmd eats a literal "!" anywhere on a line -
rem a lone one prints as "[]", and one that can pair with a later !var! swallows
rem the text between them. %PORTPID% is safe because this line runs after the
rem loop above, not inside a block.
echo [.] Port     %PORT% already serves this service ^(PID %PORTPID%^) - the engine line below is live

:doctor_jar
rem -------------------------------------------------------------- jar ----
if not exist "%JAR%" goto doctor_jar_missing
set "JSIZE="
for %%A in ("%JAR%") do set "JSIZE=%%~zA"
if not defined JSIZE goto doctor_jar_missing
set /a JMB=!JSIZE! / 1048576
echo [ok] Jar      %JAR% ^(!JMB! MiB^)
goto doctor_engine

:doctor_jar_missing
echo [X] Jar      %JAR% is missing - there is nothing to launch
set "HAVE_MAVEN="
if exist "mvnw.cmd" set "HAVE_MAVEN=1"
if not defined HAVE_MAVEN for %%i in (mvn.cmd) do if not "%%~$PATH:i"=="" set "HAVE_MAVEN=1"
if defined HAVE_MAVEN (
  echo        run start.bat once and it builds the jar with Maven
) else (
  echo        and no Maven was found either - install it, or restore a built jar
)
set /a DFAIL+=1

:doctor_engine
rem ------------------------------------------------------------ engine ----
rem /health is the authoritative answer here, because it reports
rem AppConfig.isOpenCvAvailable - the natives having actually loaded on this
rem machine, which no amount of inspecting the jar can prove.
if defined HEALTHJSON goto doctor_engine_live
if not exist "%JAR%" goto doctor_done
rem Otherwise inspect what this jar would load on Windows. The full build nests
rem every platform inside the openpnp jar; the -Pslim profile keeps linux/x86_64
rem only, which is exactly the jar that boots on Windows and then rejects every
rem frame as engine-unavailable.
set "JAR_TOOL="
if defined JAVA_BIN if exist "!JAVA_BIN:java.exe=jar.exe!" set "JAR_TOOL=!JAVA_BIN:java.exe=jar.exe!"
if not defined JAR_TOOL if defined JAVA_HOME if exist "!JAVA_HOME!\bin\jar.exe" set "JAR_TOOL=!JAVA_HOME!\bin\jar.exe"
if not defined JAR_TOOL (
  echo [.] Engine   not verified: no jar.exe found to inspect the jar - boot once, then re-run this
  goto doctor_done
)
set "JARLIST=target\jar-contents.txt"
del "%JARLIST%" >nul 2>&1
"!JAR_TOOL!" -tf "%JAR%" > "%JARLIST%" 2>nul
if errorlevel 1 (
  echo [.] Engine   not verified: jar.exe could not list the jar - boot once, then re-run this
  del "%JARLIST%" >nul 2>&1
  goto doctor_done
)
set "NATIVESTATE="
findstr /i /b /c:"BOOT-INF/lib/opencv-" "%JARLIST%" >nul 2>&1 && set "NATIVESTATE=BOOT-INF/lib/opencv-*.jar, the full cross-platform build"
if not defined NATIVESTATE (
  findstr /i /b /c:"BOOT-INF/classes/nu/pattern/opencv/windows/" "%JARLIST%" >nul 2>&1 && set "NATIVESTATE=BOOT-INF/classes, windows natives unpacked"
)
del "%JARLIST%" >nul 2>&1
if defined NATIVESTATE (
  echo [ok] Engine   Windows OpenCV natives are bundled: !NATIVESTATE!
  echo        that is what a fresh boot needs; /health is the live confirmation
  goto doctor_done
)
echo [X] Engine   the jar carries no Windows OpenCV natives
echo        built with -Pslim? that profile keeps linux/x86_64 only, so this jar
echo        would boot here but report the engine unavailable to every frame
echo        rebuild with:  mvnw.cmd clean package -DskipTests
set /a DFAIL+=1
goto doctor_done

:doctor_engine_live
set "ENGINE=unknown"
echo !HEALTHJSON! | findstr /c:"\"engine\":\"available\"" >nul 2>&1 && set "ENGINE=available"
echo !HEALTHJSON! | findstr /c:"\"engine\":\"unavailable\"" >nul 2>&1 && set "ENGINE=unavailable"
if "!ENGINE!"=="available" (
  echo [ok] Engine   loaded - the running service reports engine available
goto doctor_done
)
echo [X] Engine   the running service reports engine !ENGINE!
echo        it started, but OpenCV did not load on this platform - check the
echo        startup log for the loader's own message
set /a DFAIL+=1

:doctor_done
echo.
if !DFAIL! EQU 0 (
  echo [ok] Self-check passed - start.bat should start cleanly on port %PORT%
  exit /b 0
)
echo [X] Self-check found !DFAIL! blocking problem^(s^) - fix them before starting
exit /b 1

:usage
echo.
echo  Usage: start.bat [options]
echo.
echo    --no-build      skip Maven, launch the existing jar
echo    --rebuild       clean build + full test suite
echo    --test          run tests during the build
echo    --port N        serve on port N (default 8000)
echo    --profile NAME  spring profile: dev (H2, default) or default (PostgreSQL)
echo    --lowmem        2-core / 2 GB tuning (capped heap, Serial GC, CDS, lowmem profile)
echo    --no-browser    do not open a browser
echo    --doctor        report JDK, port, jar and OpenCV engine state, then exit
echo    --help          show this help
echo.
exit /b 0
