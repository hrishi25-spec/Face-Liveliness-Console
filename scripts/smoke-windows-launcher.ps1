#requires -version 5.1
<#
  Boot the packaged full jar through the real Windows launcher (start.bat) and
  prove it serves.

  Every other gate in this repository runs on Linux, which leaves two things
  untested at once:

    * start.bat's own code - the JDK 17+ probe, the version parse, the port
      check, the /health wait and the engine report. The probe is the fragile
      one: it reads `java -version`, which prints on *stderr*, through cmd.exe,
      and cmd.exe strips the quotes around an executable when a `for /f`
      command contains a pipe. A JDK under "C:\Program Files" was then read as
      the path "C:\Program" and the version could not be determined at all -
      which is exactly how this launcher broke, and it is invisible on a machine
      whose JDK lives under a space-free path.
    * The full jar's Windows OpenCV natives. scripts/smoke-jar.sh boots the jar
      on linux x86_64 and asserts `engine: available`; the same nested openpnp
      jar carries a different native for Windows, and nothing booted it there.

  So this script refuses to run against a JDK whose path has no space. A JDK at
  C:\hostedtoolcache\... would exercise the probe with a path cmd.exe cannot
  mangle, and a green run would then mean nothing at all.

  It never builds. Package the full jar first - that is what CI does, and it is
  what makes this a packaging check rather than a source check:

      .\mvnw.cmd --batch-mode clean package -DskipTests

  The jar name is read back out of start.bat, so the check cannot pass by
  asserting against a jar the launcher would not have found. That also means a
  release-stamped build (-Drevision=...) is not what this covers: the launcher
  pins the snapshot name.

  Exit 0 when the launcher found the JDK, found the port free, reported the
  service up with the engine available, and stopped it again. Exit 1 on any
  failure, after printing the launcher's own output. Exit 2 when the machine
  cannot run the check at all (not Windows, no JDK under a path with a space,
  no packaged full jar).

  ASCII only, deliberately: Windows PowerShell 5.1 reads a script without a BOM
  as ANSI, so a UTF-8 em dash in a string is a parse error there.
#>
[CmdletBinding()]
param(
  # The JDK to run the launcher with. Must be a real path containing a space -
  # see the header. Defaults to JAVA_HOME, then to `java.exe` on PATH.
  [string] $JavaHome = $env:JAVA_HOME,

  # 0 picks a free port, so the check does not collide with anything else on the
  # machine. An explicit port reproduces a specific case (9000, a port already
  # held by another app, ...).
  [int] $Port = 0,

  # Spring profile. `dev` is in-memory H2 and needs no database service, which
  # is what makes this runnable on a bare runner.
  [string] $Profile = 'dev',

  # Stall guard, not the assertion: the launcher's own health wait is a fixed
  # budget of about two minutes, and a cold first boot on a slow machine can
  # legitimately spend most of it extracting the ~65 MB OpenCV native.
  [int] $TimeoutSeconds = 420
)

$ErrorActionPreference = 'Stop'

$RepositoryRoot = Split-Path -Parent $PSScriptRoot
$LauncherPath = Join-Path $RepositoryRoot 'start.bat'

# The launcher prints its failures with this marker, so a run that reports one
# cannot be mistaken for a pass however the exit code reads.
$FailureMarker = '[X] '

function Exit-Check {
  param(
    [string] $Message,
    [string[]] $Detail = @(),
    [int] $Code = 1
  )

  Write-Host ''
  Write-Host "::error::$Message"
  foreach ($line in $Detail) { Write-Host "         $line" }
  exit $Code
}

# Whatever still listens on the port, after the launcher has had its chance.
# start.bat stops the service itself on every path it owns; this covers the
# paths it does not - a health wait that timed out, or a failed assertion here
# after the service is already up - so a red run cannot leak a JVM that would
# hold the port (and a GB of RAM) for the rest of the job.
function Get-PortListener {
  param([int] $Port)

  try {
    return @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop)
  } catch {
    return @()
  }
}

# Stop-Process, not `taskkill`: taskkill reports its failures on stderr, and with
# this script's $ErrorActionPreference = 'Stop' a redirected native stderr is a
# terminating error - so a PID that exited between the listing and the kill (a
# real race) would make cleanup itself the failure. The port is held by a single
# process, so taskkill's tree kill buys nothing here either.
function Stop-PortListener {
  param([int] $Port)

  foreach ($listener in (Get-PortListener -Port $Port)) {
    Write-Host "[.] stopping PID $($listener.OwningProcess) still listening on port $Port"
    try {
      Stop-Process -Id $listener.OwningProcess -Force -ErrorAction Stop
    } catch {
      Write-Host "         (could not stop PID $($listener.OwningProcess): $($_.Exception.Message))"
    }
  }
}

