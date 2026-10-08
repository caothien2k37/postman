package vn.ioc.minipostman.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.model.CollectionDoc;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.core.model.SecretCodec;

/**
 * Lưu collection dạng cây (bảng nodes: parent_id + sort_index) cùng JSON gốc của từng nút để export lại
 * không mất dữ liệu. Mọi hàm là blocking: chỉ gọi từ thread nền.
 */
public final class CollectionRepository {

    public static final String TYPE_COLLECTION = "collection";

    /** Hàng nhẹ (không có raw JSON) dùng để vẽ cây và tìm kiếm. */
    public static final class TreeItem {
        public String id;
        public String collectionId;
        public String parentId;
        /** "collection", "folder", "request" hoặc "unknown". */
        public String type;
        public String name = "";
        public String method = "";
        public String url = "";
        public int sortIndex;
    }

    /** Ngữ cảnh đủ để chạy một request: collection (không kèm cây), các folder cha và chính nút. */
    public static final class Context {
        public CollectionDoc collection;
        public List<Node> ancestors = new ArrayList<>();
        public Node node;
    }

    public enum ConflictMode { COPY, REPLACE }

    private final AppDb helper;
    private final SecretCodec codec;

    public CollectionRepository(AppDb helper, SecretCodec codec) {
        this.helper = helper;
        this.codec = codec;
    }

    // ---------------------------------------------------------------- đọc

