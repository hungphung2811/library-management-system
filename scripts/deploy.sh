#!/usr/bin/env bash
# Triển khai một lệnh: ./scripts/deploy.sh [up|down|logs|status]   (cần docker compose v2.24+, openssl)
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml --profile apps --profile support)

gen() { openssl rand -base64 32 | tr -d '/+=\n' | cut -c1-40; }
set_var() { # set_var KEY VALUE: ghi vào .env nếu KEY chưa có giá trị
  local k=$1 v=$2
  if grep -qE "^${k}=.+" .env; then return; fi
  grep -vE "^${k}=" .env > .env.tmp || true; mv .env.tmp .env; printf '%s=%s\n' "$k" "$v" >> .env
}

init_env() {
  [ -f .env ] || cp .env.example .env
  local adminpw; adminpw="$(gen)Aa1!"
  set_var AUTH_BOOTSTRAP_ADMIN_PASSWORD "$adminpw"
  set_var INTERNAL_API_KEY "$(gen)"
  set_var STOREFRONT_CLIENT_SECRET "$(gen)"
  set_var TENANT_DATA_ENCRYPTION_KEY "$(openssl rand -base64 32)"
  set_var EMAIL_VERIFICATION_OTP_SECRET "$(gen)"
  if ! grep -qE '^AUTH_JWT_PRIVATE_KEY=.+' .env; then
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /tmp/lms-jwt.pem 2>/dev/null
    local priv pub
    priv=$(awk 'NF{printf "%s\n",$0}' /tmp/lms-jwt.pem)
    pub=$(openssl pkey -in /tmp/lms-jwt.pem -pubout | awk 'NF{printf "%s\n",$0}')
    rm -f /tmp/lms-jwt.pem
    set_var AUTH_JWT_PRIVATE_KEY "$priv"; set_var AUTH_JWT_PUBLIC_KEY "$pub"
  fi
  chmod 600 .env
}

case "${1:-up}" in
  up) init_env; "${COMPOSE[@]}" up -d --build
      echo "Xong. Web: http://<máy chủ>:$(grep -E '^WEB_PORT=' .env | cut -d= -f2 || echo 80). Admin: admin / (AUTH_BOOTSTRAP_ADMIN_PASSWORD trong .env)";;
  down) "${COMPOSE[@]}" down;;
  logs) "${COMPOSE[@]}" logs -f --tail=100 "${@:2}";;
  status) "${COMPOSE[@]}" ps;;
  *) echo "Dùng: $0 [up|down|logs|status]"; exit 1;;
esac
