package vn.ioc.minipostman.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import vn.ioc.minipostman.core.importexport.ImportException;
import vn.ioc.minipostman.core.importexport.ImportResult;
import vn.ioc.minipostman.core.importexport.PostmanParser;

/** Dữ liệu dùng chung cho các test. */
public final class Fixtures {

    private Fixtures() {
    }

    public static String resource(String name) {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Thiếu resource " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ImportResult parse(String text) {
        try {
            return PostmanParser.parse(text, "test.json");
        } catch (ImportException e) {
            throw new AssertionError("Import lỗi: " + e.getMessage(), e);
        }
    }

    public static JsonObject request(String name, String method, String url) {
        JsonObject item = new JsonObject();
        item.addProperty("name", name);
        JsonObject req = new JsonObject();
        req.addProperty("method", method);
        req.add("header", new JsonArray());
        JsonObject u = new JsonObject();
        u.addProperty("raw", url);
        req.add("url", u);
        item.add("request", req);
        return item;
    }

    public static JsonObject folder(String name, JsonObject... children) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        JsonArray items = new JsonArray();
        for (JsonObject c : children) items.add(c);
        f.add("item", items);
        return f;
    }

    /** Thêm event script (listen = "prerequest" | "test") vào item/folder/collection. */
    public static void addScript(JsonObject owner, String listen, String... lines) {
        JsonArray events = owner.has("event") ? owner.getAsJsonArray("event") : new JsonArray();
        JsonObject ev = new JsonObject();
        ev.addProperty("listen", listen);
        JsonObject script = new JsonObject();
        script.addProperty("type", "text/javascript");
        JsonArray exec = new JsonArray();
        for (String l : lines) exec.add(l);
        script.add("exec", exec);
        ev.add("script", script);
        events.add(ev);
        owner.add("event", events);
    }

    public static JsonObject collection(String name, JsonObject... items) {
        JsonObject root = new JsonObject();
        JsonObject info = new JsonObject();
        info.addProperty("name", name);
        info.addProperty("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        root.add("info", info);
        JsonArray arr = new JsonArray();
        for (JsonObject i : items) arr.add(i);
        root.add("item", arr);
        return root;
    }

    /** Folder lồng nhau {@code depth} cấp, mỗi cấp có 1 request trước và 1 request sau folder con. */
    public static JsonObject nestedFolders(int depth) {
        JsonObject inner = folder("L" + depth,
                request("L" + depth + "-a", "GET", "http://x/" + depth + "a"),
                request("L" + depth + "-b", "POST", "http://x/" + depth + "b"));
        for (int d = depth - 1; d >= 1; d--) {
            inner = folder("L" + d,
                    request("L" + d + "-a", "GET", "http://x/" + d + "a"),
                    inner,
                    request("L" + d + "-b", "POST", "http://x/" + d + "b"));
        }
        return inner;
    }
}
