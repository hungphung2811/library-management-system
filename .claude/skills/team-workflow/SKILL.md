---
name: team-workflow
description: Điều phối đội subagent của dự án Library Management System phía backend (ba, sa, be-dev, tester, perf-tester, reporter) thành một quy trình từ yêu cầu đến báo cáo. Dùng khi người dùng giao một tính năng, sửa lỗi, đợt kiểm thử hoặc đánh giá cần từ hai vai trò trở lên, hoặc nói "chạy workflow", "giao cho team", "làm tính năng X từ A-Z", "dùng các agent", "điều phối", ngay cả khi không nêu tên skill. Skill này chạy trong phiên chính, vì subagent không gọi được subagent khác.
---

# Team workflow (backend)

Bạn là điều phối viên. Subagent không thể tự gọi subagent khác, nên chỉ phiên chính mới giao việc được. Hãy tự điều phối theo quy trình dưới đây.

Đội (định nghĩa ở `.claude/agents/`): `ba`, `sa`, `be-dev`, `tester`, `perf-tester`, `reporter`. Mỗi agent đã có quy tắc riêng; việc của bạn là chia việc, giao đúng thứ tự và kiểm tra kết quả.

Repo này chỉ có phần backend. Frontend ở repo anh em `../library-management-system-fe` (có đội `uiux`, `fe-dev` riêng). Việc cần sửa FE thì không giao ở đây: chốt hợp đồng API ở pha B, rồi báo người dùng mở phiên ở repo FE để làm tiếp.

## 1. Tiếp nhận và chọn vai trò

Đọc `CLAUDE.md`, `README.md` và các ADR liên quan trong `docs/decisions/` để hiểu ranh giới service, phân quyền động (ADR-0003) và migration (ADR-0004). Chọn đường đi theo loại yêu cầu, chỉ gọi vai trò thật sự cần:

| Yêu cầu | Đường đi |
|---|---|
| Tính năng backend mới | (sa) → be-dev → tester → (perf-tester) → reporter |
| Tính năng có phần FE | (sa) → be-dev chốt hợp đồng → báo người dùng chuyển sang repo FE → tester → reporter |
| Thay đổi kiến trúc (service mới, đổi ranh giới bounded context, Kafka/Outbox, nhiều database) | sa → be-dev → tester → reporter |
| Sửa lỗi | tester tái hiện → be-dev sửa → tester kiểm lại → reporter |
| Đánh giá hiệu năng | perf-tester → reporter |
| Đánh giá/thiết kế kiến trúc | sa → reporter |
| Chỉ cần báo cáo | reporter |

`(sa)` chỉ gọi khi tính năng đụng tới kiến trúc: thêm service hoặc module mới, đổi ranh giới giữa các context, thêm event Kafka, đổi luồng `lms-api` → service, thêm migration ảnh hưởng nhiều DB, hoặc cần chọn giữa các phương án có đánh đổi rõ. Thêm một endpoint hay use case nằm gọn trong khuôn sẵn có thì bỏ qua `sa`.

Nếu yêu cầu mơ hồ ở điểm làm thay đổi hướng đi, hỏi người dùng một lần trước khi giao việc; đừng để mỗi agent tự đoán khác nhau.
## 2. Các pha

