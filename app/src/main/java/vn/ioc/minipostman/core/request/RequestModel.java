package vn.ioc.minipostman.core.request;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.Strings;
import vn.ioc.minipostman.core.model.KeyValue;

/**
 * Mô hình chỉnh sửa của một request Postman. Đọc từ item JSON, và chỉ ghi lại những phần người dùng đã
 * sửa (so sánh chữ ký lúc đọc) nên mọi trường lạ/định dạng gốc của phần không đụng tới đều được giữ nguyên.
 */
public final class RequestModel {

    public static final String[] METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"};
    public static final String[] RAW_LANGUAGES = {"json", "text", "xml", "html", "javascript"};

    public static final String BODY_NONE = "none";
    public static final String BODY_RAW = "raw";
    public static final String BODY_URLENCODED = "urlencoded";
    public static final String BODY_FORMDATA = "formdata";
    public static final String BODY_GRAPHQL = "graphql";
    public static final String BODY_FILE = "file";

    private JsonObject item;
    private final Map<String, String> original = new HashMap<>();

    public String name = "";
    public String method = "GET";
    /** URL dạng text, gồm query đang bật và fragment. */
    public String url = "";
    /** Toàn bộ query param (kể cả cái bị tắt). */
    public final List<KeyValue> query = new ArrayList<>();
    public final List<KeyValue> pathVars = new ArrayList<>();
    public final List<KeyValue> headers = new ArrayList<>();

    public String bodyMode = BODY_NONE;
    public String bodyRaw = "";
    public String rawLanguage = "text";
    public final List<KeyValue> urlencoded = new ArrayList<>();
    public final List<KeyValue> formdata = new ArrayList<>();
    public String graphqlQuery = "";
    public String graphqlVariables = "";

    public String authType = AuthSpec.INHERIT;
    public final Map<String, String> authParams = new LinkedHashMap<>();
    private JsonArray authRawParams;

    public String preRequest = "";
    public String tests = "";
    /** null = dùng cài đặt chung. */
    public Boolean followRedirects;

    private RequestModel(JsonObject item) {
        this.item = item;
    }

    public static RequestModel parse(JsonObject itemJson) {
        RequestModel m = new RequestModel(itemJson.deepCopy());
        m.read();
        for (String s : SECTIONS) m.original.put(s, m.signature(s));
        return m;
    }

    private static final String[] SECTIONS = {"name", "method", "url", "headers", "body", "auth", "pre", "tests", "redirect"};

    // ---------------------------------------------------------------- đọc

    private void read() {
        name = Json.str(item, "name");
        JsonElement reqEl = item.get("request");
        if (reqEl != null && reqEl.isJsonPrimitive()) {
            url = reqEl.getAsString();
            query.addAll(UrlParts.parseQuery(UrlParts.queryOf(url)));
        } else if (reqEl != null && reqEl.isJsonObject()) {
            readRequest(reqEl.getAsJsonObject());
        }
        preRequest = scriptOf(item, "prerequest");
        tests = scriptOf(item, "test");
        JsonObject ppb = Json.obj(item, "protocolProfileBehavior");
        if (ppb != null && ppb.has("followRedirects")) followRedirects = Json.bool(ppb, "followRedirects", true);
    }

    private void readRequest(JsonObject req) {
        String m = Json.str(req, "method");
        method = m.isEmpty() ? "GET" : m.toUpperCase(java.util.Locale.ROOT);

        JsonElement urlEl = req.get("url");
        url = UrlParts.rawOf(urlEl);
        query.addAll(UrlParts.queryOfUrl(urlEl, url));
        if (urlEl != null && urlEl.isJsonObject()) {
            JsonArray vars = Json.arr(urlEl.getAsJsonObject(), "variable");
            if (vars != null) {
                for (JsonElement e : vars) {
                    if (e.isJsonObject()) pathVars.add(KeyValue.fromJson(e.getAsJsonObject()));
                }
            }
        }

        JsonArray hs = Json.arr(req, "header");
        if (hs != null) {
            for (JsonElement e : hs) {
                if (e.isJsonObject()) headers.add(KeyValue.fromJson(e.getAsJsonObject()));
            }
        }

        JsonObject body = Json.obj(req, "body");
        if (body != null && !Json.bool(body, "disabled", false)) {
            String mode = Json.str(body, "mode");
            if (!mode.isEmpty()) bodyMode = mode;
            bodyRaw = Json.str(body, "raw");
            JsonObject opts = Json.obj(Json.obj(body, "options"), "raw");
            if (opts != null && !Json.str(opts, "language").isEmpty()) rawLanguage = Json.str(opts, "language");
            readKv(Json.arr(body, "urlencoded"), urlencoded);
            readKv(Json.arr(body, "formdata"), formdata);
            JsonObject gql = Json.obj(body, "graphql");
            if (gql != null) {
                graphqlQuery = Json.str(gql, "query");
                graphqlVariables = Json.str(gql, "variables");
            }
        }

        AuthSpec auth = AuthSpec.parse(Json.obj(req, "auth"));
        if (auth != null) {
            authType = auth.type;
            authParams.putAll(auth.params);
            authRawParams = auth.rawParams;
        }
    }

