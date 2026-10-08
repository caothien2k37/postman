package vn.ioc.minipostman.core.importexport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import vn.ioc.minipostman.core.Json;

/** Đọc dữ liệu lặp cho Collection Runner: CSV (dòng đầu là tên cột) hoặc JSON (mảng các object). */
public final class DataFile {

    public static final int MAX_ROWS = 10_000;

    private DataFile() {
    }

    public static List<Map<String, String>> parse(String text, String fileName) throws ImportException {
        String t = Json.stripBom(text == null ? "" : text).trim();
        if (t.isEmpty()) throw new ImportException("File dữ liệu rỗng");
        boolean json = t.startsWith("[") || t.startsWith("{") || (fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".json"));
        List<Map<String, String>> rows = json ? parseJson(t) : parseCsv(t);
        if (rows.isEmpty()) throw new ImportException("File dữ liệu không có dòng nào");
        if (rows.size() > MAX_ROWS) throw new ImportException("Quá nhiều dòng dữ liệu (> " + MAX_ROWS + ")");
        return rows;
    }

    private static List<Map<String, String>> parseJson(String t) throws ImportException {
        JsonElement root;
        try {
            root = Json.parse(t);
        } catch (JsonParseException e) {
            throw new ImportException("JSON dữ liệu không hợp lệ: " + e.getMessage());
        }
        JsonArray arr;
        if (root.isJsonArray()) arr = root.getAsJsonArray();
        else {
            arr = new JsonArray();
            arr.add(root);
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) throw new ImportException("JSON dữ liệu phải là mảng các object");
            Map<String, String> row = new LinkedHashMap<>();
            JsonObject o = e.getAsJsonObject();
            for (String k : o.keySet()) row.put(k, Json.str(o, k));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, String>> parseCsv(String t) throws ImportException {
        List<List<String>> table = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < t.length() && t.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < t.length() && t.charAt(i + 1) == '\n') i++;
                row.add(cur.toString());
                cur.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) table.add(row);
                row = new ArrayList<>();
            } else {
                cur.append(c);
            }
        }
        if (quoted) throw new ImportException("CSV thiếu dấu nháy đóng");
        if (cur.length() > 0 || !row.isEmpty()) {
            row.add(cur.toString());
            table.add(row);
        }
        if (table.size() < 2) throw new ImportException("CSV cần dòng tiêu đề và ít nhất một dòng dữ liệu");
        List<String> header = table.get(0);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int r = 1; r < table.size(); r++) {
            Map<String, String> m = new LinkedHashMap<>();
            List<String> cells = table.get(r);
            for (int c = 0; c < header.size(); c++) {
                String key = header.get(c).trim();
                if (!key.isEmpty()) m.put(key, c < cells.size() ? cells.get(c) : "");
            }
            rows.add(m);
        }
        return rows;
    }
}
