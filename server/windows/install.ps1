#Requires -Version 5.1
<#
  Архив Правки: установить или переустановить службу на домашнем компе.

  Запускать в PowerShell ОТ ИМЕНИ АДМИНИСТРАТОРА (Claude Code сам задачи
  планировщика и правила брандмауэра не регистрирует — это делает владелец):

    powershell -ExecutionPolicy Bypass -File D:\PravkaArchive\repo\server\windows\install.ps1

  Что делает, по шагам (повтор безвреден — всё ставится заново поверх):
    1. venv и зависимости из requirements.txt;
    2. схема базы (migrate);
    3. права служебной учётной записи на файл секретов, журналы и файлы;
    4. правило брандмауэра: порт сервиса — только от роутера и только для
       python.exe (venv на Windows запускает базовый интерпретатор, поэтому
       правило на него ловит и службу из venv);
    5. задача планировщика «Pravka Archive»: при старте системы, с перезапуском;
    6. запуск, ожидание /health, самопроверка (check).
  Итог — в D:\PravkaArchive\logs\install-<дата>.log.

  Служба работает от NETWORK SERVICE, а не от SYSTEM: она смотрит в интернет,
  и учётная запись с правами на всю машину ей ни к чему. Если с ней что-то не
  заладится — запасной ключ -RunAs System.
#>
param(
    [string]$Root = 'D:\PravkaArchive',
    [string]$Python = 'C:\Program Files\Python314\python.exe',
    [string]$Router = '192.168.1.1',
    [ValidateSet('NetworkService', 'System')]
    [string]$RunAs = 'NetworkService'
)

$ErrorActionPreference = 'Stop'
$TaskName = 'Pravka Archive'
$RuleName = 'Pravka Archive'
$Server = Join-Path $Root 'repo\server'
$EnvFile = Join-Path $Root 'secrets\server.env'
$Logs = Join-Path $Root 'logs'
$VenvPy = Join-Path $Server '.venv\Scripts\python.exe'

New-Item -ItemType Directory -Force -Path $Logs | Out-Null
$Log = Join-Path $Logs ('install-{0:yyyy-MM-dd-HHmmss}.log' -f (Get-Date))
Start-Transcript -Path $Log | Out-Null

function Step([string]$text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }

function Run([string]$what, [scriptblock]$block) {
    & $block
    if ($LASTEXITCODE -ne 0) { throw "$what — код $LASTEXITCODE" }
}

function EnvValue([string]$name) {
    $line = Get-Content -LiteralPath $EnvFile -Encoding UTF8 | Where-Object { $_ -match "^\s*$name\s*=" } | Select-Object -Last 1
    if ($null -eq $line) { return '' }
    return ($line -replace "^\s*$name\s*=\s*", '').Trim()
}

