# CHECKLIST KIỂM THỬ & HƯỚNG DẪN CHẠY DEMO — THÀNH VIÊN 2
**Module:** Security & Transaction Management (Requirement 2 + 4)  
**Nhánh Git:** `feature/member2-security-transaction`  
**Màn hình khởi đầu:** `MainActivity` (Member 2 Hub — chạy độc lập với dữ liệu giả lập tại `DemoSessionProvider` & `MockAccountRepository`)

---

## PHẦN A: DỮ LIỆU MẪU & PHẦN ĐANG GIẢ LẬP (MOCK)

| Thành phần | Dữ liệu mẫu / Cơ chế giả lập | Vị trí code |
| :--- | :--- | :--- |
| **Tài khoản Khách hàng (`user_cust_01`)** | • Checking `1001000123456`: **50,000,000 VNĐ** (`ACTIVE`)<br>• Saving `1002000654321`: **120,000,000 VNĐ** (`ACTIVE`) | `MockAccountRepository.kt` |
| **Tài khoản Nhận nội bộ (`user_cust_02`)** | • Checking `1001000999888` (`Trần Thị Bình`): **15,000,000 VNĐ** (`ACTIVE`) | `MockAccountRepository.kt` |
| **Tài khoản Bị khóa Demo (`user_cust_03`)** | • Checking `1001000444555` (`Lê Hoàng Cường`): **5,000,000 VNĐ** (`FROZEN`) | `MockAccountRepository.kt` |
| **Chuyển tiền Liên ngân hàng (External)** | Danh mục ngân hàng (`VCB`, `MB`, `TCB`, `BIDV`, `ACB`) + tài khoản mẫu `0071001234567` (`PHAM MINH TUAN @ VCB`), `9704221122334` (`HOANG THI MAI @ MB`). Không chuyển tiền thật. | `ExternalBankDirectory` (`MockAccountRepository.kt`) |
| **Cổng thanh toán Sandbox (Deposit)** | Chọn giữa `VNPay Sandbox`, `MoMo Developer Sandbox`, `Napas 24/7 Sandbox`. Không chứa API Key hay Secret trong mã nguồn Android. | `SandboxGatewayConfig` (`MockAccountRepository.kt`) |
| **OTP / 2FA** | Sinh mã OTP 6 số ngẫu nhiên, lưu hash **SHA-256**, hiển thị mã demo qua banner `[SMS Demo]` trên màn hình xác nhận. Hiệu lực 120s, tối đa 3 lần nhập sai. | `SecurityService.kt` |
| **Sinh trắc học (BiometricPrompt)** | Dùng AndroidX `BiometricPrompt` cho giao dịch **>= 10,000,000 VNĐ**. Có thêm nút **Giả lập ĐẠT / THẤT BẠI** để test ngay trên emulator chưa cài vân tay. | `BiometricAuthHelper.kt` & `TransactionConfirmActivity.kt` |

---

## PHẦN B: CHECKLIST KIỂM THỬ THỦ CÔNG TRÊN ANDROID STUDIO

Mở dự án trên Android Studio, nhấn **Run (`Shift + F10`)** để mở ứng dụng trên Emulator/Thiết bị thật. Tại màn hình chính (**Member 2 Hub**), lần lượt kiểm tra các mục sau:

### 1. Luồng eKYC Khách hàng & Bank Officer Review
- [X] **TC-01: Chặn gửi trùng khi hồ sơ đang `PENDING` hoặc `APPROVED`**
  1. Ở Hub, chọn **4. Xác thực Định danh eKYC (Customer eKYC)** (mặc định tài khoản An đang có 1 hồ sơ `PENDING`).
  2. Kiểm tra màn hình: form nhập và nút gửi bị khóa với thông báo *"Đang chờ Bank Officer duyệt (Không thể tạo thêm)"*.