**Pha BA. Lấy và làm rõ đề bài (khi yêu cầu còn mơ hồ).** Áp dụng cho tính năng mới hoặc đổi hành vi mà người dùng chỉ mô tả sơ lược. Việc hỏi người dùng do phiên chính làm (subagent không hỏi lại được); agent `ba` lo phân tích và viết đề bài. Cách chạy: giao `ba` brief gốc để nó đọc code và trả về danh sách câu hỏi kèm lựa chọn; phiên chính hỏi người dùng từng câu (AskUserQuestion); rồi giao lại `ba` kèm câu trả lời để viết đề bài. Các bước dưới đây là quy trình `ba` và phiên chính cùng theo. Bỏ qua khi yêu cầu đã rõ, hoặc là sửa lỗi/đánh giá/báo cáo.
1. **Đọc trước, hỏi sau**: xem `CLAUDE.md`, `README.md`, ADR, code liên quan để biết cái gì đã có. Đừng hỏi điều đọc code là biết.
2. **Hỏi từng câu một**, ưu tiên câu trắc nghiệm có 2-4 lựa chọn (dùng AskUserQuestion), kèm khuyến nghị của bạn ở lựa chọn đầu. Mỗi câu chỉ làm rõ một điểm. Thứ tự hỏi: mục đích và người dùng → phạm vi (làm gì, không làm gì) → quy tắc nghiệp vụ và ngoại lệ → phân quyền (role nào, khóa quyền) → dữ liệu/migration → tiêu chí nghiệm thu. Dừng hỏi khi đủ để viết đề bài, thường 3-6 câu.
3. **Nêu 2-3 hướng tiếp cận** khi có đánh đổi rõ, kèm khuyến nghị và lý do; để người dùng chọn.
4. **Trình bày đề bài từng phần ngắn** (mục tiêu, phạm vi, quy tắc nghiệp vụ, API/dữ liệu dự kiến, tiêu chí nghiệm thu, ngoài phạm vi), sau mỗi phần hỏi "đúng ý chưa" trước khi viết tiếp. Không tự thêm tính năng người dùng không nhắc (YAGNI).
5. **Chốt**: ghi đề bài vào `docs/requirements/<ngày YYYY-MM-DD>-<chủ-đề>.md` (tạo thư mục nếu chưa có), đưa đường dẫn này làm đầu vào cho `sa`/`be-dev`/`tester`. Người dùng duyệt xong mới sang pha tiếp theo.

**Pha 0. Kiến trúc (nếu đụng tới kiến trúc).** Giao `sa` ra phương án: quyết định chọn và lý do, phương án đã loại, ranh giới thành phần, luồng dữ liệu, rủi ro, phân việc. `sa` ghi vào `docs/decisions/` (ADR mới, số kế tiếp) hoặc `docs/architecture/`. Đưa đường dẫn file làm đầu vào cho pha sau thay vì dán lại. Nếu có câu hỏi mở làm đổi hướng, hỏi người dùng trước. `sa` chỉ ghi trong `docs/`, không sửa code.

**Pha A. Hợp đồng API (nếu có FE dùng).** Giao `be-dev` chốt endpoint ở `lms-api` (đường dẫn `/api/**`, record request/response, mã lỗi) và ghi vào `docs/api/`. Nhắc rõ khóa quyền `TênController/tênMethod` sinh ra từ controller nội bộ (ADR-0003) vì FE phải đồng bộ `packages/api/src/permissions.ts`. Tên controller/method đổi thì khóa đổi, role đã gán mất quyền; chỉ đổi khi cần.

**Pha B. Cài đặt.** Mặc định làm trực tiếp trên nhánh hiện tại, không dùng git worktree và không hỏi lại người dùng. Chỉ khi người dùng yêu cầu rõ (ví dụ "dùng worktree") thì tạo `git worktree add .worktrees/<chủ-đề> -b <loại>/<chủ-đề>` (thư mục `.worktrees/` đã nằm trong `.gitignore`; tên nhánh theo loại `feat`/`fix`/`chore`), rồi giao `be-dev`, `tester` làm việc trong thư mục đó (ghi đường dẫn tuyệt đối vào brief). Không tự xóa worktree; khi xong, hỏi người dùng muốn merge, giữ lại hay dọn. Giao `be-dev`. Công việc chia được theo service hoặc module không phụ thuộc nhau thì gọi nhiều `be-dev` song song trong cùng một lượt (nhiều lời gọi Agent trong một tin nhắn), mỗi agent một phạm vi thư mục riêng. Việc phụ thuộc nhau (`shared-libraries` trước, service sau; migration trước, code dùng schema sau) thì giao tuần tự.

**Pha C. Kiểm thử.** Giao `tester`; thêm `perf-tester` khi liên quan hiệu năng (tìm kiếm danh mục, hàng đợi đặt trước, truy vấn nhiều DB). Hai agent này chỉ báo lỗi, không sửa code. Có lỗi thì đưa danh sách cho `be-dev` sửa rồi kiểm lại. Tối đa 2 vòng sửa; sau đó dừng và báo người dùng những lỗi còn mở thay vì lặp mãi.

