package vn.ioc.minipostman;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vn.ioc.minipostman.core.model.VarScope;

/**
 * Đường dẫn JSON đơn giản (data.accessToken, data.items[0].id) cho tính năng "Set biến từ response" kiểu cũ.
 * Script pm.* là cách làm chính; phần này giữ lại để collection/request tạo từ bản cũ vẫn chạy.
 */
public final class JsonPath {

    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)\\]");

    private JsonPath() {
    }

    /** Giá trị tại path, hoặc null nếu không có/null. */
    public static JsonElement eval(JsonElement root, String path) {
        String p = path.trim();
        if (p.startsWith("$.")) p = p.substring(2);
        else if (p.equals("$")) return root;
        JsonElement cur = root;
        for (String seg : p.split("\\.")) {
            if (seg.isEmpty()) continue;
            String key = seg;
            List<Integer> idx = new ArrayList<>();
            int b = seg.indexOf('[');
            if (b >= 0) {
                key = seg.substring(0, b);
                Matcher m = INDEX.matcher(seg.substring(b));
                while (m.find()) idx.add(Integer.parseInt(m.group(1)));
            }
            if (!key.isEmpty()) {
                if (cur == null || !cur.isJsonObject()) return null;
                cur = ((JsonObject) cur).get(key);
            }
            for (int i : idx) {
                if (cur == null || !cur.isJsonArray()) return null;
                JsonArray a = cur.getAsJsonArray();
                cur = i < a.size() ? a.get(i) : null;
            }
            if (cur == null || cur.isJsonNull()) return null;
        }
        return cur;
    }

    public static String asText(JsonElement e) {
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    /**
     * Mỗi dòng "tenBien = duong.dan.json": lấy giá trị từ response rồi lưu vào scope.
     *
     * @return tên các biến đã set
     */
    public static List<String> applyRules(String rules, JsonElement json, VarScope scope) {
        List<String> set = new ArrayList<>();
        if (rules == null || json == null) return set;
        for (String line : rules.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("//") || l.startsWith("#")) continue;
            int eq = l.indexOf('=');
            if (eq <= 0) continue;
            String var = l.substring(0, eq).trim();
            JsonElement value = eval(json, l.substring(eq + 1));
            if (value == null || var.isEmpty()) continue;
            scope.set(var, asText(value));
            set.add(var);
        }
        return set;
    }
}