- [X] **TC-02: Bank Officer Từ chối hồ sơ (`REJECTED`) & Khóa nút Duyệt/Từ chối**
  1. Quay lại Hub, chọn **5. Bank Officer: Duyệt hồ sơ eKYC**.
  2. Nhấn **Từ chối (Reject)** trên hồ sơ của `Nguyễn Văn An`, nhập lý do từ chối (vd: *"Ảnh CCCD bị mờ"*).
  - *Kết quả mong đợi:* Trạng thái đổi thành `REJECTED`, **2 nút Duyệt & Từ chối biến mất** (thay bằng thông báo chờ khách hàng gửi lại). Thử giao dịch >= 10,000,000 VNĐ sẽ bị chặn do eKYC bị từ chối.
- [X] **TC-03: Khách hàng gửi lại eKYC sau khi bị Từ chối & Officer duyệt lại (`APPROVED`)**
  1. Vào lại **4. Xác thực Định danh eKYC**: Lúc này form đã mở khóa, nút đổi thành **"Gửi lại hồ sơ eKYC (Lần #2)"**. Nhấn gửi lại hồ sơ.
  2. Vào lại **5. Bank Officer: Duyệt hồ sơ eKYC**: Hồ sơ của `Nguyễn Văn An` (`user_cust_01`) quay lại trạng thái `PENDING` (Lần gửi: `#2`) và **hiện lại 2 nút Duyệt / Từ chối**.
  3. Nhấn **Duyệt (Approve)** -> Nút duyệt khóa lại, quay về Hub thấy badge chuyển xanh `eKYC: APPROVED`. Vào lại mục 4 thấy form đã khóa vì đã định danh thành công.

### 2. Luồng Deposit, Withdraw & Validation
- [X] **TC-04: Nạp tiền (Deposit) qua cổng Sandbox**
  1. Vào **1. Nạp tiền & Rút tiền**, chọn tab **Nạp tiền (Deposit)**.
  2. Chọn tài khoản `Checking (1001000123456)`, chọn cổng `VNPay Sandbox`, bấm nút điền nhanh **500,000 (Thường)**, bấm **Kiểm tra & Tiếp tục**.
  3. Tại **Bước 1/2 (Xác nhận thông tin)** -> bấm **Xác nhận thông tin chuyển**.
  4. Tại **Bước 2/2 (Màn hình OTP)** -> bấm **Điền OTP** -> **Xác nhận OTP & Thực hiện Giao dịch**.
  - *Kết quả mong đợi:* Tự động nhảy sang Biên lai báo `COMPLETED`, số dư Checking tăng `500,000 VNĐ`.
- [X] **TC-05: Validation số tiền <= 0 hoặc vượt số dư hoặc tài khoản khóa**
  1. Trong **Nạp/Rút tiền**, lần lượt bấm nút điền nhanh **0 VNĐ (Lỗi <= 0)** -> kiểm tra thông báo lỗi màu đỏ.
  2. Chuyển sang tab **Rút tiền (Withdraw)**, bấm **200,000,000 (Thiếu số dư)** -> kiểm tra thông báo *"Số dư không đủ"*.
  3. Chọn tài khoản `1001000444555 [FROZEN]` -> kiểm tra thông báo *"Tài khoản đang bị khóa"*.
  - *Kết quả mong đợi:* Giao dịch bị chặn ngay, số dư giữ nguyên không đổi.

### 3. Luồng Chuyển tiền Nội bộ & Liên ngân hàng (3 Bước: Thông tin → Sinh trắc học → OTP)
- [X] **TC-06: Chuyển tiền cùng ngân hàng (Internal Transfer)**
  1. Vào **2. Chuyển tiền**, tab **Cùng ngân hàng**.
  2. Bấm mẫu **Hợp lệ (999888)** (`Trần Thị Bình`), chọn **1,000,000 (Thường)** -> Tiếp tục.
  3. **Bước 1:** Xem thông tin, bấm **Xác nhận thông tin chuyển** -> sang **Bước 2 (OTP)** -> Điền OTP -> **Xác nhận OTP & Thực hiện Giao dịch**.
  - *Kết quả mong đợi:* Checking giảm `1,000,000 VNĐ`, tài khoản Trần Thị Bình tăng `1,000,000 VNĐ`.
