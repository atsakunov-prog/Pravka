#Requires -Version 5.1
<#
  Дела: установить или переустановить службу ZF-Dela на домашнем компе.

  Запускать в PowerShell ОТ ИМЕНИ АДМИНИСТРАТОРА:

    powershell -ExecutionPolicy Bypass -File D:\PravkaArchive\repo\server\windows\install-dela.ps1

  По шагам (повтор безвреден):
    1. код: git pull ветки pravka и переустановка архива (update.ps1);
    2. роль базы dela_app: своя, без доступа к life и core;
    3. секреты C:\ProgramData\ZF-Dela\secrets\dela.env (пароль роли, адреса,
       ключ Claude для разбора текста — берётся у встреч);
       адрес базы Дел — ещё и в server.env архива: так у Claude в коннекторе
       «Правка» появляются инструменты дел;
    4. схемы crm и tasks (migrate ролью владельца базы);
    5. служба ZF-Dela (NSSM), виртуальная учётка NT SERVICE\ZF-Dela с типом
       SID restricted: писать может только в свои папки, C:\Bot ей закрыт,
       server.env архива она не видит;
    6. брандмауэр: порт 8102 только от роутера;
    7. перенос из Todoist, Notion и ленты (если в -Import лежат выгрузки и
       решения владельца; токен Todoist — по желанию: с ним перенос берёт и
       комментарии, а мост забирает новые задачи до переезда телефона);
    8. запуск, /health, самопроверка; перезапуск архива;
    9. встречи: токен службы и мягкий перезапуск обработчика — задачи из
       разборов поедут в «Новое»; плашка «Дела» на «Доме».
  Итог — в D:\PravkaArchive\logs\install-dela-<дата>.log.
#>
param(
    [string]$Root = 'D:\PravkaArchive',
    [string]$Python = 'C:\Program Files\Python314\python.exe',
    [int]$Port = 8102,
    [string]$PublicUrl = 'https://dela.greenfieldnotes.com',
    [string]$PhoneUrl = 'https://dela.znakomiy.netcraze.pro:8443',
    [string]$Import = 'C:\Bot\Dela\import',
    [switch]$NoUpdate
)

$ErrorActionPreference = 'Stop'
$Svc      = 'ZF-Dela'
$Ac       = "NT SERVICE\$Svc"
$Rule     = "ZF-Dela (TCP $Port, router only)"
$Server   = Join-Path $Root 'repo\server'
$VenvPy   = Join-Path $Server '.venv\Scripts\python.exe'
$OwnerEnv = Join-Path $Root 'secrets\server.env'
$PgPass   = Join-Path $Root 'secrets\postgres.txt'
$Data     = 'C:\ProgramData\ZF-Dela'
$Secrets  = Join-Path $Data 'secrets'
$DelaEnv  = Join-Path $Secrets 'dela.env'
$MeetKey  = 'D:\Meetings\secrets\anthropic.env'
$Logs     = 'C:\Bot\ZFbot\logs\dela'
$Nssm     = 'C:\Bot\ZFbot\nssm\nssm.exe'
$Owner    = 'alex'
$System   = '*S-1-5-18'
$Admins   = '*S-1-5-32-544'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host 'НУЖНЫ ПРАВА АДМИНИСТРАТОРА. Откройте PowerShell от имени администратора и запустите снова.' -ForegroundColor Red
    exit 1
}

New-Item -ItemType Directory -Force -Path (Join-Path $Root 'logs') | Out-Null
$Log = Join-Path $Root ('logs\install-dela-{0:yyyy-MM-dd-HHmmss}.log' -f (Get-Date))
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
# Пакеты pravka_dela и pravka_archive не ставятся в venv, а лежат в папке сервера:
# python -m их находит, только если эта папка в пути поиска (03.10: «No module named pravka_dela»).
$env:PYTHONPATH = $Server
Set-Location -LiteralPath $Server
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Start-Transcript -Path $Log | Out-Null

