# Checklist kiểm thử – Thành viên 1 (Core Banking)

## Tài khoản demo
| Vai trò | Username | Mật khẩu |
|---|---|---|
| Khách hàng | customer | 123456 |
| Nhân viên | officer | 123456 |

## Kết quả kiểm thử
| Mã | Trường hợp | Kết quả mong đợi | Kết quả |
|---|---|---|---|
| TC01 | Đăng nhập customer đúng | Vào Dashboard khách hàng, hiện "Xin chào, Nguyễn Văn An" | Pass |
| TC02 | Đăng nhập officer đúng | Vào Dashboard nhân viên, hiện "Xin chào, Lê Thu Hà" | Pass |
| TC03 | Sai mật khẩu | Báo "Sai tên đăng nhập hoặc mật khẩu" | Pass |
| TC04 | Để trống | Báo "Vui lòng nhập đủ…" | Pass |