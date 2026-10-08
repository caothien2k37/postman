package vn.ioc.minipostman.core.request;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

import vn.ioc.minipostman.core.Json;

/** Cấu hình auth đã đọc từ JSON Postman (hỗ trợ cả v2.1: mảng key/value và v2.0: object). */
public final class AuthSpec {

    public static final String INHERIT = "inherit";
    public static final String NOAUTH = "noauth";
    public static final String BEARER = "bearer";
    public static final String BASIC = "basic";
    public static final String APIKEY = "apikey";
    public static final String OAUTH2 = "oauth2";

    public final String type;
    public final Map<String, String> params = new LinkedHashMap<>();
    /** Mảng tham số gốc (v2.1) để ghi ngược lại mà không mất trường lạ; có thể null. */
    public final JsonArray rawParams;

    public AuthSpec(String type, JsonArray rawParams) {
        this.type = type;
        this.rawParams = rawParams;
    }

    public static boolean isSupported(String type) {
        return INHERIT.equals(type) || NOAUTH.equals(type) || BEARER.equals(type) || BASIC.equals(type)
                || APIKEY.equals(type) || OAUTH2.equals(type);
    }

    /** Đọc object "auth"; null nếu không có (nghĩa là kế thừa từ cha). */
    public static AuthSpec parse(JsonObject auth) {
        if (auth == null) return null;
        String type = Json.str(auth, "type");
        if (type.isEmpty()) return null;
        JsonElement p = auth.get(type);
        JsonArray rawArr = (p != null && p.isJsonArray()) ? p.getAsJsonArray() : null;
        AuthSpec spec = new AuthSpec(type, rawArr);
        if (rawArr != null) {
            for (JsonElement e : rawArr) {
                if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    String k = Json.str(o, "key");
                    if (!k.isEmpty()) spec.params.put(k, Json.str(o, "value"));
                }
            }
        } else if (p != null && p.isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : p.getAsJsonObject().entrySet()) {
                JsonElement v = en.getValue();
                spec.params.put(en.getKey(), v.isJsonPrimitive() ? v.getAsString() : (v.isJsonNull() ? "" : v.toString()));
            }
        }
        return spec;
    }

    public String param(String key) {
        String v = params.get(key);
        return v == null ? "" : v;
    }
}
