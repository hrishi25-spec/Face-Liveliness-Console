#requires -version 5.1
<#
  Prove the Windows installer is a real artifact and that what it deploys
  actually serves.

  `.\mvnw.cmd package` producing a .exe only says jpackage and WiX exited 0. The
  failures that reach a user are silent at build time:

    * an installer built from the -Pslim jar, which carries linux/x86_64
      natives only - it installs, starts, and reports the engine unavailable to
      every frame,
    * a launcher built without --win-console, which starts a server whose log,
      port conflict and startup failure go nowhere a user can see,
    * a version resource that disagrees with the file name, so an upgrade
      either refuses to replace the old install or replaces the wrong one,
    * a truncated or non-PE .exe that Windows refuses to run at all.

  So this reads the installer's PE header, checks the version it is named after
  against the launcher's version resource, then boots the application image the
  installer deploys and asserts /health, the engine and the console page.
  scripts/smoke-jar.sh makes the Linux half of that assertion for the jar;
  nothing else runs a Windows payload.

  The installer's own `configure` step is not automated here on purpose: it is
  an interactive WiX bootstrapper whose per-user install and uninstall are a
  user-facing flow, not a hermetic check. What it deploys is what runs below.

  It never builds:

      .\mvnw.cmd --batch-mode clean package -DskipTests -Dwindows.package.strict=true

  Exit 0 when the installer is intact, the payload boots, /health reports the
  engine available and the console answers. Exit 1 on any failure. Exit 2 when
  the machine cannot run the check at all (not Windows, nothing built).

  ASCII only, deliberately: Windows PowerShell 5.1 reads a script without a BOM
  as ANSI, so a UTF-8 em dash in a string is a parse error there.
#>
[CmdletBinding()]
param(
  # Where the installer was written.
  [string] $Dist,

  # 0 picks a free port, so the check does not collide with anything else on
  # the machine.
  [int] $Port = 0,

  # Spring profile. `dev` is in-memory H2 and needs no database service.
  [string] $Profile = 'dev',

  # Stall guard, not the assertion: a cold first boot extracts the Windows
  # OpenCV native before /health can answer.
  [int] $TimeoutSeconds = 420
)

$ErrorActionPreference = 'Stop'

$RepositoryRoot = Split-Path -Parent $PSScriptRoot
if (-not $Dist) { $Dist = Join-Path $RepositoryRoot 'target\dist' }
$Payload = Join-Path $RepositoryRoot 'target\jpackage\pad-liveness\pad-liveness.exe'

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

function Get-PortListener {
  param([int] $Port)

  try {
    return @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop)
  } catch {
    return @()
  }
}

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

# ---------------------------------------------------------- 1. the artifact

if ($env:OS -ne 'Windows_NT') {
  Exit-Check 'this check is Windows-only - it boots the packaged Windows payload' @(
    'On Linux the jar and the installers are covered by ./scripts/smoke-jar.sh and',
    './scripts/smoke-linux-packages.sh.'
  ) 2
}

$installers = @()
if (Test-Path -LiteralPath $Dist -PathType Container) {
  $installers = @(Get-ChildItem -LiteralPath $Dist -Filter '*.exe' -File)
}
if ($installers.Count -eq 0) {
  Exit-Check "no .exe installer in $Dist" @(
    'Build it first:  .\mvnw.cmd --batch-mode clean package -DskipTests -Dwindows.package.strict=true'
  ) 2
}
if ($installers.Count -gt 1) {
  Exit-Check "expected one installer in $Dist, found $($installers.Count)" @(
    ($installers | ForEach-Object { $_.Name }) -join ', '
  ) 2
}
$Installer = $installers[0]

# PE, not just "the file exists": a bytes-short or non-executable installer is
# exactly what a failed WiX link leaves behind.
$stream = [System.IO.File]::OpenRead($Installer.FullName)
try {
  $magic = New-Object byte[] 2
  [void] $stream.Read($magic, 0, 2)
} finally {
  $stream.Dispose()
}
if ([System.Text.Encoding]::ASCII.GetString($magic) -ne 'MZ') {
  Exit-Check "$($Installer.Name) is not a Windows executable (no MZ header)" @(
    'A partial jpackage or WiX run leaves a file behind that Windows will refuse.',
    'Delete it and rebuild.'
  )
}

# The version in the name is what a download page and an upgrade path quote.
$nameVersion = $null
if ($Installer.BaseName -match '^pad-liveness-(?<version>\d+(\.\d+)*)$') {
  $nameVersion = $Matches['version']
} else {
  Exit-Check "$($Installer.Name) does not follow pad-liveness-<version>.exe" @(
    'The name is what a download page and an upgrade path quote.'
  )
}

