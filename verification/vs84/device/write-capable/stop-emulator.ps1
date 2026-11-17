$ErrorActionPreference='Stop'
$adb=(Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe')
$devices=& $adb devices
if(!($devices -match '^emulator-5580\s')) {Write-Output 'Owned emulator is already absent';return}
$name=& $adb -s emulator-5580 emu avd name
if(!$name -or $name[0] -ne 'VS78_API35'){throw 'Refusing to stop anything other than the owned VS78_API35 AVD'}
& $adb -s emulator-5580 emu kill
if($LASTEXITCODE -ne 0){throw 'Owned emulator stop failed'}
# Deliberately leave the existing shared adb server and personal SDK/devices alone.
