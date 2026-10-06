#Requires -Version 5.0
<#
    iDRAC6 Virtual Console launcher for Windows
    =================================================================
    Runs Dell's Java Virtual Console on THIS PC so you get a real
    keyboard and mouse (F1, F2, Ctrl+Alt+Del, BIOS) for an iDRAC6-based
    PowerEdge server (R610 / R710 / R910, iDRAC6 firmware ~1.x-2.x).

    It does NOT contain any Dell software. At run time it downloads:
      * Dell's viewer (avctKVM.jar + the Windows x64 native libraries)
        from your OWN iDRAC, using the addresses in your viewer.jnlp
      * a private Java 8 runtime (Adoptium Temurin 8)
    everything into a "runtime" folder next to this script. Your
    system-wide Java, if any, and your system trust store are left
    untouched.

    The iDRAC6 (from ~2011) only speaks old TLS and ciphers that modern
    Java disables. This launcher re-enables them for ITS private JRE
    only, so the weakening is scoped to this one tool talking to your
    iDRAC on your local network.

    How to use
    ----------
      1. In the iDRAC web page, open the Virtual Console once so your
         browser downloads a fresh "viewer.jnlp" (the login tokens in it
         expire after a few minutes, so grab a new one right before use).
      2. Put that viewer.jnlp in THIS folder.
      3. Double-click  Run-iDRAC6-Console.bat
         (or:  powershell -ExecutionPolicy Bypass -File idrac6-console.ps1)
      4. The console window opens. Press F1 (or F2 for BIOS).

    Once you are in the BIOS, set these so you are never stuck again and
    the phone app can take over:
      * Serial Communication -> On with Console Redirection via COM2
      * Miscellaneous Settings -> F1/F2 Prompt on Error -> Disabled
#>
[CmdletBinding()]
param(
    [string]$Jnlp,
    [string]$WorkDir = (Join-Path $PSScriptRoot 'runtime')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Info($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Warn($m) { Write-Host "!!  $m" -ForegroundColor Yellow }

function Fail-Jars($urls) {
    Warn "Could not download the viewer from the iDRAC automatically."
    Warn "Open each of these in your browser (accept the security warning), save the"
    Warn "file, and copy it into:  $jarDir"
    foreach ($u in $urls) { Write-Host "    $u" -ForegroundColor White }
    throw "Viewer jars missing. See the URLs above, then run this script again."
}

function Download($url, $dest) {
    Info "Download $url"
    try {
        Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
    } catch {
        Warn "PowerShell download failed: $($_.Exception.Message)"
        $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
        if ($curl) {
            Info "Retrying with curl.exe ..."
            & $curl.Source -s -k --tlsv1 -o $dest $url 2>$null
        }
    }
    return ((Test-Path $dest) -and ((Get-Item $dest).Length -gt 0))
}

function Extract-Dlls($jarPath, $outDir) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.Name -like '*.dll') {
                $target = Join-Path $outDir $entry.Name
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
                Info "  native: $($entry.Name)"
            }
        }
    } finally {
        $zip.Dispose()
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

# ---- find the viewer.jnlp -------------------------------------------------
if (-not $Jnlp) {
    $cand = Get-ChildItem -Path $PSScriptRoot -Filter *.jnlp -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($cand) { $Jnlp = $cand.FullName }
}
if (-not $Jnlp -or -not (Test-Path $Jnlp)) {
    throw "No viewer.jnlp found. In the iDRAC web page open the Virtual Console to download viewer.jnlp, put it in this folder, and run again (or pass -Jnlp <path>)."
}
Info "Using $Jnlp"
[xml]$j = Get-Content -Raw -Path $Jnlp

$mainClass = "$($j.jnlp.'application-desc'.'main-class')"
if (-not $mainClass) { $mainClass = 'com.avocent.idrac.kvm.Main' }
$viewerArgs = @($j.jnlp.'application-desc'.argument | ForEach-Object { "$_" })
if (-not $viewerArgs) { throw "The viewer.jnlp has no <argument> entries; is it the right file?" }

# main jar
$mainJarUrl = ($j.jnlp.resources.jar | Where-Object { $_.main -eq 'true' } | Select-Object -First 1).href
if (-not $mainJarUrl) { $mainJarUrl = ($j.jnlp.resources.jar | Select-Object -First 1).href }

# Windows x64 native libraries
$nativeUrls = @()
foreach ($res in $j.jnlp.resources) {
    if ($res.os -eq 'Windows' -and $res.arch -eq 'amd64') {
        $nativeUrls += @($res.nativelib | ForEach-Object { $_.href })
    }
}
if (-not $nativeUrls) {
    $cb = "$($j.jnlp.codebase)".TrimEnd('/')
    $nativeUrls = @("$cb/software/avctKVMIOWin64.jar", "$cb/software/avctVMWin64.jar")
}
if (-not $mainJarUrl) {
    $cb = "$($j.jnlp.codebase)".TrimEnd('/')
    $mainJarUrl = "$cb/software/avctKVM.jar"
}

# ---- folders --------------------------------------------------------------
$null   = New-Item -ItemType Directory -Force -Path $WorkDir
$jreDir = Join-Path $WorkDir 'jre8'
$libDir = Join-Path $WorkDir 'natives'
$jarDir = Join-Path $WorkDir 'jars'
$null   = New-Item -ItemType Directory -Force -Path $libDir, $jarDir

# ---- relaxed TLS for talking to the old iDRAC (downloads only) ------------
[System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]'Tls12,Tls11,Tls' } catch {}