- [X] **TC-07: Validation tài khoản nhận không tồn tại / bị khóa / số tiền âm**
  1. Thử chọn mẫu **TK Khóa (444555)** và **Không tồn tại (999999)**.
  2. Thử bấm **-50,000 (Số tiền âm)** hoặc **80,000,000 (Thiếu số dư)**.
  - *Kết quả mong đợi:* Đều bị chặn và hiển thị lý do lỗi tiếng Việt rõ ràng.
- [X] **TC-08: Chuyển tiền Liên ngân hàng giá trị cao (>= 10,000,000 VNĐ) — Từ chối Sinh trắc học vs Thành công**
  1. Chuyển sang tab **Liên ngân hàng**, bấm **Mẫu VCB (PHAM MINH TUAN)**, chọn **15,000,000 (Cao - Biometric)** -> Tiếp tục.
  2. **Bước 1/3:** Bấm **Xác nhận thông tin chuyển** -> hệ thống mở **Màn hình Sinh trắc học riêng (Bước 2/3)** (chưa sinh OTP).
  3. *Test thất bại:* Bấm **Từ chối / Giả lập Sinh trắc học THẤT BẠI** -> *Mong đợi:* Chuyển thẳng sang Biên lai báo `FAILED`, **số dư Checking không đổi**.
  4. *Test thành công:* Làm lại luồng `15,000,000 VNĐ` -> Bước 1 xác nhận -> Bước 2 quét `BiometricPrompt` (hoặc bấm **Giả lập Sinh trắc học ĐẠT**) -> tự động sang **Bước 3/3 (Màn hình OTP)** -> Điền OTP & Xác nhận.
  - *Kết quả mong đợi:* Biên lai báo `COMPLETED` kèm 2 huy hiệu `OTP 2FA: Đã xác thực | Sinh trắc học: Đã xác thực`, số dư Checking bị trừ đúng `15,000,000 VNĐ`.

### 4. Kiểm thử các trường hợp biên của OTP / 2FA (Tại Bước nhập OTP)
- [X] **TC-09: Nhập sai OTP quá 3 lần**
  1. Ở màn hình nhập OTP, nhập sai mã `111111` (khác mã trên banner SMS) và bấm **Xác nhận OTP** 3 lần liên tiếp.
  - *Kết quả mong đợi:* Lần 1 & 2 báo *"Mã OTP không chính xác"*; lần 3 tự động chuyển sang Biên lai `FAILED` với lý do vượt quá 3 lần thử.
- [X] **TC-10: OTP hết hạn (120 giây)**
  1. Bấm **Gửi lại OTP mới**, sau đó bấm nút **Giả lập Hết hạn**.
  2. Bấm **Điền OTP** -> **Xác nhận OTP**.
  - *Kết quả mong đợi:* Báo lỗi *"Mã OTP đã hết hạn (quá 120 giây)"*.
- [X] **TC-11: Tái sử dụng OTP đã dùng & Từ chối OTP**
  1. Bấm **Test dùng lại OTP đã sử dụng (ALREADY_USED)** -> *Mong đợi:* Báo lỗi *"Mã OTP này đã được sử dụng"*.
  2. Bấm **Từ chối / Hủy xác thực OTP (Báo Thất bại)** -> *Mong đợi:* Chuyển sang Biên lai `FAILED`, số dư không đổi.

