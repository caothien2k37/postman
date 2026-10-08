package vn.ioc.minipostman.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Tiện ích JSON dựa trên Gson. JsonObject của Gson giữ nguyên thứ tự khóa và số được lưu dạng text gốc,
 * nên import → export không làm đổi thứ tự hay độ chính xác số.
 */
public final class Json {

    /** Độ sâu lồng tối đa của một tài liệu JSON (chống treo/tràn stack khi parse). */
    public static final int MAX_DEPTH = 512;

    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private Json() {
    }

    /** Đếm độ sâu lồng tối đa mà không dựng cây; ném lỗi nếu vượt giới hạn. */
    public static int checkDepth(String text) {
        int depth = 0;
        int max = 0;
        boolean inString = false;
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                if (++depth > max) max = depth;
                if (max > MAX_DEPTH) {
                    throw new JsonParseException("JSON lồng quá sâu (> " + MAX_DEPTH + " cấp)");
                }
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return max;
    }

    public static JsonElement parse(String text) {
        checkDepth(text);
        JsonElement e = JsonParser.parseString(text);
        if (e == null || e.isJsonNull()) throw new JsonParseException("JSON rỗng");
        return e;
    }

    public static JsonObject parseObject(String text) {
        JsonElement e = parse(text);
        if (!e.isJsonObject()) throw new JsonParseException("Cần một JSON object");
        return e.getAsJsonObject();
    }

    /** Trả về null nếu text không phải JSON hợp lệ (dùng cho response body). */
    public static JsonElement tryParse(String text) {
        if (text == null) return null;
        String t = stripBom(text).trim();
        if (t.isEmpty()) return null;
        char c = t.charAt(0);
        if (c != '{' && c != '[' && c != '"' && c != '-' && !Character.isDigit(c)
                && !t.equals("true") && !t.equals("false") && !t.equals("null")) {
            return null;
        }
        try {
            checkDepth(t);
            JsonElement e = JsonParser.parseString(t);
            return (e == null || e.isJsonNull()) ? null : e;
        } catch (RuntimeException | StackOverflowError ex) {
            return null;
        }
    }

    public static String stripBom(String s) {
        return (s != null && !s.isEmpty() && s.charAt(0) == (char) 0xFEFF) ? s.substring(1) : s;
    }

    public static String toJson(JsonElement e) {
        return COMPACT.toJson(e);
    }

    public static String pretty(JsonElement e) {
        return PRETTY.toJson(e);
    }

    /** Pretty-print text nếu là JSON object/array, ngược lại trả null. */
    public static String prettyText(String text) {
        JsonElement e = tryParse(text);
        if (e == null || !(e.isJsonObject() || e.isJsonArray())) return null;
        return PRETTY.toJson(e);
    }

    public static String str(JsonObject o, String key) {
        return str(o, key, "");
    }

    /** Đọc giá trị dạng chuỗi: null/vắng mặt → def; object/array → JSON text. */
    public static String str(JsonObject o, String key, String def) {
        if (o == null || !o.has(key)) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        if (e.isJsonPrimitive()) return e.getAsString();
        return e.toString();
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        if (o == null || !o.has(key)) return def;
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        String s = e.getAsString();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        return def;
    }

    public static JsonObject obj(JsonObject o, String key) {
        if (o == null || !o.has(key)) return null;
        JsonElement e = o.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    public static JsonArray arr(JsonObject o, String key) {
        if (o == null || !o.has(key)) return null;
        JsonElement e = o.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    public static JsonObject copy(JsonObject o) {
        return o == null ? null : o.deepCopy();
    }
}
