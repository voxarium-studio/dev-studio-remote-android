param([string]$TempRoot=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../VoxariumStudio/.voxarium_studio/temp/TASK-78/interop'))), [string]$Studio=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../VoxariumStudio'))))
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force "$TempRoot/deps","$TempRoot/cache","$TempRoot/system-temp" | Out-Null
$env:TEMP="$TempRoot/system-temp"; $env:TMP=$env:TEMP
$env:npm_config_cache="$TempRoot/cache"
$env:PYTHONDONTWRITEBYTECODE='1'
Copy-Item "$Studio/frontend/package.json","$Studio/frontend/package-lock.json" "$TempRoot/deps"
npm ci --ignore-scripts --prefix "$TempRoot/deps"
if ($LASTEXITCODE) { throw 'npm ci failed' }
npm install --prefix "$TempRoot/deps" --no-save --ignore-scripts playwright@1.56.1 ws@8.23.0
if ($LASTEXITCODE) { throw 'playwright installation failed' }
python -m venv "$TempRoot/venv"
& "$TempRoot/venv/Scripts/python.exe" -m pip install --cache-dir "$TempRoot/pip-cache" -r "$PSScriptRoot/python-lock.txt"
if ($LASTEXITCODE) { throw 'Python dependencies failed' }
Write-Host 'Run: node' "$PSScriptRoot/run.mjs"
