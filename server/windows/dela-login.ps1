#Requires -Version 5.1
<#
  Дела: войти в веб сейчас — без ожидания и без Claude.

  Запускать ОТ ИМЕНИ АДМИНИСТРАТОРА (ключи Дел видит только он) — или ярлыком
  «Войти в Дела» на рабочем столе: первый запуск сам его создаёт.

    powershell -ExecutionPolicy Bypass -File D:\PravkaArchive\repo\server\windows\dela-login.ps1

  Что делает:
    1. одноразовая ссылка входа на основной адрес веба (DELA_PUBLIC_URL, dela.kovcheg.am) —
       сразу открывается в браузере и копируется в буфер;
    2. ещё одна — QR-картинкой для телефона: навёл камеру — вошёл (картинка удаляется);
    3. ярлык «Войти в Дела» на рабочем столе, если его нет.
  Вход живёт полгода на том адресе, где вошёл. Каждая ссылка — своя, работает один раз, двое суток.
  Ссылки не пишутся в журнал. Журнал — D:\PravkaArchive\logs\dela-login-<дата>.log.
#>
param(
    [string]$User = 'sasha',
    [string]$Root = 'D:\PravkaArchive',
    [switch]$NoPhone
)

$ErrorActionPreference = 'Stop'
$Server  = Join-Path $Root 'repo\server'
$VenvPy  = Join-Path $Server '.venv\Scripts\python.exe'
$DelaEnv = 'C:\ProgramData\ZF-Dela\secrets\dela.env'
$Log     = Join-Path $Root ("logs\dela-login-{0}.log" -f (Get-Date -Format 'yyyy-MM-dd-HHmmss'))
$Me      = $MyInvocation.MyCommand.Path

function Say([string]$text, [string]$color = 'Gray') { Write-Host $text -ForegroundColor $color; Add-Content -LiteralPath $Log -Value $text -Encoding UTF8 }

function New-Link([string]$what) {
    $tmp = Join-Path $env:TEMP ("dela-login-{0}.txt" -f [guid]::NewGuid().ToString('N'))
    try {
        # Предупреждения Python идут в stderr: при Stop они оборвали бы скрипт.
        $ErrorActionPreference = 'Continue'
        $out = & $VenvPy -m pravka_dela --env $DelaEnv invite $User --out $tmp 2>&1
        if ($LASTEXITCODE -ne 0) { throw "ссылка ($what) не вышла: $out" }
        return (Get-Content -LiteralPath $tmp -Raw).Trim()
    } finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

New-Item -ItemType Directory -Force -Path (Split-Path $Log) | Out-Null
try {
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $isAdmin) { throw 'нужен запуск от имени администратора (правой кнопкой → «Запуск от имени администратора»)' }
    foreach ($need in $VenvPy, $DelaEnv) { if (-not (Test-Path -LiteralPath $need)) { throw "нет $need" } }
    $env:PYTHONPATH = $Server
    Set-Location $Server

    # 1. Этот компьютер — сразу в браузере.
    $link = New-Link 'компьютер'
    try { Set-Clipboard -Value $link } catch {}
    Start-Process $link
    Say "Дела: ссылка входа открыта в браузере и скопирована в буфер ($($link.Split('#')[0]))." 'Green'
    Say '  Не открылась — вставьте из буфера в адресную строку Chrome.'

    # 2. Ярлык на рабочем столе — дальше вход в один двойной клик.
    $lnk = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Войти в Дела.lnk'
    if (-not (Test-Path -LiteralPath $lnk)) {
        # WScript.Shell не сохраняет ярлык с кириллицей в имени (кодовая страница системы
        # западная) — сохраняем латиницей и переименовываем.
        $tmpLnk = Join-Path (Split-Path $lnk) 'dela-login.lnk'
        $sh = New-Object -ComObject WScript.Shell
        $s = $sh.CreateShortcut($tmpLnk)
        $s.TargetPath = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
        $s.Arguments = "-ExecutionPolicy Bypass -File `"$Me`""
        $s.WorkingDirectory = $Server
        $s.IconLocation = "$env:SystemRoot\System32\imageres.dll,77"
        $s.Save()
        # Флаг «запускать от имени администратора» (байт 0x15 ярлыка, бит 0x20).
        $bytes = [IO.File]::ReadAllBytes($tmpLnk)
        $bytes[0x15] = $bytes[0x15] -bor 0x20
        [IO.File]::WriteAllBytes($tmpLnk, $bytes)
        Move-Item -LiteralPath $tmpLnk -Destination $lnk -Force
        Say "  Ярлык «Войти в Дела» — на рабочем столе: дальше двойной клик и «Да»."
    }

    # 3. Телефон — QR на экране, картинка удаляется после входа.
    if (-not $NoPhone) {
        $phone = New-Link 'телефон'
        $png = Join-Path $env:TEMP ("dela-login-{0}.png" -f [guid]::NewGuid().ToString('N'))
        # Без кавычек внутри: PowerShell 5.1 срезает их в аргументах внешней программы.
        $code = 'import segno, sys; segno.make(sys.argv[1], error=sys.argv[3]).save(sys.argv[2], scale=10, border=3)'
        $ErrorActionPreference = 'Continue'
        & $VenvPy -c $code $phone $png 'm' 2>&1 | Out-Null
        $ErrorActionPreference = 'Stop'
        if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $png)) {
            Start-Process $png
            Say ''
            Say 'Телефон: наведите камеру на QR в открывшемся окне — Дела откроются уже с входом.' 'Cyan'
            Read-Host '  Нажмите Enter, когда телефон вошёл (или если телефон не нужен)' | Out-Null
            Remove-Item -LiteralPath $png -Force -ErrorAction SilentlyContinue
            Say '  QR удалён.'
        } else {
            Say '  QR для телефона не вышел — компьютер это не задевает.' 'Yellow'
        }
    }
    Say ''
    Say 'ГОТОВО. Вход живёт полгода на этом адресе.' 'Green'
} catch {
    Say "ОШИБКА: $($_.Exception.Message)" 'Red'
    Say "Журнал: $Log — скажите Claude."
}
Read-Host 'Enter — закрыть окно' | Out-Null
