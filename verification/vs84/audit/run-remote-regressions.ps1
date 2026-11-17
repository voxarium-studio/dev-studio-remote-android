param(
    [string]$StudioRoot = ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../VoxariumStudio'))),
    [string]$Python = (Join-Path $StudioRoot 'backend/.venv/Scripts/python.exe'),
    [string]$Work = ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../VoxariumStudio/.voxarium_studio/temp/TASK-78/audit/remote-tests')))
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $Work | Out-Null
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONPATH = Join-Path $StudioRoot 'remote'
$env:TEMP = $Work
$env:TMP = $Work
# Supplementary server regressions only. These use TestClient / Y.Text in places;
# they are NOT the Android/Studio browser interoperability acceptance gate.
$ErrorActionPreference = 'Continue' # Windows PowerShell wraps native stderr warnings as ErrorRecords.
& $Python -m pytest (Join-Path $StudioRoot 'remote\tests') -q -p no:cacheprovider -o asyncio_default_fixture_loop_scope=function `
    --basetemp "$Work\pytest" --junitxml "$PSScriptRoot\remote-junit.xml" 2>&1 |
    Tee-Object -FilePath "$Work\pytest.log"
$result = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
Copy-Item -LiteralPath "$Work\pytest.log" -Destination "$PSScriptRoot\remote-tests.log" -Force
exit $result
