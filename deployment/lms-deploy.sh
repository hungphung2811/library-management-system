#!/usr/bin/env bash
# =====================================================================================================
# LMS - TRIỂN KHAI TỰ ĐỘNG MỘT FILE (Ubuntu 22.04/24.04)
# =====================================================================================================
# File này chứa TẤT CẢ: docker-compose (nhúng ở cuối file), cấu hình nginx, SQL khởi tạo DB, và logic
# tự sinh env. Chỉ cần chạy một lệnh, không cần clone mã nguồn, không cần file nào khác:
#
#     bash lms-deploy.sh                # cài đặt/triển khai (chạy lại an toàn, không ghi đè .env cũ)
#
# Làm lần lượt: kiểm tra Ubuntu -> cài Docker nếu thiếu -> (thêm swap nếu RAM thấp) -> ghi docker-compose.yml
# -> SINH .env (mật khẩu admin, các bí mật, cặp khóa JWT RSA) nếu chưa có -> đăng nhập GHCR (nếu có token)
# -> pull image -> up -d -> chờ hệ thống sẵn sàng -> in địa chỉ web và mật khẩu admin.
#
# Lệnh khác:   bash lms-deploy.sh status | logs [service] | down | upgrade [tag] | backup | info
#
# BIẾN MÔI TRƯỜNG TÙY CHỌN (đặt trước lệnh, ví dụ: GHCR_USER=abc GHCR_TOKEN=ghp_xxx bash lms-deploy.sh):
#   GHCR_USER, GHCR_TOKEN   đăng nhập ghcr.io (PAT classic quyền read:packages) nếu package là riêng tư
#   LMS_TAG                 tag image (mặc định latest; production nên ghim vX.Y.Z hoặc sha)
#   LMS_REGISTRY            mặc định ghcr.io/hungphung2811
#   WEB_PORT                cổng web (mặc định 80; bận thì tự dùng 8081)
#   LMS_DIR                 thư mục cài (mặc định /opt/lms)
#   PUBLIC_URL              địa chỉ công khai có HTTPS nếu có, ví dụ https://lms.example.com (đặt CORS cho /public/**)
#   MAIL_HOST MAIL_PORT MAIL_USERNAME MAIL_PASSWORD   SMTP thật để gửi OTP (mặc định Mailpit nội bộ, thư không ra ngoài)
#   LMS_SKIP_SWAP=1         không tự thêm swap
# Sau lần cài đầu, mọi giá trị nằm trong $LMS_DIR/.env: sửa file đó rồi chạy lại `bash lms-deploy.sh` để áp dụng.
# =====================================================================================================
set -euo pipefail

LMS_DIR="${LMS_DIR:-/opt/lms}"
LMS_REGISTRY="${LMS_REGISTRY:-ghcr.io/hungphung2811}"
SELF="$(readlink -f "$0")"
log()  { printf '\033[1;34m[lms]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[lms] CẢNH BÁO:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[lms] LỖI:\033[0m %s\n' "$*" >&2; exit 1; }

SUDO=""; [ "$(id -u)" -eq 0 ] || SUDO="sudo"

# Chạy docker; nếu user chưa có quyền (vừa cài, chưa vào nhóm docker) thì dùng sudo.
D() { if docker info >/dev/null 2>&1; then docker "$@"; else $SUDO docker "$@"; fi; }
C() { (cd "$LMS_DIR" && D compose -f docker-compose.yml "$@"); }

check_os() {
  [ -r /etc/os-release ] || die "Không xác định được hệ điều hành"
  . /etc/os-release
  [ "${ID:-}" = "ubuntu" ] || warn "Script viết cho Ubuntu, máy này là ${PRETTY_NAME:-?}; vẫn thử tiếp"
  command -v curl >/dev/null || { $SUDO apt-get update -y && $SUDO apt-get install -y curl; }
  command -v openssl >/dev/null || { $SUDO apt-get update -y && $SUDO apt-get install -y openssl; }
}

install_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    log "Cài Docker Engine (get.docker.com)"
    curl -fsSL https://get.docker.com | $SUDO sh
    $SUDO systemctl enable --now docker >/dev/null 2>&1 || true
  fi
  [ "$(id -u)" -eq 0 ] || $SUDO usermod -aG docker "$USER" 2>/dev/null || true
  local v; v="$(D compose version --short 2>/dev/null | sed 's/^v//')" || true
  [ -n "$v" ] || die "Không có plugin 'docker compose' v2. Cài docker-compose-plugin rồi chạy lại"
  [ "$(printf '%s\n2.23.0\n' "$v" | sort -V | head -n1)" = "2.23.0" ] || die "Compose $v quá cũ, cần >= 2.23 (dùng configs.content)"
  log "Docker OK, compose $v"
}

ensure_swap() {
  [ "${LMS_SKIP_SWAP:-0}" = "1" ] && return 0
  local mem swap; mem=$(awk '/MemTotal/{print int($2/1024)}' /proc/meminfo); swap=$(awk '/SwapTotal/{print int($2/1024)}' /proc/meminfo)
  if [ $((mem + swap)) -lt 7000 ] && [ ! -e /swapfile ]; then
    log "RAM ${mem}MB + swap ${swap}MB < 7GB: thêm swap 4GB"
    $SUDO fallocate -l 4G /swapfile && $SUDO chmod 600 /swapfile && $SUDO mkswap /swapfile >/dev/null && $SUDO swapon /swapfile \
      && { grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | $SUDO tee -a /etc/fstab >/dev/null; } \
      || warn "Không tạo được swap, bỏ qua"
  fi
  [ "$mem" -ge 5500 ] || warn "RAM chỉ ${mem}MB, hệ thống cần khoảng 6GB trống; có thể bị kill do thiếu bộ nhớ"
}

port_busy() { ss -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "[:.]$1\$"; }

write_compose() {
  mkdir -p "$LMS_DIR" 2>/dev/null || { $SUDO mkdir -p "$LMS_DIR" && $SUDO chown "$(id -u):$(id -g)" "$LMS_DIR"; }
  [ -w "$LMS_DIR" ] || $SUDO chown "$(id -u):$(id -g)" "$LMS_DIR"
  # Phần compose nằm sau dòng marker ở cuối file này
  sed -n '/^#__COMPOSE_BELOW__$/,$p' "$SELF" | tail -n +2 > "$LMS_DIR/docker-compose.yml"
  [ -s "$LMS_DIR/docker-compose.yml" ] || die "Không trích được docker-compose.yml từ $SELF (file bị cắt cụt?)"
  log "Đã ghi $LMS_DIR/docker-compose.yml"
}

gen() { openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-40; }

