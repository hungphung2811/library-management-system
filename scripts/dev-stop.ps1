<# Tắt các service và FE (Vite, cổng 3000/3100; 3003 là cổng admin Next cũ, giữ để dọn) chạy bởi dev-start.ps1 (theo cổng). -KeepDocker: giữ container hạ tầng; mặc định cũng giữ, dùng -Docker để tắt luôn. #>
param([switch]$KeepDocker, [switch]$Docker)
foreach ($p in 8081, 8082, 8083, 8084, 8085, 8086, 3000, 3100, 3003) {
  Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | ForEach-Object {
    taskkill /PID $_.OwningProcess /T /F 2>$null | Out-Null; Write-Host "đã tắt cổng $p"
  }
}
if ($Docker -and -not $KeepDocker) { Push-Location (Join-Path $PSScriptRoot '..'); docker compose stop 2>$null | Out-Null; Pop-Location }
