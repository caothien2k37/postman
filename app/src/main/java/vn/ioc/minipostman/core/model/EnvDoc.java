package vn.ioc.minipostman.core.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.UUID;

import vn.ioc.minipostman.core.Json;

/** Một environment hoặc bộ biến globals, theo định dạng file export của Postman. */
public final class EnvDoc {

    public static final String KIND_ENVIRONMENT = "environment";
    public static final String KIND_GLOBALS = "globals";
    public static final String GLOBALS_ID = "globals";

    public String id = UUID.randomUUID().toString();
    public String kind = KIND_ENVIRONMENT;
    public String name = "";
    public boolean selected;
    public JsonObject raw = new JsonObject();
    public final VarScope vars;

    private EnvDoc(VarScope vars) {
        this.vars = vars;
    }

    public static EnvDoc create(String name, String kind) {
        EnvDoc e = new EnvDoc(new VarScope(kind.equals(KIND_GLOBALS) ? VarScope.GLOBAL : VarScope.ENVIRONMENT,
                KeyValue.Style.ENABLED_FLAG));
        e.kind = kind;
        e.name = name;
        e.raw.addProperty("id", e.id);
        e.raw.addProperty("name", name);
        e.raw.add("values", new JsonArray());
        e.raw.addProperty("_postman_variable_scope", kind);
        return e;
    }

    public static EnvDoc fromJson(JsonObject raw, String fallbackName) {
        boolean globals = KIND_GLOBALS.equals(Json.str(raw, "_postman_variable_scope"));
        String kind = globals ? KIND_GLOBALS : KIND_ENVIRONMENT;
        EnvDoc e = new EnvDoc(VarScope.fromJson(globals ? VarScope.GLOBAL : VarScope.ENVIRONMENT,
                Json.arr(raw, "values"), KeyValue.Style.ENABLED_FLAG));
        e.kind = kind;
        e.raw = raw;
        String n = Json.str(raw, "name");
        e.name = n.isEmpty() ? fallbackName : n;
        return e;
    }

    public void setName(String newName) {
        name = newName;
        raw.addProperty("name", newName);
    }

    /** JSON đầy đủ với mảng values dựng lại từ {@link #vars}. */
    public JsonObject toJson() {
        JsonObject out = raw.deepCopy();
        out.add("values", vars.toJson());
        out.addProperty("name", name);
        return out;
    }

    public EnvDoc duplicate(String newName) {
        JsonObject copy = toJson();
        copy.remove("id");
        EnvDoc e = fromJson(copy, newName);
        e.setName(newName);
        e.raw.addProperty("id", e.id);
        return e;
    }
}
