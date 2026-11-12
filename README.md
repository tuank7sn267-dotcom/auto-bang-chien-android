# Auto Bang Chiến Android V3 (bản thử nghiệm đa màn hình)

V3 giữ lại luồng chơi của V2 và bổ sung cơ chế thích nghi tọa độ cho các thiết bị có kích thước màn hình khác nhau.

## Thay đổi chính

- Đọc kích thước ảnh thực tế từ `ImageReader`, thay vì chỉ tin kích thước dự kiến lúc tạo VirtualDisplay.
- Tự phát hiện viền đen letterbox/pillarbox rất tối ở mép ảnh và chuẩn hóa vùng game về canvas logic 2400×1080.
- Dùng cùng phép ánh xạ từ canvas logic sang ảnh chụp và tọa độ chạm Accessibility.
- Bổ sung tìm ảnh mẫu OpenCV trên toàn vùng game, thử nhiều tỷ lệ co giãn riêng trục X/Y để xử lý nút bị dịch chuyển/co giãn.
- Dùng các ảnh mẫu làm điểm neo để hiệu chỉnh vị trí lưới, thanh tiến trình, deck, thẻ chiến đấu và các nút thao tác.
- Lưu ảnh debug kèm tệp TXT chứa kích thước capture và vùng game đã phát hiện.
- Tăng version lên 0.3.0. APK được build qua GitHub Actions.

## Build APK

1. Đưa toàn bộ thư mục dự án lên repository GitHub.
2. Mở tab **Actions**.
3. Chọn workflow **Build Auto Bang Chien V3 APK** và đợi dấu xanh.
4. Mở lần chạy mới nhất, tải artifact `auto-bang-chien-v3-apk`.
5. Giải nén ZIP và cài `app-debug.apk`.

## Chạy lần đầu

1. Bật dịch vụ Trợ năng cho Auto Bang Chiến.
2. Bấm **BẮT ĐẦU** và cấp quyền chia sẻ **Toàn bộ màn hình**.
3. Mở game ở chế độ ngang và để game ở foreground.
4. Nên đặt giới hạn 1 trận trong lần kiểm tra đầu tiên để xác nhận tọa độ trước khi chạy lâu.

## Kiểm thử đa thiết bị

- Điện thoại đang chạy V2 tốt (thiết bị tham chiếu).
- Điện thoại tỷ lệ 16:9, 19.5:9 hoặc 20:9.
- BlueStacks 1600×900.

V3 là bản thử nghiệm. Tự phát hiện viền đen và tìm template có thể xử lý nhiều trường hợp thay đổi tỷ lệ/kích thước, nhưng không thể đảm bảo mọi phiên bản game đều có cùng bố cục responsive. Nếu một máy vẫn bấm lệch, hãy gửi nhật ký ứng dụng và ảnh debug trong thư mục `Android/data/vn.autobangchien/files/debug` để hiệu chỉnh tiếp.

## Lưu ý

- Điện thoại cần giữ màn hình bật và game ở foreground.
- Cần bật quyền Trợ năng và cấp quyền chụp màn hình mỗi lần bắt đầu phiên chạy theo yêu cầu Android.
- Giữ lại `app/debug.keystore` để APK cập nhật có thể cài đè lên bản trước đã ký bằng cùng khóa.
