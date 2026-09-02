param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Output
)

$normalized = $Version.TrimStart('v')
$content = Get-Content -Raw -LiteralPath ./CHANGELOG.md
$pattern = "(?ms)^## \[$([regex]::Escape($normalized))\].*?(?=^## \[|\z)"
$match = [regex]::Match($content, $pattern)
if (-not $match.Success) {
    throw "CHANGELOG.md に $Version の項目がありません。"
}
Set-Content -LiteralPath $Output -Value $match.Value.Trim() -Encoding utf8NoBOM