init_env() {
  local f="$LMS_DIR/.env"
  if [ -f "$f" ]; then log "Giữ nguyên $f (đã có). Muốn sinh lại thì xóa file rồi chạy lại (CẢNH BÁO: đổi khóa JWT/mã hóa)"; return 0; fi
  log "Sinh $f (bí mật ngẫu nhiên + khóa JWT RSA 2048)"
  local tmp; tmp="$(mktemp)"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp" 2>/dev/null
  local priv pub
  priv=$(awk 'NF{printf "%s\\n",$0}' "$tmp")
  pub=$(openssl pkey -in "$tmp" -pubout | awk 'NF{printf "%s\\n",$0}')
  rm -f "$tmp"
  local wp="${WEB_PORT:-80}"
  if port_busy "$wp"; then warn "Cổng $wp đang bận, dùng 8081"; wp=8081; fi
  umask 077
  cat > "$f" <<EOF
# Sinh tự động bởi lms-deploy.sh lúc $(date -Is). SAO LƯU file này (mất khóa JWT: mọi người bị đăng xuất;
# mất TENANT_DATA_ENCRYPTION_KEY: không giải mã được dữ liệu tenant đã xuất). KHÔNG commit.
WEB_PORT=$wp
LMS_REGISTRY=$LMS_REGISTRY
LMS_TAG=${LMS_TAG:-latest}

AUTH_BOOTSTRAP_ADMIN_PASSWORD=$(gen)Aa1!
INTERNAL_API_KEY=$(gen)
STOREFRONT_CLIENT_SECRET=$(gen)
EMAIL_VERIFICATION_OTP_SECRET=$(gen)
TENANT_DATA_ENCRYPTION_KEY=$(openssl rand -base64 32)
POSTGRES_PASSWORD=postgres

AUTH_JWT_ALLOW_EPHEMERAL_KEY=false
AUTH_JWT_PRIVATE_KEY="$priv"
AUTH_JWT_PUBLIC_KEY="$pub"

PUBLIC_CORS_MODE=ENFORCE
PUBLIC_CORS_PLATFORM_ORIGINS=${PUBLIC_URL:-}
MAIL_HOST=${MAIL_HOST:-mailpit}
MAIL_PORT=${MAIL_PORT:-1025}
MAIL_USERNAME=${MAIL_USERNAME:-}
MAIL_PASSWORD=${MAIL_PASSWORD:-}

# SePay (thanh toán QR) - điền rồi chạy lại script nếu dùng
PAYMENTS_SEPAY_ENABLED=false
PAYMENTS_SEPAY_TOKEN=
PAYMENTS_SEPAY_BANK_BIN=
PAYMENTS_SEPAY_ACCOUNT_NO=
PAYMENTS_SEPAY_BANK_ACCOUNT_ID=
EOF
  chmod 600 "$f"
}

env_get() { grep -E "^$1=" "$LMS_DIR/.env" 2>/dev/null | head -n1 | cut -d= -f2- || true; }

ghcr_login() {
  if [ -n "${GHCR_TOKEN:-}" ] && [ -n "${GHCR_USER:-}" ]; then
    log "Đăng nhập ghcr.io bằng $GHCR_USER"
    printf '%s' "$GHCR_TOKEN" | D login ghcr.io -u "$GHCR_USER" --password-stdin >/dev/null || die "Đăng nhập GHCR thất bại (kiểm tra PAT quyền read:packages)"
  fi
}

wait_ready() {
  local wp; wp="$(env_get WEB_PORT)"; wp="${wp:-80}"
  log "Chờ hệ thống sẵn sàng (lần đầu migrate DB mất 1-2 phút, tối đa 10 phút)..."
  local i
  for i in $(seq 1 120); do
    if curl -fsS -o /dev/null "http://localhost:${wp}/public/site/pages" 2>/dev/null && curl -fsS -o /dev/null "http://localhost:${wp}/" 2>/dev/null; then
      log "Web và cổng công khai đã phản hồi"; return 0
    fi
    sleep 5
  done
  warn "Hết thời gian chờ. Xem: bash $SELF status ; bash $SELF logs system-service"
  return 1
}

summary() {
  local wp ip; wp="$(env_get WEB_PORT)"; wp="${wp:-80}"
  ip="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
  cat <<EOF

================ HOÀN TẤT ================
Web:            http://${ip:-<máy chủ>}$([ "$wp" = 80 ] || echo ":$wp")/
Đăng nhập:      admin / $(env_get AUTH_BOOTSTRAP_ADMIN_PASSWORD)   (đổi mật khẩu sau lần đầu đăng nhập)
Thư mục:        $LMS_DIR   (docker-compose.yml, .env - NHỚ SAO LƯU .env)
Tag image:      $(env_get LMS_TAG)   (production nên ghim vX.Y.Z)
Trạng thái:     bash $SELF status
Log:            bash $SELF logs <service>
Chưa làm:       HTTPS (đặt reverse proxy phía trước), SMTP thật (sửa MAIL_* trong .env), đổi mật khẩu các vai trò DB
==========================================
EOF
}

do_up() {
  check_os; install_docker; ensure_swap; write_compose; init_env; ghcr_login
  [ -z "${LMS_TAG:-}" ] || sed -i "s|^LMS_TAG=.*|LMS_TAG=${LMS_TAG}|" "$LMS_DIR/.env"
  if command -v ufw >/dev/null 2>&1 && $SUDO ufw status 2>/dev/null | grep -q "Status: active"; then
    $SUDO ufw allow "$(env_get WEB_PORT)/tcp" >/dev/null || true
  fi
  log "Pull image ($(env_get LMS_REGISTRY), tag $(env_get LMS_TAG))"
  C pull || die "Pull thất bại: kiểm tra GHCR_USER/GHCR_TOKEN, quyền package, hoặc tag chưa tồn tại"
  log "Khởi động"
  C up -d
  wait_ready || true
  summary
}

case "${1:-up}" in
  up|install) do_up ;;
  status)  C ps ;;
  logs)    C logs -f --tail=100 "${@:2}" ;;
  down)    C down ;;
  upgrade) [ -z "${2:-}" ] || sed -i "s|^LMS_TAG=.*|LMS_TAG=$2|" "$LMS_DIR/.env"; write_compose; ghcr_login; C pull; C up -d; wait_ready || true ;;
  backup)  f="$LMS_DIR/backup-$(date +%F-%H%M).sql.gz"; C exec -T postgres pg_dumpall -U postgres | gzip > "$f"; log "Đã sao lưu $f (nhớ sao lưu cả .env)" ;;
  info)    summary ;;
  *) echo "Dùng: bash $0 [up|status|logs [service]|down|upgrade [tag]|backup|info]"; exit 1 ;;
esac
exit 0

#__COMPOSE_BELOW__
# (Nhúng trong lms-deploy.sh; script tự ghi file này ra $LMS_DIR/docker-compose.yml và sinh .env)
# =====================================================================================================
# LMS - TRIỂN KHAI ALL-IN-ONE (một file duy nhất, đã gồm cấu hình env và các file cấu hình nhúng sẵn)
# =====================================================================================================
# Chạy:   docker compose -f docker-compose.all-in-one.yml up -d
# Dừng:   docker compose -f docker-compose.all-in-one.yml down        (thêm -v để xóa sạch dữ liệu)
# Yêu cầu: Docker Compose v2.23+ (dùng `configs.content`), máy chủ ~6 GB RAM trống.
# Image:  kéo từ GHCR (không build). Gói riêng tư thì đăng nhập trước:
#           echo <PAT read:packages> | docker login ghcr.io -u <github-user> --password-stdin
# Web:    http://<máy chủ>:80 (đổi bằng WEB_PORT). Quản trị: admin / giá trị AUTH_BOOTSTRAP_ADMIN_PASSWORD.
# Tùy chọn: --profile integration  (integration-api, tích hợp máy với máy)
#
# UBUNTU (22.04/24.04), chạy một lần:
#   curl -fsSL https://get.docker.com | sudo sh          # cài Docker Engine + plugin compose v2
#   sudo usermod -aG docker $USER && newgrp docker       # chạy docker không cần sudo
#   docker compose version                               # cần >= v2.23
#   sudo ufw allow 80/tcp                                # nếu bật ufw; chỉ mở cổng web, DB/Kafka không publish ra host
#   (khuyến nghị) thêm swap nếu RAM < 8 GB:  sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
# Sinh bí mật nhanh trên Ubuntu:  openssl rand -base64 32 | tr -d '/+=\n' | cut -c1-40
# Lưu ý cổng 80: nếu máy đã chạy nginx/apache thì đặt WEB_PORT=8081 (hoặc dừng dịch vụ đó).
#
# CÁCH ĐẶT ENV: mọi biến nằm ngay trong file này dưới dạng ${TÊN:-giá trị mặc định}.
#   - Sửa thẳng giá trị mặc định trong file này, HOẶC
#   - đặt biến môi trường / file .env cùng thư mục khi chạy để ghi đè (không cần sửa file).
# Phiên bản image: LMS_TAG (mặc định latest; production nên ghim sha hoặc vX.Y.Z), LMS_REGISTRY (mặc định ghcr.io/hungphung2811).
# ⚠️ TRƯỚC KHI CHẠY THẬT: đổi mọi giá trị đánh dấu [ĐỔI] (mật khẩu admin, các bí mật, khóa JWT cố định,
#    mật khẩu DB). Giá trị mặc định chỉ để chạy thử, KHÔNG dùng cho production.
# =====================================================================================================
name: lms

