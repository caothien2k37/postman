# Mini Postman Vars (Android, Java + XML)

Ứng dụng Android gọi API kiểu Postman: import/export Postman Collection v2.x giữ nguyên cây folder, biến theo
phạm vi, script `pm.*` chạy thật để tự lưu token, lịch sử và Collection Runner. Viết bằng **Java 17 + XML Views**
(không Kotlin/Compose), theo đặc tả trong `API_Studio_Android_Specification.md`.

## Tính năng

**Collection & Import/Export**
- Cây collection nhiều cấp (RecyclerView + DiffUtil): mở/đóng folder, tìm theo tên/URL, lọc theo method, nhãn màu method.
  Nhấn giữ để đổi tên, nhân bản, di chuyển, lên/xuống, thêm request/folder, xóa, export, chạy Runner.
- Import **Postman Collection v2.1 (và v2.0)**, **Environment**, **Globals** từ: nhiều file cùng lúc, văn bản dán, URL, **lệnh cURL**.
  Luôn có màn hình xem trước (tên, schema, số folder/request/script/biến/ví dụ, cảnh báo); trùng tên thì hỏi
  *Tạo bản sao / Thay thế / Bỏ qua*, không tự ghi đè. Import chạy trong một transaction SQLite, lỗi thì rollback.
- Parser đệ quy `item[]`, **không flatten**; giữ thứ tự, auth (collection/folder/request), body, headers (kể cả disabled),
  scripts, biến, response examples và **mọi trường lạ** (lưu JSON gốc của từng nút). Export dựng lại đúng thứ tự khóa
  và số; import → export → import cho kết quả y hệt (có test so sánh từng ký tự).
- Giới hạn an toàn khi parse: 30 MB, 50.000 phần tử, 128 cấp folder, 512 cấp JSON. File lỗi/quá sâu chỉ báo lỗi, không crash.

**Biến**
- Phạm vi Global / Collection / Environment / Local / Data với thứ tự ưu tiên Postman:
  `Local > Data > Environment > Collection > Global`; biến bị tắt không dùng để thay thế; biến lồng biến
  (`iasUrl = http://{{host}}:{{iasPort}}/api/v1/iam`); biến động `{{$timestamp}}`, `{{$isoTimestamp}}`, `{{$guid}}`,
  `{{$randomUUID}}`, `{{$randomInt}}`.
- Nhiều environment, chuyển ở màn hình chính, import/export (hỏi có kèm giá trị secret không). Biến loại 🔒 *secret*
  được ẩn trên màn hình và **mã hóa AES-GCM bằng Android Keystore** khi lưu.
- Thiếu biến thì báo rõ tên biến thay vì gửi URL hỏng.

**Request & Response**
- GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS; tab Params (kèm Path Variables), Auth, Headers, Body, Scripts, Settings.
- Auth: Inherit (folder → collection), No Auth, Bearer, Basic, API Key (header/query), OAuth 2.0 (dùng Access Token có sẵn).
- Body: raw (json/text/xml/html/javascript, có Format + kiểm tra JSON), x-www-form-urlencoded, form-data (text), GraphQL.
- Response: status, thời gian, kích thước; Body Pretty/Raw, Headers, Cookies, Tests, Console; copy và lưu file.
  Chỉ ghi lại phần bạn sửa nên không làm mất trường lạ; có dấu ● chưa lưu và hỏi khi thoát.
- Cài đặt chung: timeout, theo redirect, kiểm tra SSL (mặc định **bật**, tắt có cảnh báo), cho phép `pm.sendRequest`.

**Script (sandbox JavaScript)**
- Pre-request và Post-response chạy theo thứ tự Collection → Folder → Request. Có `pm.environment/globals/collectionVariables/variables`
  (`get/set/unset/has/clear/toObject/replaceIn`), `pm.response` (`json()`, `text()`, `code`, `status`, `responseTime`, `headers`,
  `to.have.status(...)`...), `pm.request` (sửa header/URL/body), `pm.test`, `pm.expect` (kiểu Chai), `pm.sendRequest`, `pm.cookies`,
  `console.*`, `postman.setNextRequest`, và API kiểu cũ (`postman.setEnvironmentVariable`, `tests["..."]`, `responseBody`...).
