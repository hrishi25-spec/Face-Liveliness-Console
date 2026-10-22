#requires -version 5.1
<#
  Build the Windows installer for the service.

  Run by the Maven `windows-package` profile, so on Windows

      .\mvnw.cmd package

  writes target\dist\pad-liveness-<version>.exe beside the jar, the same way
  `./mvnw package` writes a .deb and an AppImage on Linux. Run it by hand to
  work on one half:

      .\scripts\package-windows.ps1 -SkipInstaller    # app image only

  It is a script rather than Maven plugin configuration because jpackage is a
  CLI over an application image: the app image is what carries the jar, the
  bundled runtime and the launcher, and the installer is built from it.

  Requirements (Windows x86_64):

    * jpackage, from the JDK 17 the build already needs (JAVA_HOME\bin)
    * WiX Toolset 3.x for the .exe - jpackage shells out to candle.exe and
      light.exe. `choco install wixtoolset`, or the MSI from wixtoolset.org.
      jpackage does not support WiX 4/5, so 3.14.x is the version to install.

  The jar must be the full one. The `-Pslim` profile keeps linux/x86_64 natives
  and nothing else, so an installer built from it would start on Windows and
  then report the engine unavailable to every frame - that is an error here,
  not a skip.

  A missing jpackage skips everything; a missing WiX skips only the .exe (the
  app image still builds). `-Strict` turns any skip into a failure, which is
  what CI passes through -Dwindows.package.strict=true.

  ASCII only, deliberately: Windows PowerShell 5.1 reads a script without a BOM
  as ANSI, so a UTF-8 em dash in a string is a parse error there.
#>
[CmdletBinding()]
param(
  # Package version. Maven passes ${project.version}, which carries a
  # -SNAPSHOT suffix that jpackage rejects, so it is normalised below.
  [string] $Version = '1.0.0',

  # Runnable full jar. Defaults to the full jar in -Target, never the -slim one.
  [string] $Jar,

  # Build directory (Maven passes ${project.build.directory}).
  [string] $Target,

  # Where the installer lands.
  [string] $Dest,

  # Build the app image only.
  [switch] $SkipInstaller,

  # A skip becomes a failure.
  [switch] $Strict
)

$ErrorActionPreference = 'Stop'

$RepositoryRoot = Split-Path -Parent $PSScriptRoot
$Name = 'pad-liveness'
$Icon = Join-Path $RepositoryRoot 'packaging\windows\pad-liveness.ico'

# Fixed per product, not per build: Windows uses it to decide that a newer
# installer replaces this application instead of sitting beside it. Changing it
# turns every upgrade into a second copy in Apps and Features.
$UpgradeUuid = '9cb811ee-3ab1-4ae4-a4c3-d9be98ea4722'

if (-not $Target) { $Target = Join-Path $RepositoryRoot 'target' }
if (-not $Dest) { $Dest = Join-Path $Target 'dist' }

# Maven passes the switch through the environment because exec-maven-plugin has
# no portable way to add an argument conditionally.
if ($env:PAD_PACKAGE_STRICT -in @('1', 'true', 'True')) { $Strict = $true }

$script:Skipped = @()

function Write-Step {
  param([string] $Message)
  Write-Host ''
  Write-Host "- $Message"
}

function Write-Info {
  param([string] $Message)
  Write-Host "  $Message"
}

function Write-Warning2 {
  param([string] $Message)
  # stdout, not the warning stream: exec-maven-plugin does not relay the child's
  # stderr, so a warning written there reaches nobody in `.\mvnw.cmd package`.
  Write-Host "packaging: WARNING: $Message"
}

function Exit-Failure {
  param([string] $Message, [string[]] $Detail = @())

  Write-Host ''
  Write-Host "packaging: $Message"
  foreach ($line in $Detail) { Write-Host "         $line" }
  exit 1
}

# Record a half that cannot be built here. Under -Strict it is fatal, so CI
# cannot pass while silently shipping no installer.
function Skip-Half {
  param([string] $Message)

  if ($Strict) {
    Exit-Failure "$Message (strict mode: every requested artifact must be built)"
  }
  Write-Warning2 "$Message - skipping"
  $script:Skipped += $Message
}

# jpackage wants a bare x.y.z: `1.0.0-SNAPSHOT` is rejected, and a version with
# fewer than three parts gets a .0 so the file name and the version resource
# agree.
function ConvertTo-NumericVersion {
  param([string] $Value)

  $core = ($Value -split '-')[0]
  $parts = @($core -split '\.' | Where-Object { $_ -ne '' })
  if ($parts.Count -eq 0 -or $parts[0] -notmatch '^\d+$') { return '1.0.0' }
  while ($parts.Count -lt 3) { $parts += '0' }
  return ($parts[0..2] -join '.')
}

