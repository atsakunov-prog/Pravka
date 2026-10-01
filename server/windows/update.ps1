#Requires -Version 5.1
<#
  Архив Правки: обновить код и перезапустить службу.

  Запускать в PowerShell ОТ ИМЕНИ АДМИНИСТРАТОРА:

    powershell -ExecutionPolicy Bypass -File D:\PravkaArchive\repo\server\windows\update.ps1

  Забирает свежую ветку pravka и прогоняет install.ps1 заново: зависимости,
  схема, задача, проверка. Данные базы от этого не меняются — схема life
  строится заново, журнал событий не трогается.
#>
param(
    [string]$Root = 'D:\PravkaArchive',
    [string]$Python = 'C:\Program Files\Python314\python.exe',
    [string]$Router = '192.168.1.1',
    [ValidateSet('NetworkService', 'System')]
    [string]$RunAs = 'NetworkService'
)

$ErrorActionPreference = 'Stop'
$repo = Join-Path $Root 'repo'
Write-Host "== git pull в $repo" -ForegroundColor Cyan
# Репозиторий клонировал владелец, а скрипт идёт от администратора: без этого
# git откажется работать с «чужой» папкой (dubious ownership).
git config --global --add safe.directory ($repo -replace '\\', '/') 2>$null
git -C $repo pull --ff-only
if ($LASTEXITCODE -ne 0) { Write-Host 'git pull не прошёл — смотри сообщение выше.' -ForegroundColor Red; exit 1 }

& (Join-Path $PSScriptRoot 'install.ps1') -Root $Root -Python $Python -Router $Router -RunAs $RunAs
exit $LASTEXITCODE
