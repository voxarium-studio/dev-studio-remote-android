param([string]$TempRoot=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../VoxariumStudio/.voxarium_studio/temp/TASK-78/fullstack'))), [string]$Studio=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../VoxariumStudio'))))
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force "$TempRoot/deps","$TempRoot/npm-cache","$TempRoot/system-temp" | Out-Null
$env:TEMP="$TempRoot/system-temp"; $env:TMP=$env:TEMP; $env:npm_config_cache="$TempRoot/npm-cache"
Copy-Item "$Studio/frontend/package.json","$Studio/frontend/package-lock.json" "$TempRoot/deps"
npm ci --ignore-scripts --prefix "$TempRoot/deps"
if ($LASTEXITCODE) { throw 'npm ci failed' }
npm install --prefix "$TempRoot/deps" --no-save --ignore-scripts playwright@1.56.1 ws@8.18.3
if ($LASTEXITCODE) { throw 'playwright/ws installation failed' }
Write-Host 'Run with Studio backend Python (real Remote dependencies + cryptography) or equivalent virtual environment.'
