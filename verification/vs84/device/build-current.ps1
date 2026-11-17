param([switch]$ResumeCopy,[switch]$CollectOnly)
$ErrorActionPreference='Stop'
$source=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..')))
$t=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../VoxariumStudio/.voxarium_studio/temp/TASK-84/device')))
$copy="$t\current-source"
$evidence="$PSScriptRoot"
New-Item -ItemType Directory -Force $evidence,$t | Out-Null
$oldApkHash=(Get-FileHash "$source\app\build\outputs\apk\debug\app-debug.apk" -Algorithm SHA256).Hash
if(Test-Path -LiteralPath "$source\verification\editor-write-gate.json") { throw 'Closed-gate-only verification: acceptance report is now present; request a separate accepted-artifact repeat rather than omitting it from the source-copy.' }
if(!$CollectOnly){
if($ResumeCopy){
  python "$PSScriptRoot\source-snapshot.py" verify --root $copy --manifest "$t\production-before.json"
}else{
  python "$PSScriptRoot\source-snapshot.py" copy --root $source --manifest "$t\production-before.json" --destination $copy | Tee-Object "$t\copy-provenance.txt"
}
if($LASTEXITCODE -ne 0){throw 'Snapshot/provenance failed'}
if((Get-FileHash "$source\app\build\outputs\apk\debug\app-debug.apk" -Algorithm SHA256).Hash -ne $oldApkHash){throw 'Original APK already differs from historical snapshot'}
if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 21' }
$env:TEMP=$t; $env:TMP=$t; $env:VOXARIUM_DOCUMENTS_TEST_TEMP="$t\current-native-temp"
$env:npm_config_cache="$t\current-npm-cache"
$env:GRADLE_RO_DEP_CACHE="$env:USERPROFILE\.gradle\caches"
$env:GRADLE_USER_HOME="$t\current-gradle"
New-Item -ItemType Directory -Force $env:VOXARIUM_DOCUMENTS_TEST_TEMP | Out-Null
Push-Location "$copy\editor-web"
try {
  & cmd.exe /d /c 'npm.cmd ci --ignore-scripts --no-audit --no-fund --prefer-offline --fetch-retries=1 --fetch-retry-mintimeout=1000 --fetch-timeout=60000 2>&1' | Tee-Object "$t\current-npm-ci.log"
  if($LASTEXITCODE -ne 0){throw 'Pinned npm ci failed'}
} finally {Pop-Location}
# Invoke the installed exact distribution pinned by the unchanged wrapper; dependency cache
# is read-only and all fresh Gradle/cache/build files live in task temp. No excluded gates.
$gradle="$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-all\10utluxaxniiv4wxiphsi49nj\gradle-8.14.3\bin\gradle.bat"
Push-Location $copy
try {
  & cmd.exe /d /c "$gradle --no-daemon --console=plain --project-cache-dir $t\current-project-cache -PeditorBuildTemp=$t/current-web printEditorGateFingerprint assembleDebug testDebugUnitTest lintDebug 2>&1" | Tee-Object "$t\current-build.log"
  if($LASTEXITCODE -ne 0){throw 'Current-source full gate build failed'}
} finally {Pop-Location}
}
python "$PSScriptRoot\source-snapshot.py" verify --root $source --manifest "$t\production-before.json" --allow-nongate-doc-drift | Tee-Object "$t\production-after.txt"
if($LASTEXITCODE -ne 0){throw 'Production source changed'}
python "$PSScriptRoot\source-snapshot.py" verify --root $copy --manifest "$t\production-before.json" | Tee-Object "$t\copy-after.txt"
if($LASTEXITCODE -ne 0){throw 'Copied source changed'}
if((Get-FileHash "$source\app\build\outputs\apk\debug\app-debug.apk" -Algorithm SHA256).Hash -ne $oldApkHash){throw 'Original APK changed'}
$config="$copy\app\build\generated\source\buildConfig\debug\com\voxarium\remote\BuildConfig.java"
if((Get-Content $config -Raw) -notmatch 'EDITOR_WRITES_ENABLED = false'){throw 'Gate is not closed'}
Copy-Item "$copy\app\build\outputs\apk\debug\app-debug.apk" "$evidence\app-debug-verification.apk"
Copy-Item $config "$evidence\BuildConfig.java"
Copy-Item "$t\production-before.fingerprint.txt","$t\production-before.json","$t\copy-provenance.txt","$t\production-after.txt","$t\copy-after.txt","$t\current-npm-ci.log","$t\current-build.log" $evidence
New-Item -ItemType Directory -Force "$evidence\native-xml" | Out-Null
Copy-Item "$copy\app\build\test-results\testDebugUnitTest\TEST-*.xml" "$evidence\native-xml"
Copy-Item "$copy\app\build\reports\lint-results-debug.xml" $evidence

Get-FileHash "$evidence\app-debug-verification.apk" -Algorithm SHA256 | Format-List | Out-String | Set-Content "$evidence\apk.sha256.txt"
Write-Output "CURRENT_APK=$evidence\app-debug-verification.apk"