# Run start.bat through cmd.exe and collect everything it printed.
function Invoke-Launcher {
  param(
    [string] $LauncherArguments,
    [string] $JavaHomePath,
    [string] $WorkingDirectory,
    [int] $TimeoutSeconds
  )

  $startInfo = New-Object System.Diagnostics.ProcessStartInfo
  $startInfo.FileName = $env:ComSpec
  # `call "<full path>"` rather than a bare `start.bat`, for two reasons. cmd
  # only searches the current directory for a command when
  # NoDefaultCurrentDirectoryInExePath is unset, and Git Bash sets it - so the
  # documented `pwsh -File scripts/smoke-windows-launcher.ps1` run from a Git
  # Bash shell would otherwise fail with "'start.bat' is not recognized". Going
  # through `call` with a quoted path also keeps cmd's /c quote-stripping rules
  # out of the picture: a checkout whose path contains a space or an `&` would
  # otherwise depend on how cmd decides to re-parse the line.
  #
  # 2>&1 folds stderr into stdout inside cmd, so a single stream is enough to
  # read and one buffer cannot fill and deadlock the run.
  $startInfo.Arguments = '/c call "' + $LauncherPath + '" ' + $LauncherArguments + ' 2>&1'
  $startInfo.WorkingDirectory = $WorkingDirectory
  $startInfo.UseShellExecute = $false
  $startInfo.RedirectStandardInput = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.CreateNoWindow = $true

  # start.bat resolves the JDK from JAVA_HOME first and PATH second; both point
  # at the JDK under test so the run cannot silently fall back to another one
  # (a runner has two, and the other one has no space in its path).
  $startInfo.EnvironmentVariables['JAVA_HOME'] = $JavaHomePath
  $startInfo.EnvironmentVariables['Path'] = (Join-Path $JavaHomePath 'bin') + ';' + $startInfo.EnvironmentVariables['Path']

  $process = [System.Diagnostics.Process]::Start($startInfo)
  # Read asynchronously: a synchronous ReadToEnd would block past the timeout
  # below if the launcher ever stopped producing output without exiting.
  $reading = $process.StandardOutput.ReadToEndAsync()
  # Closed immediately on purpose. start.bat ends with `pause >nul`, and EOF on
  # stdin is what makes `pause` return instead of waiting for a keypress no CI
  # job can send.
  $process.StandardInput.Close()

  if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
    $process.Kill()
    Exit-Check "start.bat did not finish within ${TimeoutSeconds}s" @(
      'Killed it. Its own health wait is ~2 minutes; a boot that hangs past that is a finding,'
      'not a slow machine - the launcher only looks interactive when stdin is not closed.'
    )
  }
  $process.WaitForExit()

  return @{
    Output = $reading.GetAwaiter().GetResult()
    ExitCode = $process.ExitCode
  }
}

# ------------------------------------------------------------------ 1. preconditions

if ($env:OS -ne 'Windows_NT') {
  Exit-Check 'this check is Windows-only - it boots start.bat' @(
    'On Linux or macOS the jar is covered by ./scripts/smoke-jar.sh instead.'
  ) 2
}
if (-not (Test-Path -LiteralPath $LauncherPath -PathType Leaf)) {
  Exit-Check "start.bat was not found at $LauncherPath" @('Run this from a checkout: .\scripts\smoke-windows-launcher.ps1') 2
}