# ---- ENV DÙNG CHUNG -----------------------------------------------------------------------------------
x-jvm: &jvm
  # Heap nhỏ để tiết kiệm RAM; tăng JVM_XMX (ví dụ 512m) khi tải lớn.
  JAVA_TOOL_OPTIONS: ${JAVA_TOOL_OPTIONS:--Xmx${JVM_XMX:-256m} -XX:MaxMetaspaceSize=${JVM_MAX_METASPACE:-192m} -XX:ReservedCodeCacheSize=${JVM_CODE_CACHE:-64m} -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 ${JVM_EXTRA_OPTS:-}}
  SERVER_TOMCAT_THREADS_MAX: ${TOMCAT_THREADS_MAX:-20}
  SPRING_PROFILES_ACTIVE: prod

x-pool: &pool
  SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: ${DB_POOL_MAX:-5}
  SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE: ${DB_POOL_MIN_IDLE:-1}

x-common: &common
  # Chung cho các service gọi nhau: khóa nội bộ, JWKS, cache Redis, địa chỉ identity, Kafka.
  INTERNAL_API_KEY: ${INTERNAL_API_KEY:-doi-internal-api-key-32-ky-tu-tro-len}   # [ĐỔI] dùng chung mọi service
  JWT_JWK_SET_URI: http://identity-service:8081/.well-known/jwks.json
  IDENTITY_SERVICE_URL: http://identity-service:8081
  REDIS_HOST: redis
  REDIS_PORT: "6379"
  LMS_CACHE_ENABLED: ${LMS_CACHE_ENABLED:-true}
  KAFKA_BOOTSTRAP_SERVERS: kafka:9092

x-svc: &svc
  restart: unless-stopped
  mem_limit: ${JVM_MEM_LIMIT:-768m}

