package vn.ioc.minipostman.core.request;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.Strings;
import vn.ioc.minipostman.core.model.KeyValue;

/** Tách và ghép URL kiểu Postman: raw string ⇄ {protocol, host[], port, path[], query[]}. */
public final class UrlParts {

    private UrlParts() {
    }

    /** Phần trước dấu '?' hoặc '#'. */
    public static String baseOf(String raw) {
        String s = Strings.nz(raw);
        int cut = s.length();
        int q = s.indexOf('?');
        int h = s.indexOf('#');
        if (q >= 0) cut = Math.min(cut, q);
        if (h >= 0) cut = Math.min(cut, h);
        return s.substring(0, cut);
    }

    /** Phần query (không gồm '?'), bỏ fragment; "" nếu không có. */
    public static String queryOf(String raw) {
        String s = Strings.nz(raw);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        int q = s.indexOf('?');
        return q < 0 ? "" : s.substring(q + 1);
    }

    public static String fragmentOf(String raw) {
        String s = Strings.nz(raw);
        int h = s.indexOf('#');
        return h < 0 ? "" : s.substring(h);
    }

    /** Tách "a=b&c=d" giữ nguyên dạng text (không decode). */
    public static List<KeyValue> parseQuery(String query) {
        List<KeyValue> out = new ArrayList<>();
        if (Strings.isEmpty(query)) return out;
        for (String part : query.split("&", -1)) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            out.add(eq < 0 ? new KeyValue(part, "") : new KeyValue(part.substring(0, eq), part.substring(eq + 1)));
        }
        return out;
    }

    /** Ghép các param đang bật thành chuỗi query. */
    public static String joinQuery(List<KeyValue> params) {
        StringBuilder sb = new StringBuilder();
        for (KeyValue kv : params) {
            if (!kv.enabled || (kv.key.isEmpty() && kv.value.isEmpty())) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(kv.key).append('=').append(kv.value);
        }
        return sb.toString();
    }

    public static String buildRaw(String base, List<KeyValue> params, String fragment) {
        String q = joinQuery(params);
        return Strings.nz(base) + (q.isEmpty() ? "" : "?" + q) + Strings.nz(fragment);
    }

    /** raw của một phần tử "url" (string hoặc object); dựng lại từ thành phần nếu thiếu raw. */
    public static String rawOf(JsonElement url) {
        if (url == null || url.isJsonNull()) return "";
        if (url.isJsonPrimitive()) return url.getAsString();
        if (!url.isJsonObject()) return "";
        JsonObject o = url.getAsJsonObject();
        if (o.has("raw")) return Json.str(o, "raw");
        StringBuilder sb = new StringBuilder();
        String protocol = Json.str(o, "protocol");
        if (!protocol.isEmpty()) sb.append(protocol).append("://");
        sb.append(joinElement(o.get("host"), "."));
        String port = Json.str(o, "port");
        if (!port.isEmpty()) sb.append(':').append(port);
        String path = joinElement(o.get("path"), "/");
        if (!path.isEmpty()) sb.append('/').append(path);
        JsonArray q = Json.arr(o, "query");
        if (q != null && q.size() > 0) {
            List<KeyValue> params = new ArrayList<>();
            for (JsonElement e : q) {
                if (e.isJsonObject()) params.add(KeyValue.fromJson(e.getAsJsonObject()));
            }
            String joined = joinQuery(params);
            if (!joined.isEmpty()) sb.append('?').append(joined);
        }
        return sb.toString();
    }

    /** Vị trí cuối cùng của ký tự c nằm ngoài {{...}} và [...] (-1 nếu không có). */
    private static int topLevelIndexOf(String s, char c) {
        int brace = 0;
        int bracket = 0;
        int found = -1;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{') brace++;
            else if (ch == '}') brace = Math.max(0, brace - 1);
            else if (ch == '[') bracket++;
            else if (ch == ']') bracket = Math.max(0, bracket - 1);
            else if (ch == c && brace == 0 && bracket == 0) found = i;
        }
        return found;
    }

    private static List<String> splitOutsideBraces(String s, char sep) {
        List<String> parts = new ArrayList<>();
        int brace = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{') brace++;
            else if (ch == '}') brace = Math.max(0, brace - 1);
            if (ch == sep && brace == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        parts.add(cur.toString());
        return parts;
    }

    private static String joinElement(JsonElement e, String sep) {
        if (e == null || e.isJsonNull()) return "";
        if (e.isJsonPrimitive()) return e.getAsString();
        if (!e.isJsonArray()) return "";
        List<String> parts = new ArrayList<>();
        for (JsonElement p : e.getAsJsonArray()) {
            if (p.isJsonPrimitive()) parts.add(p.getAsString());
            else if (p.isJsonObject()) parts.add(Json.str(p.getAsJsonObject(), "value"));
        }
        return Strings.join(sep, parts);
    }

    /** query[] của URL object (kể cả param bị tắt), hoặc parse từ raw khi thiếu. */
    public static List<KeyValue> queryOfUrl(JsonElement url, String raw) {
        List<KeyValue> out = new ArrayList<>();
        if (url != null && url.isJsonObject()) {
            JsonArray q = Json.arr(url.getAsJsonObject(), "query");
            if (q != null) {
                for (JsonElement e : q) {
                    if (e.isJsonObject()) out.add(KeyValue.fromJson(e.getAsJsonObject()));
                }
                return out;
            }
        }
        return parseQuery(queryOf(raw));
    }

    /**
     * Dựng lại URL object từ raw + query; giữ các trường lạ (variable, ...) của object gốc.
     */
    public static JsonObject toUrlObject(String raw, List<KeyValue> query, JsonElement originalUrl) {
        JsonObject out = (originalUrl != null && originalUrl.isJsonObject())
                ? originalUrl.getAsJsonObject().deepCopy() : new JsonObject();
        out.addProperty("raw", raw);

        String noFragment = raw;
        int h = noFragment.indexOf('#');
        if (h >= 0) noFragment = noFragment.substring(0, h);
        String base = baseOf(noFragment);

        String rest = base;
        String protocol = "";
        int sch = rest.indexOf("://");
        if (sch > 0) {
            protocol = rest.substring(0, sch);
            rest = rest.substring(sch + 3);
        }
        String authority = rest;
        String path = "";
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            authority = rest.substring(0, slash);
            path = rest.substring(slash + 1);
        }
        String port = "";
        int at = authority.lastIndexOf('@');
        String hostPart = at >= 0 ? authority.substring(at + 1) : authority;
        int colon = topLevelIndexOf(hostPart, ':');
        if (colon >= 0) {
            port = hostPart.substring(colon + 1);
            hostPart = hostPart.substring(0, colon);
        }

        if (protocol.isEmpty()) out.remove("protocol");
        else out.addProperty("protocol", protocol);

        JsonArray host = new JsonArray();
        if (hostPart.startsWith("[")) {
            host.add(hostPart);
        } else {
            for (String p : splitOutsideBraces(hostPart, '.')) {
                if (!p.isEmpty()) host.add(p);
            }
        }
        out.add("host", host);

        if (port.isEmpty()) out.remove("port");
        else out.addProperty("port", port);

        JsonArray pathArr = new JsonArray();
        if (!path.isEmpty()) {
            for (String p : path.split("/", -1)) pathArr.add(p);
        }
        if (pathArr.size() > 0) out.add("path", pathArr);
        else out.remove("path");

        if (!query.isEmpty() || out.has("query")) {
            JsonArray qa = new JsonArray();
            for (KeyValue kv : query) qa.add(kv.toJson(KeyValue.Style.DISABLED_FLAG));
            out.add("query", qa);
        }
        return out;
    }
}