    private static void readKv(JsonArray arr, List<KeyValue> out) {
        if (arr == null) return;
        for (JsonElement e : arr) {
            if (e.isJsonObject()) out.add(KeyValue.fromJson(e.getAsJsonObject()));
        }
    }

    /** Nội dung script của một item/collection (nối các event cùng loại, bỏ event bị tắt). */
    public static String scriptOf(JsonObject owner, String listen) {
        JsonArray events = Json.arr(owner, "event");
        if (events == null) return "";
        List<String> parts = new ArrayList<>();
        for (JsonElement e : events) {
            if (!e.isJsonObject()) continue;
            JsonObject ev = e.getAsJsonObject();
            if (!listen.equals(Json.str(ev, "listen")) || Json.bool(ev, "disabled", false)) continue;
            JsonObject script = Json.obj(ev, "script");
            if (script == null) continue;
            JsonElement exec = script.get("exec");
            if (exec == null) continue;
            if (exec.isJsonArray()) {
                List<String> lines = new ArrayList<>();
                for (JsonElement l : exec.getAsJsonArray()) lines.add(l.isJsonPrimitive() ? l.getAsString() : "");
                parts.add(Strings.join("\n", lines));
            } else if (exec.isJsonPrimitive()) {
                parts.add(exec.getAsString());
            }
        }
        return Strings.join("\n", parts);
    }

    // ---------------------------------------------------------------- chỉnh URL/params

    /** Người dùng gõ vào ô URL: cập nhật các param đang bật, giữ nguyên các param đang tắt. */
    public void setUrlText(String text) {
        url = text;
        List<KeyValue> parsed = UrlParts.parseQuery(UrlParts.queryOf(text));
        List<KeyValue> next = new ArrayList<>();
        for (KeyValue p : parsed) {
            KeyValue reuse = null;
            for (KeyValue old : query) {
                if (old.enabled && old.key.equals(p.key) && !next.contains(old)) {
                    reuse = old;
                    break;
                }
            }
            if (reuse != null) {
                reuse.value = p.value;
                next.add(reuse);
            } else {
                next.add(p);
            }
        }
        for (KeyValue old : query) {
            if (!old.enabled) next.add(old);
        }
        query.clear();
        query.addAll(next);
    }

    /** Người dùng sửa bảng Params: dựng lại URL text từ các param đang bật. */
    public void syncUrlFromQuery() {
        url = UrlParts.buildRaw(UrlParts.baseOf(url), query, UrlParts.fragmentOf(url));
    }

    /** Tên các path variable dạng ":id" xuất hiện trong phần path của URL. */
    public List<String> pathVariableNames() {
        List<String> names = new ArrayList<>();
        String base = UrlParts.baseOf(url);
        int sch = base.indexOf("://");
        String rest = sch >= 0 ? base.substring(sch + 3) : base;
        int slash = rest.indexOf('/');
        if (slash < 0) return names;
        for (String seg : rest.substring(slash + 1).split("/")) {
            if (seg.length() > 1 && seg.charAt(0) == ':' && !names.contains(seg.substring(1))) {
                names.add(seg.substring(1));
            }
        }
        return names;
    }

    /** Thêm dòng path variable còn thiếu cho các ":name" trong URL. */
    public void ensurePathVars() {
        for (String n : pathVariableNames()) {
            boolean found = false;
            for (KeyValue kv : pathVars) {
                if (kv.key.equals(n)) {
                    found = true;
                    break;
                }
            }
            if (!found) pathVars.add(new KeyValue(n, ""));
        }
    }

    // ---------------------------------------------------------------- ghi

    private String signature(String section) {
        StringBuilder sb = new StringBuilder();
        switch (section) {
            case "name":
                return name;
            case "method":
                return method;
            case "url":
                sb.append(url);
                kvSig(sb, query);
                kvSig(sb, pathVars);
                return sb.toString();
            case "headers":
                kvSig(sb, headers);
                return sb.toString();
            case "body":
                sb.append(bodyMode).append('\u0001').append(bodyRaw).append('\u0001').append(rawLanguage)
                        .append('\u0001').append(graphqlQuery).append('\u0001').append(graphqlVariables);
                kvSig(sb, urlencoded);
                kvSig(sb, formdata);
                return sb.toString();
            case "auth":
                sb.append(authType);
                for (Map.Entry<String, String> e : authParams.entrySet()) {
                    sb.append('\u0001').append(e.getKey()).append('=').append(e.getValue());
                }
                return sb.toString();
            case "pre":
                return preRequest;
            case "tests":
                return tests;
            case "redirect":
                return String.valueOf(followRedirects);
            default:
                return "";
        }
    }

