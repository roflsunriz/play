param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$KeyStore,
    [Parameter(Mandatory = $true)][string]$KeyAlias,
    [Parameter(Mandatory = $true)][string]$Output,
    [Parameter(Mandatory = $true)][string]$AndroidSdk,
    [string]$ExpectedCertificateSha256 = '7ff600f42c3bf628d8742344be48e6b4af57a539e5202aff52fb765615be39ca',
    [string]$Lineage
)

$ErrorActionPreference = 'Stop'
$normalized = $Version.TrimStart('v')
if ($normalized -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$') { throw 'Invalid release version' }
if ([string]::IsNullOrWhiteSpace($env:PLAY_STORE_PASSWORD) -or [string]::IsNullOrWhiteSpace($env:PLAY_KEY_PASSWORD)) {
    throw 'Set PLAY_STORE_PASSWORD and PLAY_KEY_PASSWORD in this process before signing; do not place passwords in arguments.'
}
$inputApk = (Resolve-Path -LiteralPath $Apk).Path
$keyPath = (Resolve-Path -LiteralPath $KeyStore).Path
$outputApk = [IO.Path]::GetFullPath($Output)
if ($inputApk -eq $outputApk) { throw 'Keep the verified input APK separate from the signed output.' }
$buildTools = Join-Path $AndroidSdk 'build-tools/37.0.0'
$signer = Join-Path $buildTools $(if ($IsWindows) { 'apksigner.bat' } else { 'apksigner' })
$aapt = Join-Path $buildTools $(if ($IsWindows) { 'aapt2.exe' } else { 'aapt2' })
$badging = & $aapt dump badging $inputApk
if ($LASTEXITCODE -ne 0) { throw 'APK metadata inspection failed' }
if ($badging -match '^application-debuggable') { throw 'A public release must not be debuggable.' }
$package = $badging | Select-String -Pattern "^package: name='io\.github\.playmusic' .*versionName='$([regex]::Escape($normalized))'"
if (-not $package) { throw 'The application ID or APK version differs from the intended release.' }
$outputDirectory = Split-Path -Parent $outputApk
if (-not (Test-Path -LiteralPath $outputDirectory)) { New-Item -ItemType Directory -Path $outputDirectory | Out-Null }
$signArguments = @('sign', '--ks', $keyPath, '--ks-key-alias', $KeyAlias,
    '--ks-pass', 'env:PLAY_STORE_PASSWORD', '--key-pass', 'env:PLAY_KEY_PASSWORD',
    '--v4-signing-enabled', 'false', '--out', $outputApk)
if ($Lineage) {
    # Local one-time migration for an existing Android 9+ installation. Do not publish this as the universal APK.
    $signArguments += @('--lineage', (Resolve-Path -LiteralPath $Lineage).Path,
        '--rotation-min-sdk-version', '28', '--min-sdk-version', '28',
        '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'false', '--v3-signing-enabled', 'true')
}
& $signer @signArguments $inputApk
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
$verifyArguments = @('verify', '--verbose', '--print-certs')
if ($Lineage) { $verifyArguments += @('--min-sdk-version', '28') }
$verification = & $signer @verifyArguments $outputApk
if ($LASTEXITCODE -ne 0) { throw 'Signed APK verification failed' }
if ($ExpectedCertificateSha256 -notmatch '^[0-9a-fA-F]{64}$' -or
    -not ($verification -match [regex]::Escape("certificate SHA-256 digest: $($ExpectedCertificateSha256.ToLowerInvariant())"))) {
    throw 'The signing certificate differs from the expected release identity.'
}
$verification | Write-Output
$digest = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputApk).Hash.ToLowerInvariant()
"$digest  $([IO.Path]::GetFileName($outputApk))" | Set-Content -Encoding ascii -LiteralPath "$outputApk.sha256"
Write-Output "Signed and verified: $outputApk"
