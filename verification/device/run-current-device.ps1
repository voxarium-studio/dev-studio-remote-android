param([string]$Apk="$PSScriptRoot\current-build\app-debug-verification.apk")
$ErrorActionPreference='Stop'
$adb=(Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe')
$t=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../VoxariumStudio/.voxarium_studio/temp/TASK-78/device')))
$evidence="$PSScriptRoot\current-build"
function Adb([string[]]$a){ & $adb -s emulator-5580 @a; if($LASTEXITCODE -ne 0){throw "adb failed: $a"} }
try {
  $devices=& $adb devices
  if(!($devices -match '^emulator-5580\s')) { & "$PSScriptRoot\start-emulator.ps1" }
  $boot=$false
  for($i=0;$i -lt 45;$i++) {
    if((& $adb -s emulator-5580 shell getprop sys.boot_completed) -match '1'){$boot=$true;break}
    Start-Sleep 2
  }
  if(!$boot){throw 'Owned AVD did not boot within 90s'}
  $name=& $adb -s emulator-5580 emu avd name
  if($name[0] -ne 'VS78_API35'){throw 'Not the owned AVD'}
  Adb @('shell','input','keyevent','KEYCODE_WAKEUP')
  Adb @('shell','wm','dismiss-keyguard')
  # Ensure the target exists before per-app locale commands on a newly provisioned AVD.
  Adb @('install','-r',$Apk)
  foreach($configuration in 'en','ru-dark-large') {
    if($configuration -eq 'en') {
      Adb @('shell','cmd','locale','set-app-locales','com.voxarium.remote','--locales','en-US')
      Adb @('shell','cmd','uimode','night','no')
      Adb @('shell','settings','put','system','font_scale','1.0')
      Adb @('shell','wm','size','reset'); Adb @('shell','wm','density','reset')
    } else {
      Adb @('shell','cmd','locale','set-app-locales','com.voxarium.remote','--locales','ru-RU')
      Adb @('shell','cmd','uimode','night','yes')
      Adb @('shell','settings','put','system','font_scale','2.0')
      Adb @('shell','wm','size','840x1260'); Adb @('shell','wm','density','420')
    }
    $dest="$evidence\device-$configuration"
    New-Item -ItemType Directory -Force $dest | Out-Null
    Adb @('shell','cmd','locale','get-app-locales','com.voxarium.remote') | Set-Content "$dest\configuration.txt"
    Adb @('shell','settings','get','system','font_scale') | Add-Content "$dest\configuration.txt"
    Adb @('shell','wm','size') | Add-Content "$dest\configuration.txt"
    Adb @('shell','dumpsys','webviewupdate') | Add-Content "$dest\configuration.txt"
    try { & "$PSScriptRoot\run-probe.ps1" -Apk $Apk -LocalWritableIme }
    finally {
      Copy-Item "$t\instrumentation.txt","$t\instrumentation-target.txt" $dest -ErrorAction SilentlyContinue
      foreach($image in 'password-ime','password-rotation','readonly-webview') {
        Copy-Item "$t\$image.png" $dest -ErrorAction SilentlyContinue
      }
    }
  }
} finally {
  # This refuses a different AVD and never shuts down the shared adb server.
  & "$PSScriptRoot\stop-emulator.ps1"
  & $adb devices -l | Set-Content "$evidence\after-device-shutdown.txt"
}
