param(
    [Parameter(Mandatory = $true)][string]$Device,
    [ValidateRange(15, 300)][int]$Seconds = 90,
    [int]$Port = 8899
)

$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$captureDirectory = Join-Path $repository ('captures\' + (Get-Date -Format 'MM-dd-HH-mm-ss'))
$addon = Join-Path $PSScriptRoot 'capture-library-traffic.py'
$proxyCommand = (Get-Command mitmdump -ErrorAction Stop).Source
$originalProxy = (& adb -s $Device shell settings get global http_proxy).Trim()
if ($LASTEXITCODE -ne 0) { throw '端末の通信設定を取得できませんでした。' }
$reverseMappings = & adb -s $Device reverse --list
if ($LASTEXITCODE -ne 0) { throw '端末のポート転送状態を取得できませんでした。' }
if ($reverseMappings -match "tcp:$Port ") { throw '指定ポートには既存の転送があります。別のポートを指定してください。' }
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw '指定ポートは使用中です。別のポートを指定してください。'
}

New-Item -ItemType Directory -Path $captureDirectory -Force | Out-Null
$statePath = Join-Path $captureDirectory 'proxy-state.json'
$captureState = @{
    device = $Device
    originalProxy = $originalProxy
    port = $Port
    restored = $false
    startedAt = (Get-Date).ToString('o')
}
$captureState | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
$env:PLAY_LIBRARY_CAPTURE_DIR = $captureDirectory
$proxy = $null
$reverseAdded = $false
$proxyChanged = $false
try {
    $proxy = Start-Process -FilePath $proxyCommand -ArgumentList @(
        '-q', '--listen-host', '127.0.0.1', '--listen-port', $Port,
        '--allow-hosts', '(^|\.)spclient\.wg\.spotify\.com(:[0-9]+)?$|(^|\.)[a-z0-9-]+-spclient\.spotify\.com(:[0-9]+)?$',
        '-s', ('"' + $addon + '"')
    ) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $captureDirectory 'proxy.out.log') -RedirectStandardError (Join-Path $captureDirectory 'proxy.err.log')
    $captureState.proxyPid = $proxy.Id
    $captureState | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
    $deadline = (Get-Date).AddSeconds(10)
    while (!(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) {
        if ($proxy.HasExited -or (Get-Date) -gt $deadline) { throw '検証プロキシを起動できませんでした。' }
        Start-Sleep -Milliseconds 200
    }
    & adb -s $Device reverse "tcp:$Port" "tcp:$Port"
    if ($LASTEXITCODE -ne 0) { throw '端末のポート転送を作成できませんでした。' }
    $reverseAdded = $true
    & adb -s $Device shell settings put global http_proxy "127.0.0.1:$Port"
    if ($LASTEXITCODE -ne 0) { throw '端末の検証用プロキシを設定できませんでした。' }
    $proxyChanged = $true
    Write-Output "記録先: $captureDirectory"
    Write-Output "元のアプリでライブラリを開いてください。$Seconds 秒後に通信設定を復元します。"
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ($proxy.HasExited) { throw '検証プロキシが停止しました。' }
        Start-Sleep -Seconds 1
    }
} finally {
    $restored = $true
    if ($proxyChanged) {
        if ([string]::IsNullOrWhiteSpace($originalProxy) -or $originalProxy -eq 'null') {
            & adb -s $Device shell settings delete global http_proxy
        } else {
            & adb -s $Device shell settings put global http_proxy $originalProxy
        }
        if ($LASTEXITCODE -ne 0) {
            $restored = $false
            Write-Error '通信設定の復元に失敗しました。端末のプロキシ設定を確認してください。' -ErrorAction Continue
        }
    }
    if ($reverseAdded) {
        & adb -s $Device reverse --remove "tcp:$Port"
        if ($LASTEXITCODE -ne 0) {
            $restored = $false
            Write-Error '検証用ポート転送の解除に失敗しました。保存された復旧情報を確認してください。' -ErrorAction Continue
        }
    }
    if ($null -ne $proxy -and !$proxy.HasExited) { Stop-Process -Id $proxy.Id }
    Remove-Item Env:\PLAY_LIBRARY_CAPTURE_DIR -ErrorAction SilentlyContinue
    $captureState.restored = $restored
    $captureState | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
    if ($restored) { Write-Output '検証プロキシを終了し、通信設定を復元しました。' }
}
