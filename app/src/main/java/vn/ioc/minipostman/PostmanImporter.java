package vn.ioc.minipostman;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Đọc file Postman collection v2.x hoặc Postman environment. */
public final class PostmanImporter {

    private static final String DEFAULT_EXTRACT = "accessToken = data.accessToken\nsessionId = data.sessionId";

    private PostmanImporter() {
    }

    public static String importJson(Store store, String text) throws JSONException {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        JSONObject root = new JSONObject(text);
        if (root.has("item")) return importCollection(store, root);
        if (root.has("values")) return importEnvironment(store, root);
        throw new JSONException("Không nhận ra file Postman (cần collection v2.x hoặc environment)");
    }

    private static String str(JSONObject o, String key) {
        Object v = o.opt(key);
        return (v == null || v == JSONObject.NULL) ? "" : String.valueOf(v);
    }

    private static String importEnvironment(Store store, JSONObject root) {
        String name = str(root, "name");
        if (name.isEmpty()) name = "Imported";
        Store.Env env = store.findEnv(name);
        if (env == null) {
            env = new Store.Env(name);
            store.envs.add(env);
        }
        int n = 0;
        JSONArray vals = root.optJSONArray("values");
        if (vals != null) {
            for (int i = 0; i < vals.length(); i++) {
                JSONObject v = vals.optJSONObject(i);
                if (v == null || !v.optBoolean("enabled", true)) continue;
                String k = str(v, "key");
                if (k.isEmpty()) continue;
                env.vars.put(k, str(v, "value"));
                n++;
            }
        }
        store.activeEnv = store.envs.indexOf(env);
        store.save();
        return "Đã import environment \"" + name + "\" (" + n + " biến) và chọn làm môi trường hiện tại";
    }

    private static String importCollection(Store store, JSONObject root) {
        int added = 0;
        JSONArray cv = root.optJSONArray("variable");
        if (cv != null) {
            Store.Env env = store.activeEnvObj();
            for (int i = 0; i < cv.length(); i++) {
                JSONObject v = cv.optJSONObject(i);
                if (v == null) continue;
                String k = str(v, "key");
                if (k.isEmpty() || env.vars.containsKey(k)) continue;
                env.vars.put(k, str(v, "value"));
                added++;
            }
        }
        int[] count = {0};
        walk(store, root.optJSONArray("item"), "", bearerOf(root.optJSONObject("auth")), count);
        store.save();
        return "Đã import " + count[0] + " request"
                + (added > 0 ? ", thêm " + added + " biến vào môi trường \"" + store.activeEnvObj().name + "\"" : "");
    }

    private static String bearerOf(JSONObject auth) {
        if (auth == null || !"bearer".equals(str(auth, "type"))) return null;
        JSONArray arr = auth.optJSONArray("bearer");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && "token".equals(str(o, "key"))) return str(o, "value");
            }
        }
        return null;
    }

    private static void walk(Store store, JSONArray items, String folder, String inheritedAuth, int[] count) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String name = str(it, "name");

            if (it.has("item")) {
                String a = bearerOf(it.optJSONObject("auth"));
                String sub = folder.isEmpty() ? name : folder + " / " + name;
                walk(store, it.optJSONArray("item"), sub, a != null ? a : inheritedAuth, count);
                continue;
            }

            Store.Req r = new Store.Req();
            r.folder = folder;
            r.name = name.isEmpty() ? "Request" : name;
            Object reqObj = it.opt("request");

            if (reqObj instanceof String) {
                r.url = (String) reqObj;
            } else if (reqObj instanceof JSONObject) {
                JSONObject rq = (JSONObject) reqObj;
                String m = str(rq, "method");
                r.method = m.isEmpty() ? "GET" : m.toUpperCase(Locale.ROOT);

                Object u = rq.opt("url");
                if (u instanceof JSONObject) r.url = str((JSONObject) u, "raw");
                else if (u instanceof String) r.url = (String) u;

                StringBuilder h = new StringBuilder();
                boolean hasAuthHeader = false;
                boolean hasContentType = false;
                JSONArray hs = rq.optJSONArray("header");
                if (hs != null) {
                    for (int j = 0; j < hs.length(); j++) {
                        JSONObject hh = hs.optJSONObject(j);
                        if (hh == null) continue;
                        String key = str(hh, "key");
                        if (key.isEmpty()) continue;
                        boolean disabled = hh.optBoolean("disabled", false);
                        if (key.equalsIgnoreCase("Authorization") && !disabled) hasAuthHeader = true;
                        if (key.equalsIgnoreCase("Content-Type") && !disabled) hasContentType = true;
                        h.append(disabled ? "// " : "").append(key).append(": ").append(str(hh, "value")).append('\n');
                    }
                }

                JSONObject ra = rq.optJSONObject("auth");
                boolean noAuth = ra != null && "noauth".equals(str(ra, "type"));
                String own = bearerOf(ra);
                String auth = own != null ? own : (noAuth ? null : inheritedAuth);
                if (auth != null && !hasAuthHeader) {
                    h.append("Authorization: Bearer ").append(auth).append('\n');
                }

                JSONObject body = rq.optJSONObject("body");
                if (body != null) {
                    String mode = str(body, "mode");
                    if ("raw".equals(mode)) {
                        r.body = str(body, "raw");
                    } else if ("urlencoded".equals(mode)) {
                        JSONArray kv = body.optJSONArray("urlencoded");
                        StringBuilder sb = new StringBuilder();
                        if (kv != null) {
                            for (int j = 0; j < kv.length(); j++) {
                                JSONObject p = kv.optJSONObject(j);
                                if (p == null || p.optBoolean("disabled", false)) continue;
                                if (sb.length() > 0) sb.append('&');
                                sb.append(str(p, "key")).append('=').append(str(p, "value"));
                            }
                        }
                        r.body = sb.toString();
                        if (!hasContentType) h.append("Content-Type: application/x-www-form-urlencoded\n");
                    }
                }
                r.headers = h.toString().trim();
            }

            String lower = (r.url + " " + r.name).toLowerCase(Locale.ROOT);
            if (lower.contains("login") || lower.contains("token") || lower.contains("đăng nhập")) {
                r.extract = DEFAULT_EXTRACT;
            }
            store.requests.add(r);
            count[0]++;
        }
    }
}
