package vn.ioc.minipostman.core.importexport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.Strings;
import vn.ioc.minipostman.core.model.CollectionDoc;
import vn.ioc.minipostman.core.model.EnvDoc;
import vn.ioc.minipostman.core.model.KeyValue;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.core.request.AuthSpec;
import vn.ioc.minipostman.core.request.RequestModel;

/**
 * Phân tích Postman Collection v2.x / Environment / Globals / cURL thành mô hình trong bộ nhớ.
 * Duyệt item[] đệ quy, KHÔNG flatten: folder giữ nguyên cấp và thứ tự, mọi trường lạ giữ trong raw.
 */
public final class PostmanParser {

    public static final int MAX_CHARS = 30 * 1024 * 1024;
    public static final int MAX_NODES = 50_000;
    public static final int MAX_FOLDER_DEPTH = 128;
    private static final int MAX_WARNINGS = 40;

    private PostmanParser() {
    }

    public static ImportResult parse(String text, String sourceName) throws ImportException {
        if (text == null) throw new ImportException("Nội dung rỗng");
        if (text.length() > MAX_CHARS) {
            throw new ImportException("File quá lớn (" + (text.length() / 1024 / 1024) + " MB, tối đa "
                    + (MAX_CHARS / 1024 / 1024) + " MB)");
        }
        String t = Json.stripBom(text).trim();
        if (t.isEmpty()) throw new ImportException("Nội dung rỗng");

        if (t.regionMatches(true, 0, "curl", 0, 4) && (t.length() == 4 || Character.isWhitespace(t.charAt(4)))) {
            return CurlParser.parse(t);
        }
        if (t.charAt(0) != '{') {
            throw new ImportException("Không nhận ra định dạng: cần file JSON của Postman hoặc lệnh cURL");
        }

        JsonObject root;
        try {
            root = Json.parseObject(t);
        } catch (JsonParseException e) {
            throw new ImportException("JSON không hợp lệ: " + firstLine(e.getMessage()), e);
        } catch (StackOverflowError e) {
            throw new ImportException("JSON lồng quá sâu");
        }

        JsonObject wrapped = Json.obj(root, "collection");
        if (!root.has("item") && !root.has("info") && wrapped != null) root = wrapped;

        if (root.has("openapi") || root.has("swagger")) {
            throw new ImportException("OpenAPI/Swagger chưa được hỗ trợ (đang ở mức ưu tiên P1). "
                    + "Hãy chuyển sang Postman Collection v2.1 rồi import lại.");
        }
        if (root.has("item") && root.get("item").isJsonArray()) return parseCollection(root, sourceName);
        if (root.has("requests") && root.has("order")) {
            throw new ImportException("Collection v1 chưa hỗ trợ. Hãy xuất lại từ Postman ở định dạng v2.1.");
        }
        if (root.has("values") && root.get("values").isJsonArray()) return parseEnvironment(root, sourceName);
        throw new ImportException("Không nhận ra file: cần Postman Collection v2.x hoặc Environment/Globals");
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    // ---------------------------------------------------------------- environment

    private static ImportResult parseEnvironment(JsonObject root, String sourceName) {
        ImportResult r = new ImportResult();
        r.sourceName = sourceName;
        EnvDoc env = EnvDoc.fromJson(root, baseName(sourceName, "Imported environment"));
        r.environment = env;
        r.kind = EnvDoc.KIND_GLOBALS.equals(env.kind) ? ImportResult.Kind.GLOBALS : ImportResult.Kind.ENVIRONMENT;
        r.name = env.name;
        r.variables = env.vars.items().size();
        int secrets = 0;
        int empty = 0;
        for (KeyValue kv : env.vars.items()) {
            if (kv.isSecret()) {
                secrets++;
                if (kv.value.isEmpty()) empty++;
            }
        }
        if (secrets > 0) {
            r.warnings.add(secrets + " biến loại secret" + (empty > 0 ? " (" + empty + " biến không có giá trị trong file, cần nhập lại)" : "")
                    + "; giá trị secret được lưu mã hóa trên máy.");
        }
        r.schema = "Postman " + (r.kind == ImportResult.Kind.GLOBALS ? "Globals" : "Environment");
        return r;
    }

    // ---------------------------------------------------------------- collection

    private static final class Ctx {
        int folders;
        int requests;
        int unknown;
        int scripts;
        int examples;
        int maxDepth;
        int count;
        final ImportResult result;
        final Set<String> seenWarnings = new HashSet<>();
        int droppedWarnings;

        Ctx(ImportResult result) {
            this.result = result;
        }

        void warn(String key, String message) {
            if (!seenWarnings.add(key)) return;
            if (result.warnings.size() >= MAX_WARNINGS) {
                droppedWarnings++;
                return;
            }
            result.warnings.add(message);
        }
    }

    private static ImportResult parseCollection(JsonObject root, String sourceName) throws ImportException {
        ImportResult r = new ImportResult();
        r.kind = ImportResult.Kind.COLLECTION;
        r.sourceName = sourceName;
        Ctx ctx = new Ctx(r);

        // Bảo đảm có "info" ở đầu (collection xuất từ công cụ khác có thể thiếu).
        JsonObject info = Json.obj(root, "info");
        if (info == null) {
            JsonObject fixed = new JsonObject();
            info = new JsonObject();
            info.addProperty("name", baseName(sourceName, "Imported collection"));
            info.addProperty("schema", CollectionDoc.SCHEMA_V21);
            fixed.add("info", info);
            for (java.util.Map.Entry<String, JsonElement> e : root.entrySet()) fixed.add(e.getKey(), e.getValue());
            root = fixed;
            r.warnings.add("File không có phần info; đã tạo tên và schema mặc định.");
        }

        CollectionDoc doc = new CollectionDoc();
        doc.raw = root;
        doc.name = Json.str(info, "name");
        if (doc.name.isEmpty()) {
            doc.name = baseName(sourceName, "Imported collection");
            info.addProperty("name", doc.name);
        }
        doc.schema = Json.str(info, "schema");
        doc.updatedAt = System.currentTimeMillis();

        String schema = doc.schema.toLowerCase(Locale.ROOT);
        if (schema.contains("v2.1")) r.schema = "Postman Collection v2.1";
        else if (schema.contains("v2.0")) {
            r.schema = "Postman Collection v2.0";
            r.warnings.add("Schema v2.0: dữ liệu được đọc theo khả năng tốt nhất và giữ nguyên khi export.");
        } else {
            r.schema = doc.schema.isEmpty() ? "Không rõ (coi như v2.1)" : doc.schema;
            r.warnings.add("Schema không nhận ra (\"" + doc.schema + "\"); coi như v2.1.");
        }
        r.name = doc.name;

        ctx.scripts += countEvents(root);
        checkAuth(Json.obj(root, "auth"), "collection \"" + doc.name + "\"", ctx);
        checkScripts(root, "collection \"" + doc.name + "\"", ctx);

        JsonArray items = root.get("item").getAsJsonArray();
        root.add("item", new JsonArray()); // giữ chỗ đúng vị trí khóa "item" khi export
        parseItems(items, doc.roots, 0, ctx);

        JsonArray vars = Json.arr(root, "variable");
        r.variables = vars == null ? 0 : vars.size();
        r.folders = ctx.folders;
        r.requests = ctx.requests;
        r.unknownItems = ctx.unknown;
        r.scripts = ctx.scripts;
        r.examples = ctx.examples;
        r.maxDepth = ctx.maxDepth;
        if (ctx.droppedWarnings > 0) r.warnings.add("... và " + ctx.droppedWarnings + " cảnh báo khác.");
        r.collection = doc;
        return r;
    }

    private static void parseItems(JsonArray items, List<Node> out, int depth, Ctx ctx) throws ImportException {
        if (depth > MAX_FOLDER_DEPTH) {
            throw new ImportException("Cây folder lồng quá sâu (> " + MAX_FOLDER_DEPTH + " cấp)");
        }
        for (JsonElement el : items) {
            if (!el.isJsonObject()) {
                ctx.warn("nonobject", "Bỏ qua phần tử trong item[] không phải object.");
                continue;
            }
            if (++ctx.count > MAX_NODES) {
                throw new ImportException("Quá nhiều phần tử (> " + MAX_NODES + ")");
            }
            JsonObject it = el.getAsJsonObject();
            Node n = new Node();
            n.name = Json.str(it, "name");
            n.raw = it;
            ctx.scripts += countEvents(it);

            JsonElement sub = it.get("item");
            if (sub != null && sub.isJsonArray()) {
                n.type = Node.Type.FOLDER;
                ctx.folders++;
                ctx.maxDepth = Math.max(ctx.maxDepth, depth + 1);
                it.add("item", new JsonArray()); // con nằm ở n.children
                String where = "folder \"" + n.name + "\"";
                checkAuth(Json.obj(it, "auth"), where, ctx);
                checkScripts(it, where, ctx);
                out.add(n);
                parseItems(sub.getAsJsonArray(), n.children, depth + 1, ctx);
            } else if (it.has("request")) {
                n.type = Node.Type.REQUEST;
                ctx.requests++;
                JsonArray responses = Json.arr(it, "response");
                if (responses != null) ctx.examples += responses.size();
                checkRequest(it, n.name, ctx);
                out.add(n);
            } else {
                n.type = Node.Type.UNKNOWN;
                ctx.unknown++;
                ctx.warn("unknown:" + n.name, "Phần tử \"" + n.name + "\" không phải folder/request; được giữ nguyên để export.");
                out.add(n);
            }
        }
    }

    private static int countEvents(JsonObject o) {
        JsonArray ev = Json.arr(o, "event");
        return ev == null ? 0 : ev.size();
    }

    // ---------------------------------------------------------------- cảnh báo tương thích

    private static void checkRequest(JsonObject item, String name, Ctx ctx) {
        String label = "request \"" + name + "\"";
        JsonElement reqEl = item.get("request");
        if (reqEl != null && reqEl.isJsonObject()) {
            JsonObject req = reqEl.getAsJsonObject();
            checkAuth(Json.obj(req, "auth"), label, ctx);
            JsonObject body = Json.obj(req, "body");
            if (body != null) {
                String mode = Json.str(body, "mode");
                if ("file".equals(mode) || "binary".equals(mode)) {
                    ctx.warn("body-file", "Body dạng file/binary (" + label + ") chưa gửi được; dữ liệu vẫn được giữ.");
                }
                JsonArray fd = Json.arr(body, "formdata");
                if (fd != null) {
                    for (JsonElement e : fd) {
                        if (e.isJsonObject() && "file".equals(Json.str(e.getAsJsonObject(), "type"))) {
                            ctx.warn("formdata-file", "Form-data có trường file (" + label
                                    + ") sẽ bị bỏ qua khi gửi; dữ liệu vẫn được giữ.");
                            break;
                        }
                    }
                }
            }
        }
        checkScripts(item, label, ctx);
    }

    private static void checkAuth(JsonObject auth, String where, Ctx ctx) {
        AuthSpec a = AuthSpec.parse(auth);
        if (a == null) return;
        if (!AuthSpec.isSupported(a.type)) {
            ctx.warn("auth:" + a.type, "Auth \"" + a.type + "\" (" + where + ") chưa hỗ trợ khi gửi; cấu hình vẫn được giữ để export.");
        } else if (AuthSpec.OAUTH2.equals(a.type)) {
            ctx.warn("auth:oauth2", "OAuth 2.0 chỉ dùng Access Token có sẵn; chưa hỗ trợ lấy token tự động.");
        }
    }

    private static final String[][] UNSUPPORTED_SCRIPT_APIS = {
            {"require(", "require() (thư viện ngoài như lodash, crypto-js...)"},
            {"CryptoJS", "CryptoJS"},
            {"moment(", "moment"},
            {"cheerio", "cheerio"},
            {"xml2Json", "xml2Json"},
            {"pm.visualizer", "pm.visualizer"},
            {"await ", "async/await"},
    };

    private static void checkScripts(JsonObject owner, String where, Ctx ctx) {
        if (!owner.has("event")) return;
        String code = RequestModel.scriptOf(owner, "prerequest") + "\n" + RequestModel.scriptOf(owner, "test");
        if (code.trim().isEmpty()) return;
        for (String[] api : UNSUPPORTED_SCRIPT_APIS) {
            if (code.contains(api[0])) {
                ctx.warn("script:" + api[0], "Script (" + where + ") dùng " + api[1]
                        + ", chưa được sandbox hỗ trợ; script vẫn được giữ nguyên.");
            }
        }
    }

    static String baseName(String sourceName, String fallback) {
        String s = Strings.nz(sourceName).trim();
        if (s.isEmpty()) return fallback;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        String lower = s.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".postman_collection.json", ".postman_environment.json", ".postman_globals.json", ".json"}) {
            if (lower.endsWith(ext)) {
                s = s.substring(0, s.length() - ext.length());
                break;
            }
        }
        return s.isEmpty() ? fallback : s;
    }
}
