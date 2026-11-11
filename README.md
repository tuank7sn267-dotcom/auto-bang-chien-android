# Auto Bang Chiến — bản Android 0.2.0

Bản Android chạy trực tiếp trên điện thoại, làm việc **giống bản máy tính mới nhất**:
nhận diện màn hình bằng ảnh mẫu, bấm → chờ màn hình đổi, tự xử lý các tình huống như bản PC.

## Khác với bản 0.1.0 (bản không tự chơi được)
- Chụp màn hình đúng cách cho Android 14/15 (đăng ký callback bắt buộc) và luôn chụp theo khung ngang.
- Bấm tuần tự, chờ từng cử chỉ xong (trước đây bấm dồn nên Android huỷ gần hết các lần bấm,
  gồm cả lần chọn deck → lỗi "chọn deck rồi mà không bấm được").
- Nhận diện nhanh (trước đây rất chậm, mỗi vòng hàng chục giây).
- Chọn deck **chờ thấy dấu tích đỏ** rồi mới bấm Tấn công.
- Banner "Đã bị đánh bại" / thanh đầy → quay lại, ghép bang hội mới (không quét ô nhầm).
- Khi đánh: bấm **5 ô nhân vật**; hết trận **huỷ ngay các lần bấm còn xếp hàng**.
- **Tự phục hồi khi bấm nhầm:** màn hình lạ có mũi tên quay lại → bấm quay lại; về màn hình chính → bấm
  **Bang hội**; ở màn Bang hội → bấm **Trò chơi**; rồi tiếp tục Game chọn → Bang chiến.
- Màn hình chính của app hiển thị nhật ký chạy và trạng thái Trợ năng.

## Build bằng GitHub Actions (không cần cài Android Studio)
1. Tạo tài khoản GitHub (miễn phí) và một kho mã mới (**New repository**; nên chọn **Private**).
2. Giải nén file zip này. Trong kho vừa tạo bấm **Add file → Upload files**, kéo thả **toàn bộ nội dung**
   thư mục đã giải nén (nhớ có thư mục `.github`), bấm **Commit changes** (nhánh `main`).
   - Hoặc dùng dòng lệnh: `git init`, `git add .`, `git commit -m "init"`, `git branch -M main`,
     `git remote add origin <địa-chỉ-kho>`, `git push -u origin main`.
3. Vào tab **Actions**, thấy lần chạy "Build APK" (khoảng 5–10 phút). Dấu ✓ xanh là xong.
4. Tải APK: vào tab **Releases** (hoặc mục "Releases" bên phải trang kho) → bản **latest** →
   tải `app-debug.apk` ngay trên điện thoại. Cũng có thể tải ở mục **Artifacts** của lần chạy.
5. Mỗi lần sửa code và đẩy lên GitHub, APK mới tự được build lại. APK mới **cài đè** được bản cũ
   (đã dùng khoá ký cố định). **Lần đầu** nếu đang có bản 0.1.0 thì phải **gỡ bản cũ** trước.
6. Nếu lần build báo lỗi đỏ ❌: bấm vào lần chạy, copy dòng lỗi (mục "Build debug APK") gửi lại để sửa.

## Cài và chạy trên điện thoại
1. Mở file APK, cho phép cài từ nguồn này.
2. **Bật Trợ năng:** Cài đặt → Trợ năng → Auto Bang Chiến → bật.
   Nếu nút bị **mờ** (Android 13+ với app cài ngoài CH Play): mở app, bấm **MỞ CÀI ĐẶT ỨNG DỤNG**,
   chọn dấu **ba chấm** → **Cho phép cài đặt bị hạn chế**, rồi bật Trợ năng lại.
3. Mở app, bấm **BẮT ĐẦU**. Khi hộp thoại chia sẻ màn hình hiện ra, chọn **Toàn bộ màn hình**
   (không chọn "một ứng dụng", nếu không app chỉ thấy chính nó).
4. Chuyển sang game, **xoay ngang**, vào màn Bang chiến. Quay lại app để xem **Nhật ký**.
5. Muốn dừng: bấm **DỪNG** trong app (hoặc kéo thanh thông báo của app).

## Lưu ý
- Giữ game ở **nền trước** và không tắt màn hình. Nên tắt tối ưu pin cho Auto Bang Chiến.
- Mỗi lần bấm BẮT ĐẦU phải cấp lại quyền chia sẻ màn hình (Android yêu cầu).
- Gặp màn hình lạ (popup, mất mạng...) quá 60 lần liên tiếp, hoặc bấm quay lại 25 lần vẫn kẹt:
  bot tự dừng và lưu ảnh vào `Android/data/vn.autobangchien/files/debug/` để bạn gửi lại.
- Game có thể cấm công cụ tự động, tài khoản có nguy cơ bị phạt. Hãy cân nhắc trước khi dùng.
- Khoá ký trong repo (`app/debug.keystore`) là khoá debug công khai, chỉ dùng cho bản cài cá nhân.
  Để kho ở chế độ Private.

## Cấu trúc mã
`Cfg.kt` (toạ độ, ảnh mẫu) · `Frame.kt` (khung hình) · `Vision.kt` (nhận diện) ·
`BangChienEngine.kt` (kịch bản) · `BotAccessibilityService.kt` (bấm) ·
`CaptureBotService.kt` + `FrameSource.kt` (chụp màn hình) · `MainActivity.kt` (giao diện).
