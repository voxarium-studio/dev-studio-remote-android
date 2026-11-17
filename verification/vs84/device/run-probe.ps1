param(
  [Parameter(Mandatory=$true)][string]$Apk,
  [string]$Serial='emulator-5580',
  [string]$Sdk=$env:ANDROID_HOME,
  [switch]$LocalWritableIme
)
$ErrorActionPreference='Stop'
$t=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../VoxariumStudio/.voxarium_studio/temp/TASK-84/device')))
$j=(Join-Path $env:JAVA_HOME 'bin')
$b=Join-Path $Sdk 'build-tools\36.0.0'
$android=Join-Path $Sdk 'platforms\android-36\android.jar'
$adb=Join-Path $Sdk 'platform-tools\adb.exe'
if($Serial -ne 'emulator-5580') { throw 'This harness is restricted to the owned VS78 emulator-5580, never a personal device.' }
$avd=& $adb -s $Serial emu avd name
if($avd[0] -ne 'VS78_API35') { throw 'Refusing a device other than the owned VS78_API35 AVD.' }
if(!(Test-Path -LiteralPath $Apk -PathType Leaf)){throw 'Explicit target APK does not exist'}
New-Item -ItemType Directory -Force "$t\classes","$t\dex" | Out-Null
function Run([string]$exe,[string[]]$a) { & $exe @a; if($LASTEXITCODE -ne 0){ throw "$exe exited $LASTEXITCODE" } }
Run "$j\javac.exe" @('-encoding','UTF-8','-source','17','-target','17','-classpath',$android,'-d',"$t\classes","$PSScriptRoot\DeviceProbe.java")
Run "$j\jar.exe" @('cf',"$t\probe.jar",'-C',"$t\classes",'.')
$env:JAVA_HOME=Split-Path $j; $env:TEMP=$t; $env:TMP=$t
Run "$b\d8.bat" @('--lib',$android,'--min-api','29','--output',"$t\dex","$t\probe.jar")
Run "$b\aapt2.exe" @('link','-I',$android,'--manifest',"$PSScriptRoot\AndroidManifest.xml",'-o',"$t\unsigned.apk")
Run "$j\jar.exe" @('uf',"$t\unsigned.apk",'-C',"$t\dex",'classes.dex')
Run "$b\zipalign.exe" @('-f','4',"$t\unsigned.apk","$t\aligned.apk")
Run "$b\apksigner.bat" @('sign','--ks',"$env:USERPROFILE\.android\debug.keystore",'--ks-pass','pass:android','--out',"$t\devicecheck.apk","$t\aligned.apk")
Run $adb @('-s',$Serial,'install','-r',(Resolve-Path -LiteralPath $Apk).Path)
Run $adb @('-s',$Serial,'install','-r',"$t\devicecheck.apk")
Run $adb @('-s',$Serial,'shell','am','force-stop','com.voxarium.remote')
$local=if($LocalWritableIme){'true'}else{'false'}
foreach($name in @('password-ime','password-rotation','readonly-webview','webview-ime')) {
  if(Test-Path -LiteralPath "$t\$name.png"){Remove-Item -LiteralPath "$t\$name.png"}
}
@("APK=$((Resolve-Path -LiteralPath $Apk).Path)","APK_SHA256=$((Get-FileHash $Apk -Algorithm SHA256).Hash.ToLower())","localWritableIme=$local") | Set-Content "$t\instrumentation-target.txt"
# Bound the host-side wait as well as the instrumentation's individual event waits.
# Only this explicitly launched adb client may be stopped; never kill-server.
$runner=New-Object System.Diagnostics.Process
$runner.StartInfo.FileName=$adb
$runner.StartInfo.Arguments="-s $Serial shell am instrument -w -r -e localWritableIme $local com.voxarium.remote.devicecheck/com.voxarium.remote.devicecheck.DeviceProbe"
$runner.StartInfo.UseShellExecute=$false
$runner.StartInfo.CreateNoWindow=$true
$runner.StartInfo.RedirectStandardOutput=$true
$runner.StartInfo.RedirectStandardError=$true
$null=$runner.Start()
$stdout=$runner.StandardOutput.ReadToEndAsync();$stderr=$runner.StandardError.ReadToEndAsync()
if(!$runner.WaitForExit(240000)) { $runner.Kill(); throw 'Bounded instrumentation exceeded 240 seconds' }
$runner.WaitForExit();$runnerCode=$runner.ExitCode
$stdout.Result | Set-Content "$t\instrumentation.stdout.txt"
$stderr.Result | Set-Content "$t\instrumentation.stderr.txt"
Get-Content "$t\instrumentation.stdout.txt" | Tee-Object "$t\instrumentation.txt"
Get-Content "$t\instrumentation.stderr.txt" | Add-Content "$t\instrumentation.txt"
$runner.Dispose()
if($runnerCode -ne 0){ throw "instrumentation transport failed: $runnerCode" }
$report=Get-Content "$t\instrumentation.txt" -Raw
if($report -notmatch 'failures=0' -or $report -notmatch 'INSTRUMENTATION_CODE: -1') { throw 'device assertions failed; see instrumentation.txt' }
foreach($name in @('password-ime','password-rotation','readonly-webview','webview-ime')) {
  Run $adb @('-s',$Serial,'pull',"/sdcard/Android/data/com.voxarium.remote/files/vs84-$name.png","$t\$name.png")
}
