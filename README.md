# Cloud Portal cho Android

Cloud Portal 2.2 biến iCloud.com thành trải nghiệm ứng dụng trên Pixel: trang Apple chạy trong WebView riêng, cookie và Web Storage được giữ trong sandbox của app, URL cuối được khôi phục khi mở lại. Không còn chuyển sang Chrome khi dùng Photos, Drive hoặc Notes.

## Chức năng

- Giao diện native gồm **Trang chủ**, **iCloud**, **Tải về** và **Phiên**.
- Mở iCloud Photos, iCloud Drive và Notes ngay bên trong app.
- Chế độ iCloud toàn màn hình: thanh hệ thống và điều hướng app tự ẩn; bottom bar gọn ở cạnh dưới cho phép quay lại, tải lại và chuyển dịch vụ nhanh mà không che thanh thao tác Select của iCloud Photos.
- Cookie đăng nhập được ghi xuống bộ nhớ bền vững bằng `CookieManager.flush()`.
- DOM Storage lưu trạng thái web mà iCloud.com cần; app nhớ trang Apple cuối cùng.
- Upload bằng trình chọn tệp Android, không cấp quyền đọc toàn bộ bộ nhớ.
- Download bằng Android Download Manager, kèm User-Agent và cookie của URL Apple khi cần xác thực.
- ZIP tạo ra khi tải nhiều ảnh được WorkManager tự giải nén an toàn vào `Downloads/Cloud Portal/<tên gói>/`; nút **Xem ảnh** mở thẳng ảnh kết quả trong Files.
- Danh sách tiến độ tải xuống và mở tệp đã tải ngay trong app.
- Nút xóa đồng thời cookie, Web Storage, cache, lịch sử và phiên iCloud.

## Bảo mật phiên

Phiên đăng nhập nằm tại vùng dữ liệu riêng của package `com.trijohn.cloudportal`; backup và device transfer đều bị tắt. Mã ứng dụng:

- chỉ cho top-level WebView mở HTTPS thuộc các domain Apple đã allowlist;
- chặn chứng chỉ TLS lỗi, HTTP mixed content và file access;
- bật Android Safe Browsing;
- không dùng `addJavascriptInterface` hay máy chủ trung gian; đoạn JavaScript tương thích cục bộ chỉ sửa lỗi chiều cao `0px` của giao diện iCloud và không đọc dữ liệu tài khoản;
- không ghi cookie, Apple ID, mật khẩu hoặc mã 2FA vào log/SharedPreferences.

WebView vẫn phải bật JavaScript, DOM Storage và third-party cookies vì iCloud.com là web app và đăng nhập đi qua nhiều subdomain Apple. Khi tải tệp, cookie phù hợp được chuyển trực tiếp cho Download Manager của Android để gửi tới URL Apple; cookie không được lưu vào lịch sử download của app.

> Apple quyết định thời hạn session và có thể yêu cầu 2FA lại. Gỡ app, xóa dữ liệu Android hoặc bấm **Phiên → Xóa cookie và đăng xuất** sẽ xóa phiên. App không và không nên lưu mật khẩu Apple ID dạng văn bản.

## Vì sao vẫn dùng iCloud.com?

Apple không cung cấp API công khai để app Android bên thứ ba duyệt thư viện **iCloud Photos** hoặc **iCloud Drive cá nhân**. CloudKit Web Services chỉ truy cập container của ứng dụng do nhà phát triển tạo, không phải kho Photos/Drive chung của tài khoản. Endpoint iCloud reverse-engineer có thể ngừng hoạt động bất cứ lúc nào và sẽ buộc app tự xử lý credential nhạy cảm.

Tham khảo chính thức:

- [Apple: Use Photos on iCloud.com](https://support.apple.com/guide/icloud/photos-on-icloudcom-overview-mmbc402b84/icloud)
- [Apple: Find and view files in iCloud Drive on iCloud.com](https://support.apple.com/guide/icloud/mmebf050837b/icloud)
- [Android: CookieManager](https://developer.android.com/reference/android/webkit/CookieManager)
- [Android: WebView security checklist](https://developer.android.com/privacy-and-security/security-tips#WebView)
- [Android: WebChromeClient file chooser](https://developer.android.com/reference/android/webkit/WebChromeClient#onShowFileChooser(android.webkit.WebView,android.webkit.ValueCallback,android.webkit.WebChromeClient.FileChooserParams))
- [Apple Developer: CloudKit JS](https://developer.apple.com/documentation/cloudkitjs)

## Build và cài lên Pixel

Yêu cầu: Android Studio hỗ trợ AGP 9.3, JDK 17+, Android SDK 37 và Android 10 (API 29) trở lên.

```bash
./gradlew test lint assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK nằm tại `app/build/outputs/apk/debug/app-debug.apk`.

## Cách dùng

1. Mở app → **Ảnh**, **Drive** hoặc tab **iCloud**.
2. Trong chế độ toàn màn hình, dùng bottom bar để xem domain Apple, quay lại, tải lại hoặc chạm nút iCloud để chuyển dịch vụ; đăng nhập và hoàn tất 2FA.
3. Dùng giao diện iCloud.com để xem, chọn, upload hoặc download nội dung.
4. Mở tab **Tải về** để theo dõi và mở tệp trong thư mục Downloads.
5. Khi muốn đăng xuất hoàn toàn, mở **Phiên → Xóa cookie và đăng xuất**.

Nếu Apple hiển thị trình duyệt không được hỗ trợ, hãy cập nhật **Android System WebView** và Chrome trên Play Store. Khả năng tương thích cuối cùng vẫn phụ thuộc thay đổi từ Apple.

Cloud Portal là ứng dụng độc lập, không liên kết hoặc được Apple bảo trợ.
