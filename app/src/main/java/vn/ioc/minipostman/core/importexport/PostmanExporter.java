package vn.ioc.minipostman.core.importexport;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.model.CollectionDoc;
import vn.ioc.minipostman.core.model.EnvDoc;
import vn.ioc.minipostman.core.model.KeyValue;
import vn.ioc.minipostman.core.model.Node;

/** Dựng lại file Postman từ mô hình trong bộ nhớ, giữ nguyên các trường lạ và thứ tự item. */
public final class PostmanExporter {

    private PostmanExporter() {
    }

    public static String exportCollection(CollectionDoc doc) {
        return Json.pretty(collectionJson(doc));
    }

    public static JsonObject collectionJson(CollectionDoc doc) {
        doc.syncVars();
        JsonObject out = doc.raw.deepCopy();
        out.add("item", items(doc.roots));
        return out;
    }

    /** Xuất riêng một folder/request thành collection nhỏ (Postman không có "export folder"). */
    public static String exportNode(CollectionDoc doc, Node node) {
        CollectionDoc wrap = CollectionDoc.create(node.name.isEmpty() ? doc.name : node.name);
        wrap.roots.add(node);
        return exportCollection(wrap);
    }

    public static JsonArray items(List<Node> nodes) {
        JsonArray arr = new JsonArray();
        for (Node n : nodes) {
            JsonObject o = n.raw.deepCopy();
            if (n.type == Node.Type.FOLDER) o.add("item", items(n.children));
            arr.add(o);
        }
        return arr;
    }

    /** @param includeSecrets false → giá trị của biến loại secret được để trống. */
    public static String exportEnvironment(EnvDoc env, boolean includeSecrets) {
        JsonObject out = env.toJson();
        if (!includeSecrets) {
            JsonArray values = Json.arr(out, "values");
            if (values != null) {
                for (int i = 0; i < values.size(); i++) {
                    JsonObject v = values.get(i).getAsJsonObject();
                    if ("secret".equals(Json.str(v, "type"))) v.addProperty("value", "");
                }
            }
        }
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        out.addProperty("_postman_exported_at", iso.format(new Date()));
        out.addProperty("_postman_exported_using", "Mini Postman Vars");
        return Json.pretty(out);
    }

    public static boolean hasSecrets(EnvDoc env) {
        for (KeyValue kv : env.vars.items()) {
            if (kv.isSecret()) return true;
        }
        return false;
    }
}