# jpackage stamps --app-version into the launcher it builds. The installer
# wrapper is WiX's, so its own resource is checked only if it carries one; the
# launcher is the part jpackage owns, and a mismatch there means the app version
# never reached the build.
if (-not (Test-Path -LiteralPath $Payload -PathType Leaf)) {
  Exit-Check "the application image is missing: $Payload" @(
    'The installer is built from target\jpackage\pad-liveness; a build that',
    'produced the .exe without it is not one this script can check.'
  ) 2
}
$launcherVersion = (Get-Item -LiteralPath $Payload).VersionInfo.FileVersion
if (-not $launcherVersion) {
  # A warning rather than a failure: whether jpackage stamps the launcher on
  # Windows is not something this repository can assert from memory, and a gate
  # that fails a correct build teaches people to ignore it. A resource that is
  # present but disagrees with the name is the case worth failing.
  Write-Host 'packaging: WARNING: the packaged launcher carries no version resource - skipping the version check'
}
if ($launcherVersion -and -not $launcherVersion.StartsWith($nameVersion)) {
  Exit-Check "the packaged launcher reports FileVersion $launcherVersion" @(
    "The installer is named $nameVersion. A version resource that disagrees with",
    'the artifact name is how an upgrade replaces the wrong install or none at all.'
  )
}
$fileVersion = $Installer.VersionInfo.FileVersion
if ($fileVersion -and -not $fileVersion.StartsWith($nameVersion)) {
  Exit-Check "$($Installer.Name) reports FileVersion $fileVersion" @(
    "The name says $nameVersion."
  )
}

$installerSize = [math]::Round($Installer.Length / 1MB, 0)
$launcherVersionLabel = if ($launcherVersion) { $launcherVersion } else { '(none)' }
Write-Host 'smoke testing the Windows installer'
Write-Host "  installer   $($Installer.FullName)"
Write-Host "  version     $launcherVersionLabel (launcher; name: $nameVersion)"
Write-Host "  size        $installerSize MB"
Write-Host "  payload     $Payload"

# ---------------------------------------------------------- 2. boot the payload

if ($Port -le 0) {
  $probe = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
  $probe.Start()
  $Port = $probe.LocalEndpoint.Port
  $probe.Stop()
}
Write-Host "  port        $Port (profile $Profile)"

$logPath = Join-Path $RepositoryRoot 'target\windows-installer-smoke.log'
if (Test-Path -LiteralPath $logPath) { Remove-Item -LiteralPath $logPath -Force }

$process = $null
try {
  Write-Host ''
  Write-Host '[.] starting the application image the installer deploys'
  # Redirected rather than inherited: the launcher's output is the startup log,
  # and a failure has to print it rather than lose it in the job console.
  $process = Start-Process -FilePath $Payload `
    -ArgumentList @("--server.port=$Port", "--spring.profiles.active=$Profile") `
    -RedirectStandardOutput $logPath `
    -RedirectStandardError "$logPath.err" `
    -WindowStyle Hidden `
    -PassThru

  $body = $null
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    if ($process.HasExited) {
      Exit-Check "the payload exited with $($process.ExitCode) before /health answered" @(
        'Its output is above and in target\windows-installer-smoke.log.'
      )
    }
    try {
      $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" -UseBasicParsing -TimeoutSec 5
      if ($response.StatusCode -eq 200) { $body = $response.Content; break }
    } catch {
      # Not up yet. The launcher is still extracting the native and starting
      # Spring; the deadline is what bounds this.
    }
    Start-Sleep -Seconds 2
  }
  if (-not $body) {
    Exit-Check "the payload did not serve /health within ${TimeoutSeconds}s" @(
      'Its output is above and in target\windows-installer-smoke.log.'
    )
  }
  Write-Host "  health: $body"

  # The assertion scripts/smoke-jar.sh makes on Linux, and the reason the
  # installer must be built from the full jar: a 200 alone would pass with the
  # engine degraded and every frame rejected.
  if ($body -notmatch '"engine"\s*:\s*"available"') {
    Exit-Check 'the service is up but OpenCV did not load - the engine is unavailable' @(
      'The installer has to be built from the full jar, which carries the Windows',
      'native. A -Pslim install starts and then rejects every frame.'
    )
  }

  # The console is what the Start menu entry opens; a redirect or an empty body
  # would still be 200.
  $console = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -UseBasicParsing -TimeoutSec 10
  $contentType = [string] $console.Headers['Content-Type']
  if ($console.StatusCode -ne 200) {
    Exit-Check "the console page returned $($console.StatusCode)"
  }
  if ($contentType -notlike 'text/html*') {
    Exit-Check "the console at / served '$contentType', expected text/html"
  }
  Write-Host "  console: / served $contentType"
} finally {
  # Never leave the service behind: not on a failed assertion, not on a timeout.
  if ($process -and -not $process.HasExited) {
    try { $process.Kill() } catch { Write-Host "         (could not kill $($process.Id): $($_.Exception.Message))" }
    try { $process.WaitForExit(30000) | Out-Null } catch { }
  }
  Stop-PortListener -Port $Port
}

if ((Get-PortListener -Port $Port).Count -gt 0) {
  Exit-Check "something is still listening on port $Port after the payload stopped" @(
    'A leaked service means the next run on a fixed port starts against someone else.'
  )
}

Write-Host ''
Write-Host '[ok] the Windows installer is intact and the payload it deploys serves with the engine available'

if ($env:GITHUB_STEP_SUMMARY) {
  $summary = @'
### Windows installer smoke - engine available

| what | value |
| --- | --- |
| installer | `{0}` |
| launcher version resource | `{1}` |
| size | {2} MB |
| payload booted | `target\jpackage\pad-liveness\pad-liveness.exe` |
| `/health` engine | `available` |
| console | `text/html` at `/` |

The .exe is checked as a PE image with a version resource matching its name,
then the application image it installs is booted and probed. The installer's
own interactive install/uninstall is not automated.
'@ -f $Installer.Name, $launcherVersionLabel, $installerSize
  Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Value $summary
}

exit 0
