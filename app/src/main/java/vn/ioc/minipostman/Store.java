package vn.ioc.minipostman;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lưu environment và request vào SharedPreferences dưới dạng JSON. */
public final class Store {

    public static final class Env {
        public String name;
        public final LinkedHashMap<String, String> vars = new LinkedHashMap<>();

        public Env(String name) {
            this.name = name;
        }
    }

    public static final class Req {
        public String id = UUID.randomUUID().toString();
        public String folder = "";
        public String name = "Request mới";
        public String method = "GET";
        public String url = "";
        public String headers = "";
        public String body = "";
        public String extract = "";

        public Req copy() {
            Req c = new Req();
            c.folder = folder;
            c.name = name + " (copy)";
            c.method = method;
            c.url = url;
            c.headers = headers;
            c.body = body;
            c.extract = extract;
            return c;
        }
    }

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*\\}\\}");
    private static Store instance;

    private final SharedPreferences prefs;
    public final List<Env> envs = new ArrayList<>();
    public final List<Req> requests = new ArrayList<>();
    public int activeEnv = 0;

    public static synchronized Store get(Context context) {
        if (instance == null) {
            instance = new Store(context.getApplicationContext());
        }
        return instance;
    }

    private Store(Context context) {
        prefs = context.getSharedPreferences("store", Context.MODE_PRIVATE);
        load();
    }

    public Env activeEnvObj() {
        if (envs.isEmpty()) {
            envs.add(new Env("Default"));
        }
        if (activeEnv < 0 || activeEnv >= envs.size()) {
            activeEnv = 0;
        }
        return envs.get(activeEnv);
    }

    public Env findEnv(String name) {
        for (Env e : envs) {
            if (e.name.equals(name)) return e;
        }
        return null;
    }

    public Req findReq(String id) {
        if (id == null) return null;
        for (Req r : requests) {
            if (r.id.equals(id)) return r;
        }
        return null;
    }

    public Req newRequest() {
        Req r = new Req();
        requests.add(r);
        save();
        return r;
    }

    public void setVar(String key, String value) {
        activeEnvObj().vars.put(key, value);
        save();
    }

    /** Thay {{biến}} bằng giá trị trong environment đang chọn, hỗ trợ biến lồng biến. */
    public String resolve(String text) {
        if (text == null || text.isEmpty()) return "";
        Map<String, String> vars = activeEnvObj().vars;
        String cur = text;
        for (int pass = 0; pass < 10; pass++) {
            Matcher m = VAR.matcher(cur);
            StringBuffer sb = new StringBuffer();
            boolean changed = false;
            while (m.find()) {
                String key = m.group(1);
                String val = dynamic(key);
                if (val == null) val = vars.get(key);
                if (val != null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(val));
                    changed = true;
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                }
            }
            m.appendTail(sb);
            cur = sb.toString();
            if (!changed) break;
        }
        return cur;
    }

    public Set<String> unresolved(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = VAR.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String dynamic(String key) {
        switch (key) {
            case "$timestamp":
                return String.valueOf(System.currentTimeMillis() / 1000);
            case "$guid":
            case "$randomUUID":
                return UUID.randomUUID().toString();
            case "$randomInt":
                return String.valueOf(new Random().nextInt(1001));
            default:
                return null;
        }
    }

    private static String str(JSONObject o, String key) {
        Object v = o.opt(key);
        return (v == null || v == JSONObject.NULL) ? "" : String.valueOf(v);
    }

    private void load() {
        try {
            JSONObject root = new JSONObject(prefs.getString("data", "{}"));
            JSONArray es = root.optJSONArray("envs");
            if (es != null) {
                for (int i = 0; i < es.length(); i++) {
                    JSONObject e = es.optJSONObject(i);
                    if (e == null) continue;
                    Env env = new Env(str(e, "name"));
                    JSONObject vs = e.optJSONObject("vars");
                    if (vs != null) {
                        Iterator<String> it = vs.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            env.vars.put(k, str(vs, k));
                        }
                    }
                    envs.add(env);
                }
            }
            JSONArray rs = root.optJSONArray("requests");
            if (rs != null) {
                for (int i = 0; i < rs.length(); i++) {
                    JSONObject o = rs.optJSONObject(i);
                    if (o == null) continue;
                    Req r = new Req();
                    if (!str(o, "id").isEmpty()) r.id = str(o, "id");
                    r.folder = str(o, "folder");
                    r.name = str(o, "name");
                    r.method = str(o, "method").isEmpty() ? "GET" : str(o, "method");
                    r.url = str(o, "url");
                    r.headers = str(o, "headers");
                    r.body = str(o, "body");
                    r.extract = str(o, "extract");
                    requests.add(r);
                }
            }
            activeEnv = root.optInt("activeEnv", 0);
        } catch (JSONException ignored) {
            // Dữ liệu hỏng thì bắt đầu lại từ đầu.
        }
        activeEnvObj();
    }

    public void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray es = new JSONArray();
            for (Env e : envs) {
                JSONObject o = new JSONObject();
                o.put("name", e.name);
                JSONObject vs = new JSONObject();
                for (Map.Entry<String, String> en : e.vars.entrySet()) {
                    vs.put(en.getKey(), en.getValue());
                }
                o.put("vars", vs);
                es.put(o);
            }
            root.put("envs", es);
            JSONArray rs = new JSONArray();
            for (Req r : requests) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("folder", r.folder);
                o.put("name", r.name);
                o.put("method", r.method);
                o.put("url", r.url);
                o.put("headers", r.headers);
                o.put("body", r.body);
                o.put("extract", r.extract);
                rs.put(o);
            }
            root.put("requests", rs);
            root.put("activeEnv", activeEnv);
            prefs.edit().putString("data", root.toString()).apply();
        } catch (JSONException ignored) {
            // put() chỉ ném lỗi với số NaN/Infinity, không xảy ra ở đây.
        }
    }
}
