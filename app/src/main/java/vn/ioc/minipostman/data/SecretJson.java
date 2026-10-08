package vn.ioc.minipostman.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.model.SecretCodec;

/** Mã hóa/giải mã giá trị của các biến loại "secret" nằm trong một mảng biến kiểu Postman. */
final class SecretJson {

    private SecretJson() {
    }

    static void transform(JsonArray vars, SecretCodec codec, boolean encrypt) {
        if (vars == null) return;
        for (JsonElement e : vars) {
            if (!e.isJsonObject()) continue;
            JsonObject v = e.getAsJsonObject();
            if (!"secret".equals(Json.str(v, "type")) || !v.has("value")) continue;
            String value = Json.str(v, "value");
            v.addProperty("value", encrypt ? codec.encrypt(value) : codec.decrypt(value));
        }
    }
}
