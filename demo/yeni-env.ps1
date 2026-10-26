# .env.demo dosyasını rastgele ve güvenli değerlerle oluşturur.
# Kullanım (proje kökünden): powershell -ExecutionPolicy Bypass -File demo/yeni-env.ps1
# Var olan .env.demo dosyasının üzerine yazmaz (silmek için önce elle kaldırın).

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root '.env.demo'

if (Test-Path $target) {
    Write-Host ".env.demo zaten var, dokunulmadi: $target"
    Write-Host "Yeniden olusturmak icin once silin. (Veritabani volume'u eski sifreyi tutar; gerekirse 'docker compose down -v' ile sifirlayin.)"
    exit 0
}

$rng = [Security.Cryptography.RandomNumberGenerator]::Create()

function New-Secret([int]$bytes) {
    $b = New-Object byte[] $bytes
    $rng.GetBytes($b)
    return [Convert]::ToBase64String($b)
}

function New-Password([int]$length) {
    # Kolay yazilsin diye yalnizca harf ve rakam (telefonda giris yapilacak)
    $alphabet = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    $b = New-Object byte[] $length
    $rng.GetBytes($b)
    return -join ($b | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

$admin = New-Password 12
$officer = New-Password 12
$developer = New-Password 14

$content = @"
# BilPark yerel demo - demo/yeni-env.ps1 tarafindan uretildi. Git'e girmez.
DB_USERNAME=bilpark
DB_PASSWORD=$(New-Password 24)
JWT_SECRET=$(New-Secret 48)
ADMIN_PASSWORD=$admin
OFFICER_PASSWORD=$officer
DEVELOPER_PASSWORD=$developer
"@

# BOM'suz UTF-8 (docker compose BOM'lu dosyada ilk degiskeni yanlis okuyabilir)
[IO.File]::WriteAllText($target, $content + "`n", (New-Object Text.UTF8Encoding $false))

Write-Host ".env.demo olusturuldu: $target"
Write-Host ""
Write-Host "Demo girisleri (ilk acilista olusturulur, .env.demo icinde de yazili):"
Write-Host "  Yonetici : admin  / $admin"
Write-Host "  Gorevli  : tevfik / $officer"
Write-Host "  Gelistirici (lisans, sistem sagligi): gelistirici / $developer"
Write-Host ""
Write-Host "Simdi: docker compose up --build"
