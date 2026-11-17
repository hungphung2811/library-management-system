# TASK: REFACTOR & REDESIGN EXISTING CMS THEME – MODERN SAAS ADMIN

## 1. Vai trò

Bạn là Senior Frontend Engineer kiêm UI/UX Designer, có kinh nghiệm thiết kế Enterprise Admin Dashboard, SaaS CMS và Design System.

Nhiệm vụ của bạn là phân tích và nâng cấp toàn bộ giao diện theme của project CMS hiện tại.

**Tech stack hiện tại:**
- ReactJS
- Vite
- TypeScript (nếu project đang sử dụng)
- Ant Design
- Tailwind CSS

Đây là project đã tồn tại và đang hoạt động. Không được khởi tạo project mới.

## 2. Mục tiêu thiết kế

Hiện tại giao diện CMS đang có vấn đề:
- Màu sắc đơn điệu, thiếu điểm nhấn.
- UI chưa hiện đại, chưa tạo cảm giác chuyên nghiệp.
- Spacing giữa các component chưa đồng nhất.
- Các thành phần Button, Input, Select, Table, Modal, Drawer chưa có design language thống nhất.
- Dashboard và các trang quản trị chưa có tính thẩm mỹ cao.
- Một số component có kích thước lớn, chiếm nhiều diện tích sử dụng.

Hãy redesign theo phong cách:

**Modern SaaS Admin – Clean, Compact & Professional**

Tham khảo tinh thần thiết kế của các sản phẩm SaaS Admin Dashboard hiện đại.

Ưu tiên:
1. UI nhỏ gọn, tối ưu không gian.
2. Màu sắc có điểm nhấn nhưng không lạm dụng.
3. Typography rõ ràng, dễ đọc.
4. Các component đồng nhất.
5. Trải nghiệm quản trị nhanh, thuận tiện.
6. Dễ bảo trì và mở rộng.

## 3. Design System

### 3.1. Color palette

Sử dụng Indigo làm màu thương hiệu chính, kết hợp Violet và Slate.

Primary:
- Primary: #4338CA
- Primary Hover: #4F46E5
- Primary Active: #3730A3
- Primary Soft: #EEF2FF

Background:
- Layout Background: #F8FAFC
- Surface: #FFFFFF
- Secondary Surface: #F1F5F9

Text:
- Primary: #1E293B
- Secondary: #64748B
- Muted: #94A3B8

Border:
- Default: #E2E8F0
- Soft: #F1F5F9

Status:
- Success: #16A34A
- Warning: #D97706
- Error: #DC2626
- Info: #0284C7

Không sử dụng quá nhiều gradient. Chỉ dùng gradient cho những khu vực thực sự cần tạo điểm nhấn như Dashboard KPI hoặc promotional card.

### 3.2. Typography

Sử dụng font Inter.

Quy chuẩn:
- Page title: 20px, font-weight 600.
- Section title: 15px, font-weight 600.
- Body: 13px.
- Description: 12px.
- Label: 12px.
- Table content: 13px.

Đảm bảo typography có hierarchy rõ ràng.

### 3.3. Compact UI

Thiết kế theo hướng Compact UI.

Các kích thước mục tiêu:

| Component | Size |
|---|---|
| Sidebar | 224px |
| Header | 52px |
| Page padding | 20px |
| Button | 32px |
| Small Button | 28px |
| Input | 32px |
| Small Input | 28px |
| Table row | 40px |
| Card radius | 10px |
| Component radius | 7px |

Đây là kích thước mục tiêu, cần cân nhắc với cấu trúc component thực tế và khả năng responsive.

Không thu nhỏ đến mức gây khó thao tác.

## 4. Các khu vực cần redesign

### 4.1. Admin Layout

Redesign toàn bộ layout chính:

- Sidebar.
- Header.
- Main content.
- Breadcrumb.
- User profile.
- Notification.
- Navigation menu.

Sidebar:
- Nền trắng.
- Logo rõ ràng.
- Menu icon đồng nhất.
- Menu active sử dụng Primary Soft.
- Menu hover nhẹ.
- Hỗ trợ nested menu.
- Có trạng thái collapsed.
- Spacing nhỏ gọn.

Header:
- Chiều cao khoảng 52px.
- Có global search nếu hệ thống hỗ trợ.
- Notification.
- User avatar và dropdown.
- Có thể hỗ trợ theme switch nếu project đã có hoặc phù hợp với kiến trúc hiện tại.

Main content:
- Background #F8FAFC.
- Page padding thống nhất.
- Nội dung không bị tràn.
- Responsive tốt.

