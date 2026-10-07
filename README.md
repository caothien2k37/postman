# Mini Postman Vars (Android, Java)

App gửi HTTP request trên Android, có environment và tự set biến từ response.

## Tính năng
- Import file Postman collection v2.x và Postman environment (.json).
- Nhiều environment, chọn ở màn hình chính. Biến `{{tên}}` dùng được trong URL, header và body, kể cả biến lồng biến (`iasUrl = http://{{host}}:{{iasPort}}/api/v1/iam`).
- Biến động: `{{$timestamp}}`, `{{$guid}}`, `{{$randomUUID}}`, `{{$randomInt}}`.
- Mục "Set biến từ response": mỗi dòng `tênBiến = đường.dẫn.json`, ví dụ `accessToken = data.accessToken` hoặc `firstId = data.items[0].id`. Sau khi gửi, giá trị được lưu vào environment đang chọn.
- Khi import, request nào có `login`, `token` hoặc `Đăng nhập` trong tên/URL được điền sẵn `accessToken = data.accessToken` và `sessionId = data.sessionId`.
- Báo rõ biến nào chưa có trong environment thay vì gửi URL hỏng. Cho phép gọi `http://` trong mạng LAN.

## Build APK bằng GitHub (không cần Android Studio)
1. Tạo repo mới trên GitHub, đẩy toàn bộ thư mục này lên (có cả `.github/workflows/build-apk.yml`).
2. Vào tab **Actions**, chờ job **Build APK** chạy xong (khoảng 3–5 phút).
3. Mở lần chạy đó, tải artifact **mini-postman-vars-apk**, giải nén ra `app-debug.apk`.
4. Chép sang điện thoại và cài (cho phép cài từ nguồn không xác định).

## Build bằng Android Studio
Mở thư mục này bằng Android Studio (Hedgehog trở lên, JDK 17), chờ Gradle sync rồi Run hoặc
Build > Build APK(s). Nếu Studio báo thiếu Gradle wrapper, chạy `gradle wrapper --gradle-version 8.7`.

## Dùng với collection khay chi tiết
1. Import `khay-chi-tiet_local-iam.postman_environment.json`, rồi import `khay-chi-tiet_postman_collection.json`.
2. Mở "Đăng nhập test-chuyen-trach", bấm Send: status sẽ có dòng "Đã set biến: accessToken".
3. Mở request bất kỳ khác và Send, header `Authorization: Bearer {{accessToken}}` dùng token vừa lưu.
