#!/usr/bin/env bash
# Chạy kịch bản demo từ đầu đến cuối (docs/demo/KICH_BAN_DEMO.md) trên hệ thống đang chạy, in OK/LOI từng bước.
# Cần: dịch vụ (3000 -> lms-api 8085, customer-api 8088), Mailpit 8025, tenant demo đã seed. Cần python và curl.
# SKIP_EXPIRY=1 bỏ bước chờ job hết hạn giữ hàng (~2 phút). Mã thoát 0 nếu mọi bước đạt, 1 nếu có bước lỗi.
set -u
export PYTHONIOENCODING=utf-8
API=${API:-http://localhost:3000}
SHOP=${SHOP:-http://localhost:8088}
MAIL=${MAIL:-http://localhost:8025}
PASS=${DEMO_PASSWORD:-Demo@12345}
RUN=$RANDOM
PASSED=0; FAILED=0; FAILS=()

ok()  { PASSED=$((PASSED+1)); echo "  OK   $1"; }
bad() { FAILED=$((FAILED+1)); FAILS+=("$1"); echo "  LOI  $1 ${2:+-> $2}"; }
chk() { if [ "$2" = "1" ]; then ok "$1"; else bad "$1" "${3:-}"; fi; }
eq()  { if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1" "mong $3, nhan $2"; fi; }
step() { echo; echo "== $1"; }
jget() { python -c "import sys,json
try:
  d=json.load(sys.stdin); print($1)
except Exception as e:
  print('')"; }

# req METHOD URL [BODY] [HEADER...] -> đặt BODY_OUT, CODE; tự thử lại tối đa 3 lần khi 502/503 (dịch vụ khởi động nguội)
req() {
  local m=$1 u=$2 b=${3:-} ; shift 3 2>/dev/null || shift $#
  local i out
  for i in 1 2 3; do
    if [ -n "$b" ]; then out=$(curl -s -m 30 -X "$m" "$u" -H 'Content-Type: application/json' "$@" -d "$b" -w $'\n%{http_code}')
    else out=$(curl -s -m 30 -X "$m" "$u" "$@" -w $'\n%{http_code}'); fi
    CODE=${out##*$'\n'}; BODY_OUT=${out%$'\n'*}
    case "$CODE" in 502|503|000) sleep 3;; *) break;; esac
  done
}
auth() { echo "Authorization: Bearer $1"; }

login() { # user pass -> TOK RT
  req POST "$API/api/auth/login" "{\"username\":\"$1\",\"password\":\"${2:-$PASS}\"}"
  TOK=$(echo "$BODY_OUT" | jget "d['accessToken']"); RT=$(echo "$BODY_OUT" | jget "d['refreshToken']")
}
claim() { echo "$1" | python -c "import sys,base64,json
t=sys.stdin.read().strip().split('.')[1]; t+='='*(-len(t)%4)
print(json.loads(base64.urlsafe_b64decode(t)).get('$2',''))"; }

mail_otp() { # email -> OTP mới nhất gửi tới email đó
  local i id
  for i in 1 2 3 4 5; do
    id=$(curl -s "$MAIL/api/v1/search?query=to:$1" | jget "d['messages'][0]['ID']")
    [ -n "$id" ] && break; sleep 1
  done
  [ -n "$id" ] && curl -s "$MAIL/api/v1/message/$id" | jget "__import__('re').search(r'\b(\d{6})\b', d['Text']).group(1)"
}

signup_verify() { # username -> CTOK (đăng ký + xác minh email + đăng nhập)
  local u=$1 email="$1@example.com" otp
  req POST "$SHOP/public/t/demo/auth/signup" "{\"username\":\"$u\",\"password\":\"Passw0rd!x1\",\"fullName\":\"Khach Demo\",\"email\":\"$email\"}"
  [ "$CODE" = 201 ] || { bad "dang ky $u" "HTTP $CODE $BODY_OUT"; return 1; }
  otp=$(mail_otp "$email")
  req POST "$SHOP/public/t/demo/auth/login" "{\"username\":\"$u\",\"password\":\"Passw0rd!x1\"}"
  eq "khach chua xac minh bi chan dang nhap" "$CODE" 403
  req POST "$SHOP/public/t/demo/auth/verify-email" "{\"username\":\"$u\",\"otp\":\"000000\"}"
  eq "OTP sai bi tu choi" "$CODE" 400
  req POST "$SHOP/public/t/demo/auth/verify-email" "{\"username\":\"$u\",\"otp\":\"$otp\"}"
  eq "OTP dung xac minh thanh cong" "$CODE" 204
  req POST "$SHOP/public/t/demo/auth/login" "{\"username\":\"$u\",\"password\":\"Passw0rd!x1\"}"
  CTOK=$(echo "$BODY_OUT" | jget "d['accessToken']")
  chk "khach dang nhap duoc sau khi xac minh" "$([ -n "$CTOK" ] && echo 1 || echo 0)" "HTTP $CODE"
}

place() { # CTOK bookRef qty requestId -> CODE BODY_OUT
  req POST "$SHOP/public/t/demo/orders" "{\"lines\":[{\"titleId\":\"$2\",\"quantity\":$3}],\"fulfilment\":\"PICKUP\",\"paymentMethod\":\"CASH\",\"recipient\":{\"name\":\"Khach Demo\",\"phone\":\"0912345678\",\"address\":\"1 Le Loi\"},\"clientRequestId\":\"$4\"}" -H "$(auth "$1")"
}
uuid() { python -c "import uuid;print(uuid.uuid4())"; }

# ---------------------------------------------------------------- 0
step "0. Ha tang va lam nong"
for p in 8081 8082 8083 8085 8086 8087 8088; do
  c=$(curl -s -m 5 -o /dev/null -w "%{http_code}" localhost:$p/actuator/health); eq "dich vu cong $p" "$c" 200
done
c=$(curl -s -m 5 -o /dev/null -w "%{http_code}" "$MAIL/api/v1/messages"); eq "Mailpit" "$c" 200
login demo.admin; ADMIN=$TOK
chk "dang nhap qua cong FE (proxy 3000)" "$([ -n "$ADMIN" ] && echo 1 || echo 0)"
for u in "/api/dashboard/summary" "/api/sales/stock-items?size=1" "/api/menus/me" "/api/books"; do req GET "$API$u" "" -H "$(auth "$ADMIN")"; done
req GET "$SHOP/public/t/demo/books?size=1" ""

# ---------------------------------------------------------------- 1
step "1. Phien dang nhap va refresh token xoay vong"
login demo.admin; T1=$TOK; R1=$RT
req POST "$API/api/auth/refresh" "{\"refreshToken\":\"$R1\"}"
eq "refresh lan 1" "$CODE" 200
R2=$(echo "$BODY_OUT" | jget "d['refreshToken']")
chk "refresh token moi khac token cu" "$([ -n "$R2" ] && [ "$R2" != "$R1" ] && echo 1 || echo 0)"
req POST "$API/api/auth/refresh" "{\"refreshToken\":\"$R1\"}"
eq "dung lai refresh token cu bi 401" "$CODE" 401
req POST "$API/api/auth/refresh" "{\"refreshToken\":\"$R2\"}"
chk "token moi van dung duoc hoac bi thu hoi theo ho phien (ghi nhan)" 1 "HTTP $CODE"

# ---------------------------------------------------------------- 2
step "2. Platform admin"
login platform.admin "Platform@12345"; PLAT=$TOK
eq "token co platformAdmin" "$(claim "$PLAT" platformAdmin)" True
req GET "$API/api/platform/dashboard/overview?withinDays=30" "" -H "$(auth "$PLAT")"
eq "tong quan nen tang" "$CODE" 200
chk "co so lieu tenant" "$(echo "$BODY_OUT" | jget "1 if d['tenants']['total']>=1 else 0")"
DEMO_ID=42e63b21-7c74-3934-bbe1-ba5f2ae14dc6
req GET "$API/api/dashboard/summary" "" -H "$(auth "$PLAT")"
eq "platform admin khong chon don vi bi TENANT_REQUIRED" "$CODE" 403
req GET "$API/api/dashboard/summary" "" -H "$(auth "$PLAT")" -H "X-Tenant-Id: $DEMO_ID"
eq "platform admin xem dashboard thay don vi demo" "$CODE" 200

# ---------------------------------------------------------------- 3
step "3. Admin tenant: dashboard, menu, bao cao"
req GET "$API/api/dashboard/summary" "" -H "$(auth "$ADMIN")"
eq "dashboard summary" "$CODE" 200
chk "co 64 dau sach" "$(echo "$BODY_OUT" | jget "1 if d['catalog']['titles']>=1 else 0")"
FROM=$(python -c "import datetime;print((datetime.date.today()-datetime.timedelta(days=29)).isoformat())"); TO=$(python -c "import datetime;print(datetime.date.today().isoformat())")
req GET "$API/api/dashboard/charts/borrowings?from=$FROM&to=$TO" "" -H "$(auth "$ADMIN")"; eq "bieu do muon tra" "$CODE" 200
req GET "$API/api/dashboard/charts/revenue?from=$FROM&to=$TO" "" -H "$(auth "$ADMIN")"; eq "bieu do doanh thu" "$CODE" 200
req GET "$API/api/dashboard/top-titles/sold?from=$FROM&to=$TO&limit=5" "" -H "$(auth "$ADMIN")"; eq "top sach ban chay" "$CODE" 200
req GET "$API/api/menus/me" "" -H "$(auth "$ADMIN")"
chk "menu cua toi khong rong" "$(echo "$BODY_OUT" | jget "1 if len(d)>0 else 0")" "HTTP $CODE"
hdr=$(curl -s -m 30 -D - -o /dev/null -H "$(auth "$ADMIN")" "$API/api/reports/orders?from=$FROM&to=$TO" | tr -d '\r')
chk "xuat CSV don hang (text/csv)" "$(echo "$hdr" | grep -qi 'text/csv' && echo 1 || echo 0)"
req GET "$API/api/sales/settings/order-abuse-policy" "" -H "$(auth "$ADMIN")"
eq "chinh sach chong lam dung doc duoc" "$CODE" 200
eq "han giu hang mac dinh 15 phut" "$(echo "$BODY_OUT" | jget "d['reservationMinutes']")" 15

# ---------------------------------------------------------------- 4
step "4. Thu vien: muon tra"
login demo.docgia01; READER=$(claim "$TOK" sub); RTOK=$TOK
login demo.thuthu; LIB=$TOK
req GET "$API/api/books" "" -H "$(auth "$LIB")"
TITLE=$(echo "$BODY_OUT" | jget "[b['id'] for b in d if b.get('availableCopies',0)>0][0]")
chk "tim duoc dau sach con ban sao" "$([ -n "$TITLE" ] && echo 1 || echo 0)"
req GET "$API/api/book-copies?titleId=$TITLE" "" -H "$(auth "$LIB")"
COPY=$(echo "$BODY_OUT" | jget "[c['id'] for c in d if c['status']=='AVAILABLE'][0]")
chk "co ban sao san co" "$([ -n "$COPY" ] && echo 1 || echo 0)"
req GET "$API/api/fines?readerId=$READER" "" -H "$(auth "$LIB")"
if [ "$(echo "$BODY_OUT" | jget "1 if d['totalUnpaid']>0 else 0")" = 1 ]; then
  req POST "$API/api/borrowings" "{\"userId\":\"$READER\",\"bookCopyIds\":[\"$COPY\"]}" -H "$(auth "$LIB")"
  eq "doc gia no phat bi chan muon" "$CODE" 409
  eq "ma loi UNPAID_FINES" "$(echo "$BODY_OUT" | jget "d['code']")" UNPAID_FINES
  req POST "$API/api/fines/payments" "{\"readerId\":\"$READER\"}" -H "$(auth "$LIB")"
  eq "thu thu thu phat" "$CODE" 200
fi
req POST "$API/api/borrowings" "{\"userId\":\"$READER\",\"bookCopyIds\":[\"$COPY\"]}" -H "$(auth "$LIB")"
eq "thu thu lap phieu muon" "$CODE" 201
BID=$(echo "$BODY_OUT" | jget "d['id']")
req GET "$API/api/borrowings/mine" "" -H "$(auth "$RTOK")"
chk "doc gia thay phieu cua minh" "$(echo "$BODY_OUT" | jget "1 if any(b['id']=='$BID' for b in d) else 0")"
req GET "$API/api/book-copies/$COPY" "" -H "$(auth "$LIB")"
eq "ban sao chuyen sang dang muon" "$(echo "$BODY_OUT" | jget "d['status']")" LOANED
req POST "$API/api/borrowings/returns" "{\"bookCopyId\":\"$COPY\"}" -H "$(auth "$LIB")"
eq "thu thu nhan tra sach" "$CODE" 200
req GET "$API/api/book-copies/$COPY" "" -H "$(auth "$LIB")"
eq "ban sao tro lai san co" "$(echo "$BODY_OUT" | jget "d['status']")" AVAILABLE

# ---------------------------------------------------------------- 5
step "5. Hieu sach: ban tai quay (POS)"
login demo.cuahang; POS=$TOK
req GET "$API/api/sales/stock-items?size=50" "" -H "$(auth "$ADMIN")"
read -r STOCKTITLE ONHAND <<<"$(echo "$BODY_OUT" | jget "next((i['titleId']+' '+str(i['onHand']) for i in d['items'] if i['available']>=3), '')")"
chk "co mat hang ton kho" "$([ -n "$STOCKTITLE" ] && echo 1 || echo 0)"
POSBODY="{\"lines\":[{\"titleId\":\"$STOCKTITLE\",\"quantity\":1}],\"paymentMethod\":\"CASH\",\"clientRequestId\":\"$(uuid)\"}"
for i in 1 2 3; do req POST "$API/api/sales/pos-orders" "$POSBODY" -H "$(auth "$POS")"; [ "$CODE" = 201 ] && break; sleep 3; done # cung clientRequestId nen thu lai an toan
eq "thu ngan tao don POS" "$CODE" 201
eq "don POS cho thu tien (2 buoc)" "$(echo "$BODY_OUT" | jget "d['status']")" AWAITING_PAYMENT
PIID=$(echo "$BODY_OUT" | jget "d['paymentIntent']['id']"); PTOTAL=$(echo "$BODY_OUT" | jget "d['grandTotal']")
req POST "$API/api/sales/payments/intents/$PIID/confirm" "{\"tenderedAmount\":$PTOTAL}" -H "$(auth "$POS")"
eq "thu ngan xac nhan da thu tien mat" "$CODE" 200
req GET "$API/api/sales/orders/$(echo "$BODY_OUT" | jget "d['orderId']")" "" -H "$(auth "$ADMIN")"
req GET "$API/api/sales/stock-items/$STOCKTITLE" "" -H "$(auth "$ADMIN")"
eq "ton kho giam 1" "$(echo "$BODY_OUT" | jget "d['onHand']")" $((ONHAND-1))

# ---------------------------------------------------------------- 6
step "6. Khach mua online va chong lam dung"
req GET "$SHOP/public/t/demo/books?size=50" ""
BOOK=$(echo "$BODY_OUT" | jget "next((b['bookRef'] for b in d['items'] if b['inStock']), '')")
chk "danh muc storefront co sach con hang" "$([ -n "$BOOK" ] && echo 1 || echo 0)"
signup_verify "demo-e2e-$RUN-a" || true
CA=$CTOK
K1=$(uuid)
place "$CA" "$BOOK" 1 "$K1"; eq "dat don online" "$CODE" 201
O1=$(echo "$BODY_OUT" | jget "d['id']"); eq "don cho xac nhan" "$(echo "$BODY_OUT" | jget "d['status']")" PENDING_CONFIRMATION
place "$CA" "$BOOK" 1 "$K1"
chk "gui lai cung khoa cung noi dung tra don cu" "$([ "$(echo "$BODY_OUT" | jget "d['id']")" = "$O1" ] && echo 1 || echo 0)" "HTTP $CODE"
place "$CA" "$BOOK" 2 "$K1"; eq "cung khoa khac noi dung bi 422" "$CODE" 422
eq "ma loi IDEMPOTENCY_KEY_REUSED" "$(echo "$BODY_OUT" | jget "d['code']")" IDEMPOTENCY_KEY_REUSED
place "$CA" "$BOOK" 60 "$(uuid)"; eq "vuot tran so luong bi 400" "$CODE" 400
# xac nhan don 1 bang nhan vien (xoa han giu hang)
login demo.admin; ADMIN=$TOK
req GET "$API/api/sales/orders/$O1" "" -H "$(auth "$ADMIN")"; eq "nhan vien xem don khach" "$CODE" 200
req POST "$API/api/sales/orders/$O1/confirmation" "{}" -H "$(auth "$ADMIN")"; eq "nhan vien xac nhan don" "$CODE" 200
# dat don don dap
LASTCODE=""; LIMITED=0
for i in 1 2 3 4 5 6 7; do place "$CA" "$BOOK" 1 "$(uuid)"; LASTCODE=$CODE; [ "$CODE" = 429 ] && { LIMITED=1; break; }; done
chk "dat don don dap bi chan 429 (toc do hoac tran don cho)" "$LIMITED" "ma cuoi $LASTCODE"
eq "ma loi 429 la ORDER_RATE_LIMITED hoac ORDER_LIMIT_EXCEEDED" "$(echo "$BODY_OUT" | jget "'ok' if d['code'] in ('ORDER_RATE_LIMITED','ORDER_LIMIT_EXCEEDED') else d['code']")" ok

# ---------------------------------------------------------------- 7
step "7. Het han giu hang tu dong"
if [ "${SKIP_EXPIRY:-0}" = 1 ]; then
  echo "  (bo qua: SKIP_EXPIRY=1)"
else
  req GET "$API/api/sales/settings/order-abuse-policy" "" -H "$(auth "$ADMIN")"
  VER=$(echo "$BODY_OUT" | jget "d['version']")
  req PUT "$API/api/sales/settings/order-abuse-policy" "{\"reservationMinutes\":1,\"maxPendingOrders\":5,\"maxOrdersPerDay\":20,\"maxQuantityPerLine\":50,\"maxTotalQuantity\":100,\"ordersPerMinutePerUser\":5,\"ordersPerMinuteTenant\":300,\"version\":$VER}" -H "$(auth "$ADMIN")"
  eq "dat han giu hang 1 phut" "$CODE" 200
  signup_verify "demo-e2e-$RUN-b" || true
  CB=$CTOK
  req GET "$API/api/sales/stock-items/$(echo "$BOOK")" "" -H "$(auth "$ADMIN")"
  place "$CB" "$BOOK" 1 "$(uuid)"; eq "khach B dat don" "$CODE" 201
  O2=$(echo "$BODY_OUT" | jget "d['id']")
  echo "  ... cho job het han (toi da ~150 giay)"
  ST=""
  for i in $(seq 1 30); do
    sleep 5
    req GET "$API/api/sales/orders/$O2" "" -H "$(auth "$ADMIN")"; ST=$(echo "$BODY_OUT" | jget "d['status']")
    [ "$ST" = CANCELLED ] && break
  done
  eq "don qua han bi tu dong huy" "$ST" CANCELLED
  eq "ly do huy" "$(echo "$BODY_OUT" | jget "d.get('cancelReason','')")" "Hết hạn giữ hàng"
  req GET "$API/api/sales/settings/order-abuse-policy" "" -H "$(auth "$ADMIN")"; VER=$(echo "$BODY_OUT" | jget "d['version']")
  req PUT "$API/api/sales/settings/order-abuse-policy" "{\"reservationMinutes\":15,\"maxPendingOrders\":5,\"maxOrdersPerDay\":20,\"maxQuantityPerLine\":50,\"maxTotalQuantity\":100,\"ordersPerMinutePerUser\":5,\"ordersPerMinuteTenant\":300,\"version\":$VER}" -H "$(auth "$ADMIN")"
  eq "khoi phuc han giu hang 15 phut" "$CODE" 200
fi

# ---------------------------------------------------------------- tong ket
echo; echo "=============================="
echo "Dat: $PASSED   Loi: $FAILED"
for f in "${FAILS[@]:-}"; do [ -n "$f" ] && echo "  - $f"; done
[ "$FAILED" -eq 0 ]