$AppVersion = ConvertTo-NumericVersion -Value $Version

# --------------------------------------------------------------- 1. preconditions

if ($env:OS -ne 'Windows_NT') {
  Exit-Failure 'this packaging path is Windows-only - jpackage cannot cross-build a .exe' @(
    'On Linux use ./mvnw package, which builds the .deb and the AppImage.'
  )
}
if (-not (Test-Path -LiteralPath $Icon -PathType Leaf)) {
  Exit-Failure "the installer icon is missing: $Icon" @(
    'It is a multi-size .ico generated from packaging\linux\pad-liveness.png.'
  )
}

$Jpackage = $null
if ($env:JAVA_HOME) {
  $candidate = Join-Path ($env:JAVA_HOME.TrimEnd('\')) 'bin\jpackage.exe'
  if (Test-Path -LiteralPath $candidate -PathType Leaf) { $Jpackage = $candidate }
}
if (-not $Jpackage) {
  $onPath = Get-Command jpackage.exe -ErrorAction SilentlyContinue
  if ($onPath) { $Jpackage = $onPath.Source }
}
if (-not $Jpackage) {
  Skip-Half 'jpackage not found - it ships with JDK 17+; set JAVA_HOME or add it to PATH'
  Write-Step 'Nothing to package'
  exit 0
}

Write-Step "Preparing $Name $AppVersion"
Write-Info "jpackage: $Jpackage"

# ------------------------------------------------------------------- 2. the jar

if (-not $Jar) {
  # The full jar only. A slim jar in target\ means the last build was -Pslim,
  # and packaging it would produce an installer that cannot see a frame.
  $jars = @(Get-ChildItem -LiteralPath $Target -Filter 'pad-liveness-backend-*.jar' -File -ErrorAction SilentlyContinue)
  $full = @($jars | Where-Object { $_.Name -notlike '*-slim.jar' })
  $slim = @($jars | Where-Object { $_.Name -like '*-slim.jar' })
  if ($full.Count -gt 0) {
    $Jar = $full[0].FullName
  } elseif ($slim.Count -gt 0) {
    Exit-Failure 'target\ holds only the -Pslim jar, which has no Windows OpenCV natives' @(
      "$($slim[0].Name) keeps linux/x86_64 natives and nothing else: the installer",
      'would start here and then report the engine unavailable to every frame.',
      'Build the full jar:  .\mvnw.cmd --batch-mode clean package -DskipTests'
    )
  }
}
if (-not $Jar -or -not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
  Exit-Failure "no runnable jar under $Target" @(
    'Build it first:  .\mvnw.cmd --batch-mode clean package -DskipTests'
  )
}
$Jar = (Get-Item -LiteralPath $Jar).FullName
if ($Jar -like '*-slim.jar') {
  Exit-Failure "$(Split-Path -Leaf $Jar) is the -Pslim jar, which has no Windows natives" @(
    'The Windows installer has to be built from the full jar.'
  )
}
Write-Info "jar: $(Split-Path -Leaf $Jar) (full: every platform's natives)"

# --------------------------------------------------------------- 3. app image
#
# --main-class must be JarLauncher: jpackage launches with `java -cp`, so it
# ignores the jar manifest's Start-Class and would otherwise run the Spring
# Boot loader's main class directly and never start Spring.
#
# --win-console because this is a server, not a windowed application: without
# it the launcher has no console, and the startup log, the port in use and any
# failure go nowhere a user can see them.

$Stage = Join-Path $Target 'jpackage\input'
$AppImage = Join-Path $Target "jpackage\$Name"
$JpackageDir = Join-Path $Target 'jpackage'

if (Test-Path -LiteralPath (Join-Path $Target 'jpackage')) {
  Remove-Item -LiteralPath (Join-Path $Target 'jpackage') -Recurse -Force
}
if (-not $SkipInstaller) {
  # Only the artifact being rebuilt is cleared, so -SkipInstaller cannot delete
  # an installer someone just built.
  Get-ChildItem -LiteralPath $Dest -Filter '*.exe' -File -ErrorAction SilentlyContinue |
    Remove-Item -Force
}
New-Item -ItemType Directory -Path $Stage -Force | Out-Null
Copy-Item -LiteralPath $Jar -Destination (Join-Path $Stage 'app.jar') -Force

Write-Step 'Building the application image'
& $Jpackage `
  --type app-image `
  --name $Name `
  --app-version $AppVersion `
  --input $Stage `
  --main-jar app.jar `
  --main-class org.springframework.boot.loader.launch.JarLauncher `
  --arguments '--spring.profiles.active=dev' `
  --dest $JpackageDir `
  --icon $Icon `
  --vendor 'MOSIP' `
  --description 'MOSIP face liveness and PAD service' `
  --win-console `
  --java-options '-Djava.awt.headless=true'
if ($LASTEXITCODE -ne 0) {
  Exit-Failure "jpackage exited $LASTEXITCODE while building the application image"
}
$Payload = Join-Path $AppImage "$Name.exe"
if (-not (Test-Path -LiteralPath $Payload -PathType Leaf)) {
  Exit-Failure "jpackage did not produce $Payload"
}
$payloadSize = [math]::Round((Get-ChildItem -LiteralPath $Payload).Length / 1MB, 0)
Write-Info "app image: $AppImage (${payloadSize} MB launcher, runnable without installing)"

# ------------------------------------------------------------------ 4. the .exe

if ($SkipInstaller) {
  Write-Step 'Done'
  Write-Info "app image only: $Payload"
  exit 0
}

# jpackage shells out to WiX for the .exe. Both tools are required; checking
# only one of them turns a missing toolchain into a jpackage stack trace.
$wix = @()
foreach ($tool in @('candle.exe', 'light.exe')) {
  $found = Get-Command $tool -ErrorAction SilentlyContinue
  if ($found) { $wix += $found.Source }
}
if ($wix.Count -lt 2) {
  Skip-Half 'WiX Toolset 3 is needed for the .exe (candle.exe and light.exe on PATH) - choco install wixtoolset'
  Write-Step 'Done'
  Write-Info "app image: $Payload"
  if ($script:Skipped.Count -gt 0) {
    Write-Host ''
    Write-Host '  Not every artifact was built. Install the missing tool and re-run, or pass -Strict to make this a failure.'
  }
  exit 0
}
Write-Info "WiX: $($wix[0])"

New-Item -ItemType Directory -Path $Dest -Force | Out-Null
Write-Step 'Building the .exe'
# --win-per-user-install: installs under %LOCALAPPDATA% with no elevation, which
# is what lets a developer (and a CI runner) install it without a UAC prompt.
# --app-image reuses the image above rather than rebuilding it.
& $Jpackage `
  --type exe `
  --app-image $AppImage `
  --name $Name `
  --app-version $AppVersion `
  --dest $Dest `
  --icon $Icon `
  --vendor 'MOSIP' `
  --description 'MOSIP face liveness and PAD service' `
  --win-per-user-install `
  --win-dir-chooser `
  --win-menu `
  --win-menu-group 'MOSIP' `
  --win-shortcut `
  --win-upgrade-uuid $UpgradeUuid
if ($LASTEXITCODE -ne 0) {
  Exit-Failure "jpackage exited $LASTEXITCODE while building the .exe"
}

$Installer = Join-Path $Dest "$Name-$AppVersion.exe"
if (-not (Test-Path -LiteralPath $Installer -PathType Leaf)) {
  # jpackage names the file from the product and version; a rename here would
  # hide that jpackage disagreed about either.
  $found = @(Get-ChildItem -LiteralPath $Dest -Filter '*.exe' -File)
  if ($found.Count -eq 0) {
    Exit-Failure "jpackage reported success but produced no .exe in $Dest"
  }
  $Installer = $found[0].FullName
}
$installerSize = [math]::Round((Get-ChildItem -LiteralPath $Installer).Length / 1MB, 0)

Write-Step 'Done'
foreach ($artifact in @(Get-ChildItem -LiteralPath $Dest -File)) {
  Write-Info "$($artifact.Name)  $([math]::Round($artifact.Length / 1MB, 0)) MB"
}
Write-Info "installer: $Installer"

if ($script:Skipped.Count -gt 0) {
  Write-Host ''
  Write-Host '  Not every artifact was built. Install the missing tool and re-run, or pass -Strict to make this a failure.'
  exit 0
}

Write-Host ''
Write-Host '  Install it (per-user, no elevation). The Start menu entry under'
Write-Host "  'MOSIP' starts the service with its console on port 8000, on the dev"
Write-Host '  profile (in-memory database), so it needs no PostgreSQL:'
Write-Host ''
Write-Host "    $Installer"
Write-Host ''
Write-Host '  The build is not code signed, so Windows SmartScreen warns on first run.'
Write-Host '  An unsigned installer is fine for internal use; sign it before handing'
Write-Host '  it to anyone else.'

exit 0
