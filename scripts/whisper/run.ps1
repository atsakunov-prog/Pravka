# Запуск распознавателя Правки вручную (для проверки и отладки).
# Настройки - в whisper.env рядом. Автозапуск ставится install-service.ps1.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
& "$here\.venv\Scripts\python.exe" "$here\server.py"
