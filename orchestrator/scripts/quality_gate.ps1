<#
.SYNOPSIS
    提交前的最小质量门禁：编译 + 硬编码色值 + TODO 残留。
.DESCRIPTION
    只做三件确定能给出结论的检查，输出可读报告，退出码可直接给 CI 用。
.EXAMPLE
    pwsh -File orchestrator/scripts/quality_gate.ps1
#>
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$app = Join-Path $root 'tv_project'
$src = Join-Path $app 'app/src/main'
$problems = @()

Write-Host '== 1/3 硬编码色值 =='
$hex = Get-ChildItem -Path $src -Recurse -Include *.xml,*.kt -File |
    Select-String -Pattern '#[0-9A-Fa-f]{6,8}\b' |
    Where-Object { $_.Path -notmatch '\\values(-night)?\\colors\.xml$' -and $_.Path -notmatch '\\mipmap' }
# 二维码必须黑白，是唯一豁免
$hex = $hex | Where-Object { $_.Line -notmatch 'Color\.BLACK|Color\.WHITE|0xFF000000|0xFFFFFFFF' }
if ($hex) { $problems += '硬编码色值：' + ($hex | ForEach-Object { "$($_.Filename):$($_.LineNumber)" } | Select-Object -First 10) -join '; ' }
Write-Host ("  命中 {0} 处" -f ($hex | Measure-Object).Count)

Write-Host '== 2/3 TODO / 空实现 =='
$todo = Get-ChildItem -Path (Join-Path $src 'java') -Recurse -Include *.kt -File |
    Select-String -Pattern '\bTODO\b|\bFIXME\b|NotImplementedError'
if ($todo) { $problems += 'TODO 残留：' + ($todo | ForEach-Object { "$($_.Filename):$($_.LineNumber)" } | Select-Object -First 10) -join '; ' }
Write-Host ("  命中 {0} 处" -f ($todo | Measure-Object).Count)

if (-not $SkipBuild) {
    Write-Host '== 3/3 编译 =='
    Push-Location $app
    try {
        $env:ANDROID_HOME = 'G:\SDK'
        $out = & .\gradlew.bat :app:assembleDebug --console=plain 2>&1
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    if ($code -ne 0) {
        $errs = $out | Where-Object { $_ -match '^e: |AAPT: error' } | Select-Object -First 10
        $problems += '编译失败：' + ($errs -join '; ')
    }
    Write-Host ("  exit={0}" -f $code)
}

if ($problems.Count -eq 0) {
    Write-Host "`nQUALITY OK" -ForegroundColor Green
    exit 0
}
Write-Host "`nQUALITY FAILED" -ForegroundColor Red
$problems | ForEach-Object { Write-Host " - $_" }
exit 1