# ---- private Java 8 -------------------------------------------------------
$java = Get-ChildItem -Path $jreDir -Recurse -Filter java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $java) {
    Info "Fetching a private Java 8 runtime (Adoptium Temurin 8, ~45 MB)..."
    $zip = Join-Path $WorkDir 'jre8.zip'
    $ok = Download 'https://api.adoptium.net/v3/binary/latest/8/ga/windows/x64/jre/hotspot/normal/eclipse' $zip
    if (-not $ok) { throw "Could not download Java 8. Install Temurin 8 JRE manually, or unzip one under: $jreDir" }
    if (Test-Path $jreDir) { Remove-Item -Recurse -Force $jreDir }
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $jreDir)
    Remove-Item $zip -ErrorAction SilentlyContinue
    $java = Get-ChildItem -Path $jreDir -Recurse -Filter java.exe | Select-Object -First 1
}
if (-not $java) { throw "java.exe not found after install." }
Info "Java: $($java.FullName)"

# ---- viewer jars + native DLLs -------------------------------------------
$kvmJar = Join-Path $jarDir 'avctKVM.jar'
if (-not (Test-Path $kvmJar)) {
    if (-not (Download $mainJarUrl $kvmJar)) { Fail-Jars (@($mainJarUrl) + $nativeUrls) }
}
foreach ($u in $nativeUrls) {
    $dest = Join-Path $jarDir (Split-Path $u -Leaf)
    if (-not (Test-Path $dest)) {
        if (-not (Download $u $dest)) { Fail-Jars (@($mainJarUrl) + $nativeUrls) }
    }
    Extract-Dlls $dest $libDir
}

# ---- legacy security properties (scoped to this JRE run) ------------------
$sec = Join-Path $WorkDir 'legacy.security'
@'
# Re-enable the legacy TLS/ciphers the iDRAC6 (circa 2011) still uses.
# Applied ONLY to this launcher's private JRE via -Djava.security.properties==
# so your system Java is unaffected.
jdk.tls.disabledAlgorithms=
jdk.certpath.disabledAlgorithms=
jdk.tls.legacyAlgorithms=
jdk.tls.client.protocols=TLSv1,TLSv1.1,TLSv1.2
crypto.policy=unlimited
'@ | Set-Content -Encoding ASCII -Path $sec

# ---- launch ---------------------------------------------------------------
$javaArgs = @(
    '-cp', $kvmJar,
    "-Djava.library.path=$libDir",
    "-Djava.security.properties==$sec",
    '-Dsun.java2d.dpiaware=false',
    $mainClass
) + $viewerArgs

Info "Launching the iDRAC6 Virtual Console..."
Info "A window opens with the server's screen. Press F1 (or F2 for BIOS)."
Info "Keep this command window open; closing it closes the console."
& $java.FullName @javaArgs
$code = $LASTEXITCODE
Info "Console closed (exit $code)."
if ($code -ne 0) {
    Warn "If it closed immediately: your viewer.jnlp login tokens may have expired."
    Warn "Download a fresh viewer.jnlp from the iDRAC and run this again."
}
