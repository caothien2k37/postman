package vn.ioc.minipostman.core.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import vn.ioc.minipostman.core.Json;

/**
 * Một collection. {@link #raw} là JSON gốc của file Postman (info, event, variable, auth, các trường lạ...),
 * trong đó "item" là mảng rỗng đặt đúng vị trí cũ; cây nằm ở {@link #roots}.
 */
public final class CollectionDoc {

    public static final String SCHEMA_V21 = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";

    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String schema = "";
    public JsonObject raw = new JsonObject();
    public long updatedAt;
    public final List<Node> roots = new ArrayList<>();
    private VarScope vars;

    public static CollectionDoc create(String name) {
        CollectionDoc c = new CollectionDoc();
        c.name = name;
        c.schema = SCHEMA_V21;
        JsonObject info = new JsonObject();
        info.addProperty("_postman_id", UUID.randomUUID().toString());
        info.addProperty("name", name);
        info.addProperty("schema", SCHEMA_V21);
        c.raw.add("info", info);
        c.raw.add("item", new JsonArray());
        return c;
    }

    public void setName(String newName) {
        name = newName;
        JsonObject info = Json.obj(raw, "info");
        if (info == null) {
            info = new JsonObject();
            raw.add("info", info);
        }
        info.addProperty("name", newName);
    }

    /** Biến phạm vi collection (đọc/ghi trực tiếp trên mảng "variable" của raw khi gọi {@link #syncVars()}). */
    public VarScope vars() {
        if (vars == null) {
            vars = VarScope.fromJson(VarScope.COLLECTION, Json.arr(raw, "variable"), KeyValue.Style.DISABLED_FLAG);
        }
        return vars;
    }

    /** Ghi các thay đổi biến vào raw (gọi trước khi lưu hoặc export). */
    public void syncVars() {
        if (vars != null && (vars.isModified() || raw.has("variable") || !vars.items().isEmpty())) {
            raw.add("variable", vars.toJson());
        }
    }

    public int countRequests() {
        int n = 0;
        for (Node r : roots) n += r.countRequests();
        return n;
    }

    public int countFolders() {
        int n = 0;
        for (Node r : roots) n += r.countFolders();
        return n;
    }

    /** Sao chép sâu (id mới cho collection và mọi node). */
    public CollectionDoc deepCopy() {
        CollectionDoc c = new CollectionDoc();
        syncVars();
        c.name = name;
        c.schema = schema;
        c.raw = raw.deepCopy();
        JsonObject info = Json.obj(c.raw, "info");
        if (info != null) info.addProperty("_postman_id", UUID.randomUUID().toString());
        for (Node n : roots) c.roots.add(n.deepCopy());
        return c;
    }
}
