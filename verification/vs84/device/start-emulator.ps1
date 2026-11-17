$ErrorActionPreference='Stop'
$root=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../.tools')))
$t=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../VoxariumStudio/.voxarium_studio/temp/TASK-84/device')))
$sdk=$env:ANDROID_HOME
if((& "$sdk\platform-tools\adb.exe" devices) -match '^emulator-5580\s') { throw 'Serial emulator-5580 is already present; inspect it, do not replace another emulator.' }
if(Get-NetTCPConnection -LocalPort 5580,5581 -ErrorAction SilentlyContinue | Where-Object {$_.State -eq 'Listen' -and $_.OwningProcess -gt 0}) { throw 'Owned emulator ports have a live listener; do not terminate their owner.' }
$env:ANDROID_AVD_HOME="$root\avd"
$env:ANDROID_USER_HOME="$root\android-home"
$env:ANDROID_EMULATOR_HOME="$root\emulator-home"
$env:ANDROID_SDK_ROOT=$sdk
$env:TEMP=$t; $env:TMP=$t
New-Item -ItemType Directory -Force "$root\avd\VS78_API35.avd",$env:ANDROID_USER_HOME,$env:ANDROID_EMULATOR_HOME,$t | Out-Null
@"
avd.ini.encoding=UTF-8
path=$root\avd\VS78_API35.avd
target=android-35
"@ | Set-Content -Encoding ASCII "$root\avd\VS78_API35.ini"
@"
AvdId=VS78_API35
PlayStore.enabled=false
abi.type=x86_64
avd.ini.displayname=VS78 disposable API35
avd.ini.encoding=UTF-8
disk.dataPartition.size=2G
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=2048
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader
hw.lcd.width=1080
hw.lcd.height=1920
hw.lcd.density=420
hw.keyboard=yes
hw.mainKeys=no
image.sysdir.1=$root\android-sdk\system-images\android-35\default\x86_64\
showDeviceFrame=no
tag.display=Default Android System Image
tag.id=default
fastboot.forceColdBoot=yes
"@ | Set-Content -Encoding ASCII "$root\avd\VS78_API35.avd\config.ini"
$p=Start-Process -WindowStyle Hidden -FilePath "$sdk\emulator\emulator.exe" -ArgumentList @('-avd','VS78_API35','-no-window','-no-audio','-no-snapshot','-gpu','swiftshader','-port','5580') -RedirectStandardOutput "$t\emulator.stdout.log" -RedirectStandardError "$t\emulator.stderr.log" -PassThru
$p.Id | Set-Content "$t\emulator.pid"
Write-Output "Started owned emulator PID=$($p.Id), serial=emulator-5580"