# ---- DỊCH VỤ ------------------------------------------------------------------------------------------
services:
  # ================= HẠ TẦNG =================
  postgres:
    image: postgres:16
    restart: unless-stopped
    command: ["postgres", "-c", "shared_buffers=${PG_SHARED_BUFFERS:-64MB}", "-c", "max_connections=${PG_MAX_CONNECTIONS:-100}", "-c", "work_mem=${PG_WORK_MEM:-4MB}", "-c", "maintenance_work_mem=${PG_MAINTENANCE_WORK_MEM:-32MB}"]
    mem_limit: ${PG_MEM_LIMIT:-512m}
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-postgres}   # [ĐỔI] (DB không publish ra host)
    volumes:
      - pgdata:/var/lib/postgresql/data
    configs:
      - source: init_databases_sql
        target: /docker-entrypoint-initdb.d/init-databases.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 3s
      retries: 10

  kafka:
    image: apache/kafka:3.9.0
    restart: unless-stopped
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_HEAP_OPTS: ${KAFKA_HEAP_OPTS:--Xms128m -Xmx256m}
      KAFKA_NUM_NETWORK_THREADS: 2
      KAFKA_NUM_IO_THREADS: 4
      KAFKA_NUM_RECOVERY_THREADS_PER_DATA_DIR: 1
      KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS: 1
      KAFKA_LOG_CLEANER_DEDUPE_BUFFER_SIZE: 16777216
    mem_limit: ${KAFKA_MEM_LIMIT:-768m}

  redis:
    image: redis:7
    restart: unless-stopped
    command: ["redis-server", "--maxmemory", "${REDIS_MAXMEMORY:-64mb}", "--maxmemory-policy", "allkeys-lru"]
    mem_limit: ${REDIS_MEM_LIMIT:-128m}

  mongo:
    image: mongo:7
    restart: unless-stopped
    command: ["--wiredTigerCacheSizeGB", "${MONGO_CACHE_GB:-0.25}"]
    mem_limit: ${MONGO_MEM_LIMIT:-512m}
    volumes:
      - mongodata:/data/db
    healthcheck:
      test: ["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand('ping').ok"]
      interval: 5s
      timeout: 3s
      retries: 10

  # Hộp thư giả: thư KHÔNG ra ngoài. Dùng SMTP thật thì đặt MAIL_* ở identity-service rồi có thể xóa service này
  # (cùng khối depends_on không tham chiếu tới nó).
  mailpit:
    image: axllent/mailpit:latest
    restart: unless-stopped
    mem_limit: ${MAILPIT_MEM_LIMIT:-64m}

  # ================= BACKEND =================
  # Runner Flyway cho mọi DB: phải healthy trước khi các service khác khởi động (lần đầu mất 1-2 phút).
  system-service:
    <<: *svc
    image: ${LMS_REGISTRY:-ghcr.io/hungphung2811}/lms-system-service:${LMS_TAG:-latest}
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_started }
    environment:
      <<: [*jvm, *pool, *common]
      IDENTITY_DB_URL: jdbc:postgresql://postgres:5432/identity_db
      IDENTITY_DB_USERNAME: identity
      IDENTITY_DB_PASSWORD: identity          # [ĐỔI] phải khớp mật khẩu trong config init-databases.sql ở cuối file
      CATALOG_DB_URL: jdbc:postgresql://postgres:5432/catalog_db
      CATALOG_DB_USERNAME: catalog
      CATALOG_DB_PASSWORD: catalog            # [ĐỔI]
      CIRCULATION_DB_URL: jdbc:postgresql://postgres:5432/circulation_db
      CIRCULATION_DB_USERNAME: circulation
      CIRCULATION_DB_PASSWORD: circulation    # [ĐỔI]
      AUDIT_DB_URL: jdbc:postgresql://postgres:5432/audit_db
      AUDIT_DB_USERNAME: audit
      AUDIT_DB_PASSWORD: audit                # [ĐỔI]
      SYSTEM_DB_URL: jdbc:postgresql://postgres:5432/system_db
      SYSTEM_DB_USERNAME: system
      SYSTEM_DB_PASSWORD: system              # [ĐỔI]
      SALES_DB_URL: jdbc:postgresql://postgres:5432/sales_db
      SALES_DB_USERNAME: sales
      SALES_DB_PASSWORD: sales                # [ĐỔI]
      # Context menu chạy bằng vai trò app (chịu RLS)
      DB_URL: jdbc:postgresql://postgres:5432/system_db
      DB_USERNAME: system_app
      DB_PASSWORD: system_app                 # [ĐỔI]
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8086/actuator/health || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 30
      start_period: 30s

  identity-service:
    <<: *svc
    image: ${LMS_REGISTRY:-ghcr.io/hungphung2811}/lms-identity-service:${LMS_TAG:-latest}
    depends_on:
      postgres: { condition: service_healthy }
      system-service: { condition: service_healthy }
      kafka: { condition: service_started }
    environment:
      <<: [*jvm, *pool, *common]
      DB_URL: jdbc:postgresql://postgres:5432/identity_db
      DB_USERNAME: identity_app
      DB_PASSWORD: identity_app               # [ĐỔI]
      # --- Tài khoản quản trị khởi tạo (username: admin) ---
      AUTH_BOOTSTRAP_ADMIN_PASSWORD: ${AUTH_BOOTSTRAP_ADMIN_PASSWORD:-ChangeMe@12345}   # [ĐỔI] bắt buộc đổi
      # Muốn có platform admin (quản lý nhiều tenant): bỏ comment và đặt giá trị trước lần khởi động đầu
      # AUTH_BOOTSTRAP_PLATFORM_ADMIN_USERNAME: ${AUTH_BOOTSTRAP_PLATFORM_ADMIN_USERNAME:-}
      # AUTH_BOOTSTRAP_PLATFORM_ADMIN_PASSWORD: ${AUTH_BOOTSTRAP_PLATFORM_ADMIN_PASSWORD:-}
      # --- Khóa ký JWT (PEM, cặp RSA 2048) ---
      # Để trống + ALLOW_EPHEMERAL_KEY=true: dùng khóa tạm, MỌI NGƯỜI BỊ ĐĂNG XUẤT mỗi lần identity khởi động lại (chỉ để chạy thử).
      # Production [ĐỔI]: đặt cặp khóa cố định (xuống dòng thành \n) và ALLOW_EPHEMERAL_KEY=false. Sinh khóa:
      #   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt.pem
      #   openssl pkey -in jwt.pem -pubout -out jwt.pub
      AUTH_JWT_PRIVATE_KEY: ${AUTH_JWT_PRIVATE_KEY:-}
      AUTH_JWT_PUBLIC_KEY: ${AUTH_JWT_PUBLIC_KEY:-}
      AUTH_JWT_PREVIOUS_PUBLIC_KEY: ${AUTH_JWT_PREVIOUS_PUBLIC_KEY:-}
      AUTH_JWT_ALLOW_EPHEMERAL_KEY: ${AUTH_JWT_ALLOW_EPHEMERAL_KEY:-true}
      INTEGRATION_REVOCATION_ENABLED: ${INTEGRATION_REVOCATION_ENABLED:-false}
      # --- Bí mật ---
      STOREFRONT_CLIENT_SECRET: ${STOREFRONT_CLIENT_SECRET:-doi-storefront-secret-32-ky-tu}   # [ĐỔI] cùng giá trị với customer-api
      EMAIL_VERIFICATION_OTP_SECRET: ${EMAIL_VERIFICATION_OTP_SECRET:-doi-otp-secret-32-ky-tu-tro-len}   # [ĐỔI]
      # Khóa AES-256 xuất dữ liệu tenant = base64 của 32 byte: openssl rand -base64 32. Trống thì tắt tính năng xuất.
      TENANT_DATA_ENCRYPTION_KEY: ${TENANT_DATA_ENCRYPTION_KEY:-}
      TENANT_DATA_EXPORT_DIR: /data/tenant-exports
      TENANT_DATA_CATALOG_URL: http://catalog-service:8082
      TENANT_DATA_CIRCULATION_URL: http://circulation-service:8083
      TENANT_DATA_SUPPORT_URL: http://support-service:8084
      TENANT_DATA_SALES_URL: http://sales-service:8087
      # --- Gửi email OTP. Mặc định Mailpit nội bộ; SMTP thật thì đặt 4 biến dưới ---
      MAIL_HOST: ${MAIL_HOST:-mailpit}
      MAIL_PORT: ${MAIL_PORT:-1025}
      MAIL_USERNAME: ${MAIL_USERNAME:-}
      MAIL_PASSWORD: ${MAIL_PASSWORD:-}
    volumes:
      - tenantexports:/data/tenant-exports

  catalog-service:
    <<: *svc
    image: ${LMS_REGISTRY:-ghcr.io/hungphung2811}/lms-catalog-service:${LMS_TAG:-latest}
    depends_on:
      postgres: { condition: service_healthy }
      system-service: { condition: service_healthy }
    environment:
      <<: [*jvm, *pool, *common]
      DB_URL: jdbc:postgresql://postgres:5432/catalog_db
      DB_USERNAME: catalog_app
      DB_PASSWORD: catalog_app                # [ĐỔI]

  circulation-service:
    <<: *svc
    image: ${LMS_REGISTRY:-ghcr.io/hungphung2811}/lms-circulation-service:${LMS_TAG:-latest}
    depends_on:
      postgres: { condition: service_healthy }
      system-service: { condition: service_healthy }
      kafka: { condition: service_started }
    environment:
      <<: [*jvm, *pool, *common]
      DB_URL: jdbc:postgresql://postgres:5432/circulation_db
      DB_USERNAME: circulation_app
      DB_PASSWORD: circulation_app            # [ĐỔI]
      CATALOG_SERVICE_URL: http://catalog-service:8082

  support-service:
    <<: *svc
    image: ${LMS_REGISTRY:-ghcr.io/hungphung2811}/lms-support-service:${LMS_TAG:-latest}
    depends_on:
      postgres: { condition: service_healthy }
      system-service: { condition: service_healthy }
      mongo: { condition: service_healthy }
      kafka: { condition: service_started }
    environment:
      <<: [*jvm, *common]
      AUDIT_DB_URL: jdbc:postgresql://postgres:5432/audit_db
      AUDIT_DB_USERNAME: audit_app
      AUDIT_DB_PASSWORD: audit_app            # [ĐỔI]
      LOG_DB_URI: mongodb://mongo:27017/audit_logs

  sales-service:
    <<: *svc
    image: ${LMS_REGISTRY:-ghcr.io/hungphung2811}/lms-sales-service:${LMS_TAG:-latest}
    depends_on:
      postgres: { condition: service_healthy }
      system-service: { condition: service_healthy }
      kafka: { condition: service_started }
    environment:
      <<: [*jvm, *pool, *common]
      DB_URL: jdbc:postgresql://postgres:5432/sales_db
      DB_USERNAME: sales_app
      DB_PASSWORD: sales_app                  # [ĐỔI]
      CATALOG_SERVICE_URL: http://catalog-service:8082
      # --- Thanh toán QR SePay (tùy chọn). Bật: PAYMENTS_SEPAY_ENABLED=true và điền 4 giá trị còn lại ---
      PAYMENTS_SEPAY_ENABLED: ${PAYMENTS_SEPAY_ENABLED:-false}
      PAYMENTS_SEPAY_TOKEN: ${PAYMENTS_SEPAY_TOKEN:-}
      PAYMENTS_SEPAY_BANK_BIN: ${PAYMENTS_SEPAY_BANK_BIN:-}
      PAYMENTS_SEPAY_ACCOUNT_NO: ${PAYMENTS_SEPAY_ACCOUNT_NO:-}
      PAYMENTS_SEPAY_BANK_ACCOUNT_ID: ${PAYMENTS_SEPAY_BANK_ACCOUNT_ID:-}

  # BFF cho FE quản trị (/api/**)
  lms-api:
    <<: *svc
    image: ${LMS_REGISTRY:-ghcr.io/hungphung2811}/lms-lms-api:${LMS_TAG:-latest}
    depends_on:
      identity-service: { condition: service_started }
    environment:
      <<: [*jvm]
      JWT_JWK_SET_URI: http://identity-service:8081/.well-known/jwks.json
      IDENTITY_SERVICE_URL: http://identity-service:8081
      CATALOG_SERVICE_URL: http://catalog-service:8082
      CIRCULATION_SERVICE_URL: http://circulation-service:8083
      SUPPORT_SERVICE_URL: http://support-service:8084
      SYSTEM_SERVICE_URL: http://system-service:8086
      SALES_SERVICE_URL: http://sales-service:8087

  # BFF công khai cho khách (/public/**)
  customer-api:
    <<: *svc
    image: ${LMS_REGISTRY:-ghcr.io/hungphung2811}/lms-customer-api:${LMS_TAG:-latest}
    depends_on:
      - identity-service
      - system-service
      - sales-service
    environment:
      <<: [*jvm]
      JWT_JWK_SET_URI: http://identity-service:8081/.well-known/jwks.json
      IDENTITY_SERVICE_URL: http://identity-service:8081
      SYSTEM_SERVICE_URL: http://system-service:8086
      SALES_SERVICE_URL: http://sales-service:8087
      STOREFRONT_CLIENT_SECRET: ${STOREFRONT_CLIENT_SECRET:-doi-storefront-secret-32-ky-tu}   # [ĐỔI] cùng giá trị với identity
      # CORS động của /public/**. ENFORCE ở production; PLATFORM_ORIGINS = domain trang SaaS (https://...), nhiều giá trị cách nhau dấu phẩy.
      PUBLIC_CORS_MODE: ${PUBLIC_CORS_MODE:-ENFORCE}
      PUBLIC_CORS_PLATFORM_ORIGINS: ${PUBLIC_CORS_PLATFORM_ORIGINS:-}
      PUBLIC_CORS_DEV_ORIGINS: ${PUBLIC_CORS_DEV_ORIGINS:-}
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8088/actuator/health || exit 1"]
      interval: 10s
      timeout: 3s
      retries: 6
      start_period: 40s

  # Tùy chọn: tích hợp máy-với-máy (/api/integration/v1/**). Bật bằng --profile integration
  integration-api:
    <<: *svc
    profiles: [integration]
    image: ${LMS_REGISTRY:-ghcr.io/hungphung2811}/lms-integration-api:${LMS_TAG:-latest}
    depends_on:
      identity-service: { condition: service_started }
      redis: { condition: service_started }
    environment:
      <<: [*jvm]
      JWT_JWK_SET_URI: http://identity-service:8081/.well-known/jwks.json
      JWT_ISSUER: ${JWT_ISSUER:-library-auth-service}
      IDENTITY_SERVICE_URL: http://identity-service:8081
      CATALOG_SERVICE_URL: http://catalog-service:8082
      SALES_SERVICE_URL: http://sales-service:8087
      REDIS_HOST: redis
      REDIS_PORT: "6379"
      INTEGRATION_RATE_LIMIT_ENABLED: ${INTEGRATION_RATE_LIMIT_ENABLED:-true}
      INTEGRATION_RATE_LIMIT_PER_MINUTE: ${INTEGRATION_RATE_LIMIT_PER_MINUTE:-600}
      INTEGRATION_REVOCATION_ENABLED: ${INTEGRATION_REVOCATION_ENABLED:-false}
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8089/actuator/health || exit 1"]
      interval: 10s
      timeout: 3s
      retries: 6
      start_period: 40s

  # ================= CỔNG VÀO =================
  # Cổng vào API nội bộ (8080, không publish ra host): CORS, header bảo mật, giới hạn tốc độ, chặn /internal.
  nginx:
    image: nginx:1.27-alpine
    restart: unless-stopped
    mem_limit: ${NGINX_MEM_LIMIT:-64m}
    command: ["nginx", "-c", "/etc/nginx/lms/nginx.conf", "-g", "daemon off;"]
    depends_on:
      lms-api: { condition: service_started }
      identity-service: { condition: service_started }
      customer-api: { condition: service_started }
      circulation-service: { condition: service_started }
      support-service: { condition: service_started }
    configs:
      - { source: gw_nginx_conf, target: /etc/nginx/lms/nginx.conf }
      - { source: gw_common, target: /etc/nginx/lms/snippets/common.conf }
      - { source: gw_proxy_import, target: /etc/nginx/lms/snippets/proxy-import.conf }
      - { source: gw_proxy, target: /etc/nginx/lms/snippets/proxy.conf }
      - { source: gw_real_ip, target: /etc/nginx/lms/snippets/real-ip.conf }
      - { source: gw_security_headers, target: /etc/nginx/lms/snippets/security-headers.conf }

  # Web FE (cổng duy nhất ra host). Image có sẵn cấu hình proxy /api và /public tới host `nginx:8080`.
  web:
    image: ${LMS_REGISTRY:-ghcr.io/hungphung2811}/lms-web:${LMS_TAG:-latest}
    restart: unless-stopped
    mem_limit: 64m
    depends_on: [nginx]
    ports:
      - "${WEB_PORT:-80}:80"