    private static void kvSig(StringBuilder sb, List<KeyValue> list) {
        sb.append('\u0002');
        for (KeyValue kv : list) {
            sb.append(kv.key).append('\u0001').append(kv.value).append('\u0001').append(kv.enabled)
                    .append('\u0001').append(kv.type).append('\u0003');
        }
    }

    private boolean changed(String section) {
        return !signature(section).equals(original.get(section));
    }

    /** Có thay đổi so với lúc đọc không (dùng cho dấu "chưa lưu"). */
    public boolean isDirty() {
        for (String s : SECTIONS) {
            if (changed(s)) return true;
        }
        return false;
    }

    /**
     * Chốt trạng thái hiện tại là "đã lưu": trả về item JSON để ghi xuống DB và lấy nó làm nền cho các lần
     * chỉnh sửa tiếp theo.
     */
    public JsonObject commit() {
        JsonObject saved = toItem();
        item = saved.deepCopy();
        for (String s : SECTIONS) original.put(s, signature(s));
        AuthSpec a = AuthSpec.parse(Json.obj(Json.obj(item, "request"), "auth"));
        authRawParams = a != null ? a.rawParams : null;
        return saved;
    }

    /** Dựng lại JSON item: chỉ ghi các phần đã đổi, giữ nguyên mọi phần/trường còn lại. */
    public JsonObject toItem() {
        JsonObject out = item.deepCopy();
        if (changed("name") || !out.has("name")) out.addProperty("name", name);

        boolean requestChanged = changed("method") || changed("url") || changed("headers") || changed("body")
                || changed("auth");
        JsonElement reqEl = out.get("request");
        JsonObject req = null;
        if (reqEl != null && reqEl.isJsonObject()) {
            req = reqEl.getAsJsonObject();
        } else if (reqEl == null || requestChanged) {
            req = new JsonObject();
            out.add("request", req);
            if (reqEl != null && reqEl.isJsonPrimitive() && !changed("url")) {
                // request gốc là chuỗi URL: giữ giá trị đó khi dựng object
                req.addProperty("method", method);
                JsonObject u = new JsonObject();
                u.addProperty("raw", reqEl.getAsString());
                req.add("url", u);
            }
        }

        if (req != null) {
            if (changed("method") || !req.has("method")) req.addProperty("method", method);
            if (changed("url") || (!req.has("url") && !url.isEmpty())) writeUrl(req);
            if (changed("headers")) {
                JsonArray hs = new JsonArray();
                for (KeyValue kv : headers) {
                    if (kv.key.isEmpty() && kv.value.isEmpty()) continue;
                    hs.add(kv.toJson(KeyValue.Style.DISABLED_FLAG));
                }
                req.add("header", hs);
            }
            if (changed("body")) writeBody(req);
            if (changed("auth")) writeAuth(req);
        }
        if (changed("pre")) writeScript(out, "prerequest", preRequest);
        if (changed("tests")) writeScript(out, "test", tests);
        if (changed("redirect")) {
            JsonObject ppb = Json.obj(out, "protocolProfileBehavior");
            if (ppb == null) {
                ppb = new JsonObject();
                out.add("protocolProfileBehavior", ppb);
            }
            if (followRedirects == null) ppb.remove("followRedirects");
            else ppb.addProperty("followRedirects", followRedirects);
            if (ppb.size() == 0) out.remove("protocolProfileBehavior");
        }
        return out;
    }

    private void writeUrl(JsonObject req) {
        JsonObject u = UrlParts.toUrlObject(url, query, req.get("url"));
        if (!pathVars.isEmpty() || u.has("variable")) {
            JsonArray vars = new JsonArray();
            for (KeyValue kv : pathVars) vars.add(kv.toJson(KeyValue.Style.DISABLED_FLAG));
            u.add("variable", vars);
            if (vars.size() == 0) u.remove("variable");
        }
        req.add("url", u);
    }