**Pha D. Báo cáo.** Giao `reporter` tổng hợp. Reporter không thấy hội thoại và bị cấm bịa số liệu, nên phải đưa cho nó sự thật: việc đã làm, file đã đổi, kết quả test, và những gì chưa kiểm chứng cùng lý do.

## 3. Viết brief cho mỗi lần giao việc

Agent bắt đầu từ trạng thái trống, không thấy hội thoại này. Brief tốt gồm:
- **Mục tiêu** và lý do, nêu ADR hoặc quy tắc nghiệp vụ liên quan.
- **Đầu vào**: file cần đọc, kết quả từ agent trước (nguyên văn hoặc đường dẫn).
- **Phạm vi**: module/thư mục được sửa, thứ không được đụng.
- **Tiêu chí hoàn thành**: kiểm tra được bằng lệnh hay hành vi cụ thể.
- **Cần trả về**: file đã đổi, kết quả kiểm tra, phần chưa kiểm chứng.

## 4. Kiểm tra kết quả, đừng tin báo cáo suông

Sau mỗi agent trả về, đối chiếu với tiêu chí đã giao. Với `sa`, kiểm tra thiết kế có nêu phương án đã loại và lý do không, có khớp khuôn code thật không (đọc vài file được trích dẫn), và rủi ro chưa kiểm chứng có được ghi ⚠️ không. Việc có thể kiểm bằng công cụ thì tự kiểm: đọc file đã đổi, chạy `mvn -pl <module> -am test` (luôn có `-am`). Agent báo thiếu hoặc lỗi thì giao lại kèm thông tin cụ thể, không bỏ qua.

Hạn chế môi trường cần nói thẳng, không che:
- Máy không có `mvn` toàn cục; dùng Maven trong scratchpad. Không chạy được thì ghi ⚠️ chưa kiểm chứng, đừng ghi ✅.
- Integration test `*IT` cần Docker (Testcontainers) và mặc định bị Surefire bỏ qua; chạy bằng `-Dtest=<TênIT>`. Với Docker 29 thêm `-DargLine=-Dapi.version=1.44`.
- `OracleIT` chỉ chạy khi có `ORACLE_TEST_URL/USERNAME/PASSWORD`.
- Migration mới là file `V<n>__*.sql` trong `services/system-service/src/main/resources/db/migration/<target>/`; kiểm tra số `n` kế tiếp, không sửa file đã chạy.

## 5. Quy tắc chung

- Không `git commit`/`git push`, không đụng secret hay `.env`. Cần commit thì hỏi người dùng và theo quy ước commit trong `CLAUDE.md`.
- Working tree thường có thay đổi chưa commit của người/phiên khác: sửa có mục tiêu, đọc lại file trước khi sửa, không ghi đè cả file, không revert.
- Thay đổi hợp đồng API phải ghi ở `docs/api/` và báo người dùng để đồng bộ phía FE.
- Trả lời người dùng bằng tiếng Việt, ngắn gọn.

## 6. Báo cáo cho người dùng

Dùng bộ icon chuẩn: ✅ hoàn thành đã kiểm chứng · 🔄 đang xử lý · ⏳ chờ · ❌ lỗi · ⚠️ chưa kiểm chứng · 🚫 bị chặn · ⏭️ bỏ qua · ℹ️ ghi chú. Mỗi mục một icon và phải đúng thực tế. Cuối cùng nêu: ai đã làm gì, kết quả, lỗi còn mở, và việc người dùng cần quyết định.

Ví dụ định dạng tiến độ:

```
✅ sa: ADR-0006 về hàng đợi đặt trước
✅ be-dev: GET /api/books/available (test đơn vị đạt)
⚠️ be-dev: BorrowingIT chưa chạy được vì không có Docker
🔄 be-dev: migration V12 cho circulation
⏳ tester, reporter
```
