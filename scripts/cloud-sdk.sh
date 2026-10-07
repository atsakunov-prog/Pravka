#!/usr/bin/env bash
# Android SDK для облачной сессии Claude Code (07.10.2026).
#
# Контейнер облачной сессии свежий: Gradle и JDK в нём есть, Android SDK —
# нет. Без него ни компиляция, ни тесты не идут, а ночной разбор багов
# (docs/feedback.md) работает без человека — ставить SDK ему некому.
# Скрипт ставит ровно то, что просит сборка (compileSdk 36, ndkVersion и
# cmake из app/build.gradle.kts), и пишет sdk.dir в local.properties.
# Повторный запуск — секунда: всё на месте — ничего не качает.
#
#   bash scripts/cloud-sdk.sh
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS_ZIP="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
# build-tools 35 — их берёт сам плагин Android (без них он докачивает их на
# первой сборке, а лучше не зависеть от этого ночью).
PKGS=("platforms;android-36" "build-tools;36.0.0" "build-tools;35.0.0" "platform-tools" "ndk;27.2.12479018" "cmake;3.22.1")

ready() {
    [ -d "$SDK/platforms/android-36" ] && [ -d "$SDK/build-tools/36.0.0" ] && [ -d "$SDK/build-tools/35.0.0" ] &&
        [ -d "$SDK/platform-tools" ] &&
        [ -d "$SDK/ndk/27.2.12479018" ] && [ -d "$SDK/cmake/3.22.1" ]
}

if ! ready; then
    echo "Android SDK: ставлю в $SDK (несколько минут)…"
    if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
        tmp="$(mktemp -d)"
        curl -fsSL -o "$tmp/tools.zip" "$TOOLS_ZIP"
        unzip -q -o "$tmp/tools.zip" -d "$tmp"
        mkdir -p "$SDK/cmdline-tools"
        rm -rf "$SDK/cmdline-tools/latest"
        mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
        rm -rf "$tmp"
    fi
    manager="$SDK/cmdline-tools/latest/bin/sdkmanager"
    # yes обрывается по SIGPIPE — с pipefail это «ошибка», хотя лицензии приняты.
    yes | "$manager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
    log="$(mktemp)"
    if ! "$manager" --sdk_root="$SDK" "${PKGS[@]}" >"$log" 2>&1; then
        echo "sdkmanager упал:" >&2
        tail -20 "$log" >&2
        exit 1
    fi
    rm -f "$log"
fi
ready || { echo "Android SDK неполный в $SDK — смотри вывод выше" >&2; exit 1; }

# local.properties не в git: в нём только путь к SDK (подпись — по умолчанию
# из keystore/pravka.jks). Чужие строки, если файл уже есть, не трогаем.
props="$ROOT/local.properties"
if [ -f "$props" ] && grep -q '^sdk\.dir=' "$props"; then
    sed -i "s|^sdk\.dir=.*|sdk.dir=$SDK|" "$props"
else
    echo "sdk.dir=$SDK" >>"$props"
fi
echo "Android SDK готов: $SDK"
