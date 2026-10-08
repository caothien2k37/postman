package vn.ioc.minipostman.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.model.EnvDoc;
import vn.ioc.minipostman.core.model.SecretCodec;

/** Environment và Globals (cùng bảng "environments"), giá trị secret được mã hóa khi lưu. */
public final class EnvRepository {

    private final AppDb helper;
    private final SecretCodec codec;

    public EnvRepository(AppDb helper, SecretCodec codec) {
        this.helper = helper;
        this.codec = codec;
    }

    private EnvDoc fromRow(Cursor c) {
        String id = c.getString(0);
        String kind = c.getString(1);
        String name = c.getString(2);
        boolean selected = c.getInt(3) != 0;
        JsonObject raw;
        try {
            raw = Json.parseObject(c.getString(4));
        } catch (RuntimeException e) {
            raw = EnvDoc.create(name, kind).raw;
        }
        SecretJson.transform(Json.arr(raw, "values"), codec, false);
        EnvDoc doc = EnvDoc.fromJson(raw, name);
        doc.id = id;
        doc.kind = kind;
        doc.name = name;
        doc.selected = selected;
        return doc;
    }

    public List<EnvDoc> listEnvironments() {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<EnvDoc> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id, kind, name, selected, raw_json FROM environments "
                + "WHERE kind=? ORDER BY sort_index, rowid", new String[]{EnvDoc.KIND_ENVIRONMENT})) {
            while (c.moveToNext()) out.add(fromRow(c));
        }
        return out;
    }

    public EnvDoc get(String id) {
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT id, kind, name, selected, raw_json FROM environments WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? fromRow(c) : null;
        }
    }

    /** Bộ biến Globals duy nhất của ứng dụng (tự tạo nếu chưa có). */
    public EnvDoc getGlobals() {
        EnvDoc g = get(EnvDoc.GLOBALS_ID);
        if (g != null) return g;
        g = EnvDoc.create("Globals", EnvDoc.KIND_GLOBALS);
        g.id = EnvDoc.GLOBALS_ID;
        save(g);
        return g;
    }

    /** Environment đang chọn; luôn có ít nhất một (tạo "Default" nếu trống) như bản cũ. */
    public EnvDoc getSelected() {
        List<EnvDoc> all = listEnvironments();
        if (all.isEmpty()) {
            EnvDoc d = EnvDoc.create("Default", EnvDoc.KIND_ENVIRONMENT);
            d.selected = true;
            save(d);
            return d;
        }
        for (EnvDoc e : all) {
            if (e.selected) return e;
        }
        select(all.get(0).id);
        all.get(0).selected = true;
        return all.get(0);
    }

    public void select(String id) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues off = new ContentValues();
            off.put("selected", 0);
            db.update("environments", off, "kind=?", new String[]{EnvDoc.KIND_ENVIRONMENT});
            ContentValues on = new ContentValues();
            on.put("selected", 1);
            db.update("environments", on, "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void save(EnvDoc doc) {
        SQLiteDatabase db = helper.getWritableDatabase();
        JsonObject json = doc.toJson();
        SecretJson.transform(Json.arr(json, "values"), codec, true);
        ContentValues cv = new ContentValues();
        cv.put("kind", doc.kind);
        cv.put("name", doc.name);
        cv.put("raw_json", Json.toJson(json));
        int updated = db.update("environments", cv, "id=?", new String[]{doc.id});
        if (updated == 0) {
            cv.put("id", doc.id);
            cv.put("selected", doc.selected ? 1 : 0);
            Cursor c = db.rawQuery("SELECT MAX(sort_index) FROM environments", null);
            int next = 0;
            try {
                if (c.moveToFirst() && !c.isNull(0)) next = c.getInt(0) + 1;
            } finally {
                c.close();
            }
            cv.put("sort_index", next);
            db.insertOrThrow("environments", null, cv);
        }
    }

    public EnvDoc create(String name) {
        EnvDoc e = EnvDoc.create(uniqueName(name), EnvDoc.KIND_ENVIRONMENT);
        save(e);
        return e;
    }

    /** Thêm environment đã parse sẵn (import). */
    public EnvDoc add(EnvDoc doc, boolean makeSelected) {
        doc.kind = EnvDoc.KIND_ENVIRONMENT;
        doc.setName(uniqueName(doc.name));
        save(doc);
        if (makeSelected) select(doc.id);
        return doc;
    }

    public void delete(String id) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("environments", "id=?", new String[]{id});
        getSelected(); // bảo đảm vẫn còn một environment được chọn
    }

    public EnvDoc findByName(String name) {
        for (EnvDoc e : listEnvironments()) {
            if (e.name.equals(name)) return e;
        }
        return null;
    }

    public String uniqueName(String base) {
        List<EnvDoc> all = listEnvironments();
        String candidate = base;
        int n = 2;
        while (true) {
            boolean taken = false;
            for (EnvDoc e : all) {
                if (e.name.equals(candidate)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) return candidate;
            candidate = base + " (" + (n++) + ")";
        }
    }
}
