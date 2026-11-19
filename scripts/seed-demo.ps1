<#
.SYNOPSIS
  Nạp dữ liệu mẫu cho tenant demo (ADR-0018): chạy lần lượt identity -> catalog -> circulation -> sales -> support, mỗi service một tiến trình java -jar
  riêng (profile demo-seed, không web) rồi thoát. Chỉ dùng ở máy dev/demo; mỗi service tự từ chối khi bật profile prod.
.PARAMETER Password  Mật khẩu chung của các tài khoản demo (mặc định lấy từ biến DEMO_SEED_PASSWORD, nếu không có thì Demo@12345).
.PARAMETER Only      Chỉ chạy các bước nêu tên, ví dụ -Only catalog,circulation.
.NOTES
  Cần: docker compose up -d (postgres), system-service đã migrate xong (dev-start.ps1 hoặc mvn spring-boot:run), jar của từng service đã build
  (dev-start.ps1 -Build). Idempotent: chạy lại không nhân đôi. Muốn làm mới hoàn toàn: docker compose down -v rồi chạy lại từ đầu.
  Log ở .dev\seed-<tên>.log. Mã thoát của mỗi bước: 0 thành công, 1 lỗi bước seed, 2 guard từ chối.
  Đăng nhập demo (qua lms-api): demo.admin, demo.thuthu, demo.cuahang, demo.docgia01..24 với mật khẩu trên.
#>
param(
  [string]$Password = $(if ($env:DEMO_SEED_PASSWORD) { $env:DEMO_SEED_PASSWORD } else { 'Demo@12345' }),
  [string[]]$Only
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$logs = Join-Path $root '.dev'; New-Item -ItemType Directory -Force $logs | Out-Null
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
# Id tenant tất định = DemoIds.tenant() trong shared-libraries/demo-seed (identity tạo tenant này, các service khác dùng lại).
$tenantId = '42e63b21-7c74-3934-bbe1-ba5f2ae14dc6'

if ($env:SPRING_PROFILES_ACTIVE -match 'prod') { throw 'Từ chối: SPRING_PROFILES_ACTIVE có prod. Seed chỉ dành cho dev/demo.' }

# Thứ tự bắt buộc: identity tạo tenant trước; circulation cần catalog khớp id bản sao (cùng DemoIds, không phụ thuộc DB nhau).
$steps = @(
  @{ name = 'identity';    jar = 'services\identity-service\target\identity-service-0.1.0-SNAPSHOT.jar';       tenant = $false },
  @{ name = 'catalog';     jar = 'services\catalog-service\target\catalog-service-0.1.0-SNAPSHOT.jar';         tenant = $true },
  @{ name = 'circulation'; jar = 'services\circulation-service\target\circulation-service-0.1.0-SNAPSHOT.jar'; tenant = $true },
  @{ name = 'sales';       jar = 'services\sales-service\target\sales-service-0.1.0-SNAPSHOT.jar';             tenant = $true },
  @{ name = 'support';     jar = 'services\support-service\target\support-service-0.1.0-SNAPSHOT.jar';         tenant = $true }
)
if ($Only) { $steps = @($steps | Where-Object { $Only -contains $_.name }) }

# system-service phải đã migrate xong (các service khác chỉ validate schema)
try { $h = (Invoke-RestMethod 'http://localhost:8086/actuator/health' -TimeoutSec 3).status } catch { $h = 'DOWN' }
if ($h -ne 'UP') { throw 'system-service (8086) chưa UP: chạy scripts\dev-start.ps1 trước để migrate DB.' }

$results = @()
foreach ($s in $steps) {
  $jar = Join-Path $root $s.jar
  if (-not (Test-Path $jar)) { throw "Thiếu $($s.jar): chạy scripts\dev-start.ps1 -Build trước." }
  $args = @('-XX:TieredStopAtLevel=1', '-XX:+UseSerialGC', '-jar', $jar, '--spring.profiles.active=dev,demo-seed',
            '--lms.demo-seed.enabled=true', "--lms.demo-seed.password=$Password")
  if ($s.tenant) { $args += "--lms.demo-seed.tenant-id=$tenantId" }
  Write-Host ("Seed {0,-12} ..." -f $s.name) -NoNewline
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $p = Start-Process $java -ArgumentList $args -WorkingDirectory $root -WindowStyle Hidden -PassThru -Wait `
    -RedirectStandardOutput "$logs\seed-$($s.name).log" -RedirectStandardError "$logs\seed-$($s.name).err"
  $code = $p.ExitCode
  Write-Host (" mã thoát {0} ({1:n0}s)" -f $code, $sw.Elapsed.TotalSeconds)
  $results += @{ name = $s.name; code = $code }
  if ($code -ne 0) { Write-Host "  Xem $logs\seed-$($s.name).log"; break }
}

$failed = @($results | Where-Object { $_.code -ne 0 })
if ($failed.Count -gt 0) { throw "Seed lỗi ở bước $($failed[0].name) (mã $($failed[0].code))." }
Write-Host "Xong. Tenant demo: $tenantId"
Write-Host "Đăng nhập: demo.admin / demo.thuthu / demo.cuahang / demo.docgia01..24, mật khẩu như tham số -Password."
Write-Host 'Token cũ không có quyền mới: đăng nhập lại. Làm mới hoàn toàn: docker compose down -v rồi chạy lại.'
