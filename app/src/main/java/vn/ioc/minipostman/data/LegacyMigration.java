package vn.ioc.minipostman.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import vn.ioc.minipostman.core.model.CollectionDoc;
import vn.ioc.minipostman.core.model.EnvDoc;
import vn.ioc.minipostman.core.model.KeyValue;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.core.request.RequestModel;

/**
 * Chuyển dữ liệu của bản 1.x (SharedPreferences "store": environment + danh sách request phẳng) sang
 * cơ sở dữ liệu mới. Chạy một lần; dữ liệu cũ được giữ nguyên trong SharedPreferences (không xóa).
 */
public final class LegacyMigration {

    private static final String TAG = "LegacyMigration";
    private static final String LEGACY_COLLECTION = "Imported (bản cũ)";

    private LegacyMigration() {
    }

    public static void run(Context context, CollectionRepository collections, EnvRepository envs) {
        SharedPreferences app = context.getSharedPreferences("app", Context.MODE_PRIVATE);
        if (app.getBoolean("migrated_v2", false)) return;
        try {
            String data = context.getSharedPreferences("store", Context.MODE_PRIVATE).getString("data", null);
            if (data != null && !data.isEmpty()) migrate(new JSONObject(data), collections, envs);
        } catch (Exception e) {
            Log.w(TAG, "Không chuyển được dữ liệu cũ: " + e.getClass().getSimpleName());
        }
        app.edit().putBoolean("migrated_v2", true).apply();
    }

    private static void migrate(JSONObject root, CollectionRepository collections, EnvRepository envs) throws Exception {
        JSONArray es = root.optJSONArray("envs");
        int active = root.optInt("activeEnv", 0);
        if (es != null) {
            for (int i = 0; i < es.length(); i++) {
                JSONObject e = es.optJSONObject(i);
                if (e == null) continue;
                String name = e.optString("name", "");
                EnvDoc doc = EnvDoc.create(name.isEmpty() ? "Environment " + (i + 1) : name, EnvDoc.KIND_ENVIRONMENT);
                JSONObject vars = e.optJSONObject("vars");
                if (vars != null) {
                    Iterator<String> it = vars.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        doc.vars.set(k, vars.optString(k, ""));
                    }
                }
                envs.add(doc, i == active);
            }
        }

        JSONArray rs = root.optJSONArray("requests");
        if (rs == null || rs.length() == 0) return;
        CollectionDoc coll = CollectionDoc.create(LEGACY_COLLECTION);
        Map<String, Node> folders = new HashMap<>();
        for (int i = 0; i < rs.length(); i++) {
            JSONObject o = rs.optJSONObject(i);
            if (o == null) continue;
            List<Node> container = coll.roots;
            String path = "";
            String folder = o.optString("folder", "");
            if (!folder.trim().isEmpty()) {
                for (String part : folder.split(" / ")) {
                    String p = part.trim();
                    if (p.isEmpty()) continue;
                    path = path.isEmpty() ? p : path + " / " + p;
                    Node f = folders.get(path);
                    if (f == null) {
                        f = Node.newFolder(p);
                        container.add(f);
                        folders.put(path, f);
                    }
                    container = f.children;
                }
            }
            Node n = Node.newRequest(o.optString("name", "Request"), o.optString("method", "GET"), o.optString("url", ""));
            RequestModel m = RequestModel.parse(n.raw);
            for (String line : o.optString("headers", "").split("\n")) {
                String l = line.trim();
                boolean disabled = l.startsWith("//");
                if (disabled) l = l.substring(2).trim();
                int c = l.indexOf(':');
                if (c <= 0) continue;
                KeyValue kv = new KeyValue(l.substring(0, c).trim(), l.substring(c + 1).trim());
                kv.enabled = !disabled;
                m.headers.add(kv);
            }
            String body = o.optString("body", "");
            if (!body.isEmpty()) {
                m.bodyMode = RequestModel.BODY_RAW;
                m.bodyRaw = body;
                String t = body.trim();
                m.rawLanguage = (t.startsWith("{") || t.startsWith("[")) ? "json" : "text";
            }
            n.raw = m.toItem();
            n.extract = o.optString("extract", "");
            container.add(n);
        }
        collections.insertCollection(coll);
    }
}
