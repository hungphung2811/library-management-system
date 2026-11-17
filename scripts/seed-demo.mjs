// Nạp dữ liệu demo cho tenant mặc định qua lms-api (đi đúng đường nghiệp vụ: phân quyền, RLS, sự kiện).
// Chạy: node scripts/seed-demo.mjs   (cần lms-api 8085 + các service đang chạy)
// Biến: API, SEED_USER, SEED_PASSWORD, SEED_TENANT (mã đơn vị muốn nạp; bỏ trống = đơn vị admin dùng gần nhất),
//       SEED_TAG (hậu tố chống trùng mã thẻ/mã vạch/tên đăng nhập giữa các đơn vị; bỏ trống cho đơn vị đầu tiên),
//       SEED_ISBN_DIGIT (1 chữ số, khác nhau giữa các đơn vị vì ISBN không được trùng).
// Chạy lại được: bản ghi đã có (trùng tên đăng nhập, mã vạch, đầu sách) được bỏ qua, không tạo trùng.
// Module Bán hàng cần được bật cho tenant (platform admin: PUT /api/platform/tenants/{id}/modules/SALES).

const API = process.env.API ?? "http://localhost:8085";
const USER = process.env.SEED_USER ?? "admin";
const PASSWORD = process.env.SEED_PASSWORD ?? "Admin@12345";
const TENANT = process.env.SEED_TENANT || undefined;
const TAG = process.env.SEED_TAG ?? "";
const ISBN_DIGIT = process.env.SEED_ISBN_DIGIT ?? "1";
const arr = (d) => (Array.isArray(d) ? d : (d?.items ?? []));

let token = "";
const stat = { ok: 0, skip: 0, fail: 0 };

async function call(method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let data;
  try { data = text ? JSON.parse(text) : undefined; } catch { data = text; }
  return { ok: res.ok, status: res.status, data };
}

/** Gọi và ghi kết quả; lỗi trùng (409) tính là bỏ qua, lỗi khác báo và đi tiếp. */
async function step(label, method, path, body) {
  const r = await call(method, path, body);
  if (r.ok) stat.ok++;
  else if (r.status === 409) stat.skip++;
  else {
    stat.fail++;
    console.warn(`  ✗ ${label}: ${r.status} ${typeof r.data === "object" ? (r.data?.code ?? r.data?.message ?? "") : r.data}`);
  }
  return r;
}

const login = await call("POST", "/api/auth/login", { username: USER, password: PASSWORD, tenantCode: TENANT });
if (!login.ok) throw new Error(`Đăng nhập thất bại (${login.status}). Kiểm tra SEED_USER/SEED_PASSWORD và lms-api.`);
token = login.data.accessToken;
console.log(`Đăng nhập ${USER}, đơn vị ${TENANT ?? "(mặc định của tài khoản)"}`);

// ---------- Đầu sách và bản sao ----------
const TITLES = [
  ["Lập trình Java cơ bản", "Nguyễn Minh Khoa", 95000],
  ["Cấu trúc dữ liệu và giải thuật", "Trần Quốc Bảo", 120000],
  ["Cơ sở dữ liệu quan hệ", "Lê Thị Thu Hà", 110000],
  ["Mạng máy tính", "Phạm Đức Long", 135000],
  ["Kinh tế vi mô", "Đỗ Thanh Tùng", 98000],
  ["Nguyên lý marketing", "Vũ Hải Yến", 105000],
  ["Kế toán tài chính", "Hoàng Gia Huy", 125000],
  ["Lịch sử Việt Nam giản yếu", "Bùi Văn Sơn", 88000],
  ["Địa lý các vùng miền", "Ngô Thùy Linh", 76000],
  ["Truyện ngắn chọn lọc", "Đặng Mai Phương", 69000],
  ["Thơ Việt Nam thế kỷ XX", "Lương Anh Dũng", 82000],
  ["Tiếng Anh giao tiếp hằng ngày", "Phan Khánh Vy", 90000],
  ["Kỹ năng làm việc nhóm", "Trương Hoàng Nam", 72000],
  ["Khoa học vũ trụ cho thiếu nhi", "Đinh Bảo Ngọc", 85000],
  ["Nấu ăn gia đình", "Lâm Thu Trang", 99000],
  ["Quản trị dự án phần mềm", "Cao Thế Vinh", 145000],
];