# JAVA_HOME commonly carries a trailing separator; the launcher appends
# `\bin\java.exe` to it, and the doubled separator would keep this script's JDK
# assertion from matching what the launcher prints.
if ($JavaHome) { $JavaHome = $JavaHome.TrimEnd('\') }

$javaExe = $null
if ($JavaHome) {
  $candidate = Join-Path $JavaHome 'bin\java.exe'
  if (Test-Path -LiteralPath $candidate -PathType Leaf) {
    $javaExe = (Get-Item -LiteralPath $candidate).FullName
  }
}
if (-not $javaExe) {
  $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
  if ($onPath) { $javaExe = $onPath.Source }
  if ($JavaHome -and $javaExe) {
    Write-Host "[.] JAVA_HOME=$JavaHome has no bin\java.exe - using $javaExe from PATH"
  }
}
if (-not $javaExe) {
  Exit-Check 'no java.exe found' @(
    'Set JAVA_HOME to a JDK 17+ under a path with a space, or put one on PATH.'
  ) 2
}

# The whole point of this check. A space-free JDK cannot reproduce the
# `for /f` quoting failure, so a pass here would not have proved anything.
if ($javaExe -notmatch ' ') {
  Exit-Check "the JDK this found is at '$javaExe', which has no space in its path" @(
    'This check exists to cover cmd.exe stripping the quotes around a JDK under',
    '"C:\Program Files" - a space-free path cannot reproduce that, so a green run',
    'would mean nothing. Point JAVA_HOME at a stock install:',
    '  winget install EclipseAdoptium.Temurin.21.JDK',
    '  $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21..."'
  ) 2
}

# The jar the launcher will boot, read out of the launcher rather than repeated
# here: start.bat pins the name, so a literal in this script could drift from it
# and the check would then pass against a jar nobody launches.
$jarLine = Select-String -LiteralPath $LauncherPath -Pattern '^\s*set "JAR=([^"]+)"' | Select-Object -First 1
if (-not $jarLine) {
  Exit-Check 'could not read the pinned jar name out of start.bat' @(
    'This script follows `set "JAR=..."` in start.bat; update the pattern if that line moved.'
  ) 2
}
$launcherJar = $jarLine.Matches[0].Groups[1].Value.Trim()
$jarPath = Join-Path $RepositoryRoot $launcherJar
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
  $detail = @('Build it first:  .\mvnw.cmd --batch-mode clean package -DskipTests')
  $slim = @(Get-ChildItem -LiteralPath (Join-Path $RepositoryRoot 'target') -Filter '*-slim.jar' -ErrorAction SilentlyContinue)
  if ($slim.Count -gt 0) {
    $detail = @(
      "target\ holds $($slim[0].Name) instead - that is the -Pslim profile, which keeps",
      'linux/x86_64 only: on Windows it boots and then reports the engine unavailable.',
      'Build the full jar:  .\mvnw.cmd --batch-mode clean package -DskipTests'
    )
  }
  Exit-Check "$launcherJar is missing - the launcher has nothing to boot" $detail 2
}

if ($Port -le 0) {
  $probe = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
  $probe.Start()
  $Port = $probe.LocalEndpoint.Port
  $probe.Stop()
}

Write-Host 'smoke testing the Windows launcher'
Write-Host "  launcher  $LauncherPath"
Write-Host "  JDK       $javaExe"
Write-Host "  jar       $launcherJar"
Write-Host "  port      $Port (profile $Profile)"

# ------------------------------------------------------------------ 2. jar natives

# The doctor is the cheap half and runs first. With nothing on the port it
# inspects what the jar would load here: the full build nests the whole
# cross-platform openpnp jar, so Windows natives are present in it. It also
# exercises the launcher's argument parsing and JDK report without booting
# anything, so a jar the launcher cannot even find fails in two seconds.
Write-Host ''
Write-Host '[.] start.bat --doctor (jar inspection, nothing is built or started)'
$doctor = Invoke-Launcher -LauncherArguments "--doctor --port $Port --no-browser" -JavaHomePath $JavaHome -WorkingDirectory $RepositoryRoot -TimeoutSeconds 120
Write-Host $doctor.Output
if ($doctor.ExitCode -ne 0) {
  Exit-Check "start.bat --doctor exited $($doctor.ExitCode) - something would block a start" @(
    'The output above says which precondition failed.'
  )
}
if ($doctor.Output -notmatch '\[ok\] Engine\s+Windows OpenCV natives are bundled') {
  Exit-Check 'the packaged jar carries no Windows OpenCV natives, or jar.exe could not list it' @(
    'Expected `[ok] Engine   Windows OpenCV natives are bundled` from start.bat --doctor.',
    'Built with -Pslim? That profile keeps linux/x86_64 only, so this jar boots here',
    'and then reports the engine unavailable to every frame.'
  )
}

# ------------------------------------------------------------------ 3. boot

try {
  Write-Host ''
  Write-Host '[.] start.bat --no-build (boots the packaged jar and waits for /health)'
  $boot = Invoke-Launcher -LauncherArguments "--no-build --port $Port --no-browser --profile $Profile" -JavaHomePath $JavaHome -WorkingDirectory $RepositoryRoot -TimeoutSeconds $TimeoutSeconds
  Write-Host $boot.Output

  if ($boot.ExitCode -ne 0) {
    Exit-Check "the launcher exited $($boot.ExitCode)" @(
      'Its output is above; the startup log it points at is target\mossip-startup.log.'
    )
  }
  if ($boot.Output -match [regex]::Escape($FailureMarker)) {
    Exit-Check 'the launcher printed a failure marker on a successful exit code' @(
      'Reported above. A launcher that reports a problem and exits 0 is a finding of its own.'
    )
  }

  # The quoting regression, asserted directly: the launcher read the JDK version
  # out of a path with a space in it. Before the fix it stopped here with
  # `[X] Could not determine the Java version from "C:\Program Files\...`.
  $jdkMatch = [regex]::Match($boot.Output, '\[ok\] JDK (\d+) +->')
  if (-not $jdkMatch.Success) {
    Exit-Check 'the launcher did not report the JDK it resolved' @(
      'Expected `[ok] JDK <major> -> <path>`: the version probe is what a JDK under',
      'a path with a space used to break.'
    )
  }
  if ([int] $jdkMatch.Groups[1].Value -lt 17) {
    Exit-Check "the launcher resolved JDK $($jdkMatch.Groups[1].Value), which this project does not run on" @(
      'JDK 17 is the floor (pom.xml <java.version>); CI builds and boots on 21.'
    )
  }
  if (-not $boot.Output.ToLower().Contains($javaExe.ToLower())) {
    Exit-Check 'the launcher reported a JDK other than the one under test' @(
      "Wanted: $javaExe",
      "The launcher prints what it resolved: $($jdkMatch.Value)",
      'JAVA_HOME and PATH are both pointed at the JDK under test, so this means the',
      'launcher picked up something else - which would make the quoting check vacuous.'
    )
  }

  if ($boot.Output -notmatch "\[ok\] Port +$Port is free") {
    Exit-Check "the launcher did not report port $Port free before starting" @(
      'Its own port check runs before the boot; a missing line means it was skipped.'
    )
  }
  if ($boot.Output -notmatch '\[ok\] Service is up') {
    Exit-Check 'the launcher never reported the service up' @(
      'Its /health wait is bounded at ~2 minutes. The startup log it printed above',
      'says whether the jar died or was merely slow.'
    )
  }

  # The native assertion, the same one scripts/smoke-jar.sh makes on Linux: the
  # full jar must load the Windows OpenCV native. A jar that kept only
  # linux/x86_64 boots happily and reports `unavailable` here instead, and the
  # launcher's own line is a live /health read (AppConfig.isOpenCvAvailable).
  if ($boot.Output -notmatch 'Engine\s+available') {
    Exit-Check 'the service is up but OpenCV did not load - the engine is unavailable' @(
      'This is the Windows half of what scripts/smoke-jar.sh asserts on Linux:',
      'the packaged full jar must carry a Windows native AND load it here.',
      'The line above and target\mossip-startup.log carry the loader message.'
    )
  }

  if ($boot.Output -notmatch '\[ok\] Stopped') {
    Exit-Check 'the launcher did not report stopping the service it started' @(
      'Its cleanup runs after `pause`, which returns at EOF on a closed stdin.'
    )
  }
  if ((Get-PortListener -Port $Port).Count -gt 0) {
    Exit-Check "something is still listening on port $Port after the launcher stopped" @(
      'A leaked service means the next run on a fixed port starts against someone else.',
      'Killed it above.'
    )
  }
} finally {
  # Never leave the service behind: not on a failed assertion, not on a timeout.
  Stop-PortListener -Port $Port
}

Write-Host ''
Write-Host '[ok] the Windows launcher booted the packaged full jar and the engine is available'

if ($env:GITHUB_STEP_SUMMARY) {
  $summary = @'
### Windows launcher smoke - engine available

| what | value |
| --- | --- |
| launcher | `start.bat --no-build`, pinned jar `{0}` |
| JDK, under a path with a space | `{1}` |
| port | {2} |
| `/health` engine | `available` |

Booted through the launcher rather than `java -jar`, so the JDK probe (a path
cmd.exe could mangle), the port check, the health wait and the engine report all
ran on the real code path.
'@ -f $launcherJar, $javaExe, $Port
  Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Value $summary
}

exit 0