### 5. Lịch sử Giao dịch (Filter Highlight), Hoàn tiền & Nhật ký Bảo mật (Audit Log)
- [X] **TC-12: Xem Lịch sử, Kiểm tra Tô đậm nút Lọc & Hoàn tiền (Refund)**
  1. Vào **3. Lịch sử Giao dịch**, lần lượt bấm `TK Checking`, `TK Saving`, `Thành công`, `Thất bại / Hoàn tiền`.
  - *Kết quả mong đợi:* Nút đang chọn được tô đậm nền xanh dương chữ trắng; danh sách lọc đúng tương ứng.
  2. Bấm **Hoàn tiền Demo (Refund)** trên 1 giao dịch đã hoàn tất -> kiểm tra số dư quay lại đúng số tiền đã hoàn.
- [X] **TC-13: Kiểm tra Nhật ký Bảo mật (Audit Log) không lộ OTP / Password / Secret**
  1. Vào **6. Nhật ký Bảo mật (Audit Logs)**, bấm lọc các tab (kiểm tra tô đậm nút lọc).
  2. Bấm nút **Test ghi Log chứa OTP & Password nhạy cảm (Auto REDACTED)**.
  - *Kết quả mong đợi:* Bản ghi log hiển thị `password=[REDACTED] otp=[REDACTED] secret=[REDACTED]`.

---

## PHẦN C: ĐỐI CHIẾU CHECKLIST PHÂN CÔNG TUẦN 2 (MEMBER 2)

| Hạng mục trong `Phan_cong_Tuan_2_Kotlin.docx` | Thành phần triển khai | Trạng thái |
| :--- | :--- | :---: |
| Màn hình eKYC nhập thông tin, chụp/chọn ảnh, lưu Pending/Approved/Rejected | `EkycActivity` + `SecurityService.submitEkyc` | ✅ |
| Màn hình mô phỏng Bank Officer xem và duyệt/từ chối hồ sơ eKYC | `OfficerEkycReviewActivity` + `SecurityService.reviewEkyc` | ✅ |
| Luồng OTP/2FA: phát sinh OTP, kiểm tra sai/hết hạn/đã dùng & giới hạn 3 lần | `TransactionConfirmActivity` (Stage 3) + `SecurityService.verifyOtpDetailed` | ✅ |
| Luồng xác minh sinh trắc học cho giao dịch giá trị cao (`BiometricPrompt`) | `BiometricAuthHelper` + `TransactionConfirmActivity` (Stage 2) | ✅ |
| UI Deposit, Withdraw, Transfer & màn hình xác nhận / kết quả giao dịch | `DepositWithdrawActivity`, `TransferActivity`, `TransactionConfirmActivity`, `TransactionResultActivity` | ✅ |
| Transaction Validation (số tiền, số dư, TK gửi/nhận, trạng thái, hạn mức) | `TransactionService.validateTransactionDetailed` | ✅ |
| Chuyển tiền cùng ngân hàng & mô phỏng chuyển tiền liên ngân hàng | `TransactionService.processTransfer` + `ExternalBankDirectory` | ✅ |
| Chỉ cập nhật số dư & ghi nhận giao dịch sau khi vượt qua kiểm tra & xác thực | `TransactionService.commitTransaction` + `MockAccountRepository.updateBalanceAtomic` | ✅ |
| Transaction History & Audit Log demo không ghi password/OTP/secret | `TransactionHistoryActivity`, `AuditLogActivity`, `FormatAndSecurityUtils.sanitizeAuditDetails` | ✅ |
| Dữ liệu tài khoản/số dư giả lập riêng chạy độc lập không phụ thuộc M1 | `MockAccountRepository` + `DemoSessionProvider` | ✅ |
| Chuẩn bị phần tích hợp cổng thanh toán chế độ Sandbox (không secret/API key) | `SandboxGatewayConfig` trong `DepositWithdrawActivity` | ✅ |
| Tự kiểm thử tự động & thủ công các trường hợp biên | `Member2SecurityTransactionTest.kt` (17 unit tests) + Checklist này | ✅ |

