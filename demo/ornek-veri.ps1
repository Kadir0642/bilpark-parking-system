# Yerel demo veritabanina ornek veri yukler (demo/ornek-veri.sql).
# Kullanim (proje kokunden, 'docker compose up' calisirken):
#   powershell -ExecutionPolicy Bypass -File demo/ornek-veri.ps1
# Yalnizca compose icindeki 'db' konteynerine baglanir; SQL dosyasi da bilpark_demo disinda calismayi reddeder.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    docker compose cp demo/ornek-veri.sql db:/tmp/ornek-veri.sql
    if ($LASTEXITCODE -ne 0) { throw "Dosya konteynere kopyalanamadi. 'docker compose up' calisiyor mu?" }
    docker compose exec -T db sh -c 'psql -U "$DB_USERNAME" -d bilpark_demo -v ON_ERROR_STOP=1 -f /tmp/ornek-veri.sql'
    if ($LASTEXITCODE -ne 0) { throw "Ornek veri yuklenemedi (yukaridaki hataya bakin)." }
    Write-Host "Ornek veri yuklendi. Paneli yenileyin: http://localhost:5500/admin-panel.html"
} finally {
    Pop-Location
}