    private void writeBody(JsonObject req) {
        if (BODY_NONE.equals(bodyMode)) {
            req.remove("body");
            return;
        }
        JsonObject body = Json.obj(req, "body");
        if (body == null) {
            body = new JsonObject();
            req.add("body", body);
        }
        body.addProperty("mode", bodyMode);
        body.remove("disabled");
        switch (bodyMode) {
            case BODY_RAW: {
                body.addProperty("raw", bodyRaw);
                JsonObject options = Json.obj(body, "options");
                if (options == null) {
                    options = new JsonObject();
                    body.add("options", options);
                }
                JsonObject rawOpts = Json.obj(options, "raw");
                if (rawOpts == null) {
                    rawOpts = new JsonObject();
                    options.add("raw", rawOpts);
                }
                rawOpts.addProperty("language", rawLanguage);
                break;
            }
            case BODY_URLENCODED:
                body.add("urlencoded", kvArray(urlencoded));
                break;
            case BODY_FORMDATA:
                body.add("formdata", kvArray(formdata));
                break;
            case BODY_GRAPHQL: {
                JsonObject gql = Json.obj(body, "graphql");
                if (gql == null) {
                    gql = new JsonObject();
                    body.add("graphql", gql);
                }
                gql.addProperty("query", graphqlQuery);
                gql.addProperty("variables", graphqlVariables);
                break;
            }
            default:
                break;
        }
    }

    private static JsonArray kvArray(List<KeyValue> list) {
        JsonArray a = new JsonArray();
        for (KeyValue kv : list) {
            if (kv.key.isEmpty() && kv.value.isEmpty()) continue;
            a.add(kv.toJson(KeyValue.Style.DISABLED_FLAG));
        }
        return a;
    }

    private void writeAuth(JsonObject req) {
        if (AuthSpec.INHERIT.equals(authType)) {
            req.remove("auth");
            return;
        }
        JsonObject old = Json.obj(req, "auth");
        JsonObject auth = new JsonObject();
        auth.addProperty("type", authType);
        if (!AuthSpec.NOAUTH.equals(authType)) {
            JsonArray params = new JsonArray();
            for (Map.Entry<String, String> e : authParams.entrySet()) {
                JsonObject p = null;
                JsonArray src = (old != null && authType.equals(Json.str(old, "type")))
                        ? Json.arr(old, authType) : authRawParams;
                if (src != null) {
                    for (JsonElement el : src) {
                        if (el.isJsonObject() && e.getKey().equals(Json.str(el.getAsJsonObject(), "key"))) {
                            p = el.getAsJsonObject().deepCopy();
                            break;
                        }
                    }
                }
                if (p == null) {
                    p = new JsonObject();
                    p.addProperty("key", e.getKey());
                    p.addProperty("type", "string");
                }
                p.addProperty("value", e.getValue());
                params.add(p);
            }
            auth.add(authType, params);
        }
        req.add("auth", auth);
    }

    private static void writeScript(JsonObject out, String listen, String text) {
        JsonArray events = Json.arr(out, "event");
        int first = -1;
        List<Integer> dup = new ArrayList<>();
        if (events != null) {
            for (int i = 0; i < events.size(); i++) {
                JsonElement e = events.get(i);
                if (e.isJsonObject() && listen.equals(Json.str(e.getAsJsonObject(), "listen"))) {
                    if (first < 0) first = i;
                    else dup.add(i);
                }
            }
        }
        boolean empty = text.trim().isEmpty();
        if (events == null) {
            if (empty) return;
            events = new JsonArray();
            out.add("event", events);
        }
        if (empty) {
            List<Integer> all = new ArrayList<>(dup);
            if (first >= 0) all.add(0, first);
            for (int i = all.size() - 1; i >= 0; i--) events.remove(all.get(i));
            if (events.size() == 0) out.remove("event");
            return;
        }
        JsonArray exec = new JsonArray();
        for (String l : Strings.lines(text)) exec.add(l);
        if (first >= 0) {
            JsonObject ev = events.get(first).getAsJsonObject();
            ev.remove("disabled");
            JsonObject script = Json.obj(ev, "script");
            if (script == null) {
                script = new JsonObject();
                ev.add("script", script);
            }
            if (!script.has("type")) script.addProperty("type", "text/javascript");
            script.remove("src");
            script.add("exec", exec);
            for (int i = dup.size() - 1; i >= 0; i--) events.remove(dup.get(i));
        } else {
            JsonObject ev = new JsonObject();
            ev.addProperty("listen", listen);
            JsonObject script = new JsonObject();
            script.addProperty("type", "text/javascript");
            script.add("exec", exec);
            ev.add("script", script);
            events.add(ev);
        }
    }

    /** Bản sao độc lập (đã gồm các chỉnh sửa hiện tại) để script có thể sửa mà không ảnh hưởng bản gốc. */
    public RequestModel copy() {
        return parse(toItem());
    }

    /** Giá trị header (không phân biệt hoa thường) đang bật, hoặc null. */
    public String headerValue(String headerName) {
        for (KeyValue kv : headers) {
            if (kv.enabled && kv.key.equalsIgnoreCase(headerName)) return kv.value;
        }
        return null;
    }
}
