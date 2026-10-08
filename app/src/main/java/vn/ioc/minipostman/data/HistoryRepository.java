package vn.ioc.minipostman.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/** Lịch sử các lượt gọi gần đây. Chỉ lưu dạng request chưa thay biến (còn {{biến}}) nên không lộ token. */
public final class HistoryRepository {

    public static final int MAX_ENTRIES = 200;

    public static final class Entry {
        public long id;
        public String nodeId;
        public String collectionId;
        public String name = "";
        public String method = "";
        public String url = "";
        public long timestamp;
        /** 0 nếu lỗi kết nối hoặc chưa gửi được. */
        public int status;
        public long elapsedMs;
        public long size;
        /** Item Postman (JSON) của request tại thời điểm gửi, dùng để mở lại/gửi lại. */
        public String requestJson = "";
    }

    private final AppDb helper;

    public HistoryRepository(AppDb helper) {
        this.helper = helper;
    }

    public void add(Entry e) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("node_id", e.nodeId);
        cv.put("collection_id", e.collectionId);
        cv.put("name", e.name);
        cv.put("method", e.method);
        cv.put("url", e.url);
        cv.put("timestamp", e.timestamp);
        cv.put("status", e.status);
        cv.put("elapsed_ms", e.elapsedMs);
        cv.put("size", e.size);
        cv.put("request_json", e.requestJson);
        db.insertOrThrow("history", null, cv);
        db.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY timestamp DESC, id DESC LIMIT "
                + MAX_ENTRIES + ")");
    }

    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT id, node_id, collection_id, name, method, url, timestamp, status, elapsed_ms, size, request_json "
                        + "FROM history ORDER BY timestamp DESC, id DESC", null)) {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.id = c.getLong(0);
                e.nodeId = c.isNull(1) ? null : c.getString(1);
                e.collectionId = c.isNull(2) ? null : c.getString(2);
                e.name = c.getString(3);
                e.method = c.getString(4);
                e.url = c.getString(5);
                e.timestamp = c.getLong(6);
                e.status = c.getInt(7);
                e.elapsedMs = c.getLong(8);
                e.size = c.getLong(9);
                e.requestJson = c.getString(10);
                out.add(e);
            }
        }
        return out;
    }

    public void delete(long id) {
        helper.getWritableDatabase().delete("history", "id=?", new String[]{String.valueOf(id)});
    }

    public void clear() {
        helper.getWritableDatabase().delete("history", null, null);
    }
}
