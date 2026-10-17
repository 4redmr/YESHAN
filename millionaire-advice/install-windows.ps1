# Registers a Task Scheduler task that opens the Chairman's Letter
# every day at 07:00 and every time this user logs on.
# Kept ASCII-only so Windows PowerShell 5.1 reads it correctly without a BOM.

$ErrorActionPreference = 'Stop'
$TaskName = 'MillionaireAdvice'
$Html = Join-Path $PSScriptRoot 'index.html'
if (-not (Test-Path $Html)) { throw "index.html not found next to this script: $Html" }
$Url = ([System.Uri]$Html).AbsoluteUri

# Prefer Edge/Chrome in --app mode so the letter opens as its own clean window.
$candidates = @(
  "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
  "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
  "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
  "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
  "$env:LocalAppData\Google\Chrome\Application\chrome.exe"
)
$browser = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

if ($browser) {
  $action = New-ScheduledTaskAction -Execute $browser -Argument "--app=`"$Url`" --window-size=980,920 --new-window"
} else {
  $action = New-ScheduledTaskAction -Execute 'explorer.exe' -Argument "`"$Html`""
}

$daily = New-ScheduledTaskTrigger -Daily -At '07:00'
$logon = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$logon.Delay = 'PT20S'   # let the desktop finish loading first

$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 5)
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger @($daily, $logon) `
  -Settings $settings -Principal $principal -Description "The Chairman's Morning Letter" -Force | Out-Null

Write-Host ""
Write-Host "Installed: '$TaskName' (daily 07:00 + at logon)." -ForegroundColor Yellow
Write-Host "Opening it once now so you can see it..."
Start-ScheduledTask -TaskName $TaskName