### 4.2. Dashboard

Thiết kế dashboard hiện đại:

- Welcome section.
- KPI cards.
- Analytics charts.
- Recent activities.
- Recent data.
- System status.

KPI cards:
- Background trắng.
- Border nhẹ.
- Icon có màu riêng.
- Số liệu nổi bật.
- Có thể thêm percentage change.
- Không sử dụng shadow quá mạnh.

### 4.3. Data Management

Đây là khu vực quan trọng nhất của CMS.

Redesign:
- Table.
- Search.
- Filter.
- Pagination.
- Sorting.
- Bulk actions.
- Toolbar.
- Row actions.
- Empty state.
- Loading state.

Yêu cầu:
- Table compact.
- Header có background nhẹ.
- Row hover rõ ràng.
- Trạng thái sử dụng StatusBadge.
- Actions không chiếm quá nhiều diện tích.
- Search và filter được bố trí khoa học.
- Pagination gọn.
- Hạn chế các khoảng trắng không cần thiết.

Không thay đổi logic xử lý dữ liệu hiện tại.

### 4.4. Form

Chuẩn hóa:
- Input.
- Textarea.
- Select.
- DatePicker.
- Checkbox.
- Radio.
- Switch.
- Upload.
- Form validation.

Yêu cầu:
- Label rõ ràng.
- Khoảng cách giữa các field thống nhất.
- Required state dễ nhận biết.
- Error message dễ đọc.
- Form dài được chia thành section hợp lý.
- Footer action rõ ràng.

### 4.5. Modal và Drawer

Thiết kế lại:
- Modal.
- Confirm Dialog.
- Drawer.
- Detail Drawer.
- Edit Drawer.

Yêu cầu:
- Header rõ ràng.
- Body có spacing hợp lý.
- Footer action cố định khi nội dung dài nếu phù hợp.
- Border và radius đồng nhất.
- Hạn chế modal quá lớn.
- Drawer ưu tiên thao tác nhanh và xem chi tiết.

### 4.6. Các component khác

Chuẩn hóa thêm:
- Tabs.
- Dropdown.
- Tooltip.
- Popover.
- Breadcrumb.
- Tag.
- Badge.
- Alert.
- Notification.
- Empty.
- Skeleton.
- Loading.
- Progress.

## 5. Kiến trúc Theme

Trước tiên hãy kiểm tra kiến trúc theme hiện tại.

Nếu chưa có Design System rõ ràng, hãy tổ chức lại theo hướng:

src/
  theme/
    index.ts
    tokens.ts
    antd-theme.ts
    theme.css

  components/
    common/
      AppCard/
      AppButton/
      StatusBadge/
      PageHeader/
      DataToolbar/
      AppDrawer/
      AppEmpty/

    layout/
      AdminLayout/
      AdminSidebar/
      AdminHeader/
      PageContainer/

Không bắt buộc phải sử dụng chính xác cấu trúc trên nếu project hiện tại đã có kiến trúc tốt hơn.

### Nguyên tắc quan trọng

1. Ant Design Theme Token là nguồn cấu hình chính cho component Ant Design.
2. CSS Variables là nguồn token dùng chung cho custom CSS.
3. Tailwind sử dụng để layout, spacing, flex, grid và responsive.
4. Không tạo ra nhiều hệ thống màu sắc độc lập.
5. Không lạm dụng CSS override trực tiếp vào class nội bộ của Ant Design.
6. Tránh sử dụng !important nếu không thực sự cần thiết.
7. Không để style của các component xung đột nhau.

## 6. Quy trình thực hiện

Hãy thực hiện theo từng giai đoạn.

### Phase 1: Audit project

Đọc và phân tích:

- package.json.
- Cấu trúc src.
- Ant Design version.
- Tailwind CSS version.
- Cấu hình Vite.
- Global CSS.
- Theme hiện tại.
- Layout hiện tại.
- Các component dùng chung.
- Cách sử dụng Ant Design ConfigProvider.

Xác định:
- Những phần đang hoạt động tốt.
- Những phần có thể tái sử dụng.
- Những phần cần refactor.
- Những CSS đang bị trùng lặp.
- Những override có nguy cơ gây lỗi.

Không chỉnh sửa trước khi hiểu cấu trúc hiện tại.

### Phase 2: Build Design System

Xây dựng:
- Color tokens.
- Typography tokens.
- Spacing tokens.
- Border radius.
- Shadow.
- Ant Design theme configuration.
- Global CSS.
- Component styles.

