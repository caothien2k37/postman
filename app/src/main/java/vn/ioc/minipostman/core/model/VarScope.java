package vn.ioc.minipostman.core.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Một phạm vi biến (global, collection, environment, local, data). Biến bị tắt (disabled) không bao giờ
 * được dùng để thay thế. Theo dõi các key bị script/ứng dụng chạm tới để báo cáo và để biết khi nào cần lưu.
 */
public final class VarScope {

    public static final String GLOBAL = "global";
    public static final String COLLECTION = "collection";
    public static final String ENVIRONMENT = "environment";
    public static final String LOCAL = "local";
    public static final String DATA = "data";

    public final String name;
    private final KeyValue.Style style;
    private final List<KeyValue> items = new ArrayList<>();
    private final Set<String> touched = new LinkedHashSet<>();
    private boolean modified;

    public VarScope(String name, KeyValue.Style style) {
        this.name = name;
        this.style = style;
    }

    public static VarScope empty(String name) {
        return new VarScope(name, KeyValue.Style.ENABLED_FLAG);
    }

    public static VarScope fromJson(String name, JsonArray arr, KeyValue.Style style) {
        VarScope s = new VarScope(name, style);
        if (arr != null) {
            for (JsonElement e : arr) {
                if (e.isJsonObject()) s.items.add(KeyValue.fromJson(e.getAsJsonObject()));
            }
        }
        return s;
    }

    public static VarScope fromMap(String name, Map<String, String> map) {
        VarScope s = empty(name);
        for (Map.Entry<String, String> e : map.entrySet()) {
            s.items.add(new KeyValue(e.getKey(), e.getValue()));
        }
        return s;
    }

    public JsonArray toJson() {
        JsonArray out = new JsonArray();
        for (KeyValue kv : items) out.add(kv.toJson(style));
        return out;
    }

    /** Danh sách "sống" để màn hình chỉnh sửa thao tác trực tiếp. */
    public List<KeyValue> items() {
        return items;
    }

    public KeyValue.Style style() {
        return style;
    }

    private KeyValue find(String key, boolean enabledOnly) {
        KeyValue fallback = null;
        for (KeyValue kv : items) {
            if (!kv.key.equals(key)) continue;
            if (kv.enabled) return kv;
            if (!enabledOnly && fallback == null) fallback = kv;
        }
        return fallback;
    }

    /** Giá trị của biến đang bật, hoặc null nếu chưa có/đang tắt. */
    public String get(String key) {
        KeyValue kv = find(key, true);
        return kv == null ? null : kv.value;
    }

    public boolean has(String key) {
        return find(key, true) != null;
    }

    public boolean isSecret(String key) {
        KeyValue kv = find(key, false);
        return kv != null && kv.isSecret();
    }

    public void set(String key, String value) {
        KeyValue kv = find(key, false);
        if (kv == null) {
            kv = new KeyValue(key, value);
            kv.type = style == KeyValue.Style.ENABLED_FLAG ? "default" : "any";
            items.add(kv);
        } else {
            kv.value = value;
            kv.enabled = true;
        }
        touched.add(key);
        modified = true;
    }

    public void unset(String key) {
        boolean removed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).key.equals(key)) {
                items.remove(i);
                removed = true;
            }
        }
        if (removed) {
            touched.add(key);
            modified = true;
        }
    }

    public void clear() {
        if (items.isEmpty()) return;
        for (KeyValue kv : items) touched.add(kv.key);
        items.clear();
        modified = true;
    }

    /** Chỉ các biến đang bật; key trùng thì lấy dòng bật đầu tiên. */
    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (KeyValue kv : items) {
            if (kv.enabled && !kv.key.isEmpty() && !m.containsKey(kv.key)) m.put(kv.key, kv.value);
        }
        return m;
    }

    public boolean isModified() {
        return modified;
    }

    public void markModified() {
        modified = true;
    }

    public void resetModified() {
        modified = false;
        touched.clear();
    }

    public Set<String> touchedKeys() {
        return touched;
    }
}