function Step([string]$text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Ok([string]$text) { Write-Host "   $text" -ForegroundColor Green }
function Warn([string]$text) { Write-Host "   $text" -ForegroundColor Yellow }

function Read-Env([string]$path) {
    $map = [ordered]@{}
    if (Test-Path -LiteralPath $path) {
        foreach ($line in Get-Content -LiteralPath $path -Encoding UTF8) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { $map[$Matches[1]] = $Matches[2].Trim() }
        }
    }
    return $map
}

function Set-EnvLine([string]$path, [string]$name, [string]$value) {
    # Строку NAME=… заменить или дописать; файл — UTF-8 без BOM и с LF, как его пишет установщик архива.
    $lines = @()
    if (Test-Path -LiteralPath $path) { $lines = @(Get-Content -LiteralPath $path -Encoding UTF8) }
    $lines = @($lines | Where-Object { $_ -notmatch "^\s*$name\s*=" }) + "$name=$value"
    [IO.File]::WriteAllText($path, (($lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding($false)))
}

function Py([string]$what, [string[]]$pyArgs) {
    # Предупреждения Python идут в stderr: при Stop они оборвали бы скрипт.
    $ErrorActionPreference = 'Continue'
    $out = & $VenvPy @pyArgs 2>&1
    $code = $LASTEXITCODE
    $out | ForEach-Object { Write-Host "   $_" }
    if ($code -ne 0) { throw "$what — код $code" }
}

try {
    Step '1/9 Код'
    if (-not $NoUpdate) {
        # Как update.ps1, но расхождение с GitHub не останавливает установку: код Дел мог
        # приехать на комп раньше, чем на GitHub, — тогда ставится то, что лежит на компе.
        $repo = Join-Path $Root 'repo'
        git config --global --add safe.directory ($repo -replace '\\', '/') 2>$null
        $ErrorActionPreference = 'Continue'
        git -C $repo pull --ff-only 2>&1 | ForEach-Object { Write-Host "   $_" }
        $pulled = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($pulled -ne 0) { Warn 'git pull не прошёл (код на компе и на GitHub разошлись) — ставлю то, что на компе' }
        & (Join-Path $Server 'windows\install.ps1') -Root $Root -Python $Python
        if ($LASTEXITCODE -ne 0) { throw "install.ps1 архива — код $LASTEXITCODE" }
    }
    foreach ($need in $VenvPy, $Nssm, $OwnerEnv, $PgPass, (Join-Path $Server 'pravka_dela\__main__.py')) {
        if (-not (Test-Path -LiteralPath $need)) { throw "нет $need" }
    }
    Ok "код: $(git -C (Join-Path $Root 'repo') log --oneline -1)"

    Step '2/9 Роль базы dela_app'
    New-Item -ItemType Directory -Force -Path $Secrets, $Logs | Out-Null
    $dela = Read-Env $DelaEnv
    $pw = $null
    if ($dela['DELA_DB_URL'] -match '^postgresql://dela_app:([^@]+)@') { $pw = $Matches[1] }
    if (-not $pw) {
        $bytes = New-Object byte[] 24
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $pw = ([Convert]::ToBase64String($bytes) -replace '[+/=]', 'x')
    }
    # Роль заводит суперпользователь; пароль уходит в Python через окружение, не в командную строку.
    $env:DELA_SUPER_PW = (Get-Content -LiteralPath $PgPass -Raw).Trim()
    $env:DELA_APP_PW = $pw
    $code = @'
import os, psycopg
from psycopg import sql
dsn = os.environ["DELA_SUPER_PW"]
if not dsn.startswith("postgresql://"):
    dsn = "postgresql://postgres:%s@127.0.0.1:5432/postgres" % dsn
with psycopg.connect(dsn, autocommit=True) as c:
    have = c.execute("SELECT 1 FROM pg_roles WHERE rolname = 'dela_app'").fetchone()
    verb = "ALTER" if have else "CREATE"
    c.execute(sql.SQL(verb + " ROLE dela_app LOGIN PASSWORD {}").format(sql.Literal(os.environ["DELA_APP_PW"])))
    c.execute("ALTER ROLE dela_app SET statement_timeout = '30s'")
    c.execute("GRANT CONNECT ON DATABASE pravka TO dela_app")
    print("роль dela_app:", "обновлена" if have else "заведена")
'@
    # Код — файлом: PowerShell 5.1 портит кавычки внутри аргументов внешней программы.
    $tmp = Join-Path $env:TEMP 'dela-role.py'
    [IO.File]::WriteAllText($tmp, $code, (New-Object Text.UTF8Encoding($false)))
    try { Py 'роль dela_app' @($tmp) } finally {
        Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
        Remove-Item Env:\DELA_SUPER_PW, Env:\DELA_APP_PW -ErrorAction SilentlyContinue
    }

    Step '3/9 Секреты'
    $url = "postgresql://dela_app:$pw@127.0.0.1:5432/pravka"
    Set-EnvLine $DelaEnv 'DELA_DB_URL' $url
    Set-EnvLine $DelaEnv 'DELA_LISTEN' "0.0.0.0:$Port"
    Set-EnvLine $DelaEnv 'DELA_PUBLIC_URL' $PublicUrl
    Set-EnvLine $DelaEnv 'DELA_PHONE_URL' $PhoneUrl
    Set-EnvLine $DelaEnv 'DELA_LOGS' $Logs
    Set-EnvLine $DelaEnv 'DELA_DATA' $Data
    Set-EnvLine $OwnerEnv 'DELA_DB_URL' $url
    Ok "$DelaEnv (пароль роли — $($pw.Substring(0, 4))…); DELA_DB_URL дописан в server.env архива"
    # Кнопка Claude в вебе (разбор текста в дела): ключ встреч, если своего ещё нет.
    if (-not (Read-Env $DelaEnv)['ANTHROPIC_API_KEY']) {
        $key = (Read-Env $MeetKey)['ANTHROPIC_API_KEY']
        if ($key) { Set-EnvLine $DelaEnv 'ANTHROPIC_API_KEY' $key; Ok "ключ Claude взят у встреч ($($key.Substring(0, 4))…)" }
        else { Warn "в $MeetKey нет ANTHROPIC_API_KEY — кнопка Claude в вебе работать не будет" }
    } else { Ok 'ключ Claude уже есть' }

    Step '4/9 Схемы crm и tasks'
    Py 'migrate' @('-m', 'pravka_dela', '--env', $DelaEnv, 'migrate', '--owner-env', $OwnerEnv)

    Step '5/9 Перенос из Todoist, Notion и ленты'
    $decisions = Join-Path $Import 'decisions.json'
    if (Test-Path -LiteralPath $decisions) {
        $dela = Read-Env $DelaEnv
        if (-not $dela['DELA_TODOIST_TOKEN']) {
            Write-Host '   Токен Todoist (Todoist → Настройки → Интеграции → Разработчик → API-токен).'
            Write-Host '   С ним перенос возьмёт и комментарии, а новые задачи с телефона будут доезжать в Дела до переезда Правки.'
            Write-Host '   Просто Enter — перенести по снимку без комментариев, моста не будет (можно запустить скрипт ещё раз).'
            $sec = Read-Host '   Токен' -AsSecureString
            $tok = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)).Trim()
            if ($tok) { Set-EnvLine $DelaEnv 'DELA_TODOIST_TOKEN' $tok; Ok "токен Todoist записан ($($tok.Substring(0, 4))…)" }
        }
        $source = Join-Path $Import 'todoist-snapshot.json'
        if ((Read-Env $DelaEnv)['DELA_TODOIST_TOKEN']) {
            $api = Join-Path $Import 'todoist-api.json'
            Py 'выгрузка Todoist' @('-m', 'pravka_dela', '--env', $DelaEnv, 'export-todoist', '--out', $api)
            $source = $api
        }
        Py 'перенос' @('-m', 'pravka_dela', '--env', $DelaEnv, 'import', '--apply', '--todoist', $source,
            '--notion', $Import, '--clients', (Join-Path $Import 'ledger-clients.json'), '--plan', $decisions,
            '--report', (Join-Path $Import 'report-apply.md'))
        Ok "отчёт переноса — $(Join-Path $Import 'report-apply.md')"
    } else {
        Warn "в $Import нет decisions.json — перенос пропущен"
    }

    Step '6/9 Служба ZF-Dela'
    $svcObj = Get-Service -Name $Svc -ErrorAction SilentlyContinue
    if ($svcObj -and $svcObj.Status -ne 'Stopped') {
        & $Nssm stop $Svc | Out-Null
        try { $svcObj.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30)) } catch {}
    }
    if (-not $svcObj) { & $Nssm install $Svc $VenvPy | Out-Null }
    & $Nssm set $Svc Application $VenvPy | Out-Null
    New-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Services\$Svc\Parameters" -Name AppParameters `
        -PropertyType ExpandString -Force -Value "-m pravka_dela --env `"$DelaEnv`" serve" | Out-Null
    & $Nssm set $Svc AppDirectory $Server | Out-Null
    & $Nssm set $Svc DisplayName 'ZF Dela (дела и CRM)' | Out-Null
    & $Nssm set $Svc Description "Дела: задачи, люди, проекты и сделки. API для Правки, веба и ботов. Порт $Port, вход по токену" | Out-Null
    & $Nssm set $Svc Start SERVICE_AUTO_START | Out-Null
    & $Nssm set $Svc AppStdout "$Logs\service-stdout.log" | Out-Null
    & $Nssm set $Svc AppStderr "$Logs\service-stderr.log" | Out-Null
    & $Nssm set $Svc AppRotateFiles 1 | Out-Null
    & $Nssm set $Svc AppRotateBytes 5000000 | Out-Null
    & $Nssm set $Svc AppExit Default Restart | Out-Null
    & $Nssm set $Svc AppRestartDelay 5000 | Out-Null
    & $Nssm set $Svc AppEnvironmentExtra 'PYTHONIOENCODING=utf-8' 'PYTHONDONTWRITEBYTECODE=1' | Out-Null
    sc.exe config $Svc obj= $Ac | Out-Null
    sc.exe sidtype $Svc restricted | Out-Null

    icacls $Data /inheritance:r /grant:r "${System}:(OI)(CI)F" "${Admins}:(OI)(CI)F" "${Owner}:(OI)(CI)M" /Q | Out-Null
    icacls $Secrets /inheritance:r /grant:r "${System}:(OI)(CI)F" "${Admins}:(OI)(CI)F" "${Owner}:(OI)(CI)M" "${Ac}:(OI)(CI)R" /Q | Out-Null
    icacls $Server /grant "${Ac}:(OI)(CI)RX" /Q | Out-Null
    icacls $Logs /grant "${Ac}:(OI)(CI)M" /Q | Out-Null
    icacls 'C:\Bot\ZFbot\nssm' /grant "${Ac}:(OI)(CI)RX" /Q | Out-Null
    Write-Host '   закрываю C:\Bot для службы (на больших папках — до минуты)…'
    icacls 'C:\Bot' /deny "${Ac}:(OI)(CI)F" /Q | Out-Null
    if ($LASTEXITCODE -ne 0) { Warn "запрет на C:\Bot выставился не везде (icacls $LASTEXITCODE)" }

    # alex перезапускает службу сам: правки кода применяются без администратора.
    $sid = (New-Object Security.Principal.NTAccount($Owner)).Translate([Security.Principal.SecurityIdentifier]).Value
    $sd = (sc.exe sdshow $Svc | Where-Object { $_ -match '^D:' } | Select-Object -First 1).Trim()
    if ($sd -notmatch [regex]::Escape($sid)) {
        sc.exe sdset $Svc ($sd -replace '^D:([A-Z]*)', ('D:$1' + "(A;;CCLCSWRPWPDTLOCRRC;;;$sid)")) | Out-Null
    }
    Ok "$Svc — учётка $((Get-CimInstance Win32_Service -Filter "Name='$Svc'").StartName)"

    Step '7/9 Брандмауэр'
    Get-NetFirewallRule -DisplayName $Rule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -DisplayName $Rule -Direction Inbound -Action Allow -Protocol TCP `
        -LocalPort $Port -RemoteAddress '192.168.1.1' -Program $Python -Profile Any | Out-Null
    Ok "порт $Port — только от роутера 192.168.1.1"

    Step '8/9 Запуск и проверка'
    & $Nssm start $Svc | Out-Null
    $up = $false
    $deadline = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $deadline) {
        try {
            $h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 3
            if ($h.service -eq 'pravka-dela') { $up = $true; break }
        } catch {}
        Start-Sleep -Seconds 2
    }
    if ($up) { Ok 'служба отвечает' } else { throw "служба не отвечает — смотрите $Logs" }
    $code401 = try { (Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/sync" -UseBasicParsing -TimeoutSec 3).StatusCode } catch { $_.Exception.Response.StatusCode.value__ }
    if ($code401 -eq 401) { Ok 'без токена данные не отдаются (401)' } else { Warn "/api/sync без токена ответил $code401 вместо 401" }
    Py 'check' @('-m', 'pravka_dela', '--env', $DelaEnv, 'check')

    Stop-ScheduledTask -TaskName 'Pravka Archive' -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 8  # служба архива замечает уход сторожа за 5 с и освобождает порт
    Start-ScheduledTask -TaskName 'Pravka Archive'
    Ok 'архив перезапущен: в коннекторе «Правка» появятся инструменты дел (dela_*)'

    Step '9/9 Встречи и «Дом»'
    $meetEnv = 'D:\Meetings\secrets\dela.env'
    if ((Test-Path 'D:\Meetings\secrets') -and -not (Read-Env $meetEnv)['DELA_TOKEN']) {
        $tmpTok = Join-Path $env:TEMP 'dela-meetings.tok'
        Py 'токен встреч' @('-m', 'pravka_dela', '--env', $DelaEnv, 'token', '--user', 'sasha', '--kind', 'service', '--name', 'meetings', '--out', $tmpTok)
        Set-EnvLine $meetEnv 'DELA_URL' "http://127.0.0.1:$Port"
        Set-EnvLine $meetEnv 'DELA_TOKEN' ((Get-Content -LiteralPath $tmpTok -Raw).Trim())
        Set-EnvLine $meetEnv 'DELA_SINCE' (Get-Date -Format 'yyyy-MM-dd')
        Remove-Item -LiteralPath $tmpTok -Force
        Ok 'встречи получили токен: задачи свежих разборов поедут в «Новое»'
    }
    # Обработчик встреч перезапускается мягко: дорабатывает текущее задание (как кнопка в вебе).
    $restart = @'
import json, sqlite3, time, datetime
c = sqlite3.connect(r"D:\Meetings\db\meetings.db", timeout=30)
c.execute("INSERT INTO kv(key, value, updated_at) VALUES('restart:worker', ?, ?) "
          "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
          (json.dumps({"ts": time.time()}), datetime.datetime.now(datetime.timezone.utc).isoformat()))
c.commit()
print("обработчик встреч перезапустится, когда допишет текущее")
'@
    $tmp = Join-Path $env:TEMP 'dela-meetings-restart.py'
    [IO.File]::WriteAllText($tmp, $restart, (New-Object Text.UTF8Encoding($false)))
    try { Py 'перезапуск встреч' @($tmp) } finally { Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue }
    if (Get-Service -Name 'ZF-Home-Collector' -ErrorAction SilentlyContinue) {
        & $Nssm restart ZF-Home-Collector | Out-Null
        Ok 'сборщик «Дома» перезапущен: плашка «Дела»'
    }

    $inviteFile = Join-Path $Secrets 'invite-sasha.txt'
    Py 'ссылка входа' @('-m', 'pravka_dela', '--env', $DelaEnv, 'invite', 'sasha', '--base', $PhoneUrl, '--out', $inviteFile)

    Write-Host ''
    Write-Host 'ГОТОВО.' -ForegroundColor Green
    Write-Host "  Дальше в роутере: CrazeDNS → Add → dela → 192.168.1.77, HTTP, порт $Port, unrestricted."
    Write-Host "  Потом веб: ссылка входа — в $inviteFile (одноразовая, двое суток)."
} catch {
    Write-Host ''
    Write-Host "ОШИБКА: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Журнал: $Log — скажите Claude."
} finally {
    Stop-Transcript | Out-Null
}
