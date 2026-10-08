package vn.ioc.minipostman.core.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.request.UrlParts;

/**
 * Một nút trong cây collection (folder hoặc request). {@link #raw} là JSON item gốc của Postman, trong đó
 * mảng "item" của folder được thay bằng mảng rỗng (con nằm ở {@link #children}) để export dựng lại đúng thứ tự.
 */
public final class Node {

    public enum Type {
        FOLDER, REQUEST, UNKNOWN;

        public String db() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Type fromDb(String s) {
            for (Type t : values()) {
                if (t.db().equals(s)) return t;
            }
            return UNKNOWN;
        }
    }

    public String id = UUID.randomUUID().toString();
    public Type type = Type.REQUEST;
    public String name = "";
    public JsonObject raw = new JsonObject();
    public final List<Node> children = new ArrayList<>();
    /** Quy tắc "Set biến từ response" kiểu cũ (không thuộc schema Postman nên không export). */
    public String extract = "";

    public static Node newFolder(String name) {
        Node n = new Node();
        n.type = Type.FOLDER;
        n.name = name;
        n.raw.addProperty("name", name);
        n.raw.add("item", new JsonArray());
        return n;
    }

    public static Node newRequest(String name, String method, String url) {
        Node n = new Node();
        n.type = Type.REQUEST;
        n.name = name;
        n.raw.addProperty("name", name);
        JsonObject req = new JsonObject();
        req.addProperty("method", method);
        req.add("header", new JsonArray());
        JsonObject u = new JsonObject();
        u.addProperty("raw", url);
        req.add("url", u);
        n.raw.add("request", req);
        n.raw.add("response", new JsonArray());
        return n;
    }

    public boolean isFolder() {
        return type == Type.FOLDER;
    }

    public void setName(String newName) {
        name = newName;
        raw.addProperty("name", newName);
    }

    /** HTTP method của request ("" với folder/unknown). */
    public String method() {
        if (type != Type.REQUEST) return "";
        JsonElement r = raw.get("request");
        if (r != null && r.isJsonObject()) {
            String m = Json.str(r.getAsJsonObject(), "method");
            return m.isEmpty() ? "GET" : m.toUpperCase(java.util.Locale.ROOT);
        }
        return "GET";
    }

    public String url() {
        if (type != Type.REQUEST) return "";
        JsonElement r = raw.get("request");
        if (r == null) return "";
        if (r.isJsonPrimitive()) return r.getAsString();
        if (r.isJsonObject()) return UrlParts.rawOf(r.getAsJsonObject().get("url"));
        return "";
    }

    /** Sao chép sâu với id mới; bỏ các id của Postman trong item để tránh trùng. */
    public Node deepCopy() {
        Node c = new Node();
        c.type = type;
        c.name = name;
        c.raw = raw.deepCopy();
        c.raw.remove("id");
        c.raw.remove("_postman_id");
        c.extract = extract;
        for (Node child : children) c.children.add(child.deepCopy());
        return c;
    }

    public int countRequests() {
        int n = type == Type.REQUEST ? 1 : 0;
        for (Node c : children) n += c.countRequests();
        return n;
    }

    public int countFolders() {
        int n = type == Type.FOLDER ? 1 : 0;
        for (Node c : children) n += c.countFolders();
        return n;
    }
}
