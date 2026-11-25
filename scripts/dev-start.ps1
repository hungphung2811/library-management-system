<#
.SYNOPSIS
  Chạy nhanh cả hệ thống ở máy dev: Docker hạ tầng -> system-service -> 4 service -> lms-api -> FE (tuỳ chọn).
.PARAMETER Build   Build lại jar (mvn -DskipTests package) trước khi chạy; dừng service đang chạy trước vì Windows khóa jar.
.PARAMETER FeProd Chạy FE bản tĩnh: npm run build -w @lms/web rồi vite preview (cổng 3000, nền, log ở .dev\fe.log); đổi code FE phải chạy lại. Mặc định là npm run dev (Vite dev server, cổng 3000, proxy /api sang lms-api 8085 qua VITE_API_TARGET).
.PARAMETER WithSupport Bật thêm support-service (audit, thông báo). Mặc định TẮT vì nặng; thiếu nó thì chuông thông báo và màn Nhật ký hoạt động báo lỗi/trống.
.PARAMETER NoCds   Tắt AppCDS (mặc định: giải nén jar + tạo archive lớp class một lần ở .dev\cds, các lần sau khởi động nhanh hơn).
.PARAMETER Seq     Chạy system-service xong mới chạy các service còn lại (mặc định chỉ làm vậy khi -Build hoặc chưa từng migrate xong).
.PARAMETER Fe      Đường dẫn repo FE (app Vite apps/web) (mặc định ..\library-management-system-fe); dùng -NoFe để bỏ qua FE.
.PARAMETER Only    Chỉ chạy các service nêu tên, ví dụ -Only identity,catalog (system-service luôn chạy trước).
.NOTES
  Cần: JAVA_HOME hoặc java trên PATH; với -Build cần mvn trên PATH hoặc biến MAVEN_CMD.
  Log ở .dev\*.log, tắt bằng scripts\dev-stop.ps1.
#>
param(
  [switch]$Build,
  [switch]$NoFe,
  [switch]$FeProd,
  [switch]$WithSupport,
  [switch]$NoCds,
  [switch]$Seq,
  [string]$Fe = (Join-Path $PSScriptRoot '..\..\library-management-system-fe'),
  [string[]]$Only
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$logs = Join-Path $root '.dev'; New-Item -ItemType Directory -Force $logs | Out-Null
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
if (-not $env:INTERNAL_API_KEY) { $env:INTERNAL_API_KEY = 'dev-internal-key' }
# Dev/demo: tắt cache Redis của wrapper RequestCache (cache-common) để số liệu luôn mới; muốn thử cache thì đặt LMS_CACHE_ENABLED=true trước khi chạy.
if (-not $env:LMS_CACHE_ENABLED) { $env:LMS_CACHE_ENABLED = 'false' }
# Bật C1 + SerialGC cho khởi động nhanh hơn (dev, không dùng cho prod).
$jvm = @('-XX:TieredStopAtLevel=1', '-XX:+UseSerialGC', '-Xshare:auto')
# Biến môi trường cục bộ (bí mật như token SePay): đọc config\application.properties (KEY=VALUE mỗi dòng, '#' là chú thích; Spring Boot cũng tự nạp file này). File đã nằm trong .gitignore,
# KHÔNG đưa token vào file được commit. Biến đã đặt sẵn trong shell được ưu tiên hơn file.
$localEnv = Join-Path $root 'config\application.properties'
if (Test-Path $localEnv) {
  foreach ($line in Get-Content $localEnv -Encoding UTF8) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$' -and $line.TrimStart()[0] -ne '#') {
      if (-not [Environment]::GetEnvironmentVariable($Matches[1])) { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2]) }
    }
  }
}
# SePay Test mode (ADR-0028): bật mặc định khi có token; chưa có token thì giữ tắt để POS không hiện lỗi cấu hình.
if (-not $env:PAYMENTS_SEPAY_ENABLED -and $env:PAYMENTS_SEPAY_TOKEN) { $env:PAYMENTS_SEPAY_ENABLED = 'true' }
$secure = @('--app.security.enabled=true', '--lms.security.permission.enabled=true')

