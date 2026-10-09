# CutViet – ứng dụng chỉnh sửa video Android

Ứng dụng chỉnh sửa video kiểu CapCut (chỉ phần chỉnh sửa), viết bằng Kotlin + Jetpack Compose + AndroidX Media3.
Xuất video giữ nguyên độ phân giải, khung hình/giây và bitrate tương đương bản gốc.

## Tính năng

- Dự án nháp tự lưu; tạo dự án từ nhiều video và ảnh; nhận video được chia sẻ từ ứng dụng khác
- Dòng thời gian kiểu CapCut: đầu phát cố định ở giữa, kéo để tua, phóng to/thu nhỏ, ảnh thu nhỏ từng đoạn
- Tách, cắt đầu/cuối, xóa, nhân bản, đổi thứ tự đoạn; hoàn tác / làm lại
- Tốc độ 0.25x–4x (âm thanh đổi theo, giữ cao độ)
- Âm lượng từng đoạn (0–200%), tắt tiếng; nhạc nền (từ tệp âm thanh hoặc trích từ video), chọn điểm bắt đầu, lặp lại, âm lượng nhạc
- Xoay 90°, lật ngang/dọc, cắt khung theo tỉ lệ, thu phóng và di chuyển vùng cắt
- Tỉ lệ khung hình dự án: Gốc, 9:16, 16:9, 1:1, 4:5, 3:4, 4:3, 21:9; vừa khung hoặc lấp đầy
- 11 bộ lọc màu; điều chỉnh độ sáng, tương phản, bão hòa, nhiệt độ màu, sắc độ; áp dụng cho tất cả các đoạn
- Văn bản (màu, cỡ, đậm, nền, vị trí) và nhãn dán emoji
- Ảnh tĩnh với thời lượng tùy chỉnh
- Xuất: độ phân giải Gốc/4K/2K/1080p/720p/480p, chất lượng Gốc/Cao nhất/Tiết kiệm, H.264 hoặc H.265; lưu vào Movies/CutViet, chia sẻ ngay

## Build

Cần JDK 17 và Android SDK (platform 35).

```
./gradlew assembleRelease
```

APK nằm ở `app/build/outputs/apk/release/app-release.apk`, đã ký bằng khóa `app/cutviet.keystore`
(đi kèm mã nguồn để các bản build sau cài đè được lên bản trước).

Mỗi lần đẩy lên nhánh `main`, GitHub Actions tự build và đăng APK ở mục Releases.

Yêu cầu: Android 7.0 (API 24) trở lên.
