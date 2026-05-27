# TASK: Generate System Architecture Documentation

## 1. Context & Goal
Hệ thống backend hiện tại bao gồm 8 module. Mục tiêu của task này là dịch ngược mã nguồn hiện có để tạo ra bộ tài liệu kiến trúc phân cấp (1 file tổng quan và các file chi tiết cho từng module). Bắt buộc sử dụng Mermaid.js cho mọi biểu đồ.

## 2. Execution Steps

### Step 1: Create Root Architecture (`/architecture.md`)
Đọc toàn bộ cấu trúc thư mục, `package.json` (hoặc `pom.xml`/`docker-compose`...) và file cấu hình gốc để tạo file `architecture.md` tại thư mục gốc dự án. File này phải có:
- **System Overview:** Danh sách 8 module và Tech Stack.
- **System Architecture Diagram (Mermaid Component):** Thể hiện 8 module, mối quan hệ tương tác, giao thức giao tiếp, Database và luồng Deployment.
- **End-to-End Request Flow (Mermaid Sequence):** Luồng hoạt động của 1 Request tiêu biểu từ Client -> API Gateway/Controller -> Modules -> Database -> Trả về Client.

### Step 2: Create Module Architectures (`/[module_name]/architecture.md`)
Quét qua từng thư mục của 8 module. Trong mỗi module, tạo một file `architecture.md` riêng biệt với cấu trúc sau:
- **Module Purpose:** Tính năng và vai trò cốt lõi.
- **Class/Structure Diagram (Mermaid Class):** Biểu đồ các entity, service, repository nội bộ của module này.
- **Capabilities (Provided to Devs):** Các helper, utility, abstract class đã implement sẵn.
- **To-Do / Detailed Implementation:** Những phần logic, interface hoặc API endpoints còn thiếu cần implement chi tiết.

## 3. Constraints
- Chỉ xuất ra các file `.md` tại đúng vị trí yêu cầu.
- Không thay đổi bất kỳ file mã nguồn nào đang có.