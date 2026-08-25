# Автозапуск распознавателя при входе в Windows.
#
#   .\install-service.ps1            поставить задачу в планировщик
#   .\install-service.ps1 -Remove    убрать
#
# Задача выполняется от твоей учётной записи, окна не показывает и стартует
# через 30 секунд после входа (чтобы не драться за GPU с остальным стартом).
param([switch]$Remove)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$taskName = 'Pravka Whisper'

if ($Remove) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "Задача «$taskName» удалена."
    return
}

$python = Join-Path $here '.venv\Scripts\pythonw.exe'   # pythonw - без консольного окна
if (-not (Test-Path $python)) {
    throw "Не найден $python. Сначала создай окружение: см. README.md в этой папке."
}

$action = New-ScheduledTaskAction -Execute $python -Argument "`"$here\server.py`"" -WorkingDirectory $here
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$trigger.Delay = 'PT30S'
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)

# Планировщик запускает процесс без твоего окружения, поэтому настройки
# server.py берёт из whisper.env рядом с собой.
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings `
    -Description 'Локальный Whisper для Правки (http://127.0.0.1:8178)' -Force | Out-Null

Write-Host "Задача «$taskName» поставлена. Проверить: Invoke-RestMethod http://127.0.0.1:8178/health"
