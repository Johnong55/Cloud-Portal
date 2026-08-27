# Chính sách quyền riêng tư — Cloud Portal

Cập nhật: 27/08/2026

Cloud Portal là ứng dụng Android độc lập giúp người dùng truy cập trang web iCloud của Apple. Ứng dụng không thuộc Apple và không được Apple tài trợ hoặc bảo trợ.

## Dữ liệu được xử lý

- Thông tin đăng nhập, cookie và web storage của iCloud được Android System WebView lưu trong vùng dữ liệu riêng của ứng dụng.
- Ảnh, video và tệp do người dùng chủ động tải được lưu trong thư mục Downloads của thiết bị.
- Tùy chọn khóa sinh trắc học chỉ lưu trạng thái bật/tắt. Dữ liệu vân tay hoặc khuôn mặt do Android xử lý và không được Cloud Portal đọc hoặc lưu trữ.

## Thu thập và chia sẻ dữ liệu

Cloud Portal không có máy chủ trung gian, không tích hợp quảng cáo, analytics hoặc SDK theo dõi. Ứng dụng không gửi cookie, mật khẩu, ảnh hay tệp cho nhà phát triển. Nội dung iCloud được trao đổi trực tiếp giữa Android System WebView và các miền HTTPS thuộc Apple.

Khi người dùng chọn chia sẻ hoặc mở tệp bằng ứng dụng khác, Android sẽ chuyển tệp tới ứng dụng do người dùng lựa chọn.

## Quyền kiểm soát của người dùng

Người dùng có thể vào **Phiên → Xóa cookie và đăng xuất** để xóa cookie, web storage, cache và lịch sử iCloud trong Cloud Portal. Xóa dữ liệu ứng dụng hoặc gỡ cài đặt cũng xóa phiên cục bộ. Các tệp đã tải trong Downloads không tự bị xóa khi xóa phiên.

## Bảo mật

Cloud Portal chỉ cho phép điều hướng nội bộ tới HTTPS trên các miền Apple được cho phép, chặn lỗi chứng chỉ TLS, tắt WebView debugging và không sao lưu dữ liệu phiên lên cloud backup của Android.

## Liên hệ

Vấn đề bảo mật hoặc quyền riêng tư có thể được báo cáo qua mục Issues của repository Cloud Portal trên GitHub.