const existing = await call("GET", "/api/books?page=0&size=200");
const byName = new Map(arr(existing.data).map((t) => [t.name, t]));
// ISBN 13 số: 978604 + chữ số đơn vị + 00 + số thứ tự (2 số) + 00
const isbnOf = (n) => `978604${ISBN_DIGIT}00${String(n + 1).padStart(2, "0")}00`;
const titleIds = [];
for (const [i, [name, author, coverPrice]] of TITLES.entries()) {
  const isbn = isbnOf(i);
  let t = byName.get(name);
  if (!t) {
    const r = await step(`đầu sách ${name}`, "POST", "/api/books", { name, author, isbn, coverPrice });
    t = r.data;
  } else stat.skip++;
  if (t?.id) titleIds.push({ id: t.id, name, price: coverPrice, n: i });
}
console.log(`Đầu sách: ${titleIds.length}/${TITLES.length}`);

const copyIds = new Map(); // titleId -> [copyId]
for (const t of titleIds) {
  const list = [];
  for (let k = 1; k <= 3; k++) {
    const barcode = `DEMO${TAG}${String(t.n + 1).padStart(3, "0")}${k}`;
    const r = await step(`bản sao ${barcode}`, "POST", "/api/book-copies", { titleId: t.id, barcode });
    if (r.ok && r.data?.id) list.push(r.data.id);
    else if (r.status === 409) {
      const f = await call("GET", `/api/book-copies/by-barcode/${barcode}`);
      if (f.ok && f.data?.id) list.push(f.data.id);
    }
  }
  copyIds.set(t.id, list);
}
console.log(`Bản sao: ${[...copyIds.values()].flat().length}`);

// ---------- Độc giả ----------
const READERS = [
  ["doc.gia01", "Nguyễn Văn An", "STUDENT"],
  ["doc.gia02", "Trần Thị Bình", "STUDENT"],
  ["doc.gia03", "Lê Hoàng Cường", "STUDENT"],
  ["doc.gia04", "Phạm Thu Dung", "LECTURER"],
  ["doc.gia05", "Võ Minh Em", "STUDENT"],
  ["doc.gia06", "Đặng Quốc Phong", "LECTURER"],
];
const expiry = new Date(Date.now() + 365 * 86_400_000).toISOString().slice(0, 10);
const readers = [];
for (const [base, fullName, readerType] of READERS) {
  const username = TAG + base;
  const email = `${username}@example.vn`;
  let u = arr((await call("GET", `/api/users?keyword=${encodeURIComponent(username)}&page=0&size=5`)).data).find((x) => x.username === username);
  if (!u) {
    u = (await step(`người dùng ${username}`, "POST", "/api/users", { username, fullName, email })).data;
    if (u?.id) {
      await step(`mật khẩu ${username}`, "POST", "/api/auth/credentials", { userId: u.id, username, password: "Demo@12345", mustChangePassword: false });
      await step(`thẻ ${username}`, "POST", `/api/users/${u.id}/reader-profile`, { cardNo: `TV${TAG}${String(readers.length + 1).padStart(5, "0")}`, fullName, email, readerType, cardExpiryDate: expiry });
    }
  } else stat.skip++;
  if (u?.id) readers.push(u);
}
console.log(`Độc giả: ${readers.length}/${READERS.length} (mật khẩu demo: Demo@12345)`);

// ---------- Mượn trả ----------
// Phiếu mượn mới chỉ tạo khi chưa có phiếu nào của độc giả demo, để chạy lại không nhân đôi.
const flat = [...copyIds.values()];
for (let i = 0; i < readers.length; i++) {
  const have = await call("GET", `/api/borrowings/search?userId=${readers[i].id}&page=0&size=1`);
  if (arr(have.data).length > 0) { stat.skip++; continue; }
  const ids = [flat[i % flat.length]?.[0], flat[(i + 3) % flat.length]?.[1]].filter(Boolean);
  if (ids.length) await step(`phiếu mượn ${readers[i].username}`, "POST", "/api/borrowings", { userId: readers[i].id, bookCopyIds: ids });
}
console.log("Mượn trả: đã tạo phiếu mượn cho độc giả demo");

