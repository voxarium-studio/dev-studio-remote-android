param(
    [string]$Source = (Split-Path $PSScriptRoot),
    [string]$Work = ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../VoxariumStudio/.voxarium_studio/temp/TASK-79/build-1'))),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleHome = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-all\10utluxaxniiv4wxiphsi49nj\gradle-8.14.3"
)
$ErrorActionPreference = 'Stop'
# No installation, server/AVD control, init script, report creation or excluded gates.
# The existing standard debug identity is used; do not generate release credentials.
if (!(Test-Path -LiteralPath "$env:USERPROFILE\.android\debug.keystore")) { throw 'Existing debug keystore required; do not generate a release identity' }
if (!(Test-Path -LiteralPath "$JavaHome\bin\java.exe")) { throw 'JDK 21 missing' }
if (!(Test-Path -LiteralPath "$GradleHome\bin\gradle.bat")) { throw 'Pinned Gradle 8.14.3-all missing' }
if ((Get-Content -LiteralPath "$Source\gradle\wrapper\gradle-wrapper.properties" -Raw) -notmatch 'gradle-8\.14\.3-all.zip') { throw 'Wrapper version changed' }
$allowed = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../VoxariumStudio/.voxarium_studio/temp/TASK-79')).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
$Work = [IO.Path]::GetFullPath($Work)
if (!$Work.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Work must be inside TASK-79' }
New-Item -ItemType Directory -Force $Work | Out-Null
& python -B "$PSScriptRoot\snapshot.py" copy --source $Source --work $Work | Tee-Object "$Work\snapshot-before.log"
if ($LASTEXITCODE -ne 0) { throw 'Acceptance/source snapshot failed' }
$env:JAVA_HOME = $JavaHome
$env:TEMP = $Work; $env:TMP = $Work
$env:VOXARIUM_DOCUMENTS_TEST_TEMP = "$Work\native-temp"
$env:npm_config_cache = "$Work\npm-cache"
$env:GRADLE_RO_DEP_CACHE = "$env:USERPROFILE\.gradle\caches"
$env:GRADLE_USER_HOME = "$Work\gradle-home"
New-Item -ItemType Directory -Force $env:VOXARIUM_DOCUMENTS_TEST_TEMP | Out-Null
Push-Location "$Work\source\editor-web"
try {
    & cmd.exe /d /c 'npm.cmd ci --ignore-scripts --no-audit --no-fund --prefer-offline --fetch-retries=1 --fetch-retry-mintimeout=1000 --fetch-timeout=60000 2>&1' | Tee-Object "$Work\npm-ci.log"
    if ($LASTEXITCODE -ne 0) { throw 'Pinned npm ci failed' }
} finally { Pop-Location }
Push-Location "$Work\source"
try {
    & cmd.exe /d /c "`"$JavaHome\bin\java.exe`" -version 2>&1" | Set-Content -Encoding UTF8 "$Work\java-version.txt"
    & cmd.exe /d /c 'node --version && npm.cmd --version' | Set-Content -Encoding UTF8 "$Work\node-npm-version.txt"
    & cmd.exe /d /c "`"$GradleHome\bin\gradle.bat`" --version 2>&1" | Set-Content -Encoding UTF8 "$Work\gradle-version.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Gradle version probe failed' }
    & cmd.exe /d /c "`"$GradleHome\bin\gradle.bat`" --no-daemon --console=plain --project-cache-dir $Work\project-cache -PeditorBuildTemp=$($Work.Replace('\','/'))/web printEditorGateFingerprint assembleDebug testDebugUnitTest lintDebug 2>&1" | Tee-Object "$Work\build.log"
    if ($LASTEXITCODE -ne 0) { throw 'Full build/test/lint failed' }
} finally { Pop-Location }
& python -B "$PSScriptRoot\snapshot.py" verify --source $Source --work $Work | Tee-Object "$Work\snapshot-after.log"
if ($LASTEXITCODE -ne 0) { throw 'Source/report/evidence drift' }
$config = "$Work\source\app\build\generated\source\buildConfig\debug\com\voxarium\remote\BuildConfig.java"
if ((Get-Content -LiteralPath $config -Raw) -notmatch 'EDITOR_WRITES_ENABLED = true;') { throw 'BLOCKER: APK write gate closed' }
Write-Output "APK=$Work\source\app\build\outputs\apk\debug\app-debug.apk"
Get-FileHash -LiteralPath "$Work\source\app\build\outputs\apk\debug\app-debug.apk" -Algorithm SHA256 | Format-List
