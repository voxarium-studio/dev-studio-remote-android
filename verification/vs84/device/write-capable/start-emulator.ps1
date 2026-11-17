$ErrorActionPreference='Stop'
$root=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../.tools')))
$t=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../../VoxariumStudio/.voxarium_studio/temp/TASK-84/device/write-capable')))
$sdk=$env:ANDROID_HOME
if((& "$sdk\platform-tools\adb.exe" devices) -match '^emulator-5580\s') { throw 'Serial emulator-5580 is already present; inspect it, do not replace another emulator.' }
if(Get-NetTCPConnection -LocalPort 5580,5581 -ErrorAction SilentlyContinue | Where-Object {$_.State -eq 'Listen' -and $_.OwningProcess -gt 0}) { throw 'Owned emulator ports have a live listener; do not terminate their owner.' }
$env:ANDROID_AVD_HOME="$root\avd"
$env:ANDROID_USER_HOME="$root\android-home"
$env:ANDROID_EMULATOR_HOME="$root\emulator-home"
$env:ANDROID_SDK_ROOT=$sdk
$env:TEMP=$t; $env:TMP=$t
# Reuse the existing owned AVD without rewriting its config or provisioning SDK files.
foreach($path in @("$root\avd\VS78_API35.ini","$root\avd\VS78_API35.avd\config.ini")) {
  if(!(Test-Path -LiteralPath $path -PathType Leaf)){throw 'Owned AVD is missing; do not provision outside the follow-up scope'}
}
New-Item -ItemType Directory -Force $t | Out-Null
$p=Start-Process -WindowStyle Hidden -FilePath "$sdk\emulator\emulator.exe" -ArgumentList @('-avd','VS78_API35','-no-window','-no-audio','-no-snapshot','-gpu','swiftshader','-port','5580') -RedirectStandardOutput "$t\emulator.stdout.log" -RedirectStandardError "$t\emulator.stderr.log" -PassThru
$p.Id | Set-Content "$t\emulator.pid"
Write-Output "Started owned emulator PID=$($p.Id), serial=emulator-5580"
