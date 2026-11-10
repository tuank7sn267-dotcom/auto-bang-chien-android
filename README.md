# Auto Bang Chiến Android — 0.2.0 (native phone build)

Bản này phát triển từ project Android 0.1.0 và lấy `auto_bang_chien.py` mới nhất trong gói PC làm nguồn đối chiếu cho logic.

## Mục tiêu

- Chạy trực tiếp trên điện thoại Android; sau khi cài không cần PC/ADB.
- MediaProjection chụp màn hình theo phiên được người dùng cấp quyền.
- AccessibilityService gửi thao tác chạm tuần tự.
- OpenCV Android dùng `matchTemplate(TM_CCOEFF_NORMED)` để nhận diện 10 ảnh mẫu, thay cho bộ so khớp Kotlin thô của 0.1.0.
- Giữ các quy tắc của bản Python: thứ tự nhận diện trạng thái, thanh tiến trình, lưới 30 ô, deck 1→2→3, chờ dấu tích đỏ, bấm 3 thẻ nhân vật theo 2 vòng, hủy các chạm đang xếp hàng khi rời trận, Lobby/Tiếp tục, bỏ qua nhà hết deck, lưu ảnh màn hình lạ và dừng sau 60 khung hình lạ.

## Khác biệt bắt buộc so với bản PC

Bản PC dùng một phiên `adb shell` giữ mở để gửi `input tap` và tự benchmark 3 kiểu `screencap` (raw/gzip/PNG). APK chạy ngay trên chính điện thoại không thể gọi ADB của máy tính hoặc giữ phiên ADB host như bản PC. Bản Android thay phần vận chuyển đó bằng:

- MediaProjection + ImageReader để lấy frame trên thiết bị.
- AccessibilityService với hàng đợi chạm tuần tự; có thể xóa các chạm chưa bắt đầu khi trận kết thúc.
- OpenCV native trên Android để nhận diện ảnh mẫu.

Vì vậy logic chơi được port, nhưng tốc độ chụp/bấm không thể giống hệt transport ADB trên PC. Cần đo trên thiết bị thật.

## Build APK bằng GitHub Actions (không cần cài Android Studio)

1. Tạo một repository GitHub mới, ví dụ `auto-bang-chien-android`.
2. Upload toàn bộ nội dung thư mục dự án này lên repository (bao gồm thư mục ẩn `.github/workflows/`). Có thể dùng GitHub web: **Add file → Upload files**.
3. Chọn **Commit changes**.
4. Mở tab **Actions** của repository. Workflow **Build Android APK** sẽ tự chạy sau khi push.
5. Khi workflow có dấu xanh, mở lần chạy mới nhất → phần **Artifacts** → tải `auto-bang-chien-debug-apk`.
6. Giải nén ZIP artifact; bên trong có `app-debug.apk`. Chép APK sang điện thoại và cài đặt.

Workflow dùng JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, compile SDK 35 và OpenCV Android AAR 4.14.0 từ Maven Central.

## Nếu muốn build cục bộ

Cần JDK 17, Android SDK Platform 35 và Gradle 8.9. Từ thư mục gốc dự án chạy:

```bash
gradle --no-daemon assembleDebug
```

APK nằm tại `app/build/outputs/apk/debug/app-debug.apk`.

## Cấp quyền và chạy lần đầu

1. Cài APK, mở Auto Bang Chiến. Nếu đang cài đè bản 0.1.0 được build trên PC hoặc APK từ một lần GitHub Actions trước, có thể cần gỡ bản cũ trước do chữ ký debug khác nhau.
2. Vào **CẤP QUYỀN TRỢ NĂNG**, bật dịch vụ Auto Bang Chiến trong Cài đặt Trợ năng. Với Android 13+ nếu hệ thống chặn ứng dụng cài ngoài CH Play, vào thông tin ứng dụng → menu ⋮ → **Cho phép cài đặt bị hạn chế** (tên mục có thể khác theo hãng máy), rồi bật lại Trợ năng.
3. Bật tự xoay màn hình và xoay điện thoại sang ngang trước khi nhấn Bắt đầu (dịch vụ tạo phiên chụp theo kích thước màn hình tại thời điểm cấp quyền).
4. Nên bật **Chạy thử (chỉ nhận diện, KHÔNG chạm)** trước. Mở game Bang chiến, xác nhận game đang ngang, sau đó chuyển về ứng dụng Auto Bang Chiến.
5. Nhấn **BẮT ĐẦU**, chấp nhận hộp thoại chia sẻ màn hình; sau khi app báo đang chạy, chuyển lại game. Chọn toàn bộ màn hình nếu Android hiển thị lựa chọn phạm vi.
6. Kiểm tra Nhật ký có các trạng thái `LOBBY_SELECT`, `MAIN`, `GRID`, `DIALOG`, `BATTLE`, `RESULT` đúng với màn hình game.
7. Chỉ tắt Chạy thử khi đã xác nhận nhận diện ổn định. Nên đặt giới hạn trận nhỏ (ví dụ 1–2) ở lần chạy thao tác đầu tiên.

## Debug

- Ảnh màn hình lạ được lưu trong thư mục riêng của app: `Android/data/vn.autobangchien/files/debug/` (một số Android hạn chế duyệt thư mục này; có thể dùng trình quản lý tệp được cấp quyền).
- Nhật ký hiển thị trạng thái, điểm khớp mẫu tốt nhất và thao tác chính.
- Nếu không nhận diện được, ảnh mẫu phải được chụp lại từ cùng giao diện/độ phân giải/tỉ lệ game; không tự ý hạ ngưỡng 0.75 vì có thể gây bấm nhầm.
- Nếu thiết bị đổi tỉ lệ màn hình, vùng tọa độ có thể cần hiệu chỉnh. Hiện code chuẩn hóa về 2400×1080, nhưng điều này không bảo đảm mọi tỉ lệ/cutout đều đúng.

## Giới hạn và an toàn

- Đây là bản 0.2.0 cần kiểm thử thực tế; chưa có bằng chứng chạy ổn định trên điện thoại của bạn. Build xanh chỉ xác nhận biên dịch, không xác nhận bot chơi đúng.
- Android yêu cầu người dùng cấp quyền chụp màn hình lại mỗi phiên. Không cam kết hoạt động khi màn hình tắt, game bị đưa xuống nền hoặc hệ thống thu hồi quyền.
- Giữ game ở foreground, màn hình ngang và tránh bật tiết kiệm pin cho app trong lúc thử.
- Chạy thử ở tài khoản phụ/môi trường thử nghiệm, đặt giới hạn trận thấp. Tự động hóa có thể vi phạm điều khoản trò chơi hoặc khiến tài khoản bị hạn chế.
- `auto_bang_chien.py` trong thư mục gốc chỉ là bản PC mới nhất để đối chiếu; APK không chạy Python và không cần ADB.
