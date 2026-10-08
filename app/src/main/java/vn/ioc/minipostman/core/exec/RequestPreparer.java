package vn.ioc.minipostman.core.exec;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vn.ioc.minipostman.core.Base64Util;
import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.model.KeyValue;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.core.net.PreparedRequest;
import vn.ioc.minipostman.core.request.AuthSpec;
import vn.ioc.minipostman.core.request.RequestModel;
import vn.ioc.minipostman.core.vars.VariableResolver;

/** Biến RequestModel (còn {{biến}}) thành PreparedRequest đã resolve, áp auth và header tự động. */
public final class RequestPreparer {

    private RequestPreparer() {
    }

    /** Auth hiệu lực: của request, hoặc kế thừa từ folder gần nhất → collection. */
    public static AuthSpec effectiveAuth(RequestModel m, JsonObject collectionRaw, List<Node> ancestors) {
        if (!AuthSpec.INHERIT.equals(m.authType)) {
            AuthSpec own = new AuthSpec(m.authType, null);
            own.params.putAll(m.authParams);
            return own;
        }
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            AuthSpec a = AuthSpec.parse(Json.obj(ancestors.get(i).raw, "auth"));
            if (a != null) return a;
        }
        AuthSpec c = AuthSpec.parse(Json.obj(collectionRaw, "auth"));
        return c != null ? c : new AuthSpec(AuthSpec.NOAUTH, null);
    }

    public static PreparedRequest prepare(RequestModel m, VariableResolver r, AuthSpec auth) {
        PreparedRequest p = new PreparedRequest();
        p.method = m.method == null || m.method.isEmpty() ? "GET" : m.method.toUpperCase(java.util.Locale.ROOT);
        p.followRedirects = m.followRedirects;
        p.url = applyPathVars(r.resolve(m.url.trim()), m, r);

        for (KeyValue h : m.headers) {
            if (!h.enabled || h.key.trim().isEmpty()) continue;
            p.headers.add(new String[]{r.resolve(h.key.trim()), r.resolve(h.value)});
        }

        switch (m.bodyMode) {
            case RequestModel.BODY_RAW:
                p.bodyKind = PreparedRequest.BODY_RAW;
                p.bodyRaw = r.resolve(m.bodyRaw);
                p.putHeaderIfAbsent("Content-Type", contentTypeFor(m.rawLanguage));
                break;
            case RequestModel.BODY_URLENCODED:
                p.bodyKind = PreparedRequest.BODY_URLENCODED;
                for (KeyValue kv : m.urlencoded) {
                    if (kv.enabled && !kv.key.isEmpty()) p.form.add(new String[]{r.resolve(kv.key), r.resolve(kv.value)});
                }
                break;
            case RequestModel.BODY_FORMDATA:
                p.bodyKind = PreparedRequest.BODY_FORMDATA;
                p.removeHeader("Content-Type"); // OkHttp tự đặt kèm boundary
                for (KeyValue kv : m.formdata) {
                    if (!kv.enabled || kv.key.isEmpty()) continue;
                    if ("file".equals(kv.type)) {
                        p.warnings.add("Bỏ qua trường file \"" + kv.key + "\" (chưa hỗ trợ gửi file).");
                        continue;
                    }
                    p.form.add(new String[]{r.resolve(kv.key), r.resolve(kv.value)});
                }
                break;
            case RequestModel.BODY_GRAPHQL: {
                p.bodyKind = PreparedRequest.BODY_RAW;
                JsonObject gql = new JsonObject();
                gql.addProperty("query", r.resolve(m.graphqlQuery));
                String vars = r.resolve(m.graphqlVariables).trim();
                if (!vars.isEmpty()) {
                    com.google.gson.JsonElement parsed = Json.tryParse(vars);
                    if (parsed != null) gql.add("variables", parsed);
                }
                p.bodyRaw = Json.toJson(gql);
                p.putHeaderIfAbsent("Content-Type", "application/json");
                break;
            }
            case RequestModel.BODY_FILE:
                p.warnings.add("Body dạng file/binary chưa hỗ trợ; request được gửi không kèm body.");
                break;
            default:
                break;
        }

        applyAuth(p, auth, r);
        return p;
    }

    private static String contentTypeFor(String language) {
        switch (language == null ? "" : language) {
            case "json": return "application/json";
            case "xml": return "application/xml";
            case "html": return "text/html";
            case "javascript": return "application/javascript";
            default: return "text/plain";
        }
    }

    /** Thay các segment ":tên" trong phần path bằng giá trị path variable. */
    private static String applyPathVars(String url, RequestModel m, VariableResolver r) {
        if (m.pathVars.isEmpty()) return url;
        int cut = url.length();
        int q = url.indexOf('?');
        int h = url.indexOf('#');
        if (q >= 0) cut = Math.min(cut, q);
        if (h >= 0) cut = Math.min(cut, h);
        String base = url.substring(0, cut);
        String rest = url.substring(cut);
        for (KeyValue kv : m.pathVars) {
            if (!kv.enabled || kv.key.isEmpty() || kv.value.isEmpty()) continue;
            Pattern seg = Pattern.compile("(?<=/):" + Pattern.quote(kv.key) + "(?=/|$)");
            base = seg.matcher(base).replaceAll(Matcher.quoteReplacement(r.resolve(kv.value)));
        }
        return base + rest;
    }

    private static void applyAuth(PreparedRequest p, AuthSpec auth, VariableResolver r) {
        if (auth == null) return;
        switch (auth.type) {
            case AuthSpec.BEARER: {
                String token = r.resolve(auth.param("token"));
                if (!token.isEmpty()) p.putHeaderIfAbsent("Authorization", "Bearer " + token);
                break;
            }
            case AuthSpec.BASIC: {
                String user = r.resolve(auth.param("username"));
                String pass = r.resolve(auth.param("password"));
                p.putHeaderIfAbsent("Authorization", "Basic " + Base64Util.encodeUtf8(user + ":" + pass));
                break;
            }
            case AuthSpec.APIKEY: {
                String key = r.resolve(auth.param("key"));
                String value = r.resolve(auth.param("value"));
                if (key.isEmpty()) break;
                if ("query".equals(auth.param("in"))) p.extraQuery.add(new String[]{key, value});
                else p.putHeaderIfAbsent(key, value);
                break;
            }
            case AuthSpec.OAUTH2: {
                String token = r.resolve(auth.param("accessToken"));
                if (token.isEmpty()) {
                    p.warnings.add("OAuth 2.0: chưa có Access Token (việc tự lấy token chưa được hỗ trợ).");
                    break;
                }
                String prefix = auth.params.containsKey("headerPrefix") ? auth.param("headerPrefix") : "Bearer";
                if ("queryParams".equals(auth.param("addTokenTo"))) {
                    p.extraQuery.add(new String[]{"access_token", token});
                } else {
                    p.putHeaderIfAbsent("Authorization", (prefix.isEmpty() ? "" : prefix + " ") + token);
                }
                break;
            }
            case AuthSpec.NOAUTH:
            case AuthSpec.INHERIT:
                break;
            default:
                p.warnings.add("Auth \"" + auth.type + "\" chưa được hỗ trợ; request được gửi không kèm auth.");
                break;
        }
    }
}
