$ErrorActionPreference='Stop'
$t=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../VoxariumStudio/.voxarium_studio/temp/TASK-78/device')))
$destination=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../.tools/android-sdk/system-images/android-35/default')))
New-Item -ItemType Directory -Force $t,$destination | Out-Null
# Official Android repository metadata: sys-img/android/sys-img2-3.xml.
# Pin the exact API35 default x86_64 revision, never install into a personal SDK.
$zip="$t\x86_64-35_r02.zip"
& curl.exe -fL --connect-timeout 15 --max-time 180 --speed-time 30 --speed-limit 100000 -o $zip https://dl.google.com/android/repository/sys-img/android/x86_64-35_r02.zip 2> "$t\image-download.log"
if($LASTEXITCODE -ne 0){throw "Download failed ($LASTEXITCODE); do not keep retrying indefinitely."}
if((Get-Item $zip).Length -ne 782404023 -or (Get-FileHash $zip -Algorithm SHA1).Hash.ToLower() -ne '2d857d170c0d1b827149565da34b3383e5306f7f'){throw 'System-image checksum mismatch'}
python -c "import zipfile; zipfile.ZipFile(r'$zip').extractall(r'$destination')"
if($LASTEXITCODE -ne 0){throw 'Image extraction failed'}
