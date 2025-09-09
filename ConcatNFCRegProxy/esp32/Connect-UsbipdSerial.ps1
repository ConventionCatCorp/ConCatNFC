#Requires -RunAsAdministrator
#Requires -Version 7

<#
.SYNOPSIS
Script that continuously tries to share a list of USB devices with WSL via USBIPD
#>
[CmdletBinding()]
param (
    [string[]]$HardwareIds = @(
        "1a86:55d3" # QinHeng Electronics - USB TTL serial on ESP32s3 devboard
    ),

    [switch]$Once
)

Import-Module -Force $env:ProgramW6432'\usbipd-win\Usbipd.Powershell.dll'

Write-Host "Auto-sharing USB devices with WSL"
Write-Host "Filtering $($HardwareIds.Count) device ID(s):"
$HardwareIds | Write-Host
Write-Host "Monitoring for new devices..."

while ($true) {
    $allDevices = Get-UsbipdDevice
    $targetDevices = @()

    $HardwareIds | ForEach-Object {
        $split = $_.Split(":")
        return @{
            vid = [Convert]::ToInt32($split[0], 16)
            pid = [Convert]::ToInt32($split[1], 16)
        }
    } | ForEach-Object {

        $target = $_
        Write-Debug "Filtering VID $($target.vid) PID $($target.pid)"
        $allDevices | Where-Object {
            $_.IsConnected -and
            $_.HardwareId.Vid -eq $target.vid -and
            $_.HardwareId.Pid -eq $target.pid
        } | ForEach-Object {
            $targetDevices += @($_)
        }
    }
    Write-Debug "Found $($targetDevices.Count) candidate devices"

    $targetDevices | Where-Object {
        -not $_.IsBound
    } | ForEach-Object {
        Write-Host "Binding new device '$($_.Description)' on bus $($_.BusId)"
        usbipd bind --busid $_.BusId
    }

    $targetDevices | Where-Object {
        -not $_.IsAttached
    } | ForEach-Object {
        Write-Host "Attaching new device '$($_.Description)' on bus $($_.BusId)"
        Write-Host "Use the command Receive-Job <ID> to get the output of usbipd."
        usbipd attach --wsl --busid $_.BusId -a &
    }

    if ($Once) {
        exit 0
    }

    Write-Debug "Looping... $((Get-Date).ToString("o"))"
    Start-Sleep -Seconds 2
}
