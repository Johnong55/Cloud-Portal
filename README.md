# Cloud Portal

### iCloud của bạn, tiện hơn trên Android.

Cloud Portal là ứng dụng Android dành cho những người từng sử dụng iPhone hoặc vẫn còn ảnh và tài liệu trên iCloud. Ứng dụng đưa những dịch vụ iCloud cần thiết vào một giao diện gọn gàng, dễ dùng và phù hợp với màn hình điện thoại Android.

Thay vì mở trình duyệt, tìm lại trang iCloud và đăng nhập từ đầu, bạn có thể xem ảnh, truy cập tệp, tải nội dung và quản lý phiên ngay trong một ứng dụng duy nhất.

## Bạn có thể làm gì với Cloud Portal?

- Xem và quản lý **Ảnh iCloud** trên điện thoại Android.
- Truy cập tệp trong **iCloud Drive**.
- Mở và sử dụng **Ghi chú iCloud**.
- Giữ trạng thái đăng nhập để tiếp tục công việc ở lần mở sau.
- Tải ảnh và tệp về thiết bị mà không phải chuyển sang trình duyệt khác.
- Tự động giải nén gói ZIP khi tải nhiều ảnh cùng lúc.
- Xem ảnh và video đã tải bằng thư viện native, hỗ trợ đúng chiều EXIF, phóng to và vuốt từng ảnh.
- Theo dõi nội dung đã tải, thời điểm hoàn tất và nhận biết tệp mới tại màn hình **Tải về**.
- Khóa ứng dụng bằng sinh trắc học hoặc khóa màn hình Android.
- Chủ động xóa phiên iCloud khỏi thiết bị bất cứ lúc nào.

## Trải nghiệm được thiết kế cho Android

Cloud Portal tập trung vào không gian hiển thị nội dung. Trang chủ tối giản có chuyển động nhẹ và đóng vai trò menu truy cập nhanh; khi mở ứng dụng, iCloud vẫn là trải nghiệm chính. Giao diện web được mở rộng tối đa và thanh điều khiển nhỏ nằm riêng ở cạnh dưới, không che các nút thao tác khi bạn chọn nhiều ảnh.

Bạn có thể quay lại, tải lại trang hoặc chuyển nhanh giữa Ảnh, Drive và Ghi chú mà không cần rời khỏi màn hình đang dùng.

Ứng dụng gồm bốn khu vực chính:

- **Trang chủ** — truy cập nhanh các dịch vụ iCloud thường dùng.
- **iCloud** — không gian toàn màn hình để xem và quản lý nội dung.
- **Tải về** — theo dõi tệp, xem trạng thái giải nén và mở ảnh đã tải.
- **Phiên** — kiểm soát trạng thái đăng nhập và đăng xuất an toàn.

## Tải nhiều ảnh, không cần tự giải nén

Khi iCloud đóng gói nhiều ảnh thành một tệp ZIP, Cloud Portal sẽ tự xử lý và đặt ảnh vào:

```text
Downloads/Cloud Portal/<tên gói ảnh>/
```

Sau khi hoàn tất, nút **Xem ảnh** sẽ xuất hiện để bạn đi thẳng tới nội dung vừa tải. Tệp ZIP gốc vẫn được giữ lại trong Downloads để bạn có thể sao lưu hoặc sử dụng khi cần.

## Quyền riêng tư

Cloud Portal không yêu cầu bạn nhập mật khẩu Apple ID vào một biểu mẫu riêng và không gửi thông tin đăng nhập tới máy chủ trung gian. Phiên iCloud được giữ trong vùng dữ liệu riêng của ứng dụng trên thiết bị.

Bạn có thể mở **Phiên → Xóa cookie và đăng xuất** để xóa trạng thái đăng nhập. Việc gỡ ứng dụng hoặc xóa dữ liệu ứng dụng trong Android cũng sẽ xóa phiên đã lưu.

Xem đầy đủ tại [Chính sách quyền riêng tư](PRIVACY.md).

## Yêu cầu thiết bị

- Android 10 trở lên.
- Android System WebView và Chrome nên được cập nhật.
- Kết nối Internet để sử dụng các dịch vụ iCloud.
- Apple có thể yêu cầu xác thực hai yếu tố khi đăng nhập lại.

## Dành cho nhà phát triển

<details>
<summary>Build và cài APK</summary>

Yêu cầu JDK 17 và Android SDK 37.

```bash
./gradlew test lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK debug được tạo tại `app/build/outputs/apk/debug/app-debug.apk`.

Quy trình ký AAB và kiểm tra trước khi phát hành nằm trong [Production release checklist](docs/RELEASE.md).

</details>

## Tuyên bố

Cloud Portal là dự án độc lập, không liên kết, không được tài trợ và không được Apple bảo trợ. iCloud, iPhone và Apple là thương hiệu của Apple Inc. Khả năng tương thích có thể thay đổi khi Apple cập nhật iCloud.com.