volumes:
  pgdata:
  mongodata:
  tenantexports:

# ---- FILE CẤU HÌNH NHÚNG SẴN (không cần thư mục infrastructure/) --------------------------------------
# Ký tự `$` trong nội dung nginx đã được viết thành `$$` để Compose không nội suy biến.
configs:
  init_databases_sql:
    content: |
      -- Mỗi service sở hữu một database riêng.
      -- Hai vai trò cho mỗi DB:
      --   <svc>      : chủ DB và chủ bảng, chỉ system-service dùng (chạy Flyway).
      --   <svc>_app  : service dùng lúc chạy. NOSUPERUSER NOBYPASSRLS, không sở hữu bảng nên chịu Row-Level Security.
      --                Quyền SELECT/INSERT/UPDATE/DELETE do callback afterMigrate.sql của system-service cấp sau mỗi
      --                lần migrate (kể cả khi không có script mới).
      -- Script này chỉ chạy khi volume postgres còn trống. Volume cũ: `docker compose down -v`, hoặc chạy tay các lệnh
      -- CREATE USER ..._app bên dưới rồi khởi động lại/chạy lại system-service (afterMigrate tự cấp quyền, không cần
      -- GRANT tay).
      CREATE USER identity WITH PASSWORD 'identity';
      CREATE DATABASE identity_db OWNER identity;
      CREATE USER identity_app WITH PASSWORD 'identity_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

      CREATE USER circulation WITH PASSWORD 'circulation';
      CREATE DATABASE circulation_db OWNER circulation;
      CREATE USER circulation_app WITH PASSWORD 'circulation_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

      -- support-service, ngữ cảnh audit: PostgreSQL (AUDIT_DB) cho audit log; bản thô của event nằm ở MongoDB (LOG_DB).
      CREATE USER audit WITH PASSWORD 'audit';
      CREATE DATABASE audit_db OWNER audit;
      CREATE USER audit_app WITH PASSWORD 'audit_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

      CREATE USER catalog WITH PASSWORD 'catalog';
      CREATE DATABASE catalog_db OWNER catalog;
      CREATE USER catalog_app WITH PASSWORD 'catalog_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

      -- system-service, ngữ cảnh menu (ADR-0007): system_db do chính runner migration của system-service migrate (target
      -- `system`). Vai trò `system` là chủ DB (chỉ runner dùng); nếu CREATE USER system lỗi thì đổi tên thành system_owner
      -- và đặt SYSTEM_DB_USERNAME tương ứng.
      CREATE USER system WITH PASSWORD 'system';
      CREATE DATABASE system_db OWNER system;
      CREATE USER system_app WITH PASSWORD 'system_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

      -- sales-service (ADR-0010): sales_db do system-service migrate (target `sales`); sales_app dùng lúc chạy, chịu RLS.
      CREATE USER sales WITH PASSWORD 'sales';
      CREATE DATABASE sales_db OWNER sales;
      CREATE USER sales_app WITH PASSWORD 'sales_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
  gw_nginx_conf:
    content: |
      # Cổng vào duy nhất cho frontend. Nginx lo: CORS cho /api/** (còn /public/** do customer-api quyết theo Origin và mã
      # tenant, ADR-0013: nginx chuyển nguyên Origin và KHÔNG tự trả CORS/OPTIONS ở đó), header bảo mật, giới hạn tốc độ,
      # chặn /internal và /.well-known, chuyển tiếp mọi /api/** về lms-api, /public/** về customer-api. Nginx bản miễn phí không kiểm JWT: lms-api (và từng service nội bộ) tự kiểm token.
      # Chạy: nginx -c /etc/nginx/lms/nginx.conf -g 'daemon off;'
      worker_processes auto;
      pid /tmp/nginx.pid;

      events {
          worker_connections 1024;
      }

      http {
          include /etc/nginx/mime.types;
          default_type application/json;
          server_tokens off;
          sendfile on;
          keepalive_timeout 65;
          client_max_body_size 1m;

          # DNS của Docker. Dùng biến trong proxy_pass để nginx khởi động được dù service chưa lên và tự cập nhật IP.
          resolver 127.0.0.11 valid=10s ipv6=off;

          log_format main '$$remote_addr "$$request" $$status $$body_bytes_sent rt=$$request_time rid=$$request_id';
          access_log /dev/stdout main;
          error_log /dev/stderr warn;

          # IP thật sau LB/CDN (mặc định rỗng, xem snippets/real-ip.conf). Phải đứng trước các limit_req_zone theo IP.
          include /etc/nginx/lms/snippets/real-ip.conf;

          # Origin của frontend được phép (CORS) CHO /api/**. Thêm origin production vào đây. /public/** không dùng map này.
          map $$http_origin $$cors_origin {
              default "";
              "http://localhost:3000" $$http_origin;
              "http://localhost:5173" $$http_origin;
          }

          # Chống dò mật khẩu: 10 lần/phút/IP, cho phép dồn 5 lần
          limit_req_zone $$binary_remote_addr zone=login:10m rate=10r/m;
          # Quên mật khẩu (yêu cầu + xác nhận token): 5 lần/phút/IP; thêm giới hạn theo tài khoản ở identity
          limit_req_zone $$binary_remote_addr zone=pwreset:10m rate=5r/m;
          limit_req_status 429;

          # Cổng công khai /public/** (customer-api, ADR-0011 2.7). public_read: mọi đọc công khai. public_form: form liên hệ và
          # đăng ký tài khoản khách, chỉ tính POST (khóa rỗng thì không bị giới hạn). Vùng tính theo IP biên của nginx:
          # nếu sau này đặt sau load balancer/CDN phải cấu hình set_real_ip_from + real_ip_header, nếu không mọi khách chung
          # một IP và bị 429 hàng loạt.
          limit_req_zone $$binary_remote_addr zone=public_read:10m rate=20r/s;
          map $$request_method $$public_form_key {
              default "";
              POST $$binary_remote_addr;
          }
          limit_req_zone $$public_form_key zone=public_form:10m rate=5r/m;
          # Đặt/hủy đơn của khách (chỉ tính POST): lớp ngoài cùng theo IP, rộng hơn public_form vì nhiều khách có thể chung một IP
          # (NAT). Giới hạn chính xác theo khách và theo tenant do sales-service làm bằng Redis (chính sách tenant), xem
          # docs/security/ORDER_ABUSE_AUDIT.md pha 4.
          limit_req_zone $$public_form_key zone=public_order:10m rate=20r/m;
          # Thăm dò trạng thái thanh toán đơn (GET .../orders/{id}/payment, ADR-0029 3.4): FE hỏi 3 giây/lần, 2r/s/IP đủ cho
          # khoảng 6 khách sau một NAT. ⚠️ Số đề xuất, chưa đo.
          limit_req_zone $$binary_remote_addr zone=public_poll:10m rate=2r/s;

          # Giới hạn theo mã tenant (ADR-0013 2.11), BẢO VỆ BACKEND chứ không chống cào: ngưỡng cao, cộng dồn mọi IP. Mã hợp
          # lệ (cùng mẫu với customer-api, chữ thường) làm khóa; mã khác (chữ hoa, ký tự lạ) dồn chung khóa "~invalid"; đường dẫn
          # không có mã (/public/site/**, /public/auth/**) khóa rỗng nên không bị vùng này giới hạn. Dùng $$uri (đã giải mã %XX,
          # gộp //) để %61bc và abc cùng một khóa. ⚠️ 50r/s burst 100 là số đề xuất chờ đo (P-O2).
          map $$uri $$public_code_key {
              "~^/public/t/(?<pcode>[a-z0-9-]{2,50})(?:[/;]|$$)" $$pcode;
              "~^/public/t/"                                     "~invalid";
              default                                            "";
          }
          limit_req_zone $$public_code_key zone=public_tenant:10m rate=50r/s;

          # Public API v1 (SAAS_API_AUTHENTICATION_DESIGN 4.4.1, pha P2): tenant xác định bằng header X-Site-Key thay cho {code}. Vùng
          # public_sitekey theo site key là NGƯỠNG BẢO VỆ BACKEND (cộng dồn mọi IP, site key công khai nên không chống cào); chống
          # nguồn đơn lẻ vẫn là public_read/login theo IP. Khóa chỉ nhận đúng mẫu site key (lms_pk_ + base64url 32..56), giá trị lạ dồn
          # chung khóa "~invalid", header vắng thì khóa rỗng (không bị vùng này giới hạn, vẫn bị vùng theo IP). Site key không
          # vào log truy cập của nginx (log_format mặc định không ghi header). ⚠️ 50r/s burst 100 là số đề xuất chờ đo (P-P1).
          map $$http_x_site_key $$public_sitekey_key {
              "~^(?<pksk>lms_pk_[A-Za-z0-9_-]{32,56})$$" $$pksk;
              ""                                         "";
              default                                    "~invalid";
          }
          limit_req_zone $$public_sitekey_key zone=public_sitekey:10m rate=50r/s;

          # Integration API cho backend của tenant (SAAS_API_AUTHENTICATION_DESIGN 4.4.1, pha I2). oauth_token chống dò client
          # secret theo IP (secret 256 bit nên vét cạn không khả thi, đây là chống DoS/ký RS256 liên tục); không khóa client theo
          # số lần sai (tránh DoS bởi người biết client_id). integration_ip là bảo vệ thô theo IP; giới hạn theo application
          # (Redis) là việc của integration-api ở pha I3. ⚠️ 30r/m (burst 10) và 20r/s (burst 40) là số đề xuất chờ đo.
          limit_req_zone $$binary_remote_addr zone=oauth_token:10m rate=30r/m;
          limit_req_zone $$binary_remote_addr zone=integration_ip:10m rate=20r/s;

          server {
              listen 8080;

              set $$lms_api http://lms-api:8085;
              set $$customer_api http://customer-api:8088;
              set $$integration_api http://integration-api:8089;

              location = /healthz {
                  access_log off;
                  default_type application/json;
                  return 200 '{"status":"UP"}';
              }

              # API nội bộ và JWKS không bao giờ ra ngoài
              location ^~ /internal/ {
                  return 404;
              }
              location ^~ /.well-known/ {
                  return 404;
              }

              # Giới hạn tốc độ đăng nhập (chống dò mật khẩu); vẫn đi qua lms-api như mọi API khác
              location = /api/auth/login {
                  limit_req zone=login burst=5 nodelay;
                  include /etc/nginx/lms/snippets/common.conf;
                  set $$up $$lms_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }

              # Quên mật khẩu (công khai): giới hạn tốc độ theo IP, vẫn đi qua lms-api
              location ~ ^/api/auth/password-reset-(requests|confirmations)$$ {
                  limit_req zone=pwreset burst=3 nodelay;
                  include /etc/nginx/lms/snippets/common.conf;
                  set $$up $$lms_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }

              # ---- Đa tenant: API nền tảng và danh sách tenant của tôi đi qua lms-api, lms-api chuyển tới identity-service
              # với header Authorization nguyên vẹn (identity tự kiểm claim platformAdmin/tenantId). Đăng nhập, làm mới,
              # đổi tenant (/api/auth/**) cũng đi qua lms-api (không còn route thẳng vào identity).
              # /t/<code> chỉ là đường dẫn của FE (SPA), không qua nginx này. ----
              location ^~ /api/platform/ {
                  include /etc/nginx/lms/snippets/common.conf;
                  set $$up $$lms_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }
              location = /api/me/tenants {
                  include /etc/nginx/lms/snippets/common.conf;
                  set $$up $$lms_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }

              # ---- Nhập master data từ Excel (ADR-0024): /api/<module>/imports/** (upload multipart, xác nhận, tải file lỗi/mẫu).
              # Thân tối đa 6 MB (file 5 MB + overhead multipart; lms-api trả 413 JSON khi file > 5 MB), timeout 60 s.
              # Regex đứng trước `location /api/` nên thắng; không ảnh hưởng /api/platform/ (^~). ----
              location ~ "^/api/[a-z-]+/imports(?:/|$$)" {
                  client_max_body_size 6m;
                  include /etc/nginx/lms/snippets/common.conf;
                  set $$up $$lms_api;
                  include /etc/nginx/lms/snippets/proxy-import.conf;
                  proxy_pass $$up;
              }

              # ---- Mặc định: mọi API cho FE đi qua lms-api (gồm /api/notifications/**) ----
              location /api/ {
                  include /etc/nginx/lms/snippets/common.conf;
                  set $$up $$lms_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }

              # ---- Cổng công khai cho khách (customer-api): trang giới thiệu, form liên hệ, storefront ----
              # Dùng `location /public/` thường (không `^~`) để các location regex bên dưới còn được xét. Không route /actuator.
              location /public/ {
                  client_max_body_size 16k;
                  limit_req zone=public_read burst=40 nodelay;
                  limit_req zone=public_tenant burst=100 nodelay;
                  include /etc/nginx/lms/snippets/security-headers.conf;
                  set $$up $$customer_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }
              # Form liên hệ/dùng thử và đăng ký tài khoản khách: 5 lần/phút/IP (chỉ POST), dồn tối đa 3
              # Regex chấp nhận biến thể dấu "/" cuối và tham số đường dẫn ";x=1" để không lọt sang vùng public_read.
              location ~ "^/public/site/contact-requests(?:[/;].*)?$$" {
                  client_max_body_size 16k;
                  limit_req zone=public_form burst=3 nodelay;
                  limit_req zone=public_tenant burst=100 nodelay;
                  include /etc/nginx/lms/snippets/security-headers.conf;
                  set $$up $$customer_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }
              # Xác minh email và gửi lại OTP: cùng mức đăng ký (5 lần/phút/IP, chỉ POST, dồn tối đa 3)
              location ~ "^/public/t/[^/]+/auth/(?:verify-email|resend-verification)(?:[/;].*)?$$" {
                  client_max_body_size 16k;
                  limit_req zone=public_form burst=3 nodelay;
                  limit_req zone=public_tenant burst=100 nodelay;
                  include /etc/nginx/lms/snippets/security-headers.conf;
                  set $$up $$customer_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }
              location ~ "^/public/t/[^/]+/auth/signup(?:[/;].*)?$$" {
                  client_max_body_size 16k;
                  limit_req zone=public_form burst=3 nodelay;
                  limit_req zone=public_tenant burst=100 nodelay;
                  include /etc/nginx/lms/snippets/security-headers.conf;
                  set $$up $$customer_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }
              # Thăm dò thanh toán đơn (ADR-0029): PHẢI đứng trước location /orders bên dưới (regex xét theo thứ tự khai báo),
              # nếu không đường này rơi vào public_order (chỉ tính POST) và không bị hãm theo IP.
              location ~ "^/public/t/[^/]+/orders/[^/]+/payment(?:[;].*)?$$" {
                  client_max_body_size 16k;
                  limit_req zone=public_poll burst=10 nodelay;
                  limit_req zone=public_tenant burst=100 nodelay;
                  include /etc/nginx/lms/snippets/security-headers.conf;
                  set $$up $$customer_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }
              # Đặt/hủy đơn: 20 lần/phút/IP (chỉ POST), dồn tối đa 10; thân tối đa 64k (đơn tới 200 dòng). Cùng mẫu regex với trên.
              location ~ "^/public/t/[^/]+/orders(?:[/;].*)?$$" {
                  client_max_body_size 64k;
                  limit_req zone=public_order burst=10 nodelay;
                  limit_req zone=public_tenant burst=100 nodelay;
                  include /etc/nginx/lms/snippets/security-headers.conf;
                  set $$up $$customer_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }
              # Liên hệ của khách gửi cửa hàng (ADR-0015 2.7): cùng mức form công khai (5 lần/phút/IP, chỉ POST, dồn tối đa 3).
              # Không thêm CORS ở đây (CORS do customer-api xử lý, ADR-0013). Thân tối đa 16k đủ cho lời nhắn 2000 ký tự.
              location ~ "^/public/t/[^/]+/contact-requests(?:[/;].*)?$$" {
                  client_max_body_size 16k;
                  limit_req zone=public_form burst=3 nodelay;
                  limit_req zone=public_tenant burst=100 nodelay;
                  include /etc/nginx/lms/snippets/security-headers.conf;
                  set $$up $$customer_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }
              # Đăng nhập khách: cùng mức với /api/auth/login (vùng login)
              location ~ "^/public/t/[^/]+/auth/login(?:[/;].*)?$$" {
                  client_max_body_size 16k;
                  limit_req zone=login burst=5 nodelay;
                  limit_req zone=public_tenant burst=100 nodelay;
                  include /etc/nginx/lms/snippets/security-headers.conf;
                  set $$up $$customer_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }

              # ---- Public API v1 cho website của tenant (customer-api, X-Site-Key, pha P2) ----
              # Chỉ include security-headers.conf (KHÔNG common.conf): CORS do customer-api quyết theo Application của site key,
              # nginx KHÔNG trả OPTIONS và chuyển nguyên Origin/X-Site-Key. `^~` để thắng regex import `^/api/[a-z-]+/imports` và
              # `location /api/` (nếu không, /api/public/** rơi về lms-api). Thân tối đa 16k (khớp RequestSizeLimitFilter).
              location ^~ /api/public/ {
                  client_max_body_size 16k;
                  limit_req zone=public_read burst=40 nodelay;
                  limit_req zone=public_sitekey burst=100 nodelay;
                  include /etc/nginx/lms/snippets/security-headers.conf;
                  set $$up $$customer_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }
              # Đăng nhập khách: cùng mức với /api/auth/login (vùng login)
              location = /api/public/v1/sessions {
                  client_max_body_size 16k;
                  limit_req zone=login burst=5 nodelay;
                  limit_req zone=public_sitekey burst=100 nodelay;
                  include /etc/nginx/lms/snippets/security-headers.conf;
                  set $$up $$customer_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }
              # Đăng ký khách: cùng mức form công khai (5 lần/phút/IP, chỉ POST, dồn tối đa 3)
              location = /api/public/v1/sessions/signup {
                  client_max_body_size 16k;
                  limit_req zone=public_form burst=3 nodelay;
                  limit_req zone=public_sitekey burst=100 nodelay;
                  include /etc/nginx/lms/snippets/security-headers.conf;
                  set $$up $$customer_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }
              # Đặt/hủy đơn: cùng mức đường cũ (20 lần/phút/IP, chỉ POST, dồn tối đa 10); thân tối đa 16k như customer-api
              location ^~ /api/public/v1/orders {
                  client_max_body_size 16k;
                  limit_req zone=public_order burst=10 nodelay;
                  limit_req zone=public_sitekey burst=100 nodelay;
                  include /etc/nginx/lms/snippets/security-headers.conf;
                  set $$up $$customer_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }

              # ---- Integration API cho backend của tenant (integration-api, OAuth2 client_credentials) ----
              # Chỉ server-to-server: KHÔNG CORS (không include common.conf), chỉ header bảo mật. `^~` để thắng regex import
              # `^/api/[a-z-]+/imports` và `location /api/` (nếu không, /api/integration/** rơi về lms-api). nginx không kiểm JWT;
              # integration-api bắt buộc aud lms-integration. JWKS vẫn bị chặn (/.well-known/ ở trên, Q11).
              # Token endpoint: khớp đúng đường dẫn (=), 30 lần/phút/IP, dồn tối đa 10, thân tối đa 4k.
              location = /api/integration/v1/oauth/token {
                  client_max_body_size 4k;
                  limit_req zone=oauth_token burst=10 nodelay;
                  include /etc/nginx/lms/snippets/security-headers.conf;
                  set $$up $$integration_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }
              location ^~ /api/integration/ {
                  client_max_body_size 64k;
                  limit_req zone=integration_ip burst=40 nodelay;
                  include /etc/nginx/lms/snippets/security-headers.conf;
                  set $$up $$integration_api;
                  include /etc/nginx/lms/snippets/proxy.conf;
                  proxy_pass $$up;
              }

              location / {
                  return 404;
              }
          }
      }
  gw_common:
    content: |
      # CORS của nginx + header bảo mật, CHỈ cho /api/**. /public/** KHÔNG include file này (CORS do customer-api quyết,
      # ADR-0013): include security-headers.conf thay thế. Phải include trong từng location vì add_header của location ghi đè
      # cấp server (và add_header trong location cũng ghi đè của include trước nó nên file này tự gồm security-headers.conf).
      include /etc/nginx/lms/snippets/security-headers.conf;
      add_header Access-Control-Allow-Origin $$cors_origin always;
      add_header Access-Control-Allow-Credentials true always;
      add_header Access-Control-Expose-Headers "X-Correlation-Id, Content-Disposition, X-Report-Truncated" always;
      add_header Vary Origin always;

      if ($$request_method = OPTIONS) {
          add_header Access-Control-Allow-Origin $$cors_origin always;
          add_header Access-Control-Allow-Credentials true always;
          add_header Access-Control-Allow-Methods "GET, POST, PUT, PATCH, DELETE, OPTIONS" always;
          add_header Access-Control-Allow-Headers "Authorization, Content-Type, X-Correlation-Id" always;
          add_header Access-Control-Max-Age 3600 always;
          add_header Vary Origin always;
          add_header Content-Length 0;
          return 204;
      }
  gw_proxy_import:
    content: |
      # Như proxy.conf nhưng timeout 60 s cho upload/tải file nhập Excel (ADR-0024). Không include cùng proxy.conf (trùng chỉ thị bị nginx từ chối).
      proxy_http_version 1.1;
      proxy_set_header Host $$host;
      proxy_set_header X-Real-IP $$remote_addr;
      proxy_set_header X-Forwarded-For $$proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $$scheme;
      proxy_set_header X-Forwarded-Host $$host;
      # Luôn đặt correlation id ở biên để client không giả mạo được
      proxy_set_header X-Correlation-Id $$request_id;
      proxy_set_header Connection "";
      proxy_connect_timeout 3s;
      proxy_send_timeout 60s;
      proxy_read_timeout 60s;
      proxy_redirect off;
      # Header bảo mật do nginx đặt (snippets/common.conf); ẩn bản của upstream để không bị lặp
      proxy_hide_header X-Content-Type-Options;
      proxy_hide_header X-Frame-Options;
      proxy_hide_header Referrer-Policy;
      proxy_hide_header X-XSS-Protection;
  gw_proxy:
    content: |
      proxy_http_version 1.1;
      proxy_set_header Host $$host;
      proxy_set_header X-Real-IP $$remote_addr;
      proxy_set_header X-Forwarded-For $$proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $$scheme;
      proxy_set_header X-Forwarded-Host $$host;
      # Luôn đặt correlation id ở biên để client không giả mạo được
      proxy_set_header X-Correlation-Id $$request_id;
      proxy_set_header Connection "";
      proxy_connect_timeout 3s;
      proxy_send_timeout 30s;
      proxy_read_timeout 30s;
      proxy_redirect off;
      # Header bảo mật do nginx đặt (snippets/common.conf); ẩn bản của upstream để không bị lặp
      proxy_hide_header X-Content-Type-Options;
      proxy_hide_header X-Frame-Options;
      proxy_hide_header Referrer-Policy;
      proxy_hide_header X-XSS-Protection;
  gw_real_ip:
    content: |
      # IP thật của client cho các vùng limit_req theo IP. MẶC ĐỊNH RỖNG: nginx là biên, $$remote_addr là IP thật.
      # Khi đặt sau load balancer/CDN, môi trường đó gắn (mount) file này với nội dung, ví dụ:
      #   set_real_ip_from 10.0.0.0/8;        # CIDR của LB/CDN, TUYỆT ĐỐI không để 0.0.0.0/0
      #   real_ip_header X-Forwarded-For;     # Cloudflare: CF-Connecting-IP
      #   real_ip_recursive on;
      # Tin X-Forwarded-For từ nguồn ngoài CIDR đã khai là để khách tự chọn IP và vô hiệu hóa mọi vùng theo IP.
  gw_security_headers:
    content: |
      # Header bảo mật dùng chung. Phải include trong từng location vì add_header của location ghi đè cấp server.
      # Tách khỏi CORS (ADR-0013 2.1): /public/** chỉ include file này (CORS do customer-api quyết theo Origin và mã tenant),
      # /api/** include common.conf (gồm file này + CORS của nginx). Nhờ vậy một phản hồi không bao giờ có hai
      # Access-Control-Allow-Origin.
      add_header X-Content-Type-Options nosniff always;
      add_header X-Frame-Options DENY always;
      add_header Referrer-Policy no-referrer always;