- Sandbox: không có `Packages/java.*`, không filesystem/Android API, giới hạn thời gian (5 giây, không bắt được) và độ sâu đệ quy,
  mỗi script một scope riêng, thư viện chuẩn bị niêm phong giữa các lần chạy. Console/lỗi được **che token, secret và JWT**.
- Mục "Set biến từ response" của bản 1.x vẫn còn trong tab Scripts. Khi import, request có `login`, `token` hoặc `đăng nhập`
  trong tên/URL và chưa có script post-response sẽ được điền sẵn quy tắc đó như bản cũ (không đổi JSON Postman, không export).
  Cách làm chính bây giờ là script `pm.*`.

**Khác**: History (200 lượt gần nhất, chỉ lưu bản còn `{{biến}}`), Collection Runner (số lần lặp, dữ liệu CSV/JSON, dừng, giới hạn 1000 lượt,
`setNextRequest`), Dark mode, icon riêng. Dữ liệu của bản 1.x được **tự chuyển** sang cơ sở dữ liệu mới ở lần mở đầu tiên
(folder dạng `A / B` được dựng lại thành cây).

## Cấu trúc mã nguồn

```
app/src/main/java/vn/ioc/minipostman/
├── core/            Java thuần (không phụ thuộc Android) nên test được trên JVM
│   ├── model/       Node, CollectionDoc, EnvDoc, VarScope, KeyValue
│   ├── importexport/ PostmanParser, PostmanExporter, CurlParser, DataFile
│   ├── request/     RequestModel (đọc/ghi một request), UrlParts, AuthSpec
│   ├── vars/        VariableResolver, Redactor
│   ├── net/         HttpEngine (OkHttp), PreparedRequest, ResponseData, cookie jar
│   ├── script/      JsSandbox (Rhino) + resources/pm_prelude.js (đối tượng `pm`)
│   └── exec/        RequestExecutor: pre-request → thay biến → gửi → post-response
├── data/            SQLite (AppDb, CollectionRepository, EnvRepository, HistoryRepository), Keystore, migration bản cũ
├── ui/              TreeAdapter, CollectionViewModel (LiveData), KeyValueEditor, hộp thoại
└── *.java           Activity (Main, Request, Env, Import, History, Runner) + Store
```

## Build

**GitHub Actions** (không cần Android Studio): đẩy repo lên GitHub, tab **Actions** → job **Build APK** chạy test rồi build,
tải artifact **mini-postman-vars-apk** (`app-debug.apk`), chép sang điện thoại và cài.

**Android Studio** (Hedgehog trở lên, JDK 17): mở thư mục, chờ Gradle sync, Run hoặc Build > Build APK(s).

**Dòng lệnh** (Gradle 8.7 như trong `gradle-wrapper.properties` và workflow):

```bash
./gradlew testDebugUnitTest   # 90 test: parser, round-trip, biến, script, HTTP, SQLite, màn hình (Robolectric)
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug
```

## Đối chiếu với kịch bản nghiệm thu của đặc tả (mục 11)