    public List<TreeItem> loadTree() {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<TreeItem> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id, name, sort_index FROM collections ORDER BY sort_index, rowid", null)) {
            while (c.moveToNext()) {
                TreeItem t = new TreeItem();
                t.id = c.getString(0);
                t.collectionId = t.id;
                t.type = TYPE_COLLECTION;
                t.name = c.getString(1);
                t.sortIndex = c.getInt(2);
                out.add(t);
            }
        }
        try (Cursor c = db.rawQuery("SELECT id, collection_id, parent_id, sort_index, node_type, name, method, url "
                + "FROM nodes ORDER BY collection_id, sort_index", null)) {
            while (c.moveToNext()) {
                TreeItem t = new TreeItem();
                t.id = c.getString(0);
                t.collectionId = c.getString(1);
                t.parentId = c.isNull(2) ? null : c.getString(2);
                t.sortIndex = c.getInt(3);
                t.type = c.getString(4);
                t.name = c.getString(5);
                t.method = c.getString(6);
                t.url = c.getString(7);
                out.add(t);
            }
        }
        return out;
    }

    public boolean isCollection(String id) {
        return one(helper.getReadableDatabase(), "SELECT 1 FROM collections WHERE id=?", id) != null;
    }

    public String findCollectionByName(String name) {
        return one(helper.getReadableDatabase(), "SELECT id FROM collections WHERE name=? LIMIT 1", name);
    }

    public int countRequests(String collectionId) {
        String n = one(helper.getReadableDatabase(),
                "SELECT COUNT(*) FROM nodes WHERE collection_id=? AND node_type='request'", collectionId);
        return n == null ? 0 : Integer.parseInt(n);
    }

    /** Collection kèm toàn bộ cây (dùng để export/nhân bản/chạy runner). */
    public CollectionDoc loadFull(String collectionId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        CollectionDoc doc = loadHeader(db, collectionId);
        if (doc == null) return null;
        List<Node> flat = new ArrayList<>();
        List<String> parents = new ArrayList<>();
        Map<String, Node> byId = new HashMap<>();
        try (Cursor c = db.rawQuery("SELECT id, parent_id, node_type, name, raw_json, extract FROM nodes "
                + "WHERE collection_id=? ORDER BY sort_index", new String[]{collectionId})) {
            while (c.moveToNext()) {
                Node n = nodeFrom(c.getString(0), c.getString(2), c.getString(3), c.getString(4), c.getString(5));
                flat.add(n);
                parents.add(c.isNull(1) ? null : c.getString(1));
                byId.put(n.id, n);
            }
        }
        for (int i = 0; i < flat.size(); i++) {
            String p = parents.get(i);
            Node parent = p == null ? null : byId.get(p);
            (parent == null ? doc.roots : parent.children).add(flat.get(i));
        }
        return doc;
    }

    /** Nút cùng toàn bộ con cháu. */
    public Node loadSubtree(String nodeId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Node root = loadNode(db, nodeId);
        if (root != null) loadChildren(db, root);
        return root;
    }

    private void loadChildren(SQLiteDatabase db, Node parent) {
        try (Cursor c = db.rawQuery("SELECT id, node_type, name, raw_json, extract FROM nodes "
                + "WHERE parent_id=? ORDER BY sort_index", new String[]{parent.id})) {
            while (c.moveToNext()) {
                parent.children.add(nodeFrom(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)));
            }
        }
        for (Node child : parent.children) loadChildren(db, child);
    }

    public Node loadNode(String nodeId) {
        return loadNode(helper.getReadableDatabase(), nodeId);
    }

    private Node loadNode(SQLiteDatabase db, String nodeId) {
        try (Cursor c = db.rawQuery("SELECT id, node_type, name, raw_json, extract FROM nodes WHERE id=?",
                new String[]{nodeId})) {
            if (!c.moveToFirst()) return null;
            return nodeFrom(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4));
        }
    }

    private static Node nodeFrom(String id, String type, String name, String rawJson, String extract) {
        Node n = new Node();
        n.id = id;
        n.type = Node.Type.fromDb(type);
        n.name = name;
        n.extract = extract == null ? "" : extract;
        try {
            n.raw = Json.parseObject(rawJson);
        } catch (RuntimeException e) {
            n.raw = new JsonObject();
            n.raw.addProperty("name", name);
        }
        return n;
    }

    /** Collection không kèm cây (info, auth, event, biến...). */
    public CollectionDoc loadCollectionHeader(String collectionId) {
        return loadHeader(helper.getReadableDatabase(), collectionId);
    }

    private CollectionDoc loadHeader(SQLiteDatabase db, String collectionId) {
        try (Cursor c = db.rawQuery("SELECT id, name, schema, raw_json, updated_at FROM collections WHERE id=?",
                new String[]{collectionId})) {
            if (!c.moveToFirst()) return null;
            CollectionDoc doc = new CollectionDoc();
            doc.id = c.getString(0);
            doc.name = c.getString(1);
            doc.schema = c.getString(2);
            doc.updatedAt = c.getLong(4);
            try {
                doc.raw = Json.parseObject(c.getString(3));
            } catch (RuntimeException e) {
                doc = CollectionDoc.create(doc.name);
                doc.id = collectionId;
            }
            SecretJson.transform(Json.arr(doc.raw, "variable"), codec, false);
            return doc;
        }
    }

    /** Collection (không có cây) + folder cha + nút: đủ để chạy một request. */
    public Context loadContext(String nodeId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Context ctx = new Context();
        ctx.node = loadNode(db, nodeId);
        if (ctx.node == null) return null;
        String collectionId = one(db, "SELECT collection_id FROM nodes WHERE id=?", nodeId);
        String parent = one(db, "SELECT parent_id FROM nodes WHERE id=?", nodeId);
        while (parent != null) {
            Node folder = loadNode(db, parent);
            if (folder == null) break;
            ctx.ancestors.add(folder);
            parent = one(db, "SELECT parent_id FROM nodes WHERE id=?", parent);
        }
        Collections.reverse(ctx.ancestors);
        ctx.collection = loadHeader(db, collectionId);
        return ctx.collection == null ? null : ctx;
    }

    // ---------------------------------------------------------------- ghi

    private String rawForStorage(CollectionDoc doc) {
        doc.syncVars();
        JsonObject copy = doc.raw.deepCopy();
        SecretJson.transform(Json.arr(copy, "variable"), codec, true);
        return Json.toJson(copy);
    }

    private void insertCollectionRow(SQLiteDatabase db, CollectionDoc doc, int sortIndex) {
        ContentValues cv = new ContentValues();
        cv.put("id", doc.id);
        cv.put("name", doc.name);
        cv.put("schema", doc.schema == null ? "" : doc.schema);
        cv.put("raw_json", rawForStorage(doc));
        cv.put("sort_index", sortIndex);
        cv.put("updated_at", System.currentTimeMillis());
        db.insertOrThrow("collections", null, cv);
    }

    private void insertNodes(SQLiteDatabase db, String collectionId, String parentId, List<Node> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            insertNode(db, collectionId, parentId, i, n);
            insertNodes(db, collectionId, n.id, n.children);
        }
    }

    private void insertNode(SQLiteDatabase db, String collectionId, String parentId, int sortIndex, Node n) {
        ContentValues cv = new ContentValues();
        cv.put("id", n.id);
        cv.put("collection_id", collectionId);
        if (parentId == null) cv.putNull("parent_id");
        else cv.put("parent_id", parentId);
        cv.put("sort_index", sortIndex);
        cv.put("node_type", n.type.db());
        cv.put("name", n.name);
        cv.put("method", n.method());
        cv.put("url", n.url());
        cv.put("raw_json", Json.toJson(n.raw));
        cv.put("extract", n.extract == null ? "" : n.extract);
        db.insertOrThrow("nodes", null, cv);
    }

    private int nextCollectionIndex(SQLiteDatabase db) {
        String max = one(db, "SELECT MAX(sort_index) FROM collections");
        return max == null ? 0 : Integer.parseInt(max) + 1;
    }

    private int nextChildIndex(SQLiteDatabase db, String collectionId, String parentId) {
        String max = parentId == null
                ? one(db, "SELECT MAX(sort_index) FROM nodes WHERE collection_id=? AND parent_id IS NULL", collectionId)
                : one(db, "SELECT MAX(sort_index) FROM nodes WHERE parent_id=?", parentId);
        return max == null ? 0 : Integer.parseInt(max) + 1;
    }

    /** Ghi cả collection trong MỘT transaction: lỗi thì rollback, không bao giờ còn collection nửa chừng. */
    public String insertCollection(CollectionDoc doc) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            insertCollectionRow(db, doc, nextCollectionIndex(db));
            insertNodes(db, doc.id, null, doc.roots);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return doc.id;
    }

    /** Import theo chế độ xử lý trùng tên: COPY tạo bản sao tên mới, REPLACE thay thế bản cũ cùng vị trí. */
    public String importCollection(CollectionDoc doc, ConflictMode mode) {
        SQLiteDatabase db = helper.getWritableDatabase();
        String existing = findCollectionByName(doc.name);
        db.beginTransaction();
        try {
            int index = nextCollectionIndex(db);
            if (existing != null && mode == ConflictMode.REPLACE) {
                String idx = one(db, "SELECT sort_index FROM collections WHERE id=?", existing);
                if (idx != null) index = Integer.parseInt(idx);
                db.delete("collections", "id=?", new String[]{existing});
            } else if (existing != null) {
                doc.setName(uniqueCollectionName(db, doc.name));
            }
            insertCollectionRow(db, doc, index);
            insertNodes(db, doc.id, null, doc.roots);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return doc.id;
    }

    private String uniqueCollectionName(SQLiteDatabase db, String base) {
        String candidate = base + " (copy)";
        int n = 2;
        while (one(db, "SELECT 1 FROM collections WHERE name=?", candidate) != null) {
            candidate = base + " (copy " + (n++) + ")";
        }
        return candidate;
    }

    public String createCollection(String name) {
        CollectionDoc doc = CollectionDoc.create(name);
        return insertCollection(doc);
    }

    public String createFolder(String collectionId, String parentId, String name) {
        SQLiteDatabase db = helper.getWritableDatabase();
        Node n = Node.newFolder(name);
        insertNode(db, collectionId, parentId, nextChildIndex(db, collectionId, parentId), n);
        return n.id;
    }

    public Node createRequest(String collectionId, String parentId, String name) {
        SQLiteDatabase db = helper.getWritableDatabase();
        Node n = Node.newRequest(name, "GET", "");
        insertNode(db, collectionId, parentId, nextChildIndex(db, collectionId, parentId), n);
        return n;
    }

    /** Thêm một cây node có sẵn (ví dụ khôi phục từ history) vào cuối một folder/collection. */
    public void addSubtree(String collectionId, String parentId, Node subtree) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            insertNode(db, collectionId, parentId, nextChildIndex(db, collectionId, parentId), subtree);
            insertNodes(db, collectionId, subtree.id, subtree.children);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void updateNode(Node n) {
        ContentValues cv = new ContentValues();
        cv.put("name", n.name);
        cv.put("method", n.method());
        cv.put("url", n.url());
        cv.put("raw_json", Json.toJson(n.raw));
        cv.put("extract", n.extract == null ? "" : n.extract);
        helper.getWritableDatabase().update("nodes", cv, "id=?", new String[]{n.id});
    }

    /** Lưu phần "đầu" của collection (info, auth, event, biến...). Không đụng tới cây. */
    public void saveCollection(CollectionDoc c) {
        ContentValues cv = new ContentValues();
        cv.put("name", c.name);
        cv.put("schema", c.schema == null ? "" : c.schema);
        cv.put("raw_json", rawForStorage(c));
        cv.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update("collections", cv, "id=?", new String[]{c.id});
    }

    public void rename(String id, String newName) {
        SQLiteDatabase db = helper.getWritableDatabase();
        if (isCollection(id)) {
            CollectionDoc c = loadHeader(db, id);
            if (c == null) return;
            c.setName(newName);
            saveCollection(c);
        } else {
            Node n = loadNode(db, id);
            if (n == null) return;
            n.setName(newName);
            updateNode(n);
        }
    }

    public void delete(String id) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            if (isCollection(id)) {
                db.delete("collections", "id=?", new String[]{id});
            } else {
                deleteSubtree(db, id);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void deleteSubtree(SQLiteDatabase db, String id) {
        List<String> children = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id FROM nodes WHERE parent_id=?", new String[]{id})) {
            while (c.moveToNext()) children.add(c.getString(0));
        }
        for (String child : children) deleteSubtree(db, child);
        db.delete("nodes", "id=?", new String[]{id});
    }

    /** Nhân bản collection hoặc nút (kèm con cháu); bản sao nằm ngay sau bản gốc. Trả về id mới. */
    public String duplicate(String id) {
        SQLiteDatabase db = helper.getWritableDatabase();
        if (isCollection(id)) {
            CollectionDoc full = loadFull(id);
            if (full == null) return null;
            CollectionDoc copy = full.deepCopy();
            copy.setName(uniqueCollectionName(db, full.name));
            return insertCollection(copy);
        }
        Node original = loadSubtree(id);
        if (original == null) return null;
        String collectionId = one(db, "SELECT collection_id FROM nodes WHERE id=?", id);
        String parentId = one(db, "SELECT parent_id FROM nodes WHERE id=?", id);
        Node copy = original.deepCopy();
        copy.setName(original.name + " (copy)");
        db.beginTransaction();
        try {
            insertNode(db, collectionId, parentId, nextChildIndex(db, collectionId, parentId), copy);
            insertNodes(db, collectionId, copy.id, copy.children);
            List<String> order = siblingIds(db, collectionId, parentId);
            order.remove(copy.id);
            order.add(order.indexOf(id) + 1, copy.id);
            resequence(db, order);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return copy.id;
    }

    /**
     * Chuyển nút sang folder/collection khác (cuối danh sách). targetParentId = null nghĩa là gốc collection.
     * Trả false nếu đích nằm trong chính nút đang chuyển.
     */
    public boolean move(String nodeId, String targetCollectionId, String targetParentId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        for (String p = targetParentId; p != null; p = one(db, "SELECT parent_id FROM nodes WHERE id=?", p)) {
            if (p.equals(nodeId)) return false;
        }
        db.beginTransaction();
        try {
            List<String> subtree = new ArrayList<>();
            collectIds(db, nodeId, subtree);
            for (String sid : subtree) {
                ContentValues cv = new ContentValues();
                cv.put("collection_id", targetCollectionId);
                db.update("nodes", cv, "id=?", new String[]{sid});
            }
            ContentValues cv = new ContentValues();
            if (targetParentId == null) cv.putNull("parent_id");
            else cv.put("parent_id", targetParentId);
            cv.put("sort_index", nextChildIndex(db, targetCollectionId, targetParentId));
            db.update("nodes", cv, "id=?", new String[]{nodeId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return true;
    }

    private void collectIds(SQLiteDatabase db, String id, List<String> out) {
        out.add(id);
        List<String> children = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id FROM nodes WHERE parent_id=?", new String[]{id})) {
            while (c.moveToNext()) children.add(c.getString(0));
        }
        for (String child : children) collectIds(db, child, out);
    }

    /** Đổi thứ tự trong cùng danh sách anh em: delta = -1 lên, +1 xuống. */
    public void moveUpDown(String id, int delta) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            if (isCollection(id)) {
                List<String> order = new ArrayList<>();
                try (Cursor c = db.rawQuery("SELECT id FROM collections ORDER BY sort_index, rowid", null)) {
                    while (c.moveToNext()) order.add(c.getString(0));
                }
                reorder(order, id, delta);
                for (int i = 0; i < order.size(); i++) {
                    ContentValues cv = new ContentValues();
                    cv.put("sort_index", i);
                    db.update("collections", cv, "id=?", new String[]{order.get(i)});
                }
            } else {
                String collectionId = one(db, "SELECT collection_id FROM nodes WHERE id=?", id);
                String parentId = one(db, "SELECT parent_id FROM nodes WHERE id=?", id);
                List<String> order = siblingIds(db, collectionId, parentId);
                reorder(order, id, delta);
                resequence(db, order);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void reorder(List<String> order, String id, int delta) {
        int i = order.indexOf(id);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= order.size()) return;
        order.remove(i);
        order.add(j, id);
    }

    private List<String> siblingIds(SQLiteDatabase db, String collectionId, String parentId) {
        List<String> ids = new ArrayList<>();
        String sql = parentId == null
                ? "SELECT id FROM nodes WHERE collection_id=? AND parent_id IS NULL ORDER BY sort_index"
                : "SELECT id FROM nodes WHERE parent_id=? ORDER BY sort_index";
        try (Cursor c = db.rawQuery(sql, new String[]{parentId == null ? collectionId : parentId})) {
            while (c.moveToNext()) ids.add(c.getString(0));
        }
        return ids;
    }

    private void resequence(SQLiteDatabase db, List<String> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            ContentValues cv = new ContentValues();
            cv.put("sort_index", i);
            db.update("nodes", cv, "id=?", new String[]{orderedIds.get(i)});
        }
    }

    // ---------------------------------------------------------------- tiện ích

    private static String one(SQLiteDatabase db, String sql, String... args) {
        try (Cursor c = db.rawQuery(sql, args.length == 0 ? null : args)) {
            if (c.moveToFirst() && !c.isNull(0)) return c.getString(0);
            return null;
        }
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Đường dẫn dạng "Collection / Folder / Folder con" cho các hộp chọn. */
    public static Map<String, String> containerPaths(List<TreeItem> tree) {
        Map<String, TreeItem> byId = new HashMap<>();
        for (TreeItem t : tree) byId.put(t.id, t);
        Map<String, String> out = new LinkedHashMap<>();
        for (TreeItem t : tree) {
            if (!TYPE_COLLECTION.equals(t.type) && !"folder".equals(t.type)) continue;
            StringBuilder sb = new StringBuilder(t.name);
            String p = t.parentId;
            int guard = 0;
            while (p != null && guard++ < 200) {
                TreeItem parent = byId.get(p);
                if (parent == null) break;
                sb.insert(0, parent.name + " / ");
                p = parent.parentId;
            }
            if (!TYPE_COLLECTION.equals(t.type)) {
                TreeItem coll = byId.get(t.collectionId);
                if (coll != null) sb.insert(0, coll.name + " / ");
            }
            out.put(t.id, sb.toString());
        }
        return out;
    }
}
