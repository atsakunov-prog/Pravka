# Reconnect adb over Wi-Fi. The connection drops after every phone reboot
# and sometimes on its own - this is the most frequently used command here.
#
# Usage:
#   .\scripts\reconnect.ps1                              reconnect to the saved address
#   .\scripts\reconnect.ps1 192.168.1.50:37000           connect to a new address and save it
#   .\scripts\reconnect.ps1 -Pair 192.168.1.50:37123 -Code 123456   one-time pairing
#
# First-time setup on the phone:
#   Settings > System > Developer options > Wireless debugging > ON
#   "Pair device with pairing code" gives the PAIRING ip:port and a 6-digit code:
#       .\scripts\reconnect.ps1 -Pair <pairing ip:port> -Code <code>
#   The main Wireless debugging screen shows the CONNECT ip:port (different port!):
#       .\scripts\reconnect.ps1 <connect ip:port>

param(
    [Parameter(Position = 0)] [string]$Address,
    [string]$Pair,
    [string]$Code
)

$savedFile = Join-Path $PSScriptRoot ".adb-address"

if ($Pair) {
    if (-not $Code) {
        Write-Error "Pairing requires -Code <6-digit code from the phone>"
        exit 1
    }
    adb pair $Pair $Code
    Write-Host "Paired. Now connect using the CONNECT ip:port from the Wireless debugging screen:"
    Write-Host "  .\scripts\reconnect.ps1 <ip:port>"
    exit 0
}

if (-not $Address) {
    if (Test-Path $savedFile) {
        $Address = (Get-Content $savedFile -Raw).Trim()
    } else {
        Write-Error "No saved address yet. Run: .\scripts\reconnect.ps1 <ip:port>"
        exit 1
    }
}

adb disconnect $Address 2>$null | Out-Null
adb connect $Address
Set-Content -Path $savedFile -Value $Address
adb devices