// ---------- Bán hàng (cần module SALES) ----------
const sup = await call("GET", "/api/sales/suppliers?page=0&size=50");
if (sup.status === 403 || sup.status === 404) {
  console.warn("Bỏ qua Bán hàng: tenant chưa bật module SALES (hoặc token chưa có quyền). Bật module rồi đăng nhập lại và chạy lại.");
} else {
  const SUPPLIERS = [
    ["Nhà phân phối Sách Việt", "0281234567", "lienhe@sachviet.example.vn", "Số 10 Đường Chính, Quận Trung Tâm"],
    ["Công ty Phát hành Tri Thức", "0287654321", "kinhdoanh@trithuc.example.vn", "Số 25 Đường Thương Mại, Quận Phía Nam"],
    ["Nhà sách Ánh Dương", "0289998888", "info@anhduong.example.vn", "Số 5 Đường Văn Hóa, Quận Phía Bắc"],
  ];
  const have = new Map(arr(sup.data).map((s) => [s.name, s]));
  const supIds = [];
  for (const [name, phone, email, address] of SUPPLIERS) {
    let s = have.get(name);
    if (!s) s = (await step(`nhà cung cấp ${name}`, "POST", "/api/sales/suppliers", { name, phone, email, address, note: null })).data;
    else stat.skip++;
    if (s?.id) supIds.push(s.id);
  }

  const stock = titleIds.slice(0, 12);
  for (const t of stock) await step(`tồn kho ${t.name}`, "POST", "/api/sales/stock-items", { titleId: t.id, sellingPrice: Math.round(t.price * 1.1 / 1000) * 1000, lowStockThreshold: 5 });

  const items = await call("GET", "/api/sales/stock-items?page=0&size=100");
  const onHand = new Map(arr(items.data).map((i) => [i.titleId, i.onHand ?? 0]));
  const need = stock.filter((t) => (onHand.get(t.id) ?? 0) === 0);
  if (need.length && supIds.length) {
    const r = await step("phiếu nhập kho", "POST", "/api/sales/goods-receipts", {
      supplierId: supIds[0],
      lines: need.map((t) => ({ titleId: t.id, quantity: 20 + (t.n % 4) * 5, unitCost: Math.round(t.price * 0.6 / 1000) * 1000 })),
      clientRequestId: crypto.randomUUID(),
    });
    if (r.ok && r.data?.id) await step("xác nhận phiếu nhập", "POST", `/api/sales/goods-receipts/${r.data.id}/confirmation`);
  } else if (!need.length) stat.skip++;

  const orders = await call("GET", "/api/sales/orders?page=0&size=1");
  if (arr(orders.data).length === 0) {
    const PAY = ["CASH", "BANK_TRANSFER", "CARD", "CASH", "CASH"];
    for (let i = 0; i < 5; i++) {
      await step(`đơn POS ${i + 1}`, "POST", "/api/sales/pos-orders", {
        lines: [{ titleId: stock[i].id, quantity: 1 + (i % 2) }, { titleId: stock[(i + 4) % stock.length].id, quantity: 1 }],
        paymentMethod: PAY[i],
        ...(i % 2 === 0 ? { walkIn: { name: ["Khách Lê", "Khách Trần", "Khách Phạm"][i / 2], phone: null } } : {}),
        clientRequestId: crypto.randomUUID(),
      });
    }
  } else stat.skip++;
  console.log(`Bán hàng: ${supIds.length} nhà cung cấp, ${stock.length} mặt hàng, phiếu nhập và đơn POS`);
}

console.log(`Xong: ${stat.ok} thành công, ${stat.skip} bỏ qua (đã có), ${stat.fail} lỗi.`);
process.exitCode = stat.fail > 0 ? 1 : 0;
