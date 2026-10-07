package vn.ioc.minipostman;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Đường dẫn JSON đơn giản: data.accessToken, data.items[0].id */
public final class JsonPath {

    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)\\]");

    private JsonPath() {
    }

    public static Object parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (!(t.startsWith("{") || t.startsWith("["))) return null;
        try {
            return new JSONTokener(t).nextValue();
        } catch (Exception e) {
            return null;
        }
    }

    public static String pretty(Object json) {
        try {
            String s = json instanceof JSONObject
                    ? ((JSONObject) json).toString(2)
                    : ((JSONArray) json).toString(2);
            return s.replace("\\/", "/");
        } catch (Exception e) {
            return String.valueOf(json);
        }
    }

    public static Object eval(Object root, String path) {
        String p = path.trim();
        if (p.startsWith("$.")) p = p.substring(2);
        Object cur = root;
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
                if (!(cur instanceof JSONObject)) return null;
                cur = ((JSONObject) cur).opt(key);
            }
            for (int i : idx) {
                if (!(cur instanceof JSONArray)) return null;
                cur = ((JSONArray) cur).opt(i);
            }
            if (cur == null || cur == JSONObject.NULL) return null;
        }
        return cur;
    }
}
