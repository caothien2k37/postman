package vn.ioc.minipostman.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** SQLite theo schema gợi ý của đặc tả: collections, nodes (cây), environments, history. */
public final class AppDb extends SQLiteOpenHelper {

    private static final String NAME = "minipostman.db";
    private static final int VERSION = 1;

    public AppDb(Context context) {
        super(context.getApplicationContext(), NAME, null, VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE collections ("
                + "id TEXT PRIMARY KEY, name TEXT NOT NULL, schema TEXT NOT NULL DEFAULT '', "
                + "raw_json TEXT NOT NULL, sort_index INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE nodes ("
                + "id TEXT PRIMARY KEY, "
                + "collection_id TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE, "
                + "parent_id TEXT, sort_index INTEGER NOT NULL, node_type TEXT NOT NULL, "
                + "name TEXT NOT NULL DEFAULT '', method TEXT NOT NULL DEFAULT '', url TEXT NOT NULL DEFAULT '', "
                + "raw_json TEXT NOT NULL, extract TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_nodes_tree ON nodes(collection_id, parent_id, sort_index)");
        db.execSQL("CREATE TABLE environments ("
                + "id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL, "
                + "selected INTEGER NOT NULL DEFAULT 0, raw_json TEXT NOT NULL, sort_index INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE history ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, node_id TEXT, collection_id TEXT, "
                + "name TEXT NOT NULL DEFAULT '', method TEXT NOT NULL DEFAULT '', url TEXT NOT NULL DEFAULT '', "
                + "timestamp INTEGER NOT NULL, status INTEGER NOT NULL DEFAULT 0, elapsed_ms INTEGER NOT NULL DEFAULT 0, "
                + "size INTEGER NOT NULL DEFAULT 0, request_json TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_history_time ON history(timestamp DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Phiên bản 1: chưa có migration nào. Thêm các bước nâng cấp tại đây khi đổi schema.
    }
}