Đảm bảo tương thích với phiên bản dependency hiện tại.

Không tự ý nâng cấp Ant Design hoặc Tailwind.

### Phase 3: Refactor Layout

Áp dụng theme mới cho:
- Sidebar.
- Header.
- Main content.
- Navigation.
- Breadcrumb.

Giữ nguyên:
- Routing.
- Permission.
- Authentication.
- Menu configuration.
- Các chức năng hiện có.

### Phase 4: Refactor UI Components

Áp dụng Design System cho:
- Table.
- Form.
- Button.
- Input.
- Select.
- Modal.
- Drawer.
- Tabs.
- Tag.
- Dropdown.
- Pagination.

Ưu tiên tái sử dụng component chung.

Không sửa business logic hoặc API contract.

### Phase 5: Apply vào các màn hình hiện có

Quét các trang CMS và áp dụng theme thống nhất.

Không chỉ tạo theme mới nhưng để các trang hiện tại tiếp tục sử dụng style cũ.

Đặc biệt kiểm tra:
- Các trang có nhiều Table.
- Các trang có nhiều Form.
- Các trang có nhiều Filter.
- Các trang sử dụng Modal/Drawer.
- Các trang có nhiều trạng thái dữ liệu.

### Phase 6: Verification

Sau khi hoàn thành:

1. Chạy TypeScript check.
2. Chạy ESLint nếu project có.
3. Chạy Vite build.
4. Kiểm tra các lỗi CSS.
5. Kiểm tra responsive.
6. Kiểm tra overflow.
7. Kiểm tra các component có style không đồng nhất.
8. Kiểm tra các chức năng cũ vẫn hoạt động.

Nếu project có MCP Playwright, hãy sử dụng để:
- Mở các màn hình chính.
- Chụp screenshot trước và sau.
- Kiểm tra UI thực tế.
- Kiểm tra Table, Form, Modal, Drawer.
- Kiểm tra thao tác người dùng.
- Kiểm tra responsive.
- Phát hiện các lỗi UI/UX.

Không chỉ đánh giá bằng code. Phải kiểm tra giao diện được render thực tế.

## 7. Những điều tuyệt đối không được làm

- Không tạo project mới.
- Không rewrite toàn bộ ứng dụng.
- Không thay đổi API.
- Không thay đổi business logic.
- Không thay đổi authentication.
- Không thay đổi permission.
- Không thay đổi routing.
- Không xóa các component đang được sử dụng.
- Không tự ý thay đổi dependency version.
- Không thay đổi cấu trúc dữ liệu.
- Không tạo một bộ theme chỉ hoạt động trên Dashboard.
- Không chỉ tạo CSS demo mà phải áp dụng vào các trang thực tế.

Nếu phát hiện vấn đề kiến trúc, hãy ưu tiên giải pháp ít ảnh hưởng nhất.

## 8. Tiêu chí nghiệm thu

| Tiêu chí | Kết quả mong muốn |
|---|---|
| Visual Design | Modern SaaS Admin |
| Color | Indigo/Violet + Slate |
| UI Density | Compact |
| Consistency | Đồng nhất toàn CMS |
| Ant Design | Sử dụng Theme Token |
| Tailwind | Không xung đột CSS |
| Responsive | Hoạt động tốt |
| Maintainability | Dễ mở rộng |
| Existing Features | Không bị ảnh hưởng |
| Build | Thành công |

## 9. Báo cáo kết quả

Sau khi hoàn thành, hãy trả về:

1. Audit hiện trạng project.
2. Các vấn đề UI/UX đã phát hiện.
3. Kiến trúc Theme mới.
4. Danh sách file đã tạo.
5. Danh sách file đã chỉnh sửa.
6. Những component đã được chuẩn hóa.
7. Những màn hình đã được áp dụng.
8. Kết quả TypeScript, Lint và Build.
9. Kết quả kiểm thử Playwright nếu có.
10. Những vấn đề còn tồn tại.

**Yêu cầu cuối cùng:**

Hãy trực tiếp triển khai thay đổi trên project hiện tại, không dừng lại ở việc đưa ra phân tích hoặc đề xuất.

Ưu tiên một hệ thống CMS đẹp, chuyên nghiệp, nhỏ gọn, có điểm nhấn màu sắc, dễ sử dụng và có khả năng mở rộng lâu dài.

Design reference: Modern SaaS Admin Dashboard với nền sáng, Sidebar trắng, Primary Indigo/Violet, KPI cards có màu sắc riêng, Table compact, border nhẹ và khoảng cách được kiểm soát chặt chẽ.