$services = [ordered]@{
  # system/identity phải bật kiểm quyền: tắt thì GET /api/menus/me luôn rỗng (không có JWT) và ADR-0016 (platform admin hành động thay tenant qua X-Tenant-Id) không có tác dụng
  system      = @{ port = 8086; jar = 'services\system-service\target\system-service-0.1.0-SNAPSHOT-exec.jar'; args = $secure }
  identity    = @{ port = 8081; jar = 'services\identity-service\target\identity-service-0.1.0-SNAPSHOT.jar'; args = $secure }
  catalog     = @{ port = 8082; jar = 'services\catalog-service\target\catalog-service-0.1.0-SNAPSHOT.jar'; args = $secure }
  circulation = @{ port = 8083; jar = 'services\circulation-service\target\circulation-service-0.1.0-SNAPSHOT.jar'; args = $secure }
  support     = @{ port = 8084; jar = 'services\support-service\target\support-service-0.1.0-SNAPSHOT.jar'; args = $secure }
  'lms-api'   = @{ port = 8085; jar = 'lms-api\target\lms-api-0.1.0-SNAPSHOT.jar'; args = @() }
}

function Test-Up($port) {
  try { (Invoke-RestMethod "http://localhost:$port/actuator/health" -TimeoutSec 2).status -eq 'UP' } catch { $false }
}
function Wait-Up($names, $timeoutSec = 240) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  foreach ($n in $names) {
    while (-not (Test-Up $services[$n].port)) {
      if ($sw.Elapsed.TotalSeconds -gt $timeoutSec) { throw "$n không lên trong $timeoutSec giây, xem $logs\$n.log" }
      Start-Sleep 2
    }
    Write-Host ("  {0,-12} UP ({1:n0}s)" -f $n, $sw.Elapsed.TotalSeconds)
  }
}
# AppCDS: giải nén jar (Spring Boot tools) rồi chạy một lần "huấn luyện" (thoát ngay sau khi nạp context) để ghi archive lớp class.
function Get-Runnable($n) {
  $s = $services[$n]; $src = Join-Path $root $s.jar
  if ($NoCds) { return @{ jar = $src; opts = @() } }
  $dir = Join-Path $logs "cds\$n"; $jsa = Join-Path $logs "cds\$n.jsa"; $run = Join-Path $dir (Split-Path $src -Leaf)
  if (-not (Test-Path $jsa) -or (Get-Item $jsa).LastWriteTime -lt (Get-Item $src).LastWriteTime) {
    Remove-Item $dir, $jsa -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force (Split-Path $dir) | Out-Null
    & $java '-Djarmode=tools' -jar $src extract --destination $dir | Out-Null
    $train = Start-Process $java -ArgumentList ($jvm + @("-XX:ArchiveClassesAtExit=$jsa", '-Dspring.context.exit=onRefresh', '-jar', $run) + $s.args) `
      -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$logs\$n-cds.log" -RedirectStandardError "$logs\$n-cds.err"
    $script:training += ,@($n, $train)
  }
  return @{ jar = $run; opts = @("-XX:SharedArchiveFile=$jsa") }
}
function Wait-Training {
  foreach ($t in $script:training) { $t[1].WaitForExit(); Write-Host "  CDS $($t[0]) đã tạo archive" }
  $script:training = @()
}
$script:training = @()
function Start-Svc($n) {
  $s = $services[$n]; if (Test-Up $s.port) { Write-Host "  $n đã chạy"; return }
  $r = Get-Runnable $n
  Wait-Training
  Start-Process $java -ArgumentList ($jvm + $r.opts + @('-jar', $r.jar) + $s.args) -WorkingDirectory $root -WindowStyle Hidden `
    -RedirectStandardOutput "$logs\$n.log" -RedirectStandardError "$logs\$n.err"
}

if ($Build) {
  & (Join-Path $PSScriptRoot 'dev-stop.ps1') -KeepDocker
  $mvn = if ($env:MAVEN_CMD) { $env:MAVEN_CMD } else { 'mvn' }
  Write-Host 'Build jar...'; Push-Location $root; & $mvn -q -DskipTests package; if ($LASTEXITCODE) { throw 'Build lỗi' }; Pop-Location
}

Write-Host 'Docker hạ tầng...'; Push-Location $root; $ErrorActionPreference = 'Continue'; docker compose up -d 2>$null | Out-Null; $ErrorActionPreference = 'Stop'; Pop-Location
foreach ($c in 'postgres', 'mongo') {
  $ErrorActionPreference = 'Continue'
  for ($i = 0; $i -lt 60 -and (docker compose -f (Join-Path $root 'docker-compose.yml') ps $c --format '{{.Health}}' 2>$null) -notmatch 'healthy'; $i++) { Start-Sleep 2 }
  $ErrorActionPreference = 'Stop'
}

$want = if ($Only) { @('system') + $Only } elseif ($WithSupport) { @($services.Keys) } else { @($services.Keys | Where-Object { $_ -ne 'support' }) }
if ($want -notcontains 'support') { Write-Host 'support-service: tắt (thêm -WithSupport để bật)' }
$total = [Diagnostics.Stopwatch]::StartNew()
$marker = Join-Path $logs 'migrated'
$jarSys = Join-Path $root $services.system.jar
# Các service khác chỉ validate schema nên chạy song song với system-service được khi DB đã migrate từ trước.
$parallel = -not $Build -and -not $Seq -and (Test-Path $marker) -and ((Get-Item $marker).LastWriteTime -gt (Get-Item $jarSys).LastWriteTime)
$rest = $want | Where-Object { $_ -ne 'system' }
if ($parallel) {
  Write-Host 'Chạy song song mọi service...'
  foreach ($n in $want) { Start-Svc $n }
  Wait-Up $want
} else {
  Write-Host 'system-service (migrate DB)...'; Start-Svc 'system'; Wait-Up @('system')
  New-Item -ItemType File -Force $marker | Out-Null
  Write-Host 'Các service còn lại...'; foreach ($n in $rest) { Start-Svc $n }; Wait-Up $rest
}
Write-Host ("Backend sẵn sàng sau {0:n0}s" -f $total.Elapsed.TotalSeconds)

if (-not $NoFe -and (Test-Path $Fe)) {
  $env:VITE_API_TARGET = 'http://localhost:8085'
  $feDir = (Resolve-Path $Fe).Path
  $feUp = [bool](Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue)
  if ($FeProd) {
    # Cổng 3000 phải trống cho vite preview (--strictPort), nên tắt FE đang chạy trước.
    foreach ($p in 3000, 3100) {
      $ErrorActionPreference = 'Continue'
      Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | ForEach-Object { taskkill /PID $_.OwningProcess /T /F 2>$null | Out-Null }
      $ErrorActionPreference = 'Stop'
    }
    Write-Host 'FE: build production...'
    Push-Location $feDir; $ErrorActionPreference = 'Continue'
    npm run build -w @lms/web 2>&1 | Out-File "$logs\fe-build.log" -Encoding utf8
    $buildFailed = $LASTEXITCODE -ne 0; $ErrorActionPreference = 'Stop'; Pop-Location
    if ($buildFailed) { throw "FE build lỗi, xem $logs\fe-build.log" }
    Start-Process cmd.exe -ArgumentList '/c', 'npm run preview -w @lms/web -- --port 3000 --strictPort' -WorkingDirectory $feDir -WindowStyle Hidden `
      -RedirectStandardOutput "$logs\fe.log" -RedirectStandardError "$logs\fe.err"
    Write-Host 'FE (bản tĩnh, vite preview): http://localhost:3000'
  } else {
    if (-not $feUp) {
      Start-Process cmd.exe -ArgumentList '/c', 'npm run dev' -WorkingDirectory $feDir -WindowStyle Hidden `
        -RedirectStandardOutput "$logs\fe.log" -RedirectStandardError "$logs\fe.err"
    }
    Write-Host 'FE (Vite dev): http://localhost:3000'
  }
}
Write-Host 'Xong. Đăng nhập admin / Admin@12345. Tắt: scripts\dev-stop.ps1'
