// Script MỘT LẦN (mongosh): gán tenant mặc định cho bản ghi audit cũ trong collection raw_events (LOG_DB).
// Chạy:  mongosh "<uri>/<LOG_DB>" infrastructure/mongo/backfill-tenant-id.js
// Bản ghi cũ: _id = eventId, không có tenantId. Bản ghi mới: _id = "{tenantId}:{eventId}" (MongoRawEventArchive).
// Idempotent: chỉ xử lý document chưa có tenantId; chạy lại không đổi gì.
// Lưu ý: _id không sửa tại chỗ được nên chèn bản mới rồi xóa bản cũ. Hãy thử trên bản sao dữ liệu trước.

const DEFAULT_TENANT_ID = "00000000-0000-0000-0000-0000000000d1";
const coll = db.getCollection("raw_events");

let migrated = 0;
coll.find({ tenantId: { $exists: false } }).forEach((old) => {
  const eventId = old.eventId ? String(old.eventId) : String(old._id);
  const newId = DEFAULT_TENANT_ID + ":" + eventId;
  const doc = Object.assign({}, old, { _id: newId, tenantId: DEFAULT_TENANT_ID, eventId: eventId });
  // Upsert theo _id mới: nếu đã có bản mới (redeliver sau khi đổi code) thì giữ bản mới.
  coll.updateOne({ _id: newId }, { $setOnInsert: doc }, { upsert: true });
  coll.deleteOne({ _id: old._id });
  migrated++;
});

// Index phục vụ truy vấn theo tenant (idempotent).
coll.createIndex({ tenantId: 1, eventId: 1 });

print("backfill-tenant-id: đã chuyển " + migrated + " bản ghi sang tenant " + DEFAULT_TENANT_ID);
