package vn.ioc.minipostman.core.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import vn.ioc.minipostman.core.Json;

/**
 * Một dòng key/value (header, query param, form field, biến). Giữ lại JSON gốc ({@link #raw}) để
 * khi ghi ngược lại các trường lạ (description, contentType, ...) không bị mất.
 */
public final class KeyValue {

    /** Cách Postman đánh dấu dòng bị tắt. */
    public enum Style {
        /** headers, query, urlencoded, formdata, collection variable: {"disabled": true} */
        DISABLED_FLAG,
        /** environment / globals: {"enabled": false} */
        ENABLED_FLAG
    }

    public String key = "";
    public String value = "";
    public boolean enabled = true;
    /** "text"/"file" (formdata), "default"/"secret"/"any" (biến), hoặc null. */
    public String type;
    public JsonObject raw;

    public KeyValue() {
    }

    public KeyValue(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public static KeyValue fromJson(JsonObject o) {
        KeyValue kv = new KeyValue();
        kv.raw = o;
        kv.key = Json.str(o, "key");
        kv.value = Json.str(o, "value");
        kv.enabled = !Json.bool(o, "disabled", false) && Json.bool(o, "enabled", true);
        kv.type = o.has("type") ? Json.str(o, "type") : null;
        return kv;
    }

    public boolean isSecret() {
        return "secret".equals(type);
    }

    public KeyValue copy() {
        KeyValue c = new KeyValue(key, value);
        c.enabled = enabled;
        c.type = type;
        c.raw = Json.copy(raw);
        return c;
    }

    public JsonObject toJson(Style style) {
        JsonObject out = raw != null ? raw.deepCopy() : new JsonObject();
        out.addProperty("key", key);

        // Giữ nguyên kiểu số/boolean của value gốc nếu người dùng không sửa.
        JsonElement old = raw != null ? raw.get("value") : null;
        boolean keepOriginalValue = old != null && old.isJsonPrimitive()
                && !old.getAsJsonPrimitive().isString() && Json.str(raw, "value").equals(value);
        if (!keepOriginalValue) out.addProperty("value", value);

        if (type != null && (out.has("type") || !type.isEmpty())) out.addProperty("type", type);

        if (style == Style.DISABLED_FLAG) {
            if (!enabled) out.addProperty("disabled", true);
            else out.remove("disabled");
        } else if (!enabled || out.has("enabled") || raw == null) {
            out.addProperty("enabled", enabled);
        }
        return out;
    }
}
