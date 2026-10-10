# CutViet – ứng dụng chỉnh sửa video Android

Ứng dụng chỉnh sửa video kiểu CapCut (chỉ phần chỉnh sửa), viết bằng Kotlin + Jetpack Compose + AndroidX Media3.
Xuất video giữ nguyên độ phân giải, khung hình/giây và bitrate tương đương bản gốc.

## Tính năng

- Dự án nháp tự lưu; tạo dự án từ nhiều video và ảnh; nhận video được chia sẻ từ ứng dụng khác
- Dòng thời gian kiểu CapCut: đầu phát cố định ở giữa, kéo để tua, phóng to/thu nhỏ, ảnh thu nhỏ từng đoạn
- Tách, cắt đầu/cuối (kéo mép đoạn trên dòng thời gian; đoạn ảnh kéo dài/thu ngắn tùy ý), xóa, nhân bản, đổi thứ tự đoạn; hoàn tác / làm lại từng thao tác (giữ 5 bước, còn sau khi mở lại dự án)
- Tốc độ 0.25x–4x (âm thanh đổi theo, giữ cao độ)
- Âm lượng từng đoạn (0–200%), tắt tiếng
- Nhiều lớp âm thanh: nhạc (từ tệp âm thanh hoặc video), âm thanh tách từ đoạn video ("Tách âm thanh"), giọng đọc;
  mỗi lớp có âm lượng, tốc độ, cắt đoạn, lặp lại, kéo dãn hai mép và giữ-kéo để di chuyển trên dòng thời gian
- Lớp phủ (video hoặc ảnh chồng lên video chính): vị trí, kích thước, xoay, độ trong suốt, lật, âm lượng và tốc độ riêng,
  kéo dãn hai mép, giữ-kéo để di chuyển, nhân bản, xóa
- Lớp phủ xếp tầng: mỗi lớp phủ một tầng riêng trên dòng thời gian (có ảnh khung hình, tên tệp, thời lượng), tầng trên cùng là lớp nằm trên;
  giữ rồi kéo để dời thời điểm hoặc kéo lên/xuống để đổi tầng, kéo hai mép để kéo dài/thu ngắn, bám vào mép đoạn và đầu phát
- Lớp phủ hiện ngay trên khung xem trước khi chỉnh sửa (đúng vị trí, kích thước, tẩy nền, mặt nạ, vùng đã tẩy), cập nhật tức thì khi kéo/chỉnh; video lớp phủ chạy cùng video chính khi bấm phát
- Trên khung xem trước: chạm để chọn lớp phủ, kéo để di chuyển, kéo góc dưới bên phải để đổi kích thước
- Tẩy nền lớp phủ (phông xanh lá/xanh dương, nền đen/trắng hoặc màu tùy chọn) với cường độ và làm mềm viền
- Tẩy một phần lớp phủ bằng cọ vẽ tay (tẩy/khôi phục, cỡ cọ, độ mềm, phóng to để tẩy chi tiết)
- Mặt nạ lớp phủ: tròn, chữ nhật bo góc, trái tim, ngôi sao, đường thẳng, dải gương; làm mềm viền, xoay, đảo ngược
- Giọng đọc AI tiếng Việt từ văn bản (dùng giọng tiếng Việt có trên điện thoại): nhiều giọng, mẫu giọng, tốc độ 0.5x–3x,
  ước tính thời lượng ngay khi gõ và đo chính xác sau đó, hiện đoạn video mà giọng đọc sẽ phủ lên
- Cắt khung toàn màn hình: kéo góc/cạnh khung cắt, kéo bên trong để di chuyển, chỉnh nghiêng bằng thước xoay (-45° đến 45°), phản chiếu, xoay 90°, chọn tỉ lệ (tự do, gốc, vuông, 9:16, 16:9, 4:5, 3:4, 21:9...); xem/tua video ngay trong màn hình cắt
- Xoay 90°, lật ngang/dọc (khung cắt xoay theo hình)
- Tỉ lệ khung hình dự án: Gốc, 9:16, 16:9, 1:1, 4:5, 3:4, 4:3, 21:9; vừa khung hoặc lấp đầy
- 11 bộ lọc màu; điều chỉnh độ sáng, tương phản, bão hòa, nhiệt độ màu, sắc độ; áp dụng cho tất cả các đoạn
- Văn bản (màu, cỡ, đậm, nền, vị trí) và nhãn dán emoji
- Ảnh tĩnh với thời lượng tùy chỉnh
- Xuất: độ phân giải Gốc/4K/2K/1080p/720p/480p, chất lượng Gốc/Cao nhất/Tiết kiệm, H.264 hoặc H.265; lưu vào Movies/CutViet, chia sẻ ngay

## Build

Cần JDK 17 và Android SDK (platform 36).

```
./gradlew assembleRelease
```

APK nằm ở `app/build/outputs/apk/release/app-release.apk`, đã ký bằng khóa `app/cutviet.keystore`
(đi kèm mã nguồn để các bản build sau cài đè được lên bản trước).

Mỗi lần đẩy lên nhánh `main`, GitHub Actions tự build và đăng APK ở mục Releases.

Yêu cầu: Android 7.0 (API 24) trở lên.