| Hạng mục | Trạng thái | Bằng chứng |
|---|---|---|
| Import v2.1 có ≥ 5 tầng folder | Đạt | `PostmanParserTest.nestedFoldersKeepParentChildAndOrder` (6 tầng), `CollectionRepositoryTest.treeKeepsNestingAndSiblingOrder` |
| Import 200 API giữ tên, method, thứ tự, phân cấp | Đạt | `twoHundredRequestsKeepNamesMethodsAndOrder`, `twoHundredRequestsKeepOrderAfterReload` |
| Không mất Params/Headers/Auth/Body/Scripts/examples | Đạt | `RoundTripTest.importExportIsIdenticalIncludingOrderNumbersAndUnknownFields`, `importThenLoadThenExportIsIdenticalToOriginal` |
| Login → `pm.environment.set` lưu token | Đạt | `RequestExecutorTest.loginSavesTokenInRightScopes...`, `ActivitiesTest.sendLoginFromScreenSavesTokenAndRecordsHistory` |
| GET khác dùng `{{access_token_v1}}` | Đạt | cùng test trên (Bearer kế thừa từ collection) |
| Import → Export → Re-import không mất dữ liệu | Đạt | `RoundTripTest.reImportOfExportIsStable` |
| Import Environment, chọn môi trường khác | Đạt | `PostmanParserTest.environmentWithSecretIsParsed`, `CollectionRepositoryTest.environmentSelectionDefaultAndDeleteFallback` |
| Pre-request/Post-response và assertions | Đạt | `RequestExecutorTest` (thứ tự C→F→R, `pm.test`/`pm.expect`), `JsSandboxTest` |
| Đóng/mở app không mất dữ liệu | Đạt (mức DB) | dữ liệu nằm trong SQLite, test nạp lại từ DB; chưa thử quy trình kill process trên máy thật |
| JSON lỗi/trùng/quá lớn không crash | Đạt | `invalidInputGivesFriendlyErrors`, `tooDeepTreeIsRejected...`, `duplicateNameCopyAddsNewReplaceKeepsPosition`, `failedImportRollsBack...` |
| Chức năng chưa hỗ trợ được cảnh báo | Đạt | `warnsAboutUnsupportedFeaturesButStillImports`, OpenAPI/v1 bị từ chối rõ ràng |
| Bảng đối chiếu ≥ 70% tính năng Postman Desktop | **Chưa tuyên bố** | đặc tả yêu cầu chốt danh mục tham chiếu và test từng mục; bảng trên chỉ phản ánh mục 11 |

### Chưa làm (và cách app xử lý)
- **OpenAPI/Swagger**, import **thư mục** và **ZIP**: chưa có; OpenAPI bị từ chối với thông báo rõ ràng.
- **OAuth 2.0 tự lấy token**, Digest/AWS Signature/Hawk/NTLM: giữ nguyên cấu hình khi export nhưng khi gửi chỉ cảnh báo, không gửi auth đó.
- **Gửi file** (form-data file, binary body): bị bỏ qua khi gửi (có cảnh báo), dữ liệu gốc vẫn được giữ.
- Script dùng `require(...)` (lodash, crypto-js, moment...), `async/await`: báo lỗi/cảnh báo rõ; không có giới hạn *bộ nhớ* cho script (chỉ giới hạn thời gian và độ sâu).
- `pm.sendRequest` chạy đồng bộ rồi gọi callback ngay (khác Postman là bất đồng bộ), tối đa 10 lần mỗi script.
- Nhiều tab request cùng lúc, bố cục hai cột cho tablet, quản lý cookie chi tiết, proxy, GraphQL/WebSocket/gRPC, Mock Server/Monitor.
- Chưa có kiểm thử giao diện **trên thiết bị/emulator** (Espresso); thay vào đó dùng Robolectric trên JVM. Rhino trên môi trường chạy Android thật (ART) chưa được thử ở đây.

### Lựa chọn kỹ thuật khác đặc tả
| Đặc tả gợi ý | Dùng | Lý do |
|---|---|---|
| QuickJS (JNI) | **Rhino** (interpreter mode) | Bảng kiến trúc cho phép "engine phù hợp"; Rhino thuần Java nên chạy test trên JVM, có ClassShutter và giới hạn thời gian theo số lệnh. Hạn chế: ES5 + một phần ES6 (có `const/let`, arrow function, template string; không có class/async) |
| Room | `SQLiteOpenHelper` | Cùng schema (collections, nodes, environments, history), không cần annotation processor |
| Jackson | Gson | Giữ thứ tự khóa và số dạng text gốc để round-trip chính xác |
| MVVM toàn app | ViewModel + LiveData ở màn hình cây | Các màn hình còn lại đơn giản, dùng Activity + `Store` |

## Bảo mật
- Token/secret không được ghi log; console, thông báo lỗi và history đều che giá trị nhạy cảm. History chỉ lưu request chưa thay biến.
- `allowBackup=false`; biến secret mã hóa bằng khóa Keystore. Token trong `auth` của file collection import vẫn nằm trong JSON gốc
  (như file Postman) — đừng export collection chứa token thật lên nơi công khai.
- `usesCleartextTraffic=true` để gọi API trong mạng LAN qua `http://` như bản 1.x. HTTPS luôn kiểm tra chứng chỉ trừ khi bạn tự tắt trong Cài đặt.
- Script từ collection import chỉ gọi mạng qua `pm.sendRequest` (có thể tắt trong Cài đặt).