$failed = $false
try {
    Step 'Проверка прав и путей'
    $me = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not $me.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Нужен PowerShell от имени администратора: правой кнопкой по PowerShell → «Запуск от имени администратора».'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $Server 'pravka_archive'))) {
        throw "Нет $Server\pravka_archive — сначала склонировать репозиторий в $Root\repo (README, «Развёртывание»)."
    }
    if (-not (Test-Path -LiteralPath $EnvFile)) { throw "Нет $EnvFile — это часть А, шаг 5." }
    if (-not (Test-Path -LiteralPath $Python)) { throw "Нет Python по пути «$Python». Передай свой: -Python <путь к python.exe>." }

    $listen = EnvValue 'PRAVKA_LISTEN'
    if (-not $listen) { $listen = '0.0.0.0:8090' }
    $port = [int]($listen.Split(':')[-1])
    Write-Host "Порт сервиса: $port (PRAVKA_LISTEN=$listen)"
    foreach ($name in 'PRAVKA_OWNER_PASSWORD', 'PRAVKA_PUBLIC_URL', 'PRAVKA_INGEST_TOKEN') {
        if (-not (EnvValue $name)) { throw "В $EnvFile пуст $name — без него сервис не стартует." }
    }
    if (-not (EnvValue 'ICU_API_KEY')) {
        Write-Warning 'ICU_API_KEY пуст: сборщик intervals будет спать, пока ключ не появится (после — перезапустить задачу).'
    }

    Step 'venv и зависимости'
    if (-not (Test-Path -LiteralPath $VenvPy)) {
        Run 'venv не создался' { & $Python -m venv (Join-Path $Server '.venv') }
    }
    Run 'pip не обновился' { & $VenvPy -m pip install --disable-pip-version-check -q --upgrade pip }
    Run 'зависимости не встали' { & $VenvPy -m pip install --disable-pip-version-check -q -r (Join-Path $Server 'requirements.txt') }

    Step 'Схема базы'
    Push-Location -LiteralPath $Server
    try { Run 'migrate упал' { & $VenvPy -m pravka_archive migrate --env $EnvFile } } finally { Pop-Location }

    Step "Учётная запись службы: $RunAs"
    if ($RunAs -eq 'NetworkService') {
        $sid = '*S-1-5-20'   # NETWORK SERVICE — по SID, имя зависит от языка Windows
        Run 'права на server.env' { icacls $EnvFile /grant "${sid}:(R)" | Out-Null }
        foreach ($dir in 'logs', 'blobs') {
            $path = Join-Path $Root $dir
            New-Item -ItemType Directory -Force -Path $path | Out-Null
            Run "права на $dir" { icacls $path /grant "${sid}:(OI)(CI)M" | Out-Null }
        }
        Run 'права на код' { icacls $Server /grant "${sid}:(OI)(CI)RX" | Out-Null }
        $principal = New-ScheduledTaskPrincipal -UserId 'NT AUTHORITY\NETWORKSERVICE' -LogonType ServiceAccount -RunLevel Limited
    } else {
        $principal = New-ScheduledTaskPrincipal -UserId 'NT AUTHORITY\SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    }

    Step 'Брандмауэр'
    Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -DisplayName $RuleName -Direction Inbound -Protocol TCP -LocalPort $port `
        -RemoteAddress $Router -Program $Python -Action Allow -Profile Any | Out-Null
    Write-Host "TCP $port открыт только для $Router (роутер) и только для $Python, на любом профиле сети."
    # Правила на тот же порт, заведённые раньше руками (часть А), не трогаем —
    # только показываем: два разрешающих правила не мешают друг другу.
    $others = Get-NetFirewallPortFilter -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -eq "$port" } | Get-NetFirewallRule -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -ne $RuleName }
    foreach ($r in $others) { Write-Host "Есть и другое правило на $port`: «$($r.DisplayName)» ($($r.Action), $($r.Direction))" }

    Step 'Задача планировщика'
    $existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($existing) {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    }
    $action = New-ScheduledTaskAction -Execute $VenvPy -Argument "-m pravka_archive serve --env `"$EnvFile`"" -WorkingDirectory $Server
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $settings = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
        -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -StartWhenAvailable -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal | Out-Null

    # Порт должен быть свободен: на нём мог остаться проверочный hello.py из
    # шага 8 части А — тогда служба не встанет, а /health ответит не она.
    Start-Sleep -Seconds 2
    $busy = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($busy) {
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $($busy.OwningProcess)" -ErrorAction SilentlyContinue
        $what = if ($proc) { "$($proc.Name) (PID $($proc.ProcessId)): $($proc.CommandLine)" } else { "PID $($busy.OwningProcess)" }
        throw "Порт $port уже занят: $what. Если это проверочный hello.py — останови его и запусти установку ещё раз."
    }
    Start-ScheduledTask -TaskName $TaskName

    Step 'Ждём, пока служба ответит'
    $ok = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        try {
            $r = Invoke-RestMethod -Uri "http://127.0.0.1:$port/health" -TimeoutSec 3
            if ($r.service -eq 'pravka-archive') { $ok = $true; break }
        } catch { }
    }
    if ($ok) {
        Write-Host "Служба отвечает: http://127.0.0.1:$port/health" -ForegroundColor Green
    } else {
        $failed = $true
        Write-Warning 'Служба не ответила за 30 секунд.'
        Get-ScheduledTaskInfo -TaskName $TaskName | Format-List LastRunTime, LastTaskResult | Out-String | Write-Host
        $appLog = Join-Path $Logs 'archive.log'
        if (Test-Path -LiteralPath $appLog) { Write-Host 'Конец archive.log:'; Get-Content -LiteralPath $appLog -Tail 30 | Write-Host }
        Write-Host 'Если ошибка загрузки DLL — смотри журнал Microsoft-Windows-CodeIntegrity/Operational (Smart App Control).'
        Write-Host 'Если ошибка доступа к файлам — попробуй ещё раз с ключом -RunAs System.'
    }

    Step 'Самопроверка'
    Push-Location -LiteralPath $Server
    try { & $VenvPy -m pravka_archive check --env $EnvFile } finally { Pop-Location }

    Step 'Дальше'
    $public = EnvValue 'PRAVKA_PUBLIC_URL'
    Write-Host "1. С телефона по мобильной сети открой $public/health — должен ответить pravka-archive."
    Write-Host "2. claude.ai → Settings → Connectors → Add custom connector: «Правка», адрес $public/mcp → Connect → пароль архива."
    Write-Host '3. Телефон: в консоли этого компа — .venv\Scripts\python -m pravka_archive pair (QR с адресом и токеном).'
} catch {
    $failed = $true
    Write-Host ''
    Write-Host "ОШИБКА: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    Write-Host ''
    Write-Host "Журнал установки: $Log"
    Stop-Transcript | Out-Null
}
if ($failed) { exit 1 }
