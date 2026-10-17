@echo off
rem Removes the scheduled task. The files in this folder are left as they are.
powershell -NoProfile -ExecutionPolicy Bypass -Command "Unregister-ScheduledTask -TaskName 'MillionaireAdvice' -Confirm:$false; Write-Host 'Removed.'"
pause
