package vn.ioc.minipostman.core.importexport;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.Strings;
import vn.ioc.minipostman.core.model.CollectionDoc;
import vn.ioc.minipostman.core.model.KeyValue;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.core.request.UrlParts;

/** Chuyển một lệnh cURL (bash hoặc "Copy as cURL" của trình duyệt) thành một request Postman v2.1. */
public final class CurlParser {

    private CurlParser() {
    }

    public static ImportResult parse(String command) throws ImportException {
        List<String> tokens = tokenize(command);
        if (tokens.isEmpty() || !tokens.get(0).equalsIgnoreCase("curl")) {
            throw new ImportException("Không phải lệnh cURL");
        }

        String method = null;
        String url = null;
        boolean head = false;
        boolean getMode = false;
        String user = null;
        List<String[]> headers = new ArrayList<>();
        List<String> data = new ArrayList<>();
        boolean dataIsUrlencode = false;
        List<String[]> form = new ArrayList<>();
        boolean json = false;
        List<String> ignored = new ArrayList<>();

        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            String[] attached = splitAttached(t);
            String opt = attached[0];
            String inline = attached[1];

            switch (opt) {
                case "-X": case "--request":
                    method = inline != null ? inline : next(tokens, ++i);
                    break;
                case "-H": case "--header": {
                    String h = inline != null ? inline : next(tokens, ++i);
                    int c = h.indexOf(':');
                    if (c > 0) headers.add(new String[]{h.substring(0, c).trim(), h.substring(c + 1).trim()});
                    break;
                }
                case "-d": case "--data": case "--data-raw": case "--data-binary": case "--data-ascii":
                    data.add(inline != null ? inline : next(tokens, ++i));
                    break;
                case "--data-urlencode":
                    data.add(inline != null ? inline : next(tokens, ++i));
                    dataIsUrlencode = true;
                    break;
                case "--json":
                    data.add(inline != null ? inline : next(tokens, ++i));
                    json = true;
                    break;
                case "-F": case "--form": case "--form-string": {
                    String f = inline != null ? inline : next(tokens, ++i);
                    int eq = f.indexOf('=');
                    form.add(eq < 0 ? new String[]{f, ""} : new String[]{f.substring(0, eq), f.substring(eq + 1)});
                    break;
                }
                case "-u": case "--user":
                    user = inline != null ? inline : next(tokens, ++i);
                    break;
                case "-A": case "--user-agent":
                    headers.add(new String[]{"User-Agent", inline != null ? inline : next(tokens, ++i)});
                    break;
                case "-e": case "--referer":
                    headers.add(new String[]{"Referer", inline != null ? inline : next(tokens, ++i)});
                    break;
                case "-b": case "--cookie":
                    headers.add(new String[]{"Cookie", inline != null ? inline : next(tokens, ++i)});
                    break;
                case "--url":
                    url = inline != null ? inline : next(tokens, ++i);
                    break;
                case "-I": case "--head":
                    head = true;
                    break;
                case "-G": case "--get":
                    getMode = true;
                    break;
                case "-o": case "--output": case "-m": case "--max-time": case "--connect-timeout":
                case "--retry": case "-w": case "--write-out": case "-x": case "--proxy": case "--cacert":
                case "--cert": case "--key": case "-T": case "--upload-file": case "-c": case "--cookie-jar":
                    if (inline == null) i++;
                    ignored.add(opt);
                    break;
                default:
                    if (t.startsWith("-") && t.length() > 1) {
                        // Cờ không có tham số (-L, -k, -s, -sSL, --compressed, ...)
                        continue;
                    }
                    if (url == null) url = t;
                    break;
            }
        }

        if (url == null || url.isEmpty()) throw new ImportException("Lệnh cURL không có URL");
        if (!url.contains("://") && !url.startsWith("{{")) url = "http://" + url;

        // Method
        boolean hasBody = !data.isEmpty() || !form.isEmpty();
        if (method == null) method = head ? "HEAD" : (hasBody && !getMode ? "POST" : "GET");
        method = method.toUpperCase(Locale.ROOT);

        // Header Content-Type (nếu có)
        String contentType = null;
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase("Content-Type")) contentType = h[1];
        }
        if (json && contentType == null) {
            headers.add(new String[]{"Content-Type", "application/json"});
            headers.add(new String[]{"Accept", "application/json"});
            contentType = "application/json";
        }

        // Query: -G chuyển data sang query
        List<KeyValue> query = UrlParts.parseQuery(UrlParts.queryOf(url));
        String fragment = UrlParts.fragmentOf(url);
        String base = UrlParts.baseOf(url);
        if (getMode && !data.isEmpty()) {
            for (String d : data) query.addAll(UrlParts.parseQuery(d));
            data.clear();
            hasBody = !form.isEmpty();
        }
        String rawUrl = UrlParts.buildRaw(base, query, fragment);

        // Request object
        JsonObject req = new JsonObject();
        req.addProperty("method", method);
        JsonArray hs = new JsonArray();
        for (String[] h : headers) {
            JsonObject ho = new JsonObject();
            ho.addProperty("key", h[0]);
            ho.addProperty("value", h[1]);
            ho.addProperty("type", "text");
            hs.add(ho);
        }
        req.add("header", hs);

        if (!form.isEmpty()) {
            JsonObject body = new JsonObject();
            body.addProperty("mode", "formdata");
            JsonArray fd = new JsonArray();
            for (String[] f : form) {
                JsonObject o = new JsonObject();
                o.addProperty("key", f[0]);
                if (f[1].startsWith("@")) {
                    o.addProperty("type", "file");
                    o.addProperty("src", f[1].substring(1));
                } else {
                    o.addProperty("value", f[1]);
                    o.addProperty("type", "text");
                }
                fd.add(o);
            }
            body.add("formdata", fd);
            req.add("body", body);
        } else if (!data.isEmpty()) {
            String joined = Strings.join("&", data);
            JsonObject body = new JsonObject();
            String ctLower = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
            if (ctLower.contains("x-www-form-urlencoded") || (contentType == null && dataIsUrlencode)) {
                body.addProperty("mode", "urlencoded");
                JsonArray ue = new JsonArray();
                for (KeyValue kv : UrlParts.parseQuery(joined)) {
                    JsonObject o = new JsonObject();
                    o.addProperty("key", kv.key);
                    o.addProperty("value", kv.value);
                    o.addProperty("type", "text");
                    ue.add(o);
                }
                body.add("urlencoded", ue);
            } else {
                body.addProperty("mode", "raw");
                body.addProperty("raw", joined);
                String trimmed = joined.trim();
                boolean looksJson = ctLower.contains("json") || (contentType == null
                        && (trimmed.startsWith("{") || trimmed.startsWith("[")));
                JsonObject opts = new JsonObject();
                JsonObject rawOpts = new JsonObject();
                rawOpts.addProperty("language", looksJson ? "json" : (ctLower.contains("xml") ? "xml" : "text"));
                opts.add("raw", rawOpts);
                body.add("options", opts);
            }
            req.add("body", body);
        }

        if (user != null) {
            int c = user.indexOf(':');
            String u = c < 0 ? user : user.substring(0, c);
            String p = c < 0 ? "" : user.substring(c + 1);
            JsonObject auth = new JsonObject();
            auth.addProperty("type", "basic");
            JsonArray params = new JsonArray();
            params.add(param("username", u));
            params.add(param("password", p));
            auth.add("basic", params);
            req.add("auth", auth);
        }

        req.add("url", UrlParts.toUrlObject(rawUrl, query, null));

        String host = hostOf(base);
        String path = base;
        int sch = path.indexOf("://");
        if (sch >= 0) path = path.substring(sch + 3);
        int slash = path.indexOf('/');
        path = slash < 0 ? "/" : path.substring(slash);
        Node node = Node.newRequest(method + " " + path, method, rawUrl);
        node.raw.add("request", req);

        CollectionDoc doc = CollectionDoc.create("cURL: " + host);
        doc.roots.add(node);

        ImportResult r = new ImportResult();
        r.kind = ImportResult.Kind.CURL;
        r.sourceName = "cURL";
        r.name = doc.name;
        r.schema = "Lệnh cURL → Postman Collection v2.1";
        r.requests = 1;
        r.collection = doc;
        if (!ignored.isEmpty()) {
            r.warnings.add("Bỏ qua tùy chọn cURL chưa hỗ trợ: " + Strings.join(", ", ignored));
        }
        for (String[] f : form) {
            if (f[1].startsWith("@")) {
                r.warnings.add("Form-data có file \"" + f[1].substring(1) + "\": file sẽ không được gửi từ Android.");
                break;
            }
        }
        return r;
    }

    private static JsonObject param(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        o.addProperty("value", value);
        o.addProperty("type", "string");
        return o;
    }

    private static String hostOf(String base) {
        String s = base;
        int sch = s.indexOf("://");
        if (sch >= 0) s = s.substring(sch + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s.isEmpty() ? "request" : s;
    }

    private static String next(List<String> tokens, int i) throws ImportException {
        if (i >= tokens.size()) throw new ImportException("Lệnh cURL thiếu giá trị cho tùy chọn cuối");
        return tokens.get(i);
    }

    /** "-XPOST" → {"-X","POST"}; "--request=POST" → {"--request","POST"}; còn lại {token,null}. */
    private static String[] splitAttached(String t) {
        if (t.startsWith("--")) {
            int eq = t.indexOf('=');
            if (eq > 0) return new String[]{t.substring(0, eq), t.substring(eq + 1)};
            return new String[]{t, null};
        }
        if (t.length() > 2 && t.charAt(0) == '-' && "XHduFAbe".indexOf(t.charAt(1)) >= 0) {
            return new String[]{t.substring(0, 2), t.substring(2)};
        }
        return new String[]{t, null};
    }

    /** Tách lệnh theo quy tắc shell: nháy đơn, nháy kép, $'...', escape bằng \ và nối dòng. */
    static List<String> tokenize(String cmd) throws ImportException {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        int n = cmd.length();
        for (int i = 0; i < n; i++) {
            char c = cmd.charAt(i);
            if (c == '\\' && i + 1 < n && (cmd.charAt(i + 1) == '\n' || cmd.charAt(i + 1) == '\r')) {
                i++;
                if (cmd.charAt(i) == '\r' && i + 1 < n && cmd.charAt(i + 1) == '\n') i++;
                continue;
            }
            if (c == '^' && i + 1 < n && (cmd.charAt(i + 1) == '\n' || cmd.charAt(i + 1) == '\r')) {
                i++;
                if (cmd.charAt(i) == '\r' && i + 1 < n && cmd.charAt(i + 1) == '\n') i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
                continue;
            }
            inToken = true;
            if (c == '\'') {
                int end = cmd.indexOf('\'', i + 1);
                if (end < 0) throw new ImportException("Lệnh cURL thiếu dấu nháy đơn đóng");
                cur.append(cmd, i + 1, end);
                i = end;
            } else if (c == '$' && i + 1 < n && cmd.charAt(i + 1) == '\'') {
                i = readAnsiC(cmd, i + 2, cur);
            } else if (c == '"') {
                i++;
                boolean closed = false;
                for (; i < n; i++) {
                    char d = cmd.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        char e = cmd.charAt(i + 1);
                        if (e == '"' || e == '\\' || e == '$' || e == '`') {
                            cur.append(e);
                            i++;
                            continue;
                        }
                        if (e == '\n') {
                            i++;
                            continue;
                        }
                        cur.append(d);
                    } else if (d == '"') {
                        closed = true;
                        break;
                    } else {
                        cur.append(d);
                    }
                }
                if (!closed) throw new ImportException("Lệnh cURL thiếu dấu nháy kép đóng");
            } else if (c == '\\' && i + 1 < n) {
                cur.append(cmd.charAt(++i));
            } else {
                cur.append(c);
            }
        }
        if (inToken) out.add(cur.toString());
        return out;
    }

    /** Đọc chuỗi $'...' (escape kiểu C); trả về vị trí dấu nháy đóng. */
    private static int readAnsiC(String s, int start, StringBuilder cur) throws ImportException {
        int n = s.length();
        for (int i = start; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\'') return i;
            if (c == '\\' && i + 1 < n) {
                char e = s.charAt(++i);
                switch (e) {
                    case 'n': cur.append('\n'); break;
                    case 'r': cur.append('\r'); break;
                    case 't': cur.append('\t'); break;
                    case '\\': cur.append('\\'); break;
                    case '\'': cur.append('\''); break;
                    case '"': cur.append('"'); break;
                    case 'u':
                    case 'x': {
                        int len = e == 'u' ? 4 : 2;
                        int end = Math.min(n, i + 1 + len);
                        try {
                            cur.append((char) Integer.parseInt(s.substring(i + 1, end), 16));
                            i = end - 1;
                        } catch (NumberFormatException ex) {
                            cur.append('\\').append(e);
                        }
                        break;
                    }
                    default: cur.append('\\').append(e);
                }
            } else {
                cur.append(c);
            }
        }
        throw new ImportException("Lệnh cURL thiếu dấu nháy đóng của chuỗi $'...'");
    }
}
