package vn.ioc.minipostman.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.google.gson.JsonObject;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import vn.ioc.minipostman.AppTestBase;
import vn.ioc.minipostman.core.Fixtures;
import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.importexport.ImportResult;
import vn.ioc.minipostman.core.importexport.PostmanExporter;
import vn.ioc.minipostman.core.model.CollectionDoc;
import vn.ioc.minipostman.core.model.EnvDoc;
import vn.ioc.minipostman.core.model.KeyValue;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.core.model.SecretCodec;
import vn.ioc.minipostman.data.CollectionRepository.ConflictMode;
import vn.ioc.minipostman.data.CollectionRepository.TreeItem;

/** Kiểm tra tầng SQLite thật (Robolectric): cây, transaction, CRUD, mã hóa secret, di chuyển dữ liệu cũ. */
public class CollectionRepositoryTest extends AppTestBase {

    private AppDb db;
    private CollectionRepository repo;
    private EnvRepository envs;

    /** Codec giả "mã hóa" bằng đảo chuỗi để kiểm chứng giá trị không nằm dạng rõ trong DB. */
    private static final SecretCodec REVERSING = new SecretCodec() {
        @Override
        public String encrypt(String plain) {
            return "enc1:" + new StringBuilder(plain).reverse();
        }

        @Override
        public String decrypt(String stored) {
            return stored.startsWith("enc1:") ? new StringBuilder(stored.substring(5)).reverse().toString() : stored;
        }
    };

    @Before
    public void setUp() {
        db = new AppDb(app);
        repo = new CollectionRepository(db, REVERSING);
        envs = new EnvRepository(db, REVERSING);
    }

    private static TreeItem byName(List<TreeItem> tree, String name) {
        for (TreeItem t : tree) {
            if (t.name.equals(name)) return t;
        }
        return null;
    }

    private String importRich() {
        ImportResult r = Fixtures.parse(Fixtures.resource("rich_collection.json"));
        return repo.importCollection(r.collection, ConflictMode.COPY);
    }

    // ---------------------------------------------------------------- cây + round-trip qua DB

    @Test
    public void importThenLoadThenExportIsIdenticalToOriginal() {
        String original = Fixtures.resource("rich_collection.json");
        String id = importRich();
        CollectionDoc loaded = repo.loadFull(id);
        String exported = PostmanExporter.exportCollection(loaded);
        assertEquals(Json.toJson(Json.parse(original)), Json.toJson(Json.parse(exported)));
    }

    @Test
    public void treeKeepsNestingAndSiblingOrder() {
        String id = importRich();
        List<TreeItem> tree = repo.loadTree();
        TreeItem outgoing = byName(tree, "Outgoing Documents");
        TreeItem docs = byName(tree, "Document Management");
        assertEquals(docs.id, outgoing.parentId);
        assertEquals("folder", outgoing.type);
        assertEquals(id, outgoing.collectionId);

        List<String> childrenOfOutgoing = new ArrayList<>();
        for (TreeItem t : tree) {
            if (outgoing.id.equals(t.parentId)) childrenOfOutgoing.add(t.name);
        }
        assertEquals(java.util.Arrays.asList("List Documents", "Create Document", "Upload", "Delete Document"), childrenOfOutgoing);
        assertEquals("DELETE", byName(tree, "Delete Document").method);
        assertEquals("{{base_url}}/auth/me", byName(tree, "Get Current User").url);
    }

    @Test
    public void twoHundredRequestsKeepOrderAfterReload() {
        List<JsonObject> folders = new ArrayList<>();
        int idx = 0;
        for (int f = 0; f < 10; f++) {
            List<JsonObject> reqs = new ArrayList<>();
            for (int i = 0; i < 20; i++, idx++) reqs.add(Fixtures.request("API " + idx, "GET", "http://h/" + idx));
            folders.add(Fixtures.folder("F" + f, reqs.toArray(new JsonObject[0])));
        }
        ImportResult r = Fixtures.parse(Json.toJson(Fixtures.collection("Big", folders.toArray(new JsonObject[0]))));
        String id = repo.importCollection(r.collection, ConflictMode.COPY);
        assertEquals(200, repo.countRequests(id));

        List<String> names = new ArrayList<>();
        for (TreeItem t : repo.loadTree()) {
            if ("request".equals(t.type)) names.add(t.name);
        }
        // loadTree trả theo (collection, sort_index) nên không phân cấp: so thứ tự trong từng folder qua loadFull
        CollectionDoc full = repo.loadFull(id);
        int expected = 0;
        for (Node folder : full.roots) {
            for (Node req : folder.children) assertEquals("API " + (expected++), req.name);
        }
        assertEquals(200, expected);
    }

    @Test
    public void contextHasAncestorsOutermostFirstAndCollectionHeader() {
        importRich();
        TreeItem list = byName(repo.loadTree(), "List Documents");
        CollectionRepository.Context ctx = repo.loadContext(list.id);
        assertNotNull(ctx);
        assertEquals(2, ctx.ancestors.size());
        assertEquals("Document Management", ctx.ancestors.get(0).name);
        assertEquals("Outgoing Documents", ctx.ancestors.get(1).name);
        assertEquals("bearer", ctx.collection.raw.getAsJsonObject("auth").get("type").getAsString());
        assertEquals("https://api.example.com", ctx.collection.vars().get("base_url"));
        assertEquals(0, ctx.collection.raw.getAsJsonArray("item").size()); // không kéo cả cây vào ngữ cảnh
    }

    // ---------------------------------------------------------------- transaction / xung đột

    @Test
    public void failedImportRollsBackNothingLeftBehind() {
        CollectionDoc doc = CollectionDoc.create("Broken");
        Node a = Node.newRequest("A", "GET", "http://x");
        Node b = Node.newRequest("B", "GET", "http://x");
        b.id = a.id; // trùng khóa chính -> insert thứ hai thất bại giữa chừng
        doc.roots.add(a);
        doc.roots.add(b);
        try {
            repo.insertCollection(doc);
            fail("Phải lỗi vì trùng id");
        } catch (RuntimeException expected) {
            // ok
        }
        assertNull(repo.findCollectionByName("Broken"));
        assertTrue(repo.loadTree().isEmpty());
    }

    @Test
    public void duplicateNameCopyAddsNewReplaceKeepsPosition() {
        ImportResult first = Fixtures.parse(Json.toJson(Fixtures.collection("Same", Fixtures.request("One", "GET", "http://x"))));
        String firstId = repo.importCollection(first.collection, ConflictMode.COPY);
        CollectionDoc other = Fixtures.parse(Json.toJson(Fixtures.collection("Other", Fixtures.request("O", "GET", "http://x")))).collection;
        repo.importCollection(other, ConflictMode.COPY);

        CollectionDoc dup = Fixtures.parse(Json.toJson(Fixtures.collection("Same", Fixtures.request("Two", "GET", "http://x")))).collection;
        String copyId = repo.importCollection(dup, ConflictMode.COPY);
        assertEquals("Same (copy)", repo.loadCollectionHeader(copyId).name);
        assertNotNull(repo.loadCollectionHeader(firstId));

        CollectionDoc replacement = Fixtures.parse(Json.toJson(Fixtures.collection("Same", Fixtures.request("Three", "GET", "http://x")))).collection;
        String newId = repo.importCollection(replacement, ConflictMode.REPLACE);
        assertNull(repo.loadCollectionHeader(firstId));                 // bản cũ đã bị thay
        List<TreeItem> tree = repo.loadTree();
        assertEquals("Same", tree.get(0).name);                         // giữ nguyên vị trí đầu danh sách
        assertEquals(newId, tree.get(0).id);
        assertNull(byName(tree, "One"));
        assertNotNull(byName(tree, "Three"));
    }

    // ---------------------------------------------------------------- CRUD

    @Test
    public void createRenameDuplicateMoveReorderDelete() {
        String cid = repo.createCollection("C");
        String f1 = repo.createFolder(cid, null, "F1");
        String f2 = repo.createFolder(cid, null, "F2");
        Node r1 = repo.createRequest(cid, f1, "R1");
        Node r2 = repo.createRequest(cid, f1, "R2");
        repo.createRequest(cid, null, "Top");

        repo.rename(r1.id, "R1 renamed");
        assertEquals("R1 renamed", repo.loadNode(r1.id).raw.get("name").getAsString());
        repo.rename(cid, "C renamed");
        assertEquals("C renamed", repo.loadCollectionHeader(cid).raw.getAsJsonObject("info").get("name").getAsString());

        // nhân bản folder kèm con
        String copy = repo.duplicate(f1);
        assertEquals("F1 (copy)", repo.loadNode(copy).name);
        assertEquals(2, repo.loadSubtree(copy).children.size());
        List<String> top = new ArrayList<>();
        for (Node n : repo.loadFull(cid).roots) top.add(n.name);
        assertEquals(java.util.Arrays.asList("F1", "F1 (copy)", "F2", "Top"), top);

        // di chuyển request sang F2 và không cho đưa folder vào chính con của nó
        assertTrue(repo.move(r2.id, cid, f2));
        assertFalse(repo.move(f1, cid, f1));
        Node f2Tree = repo.loadSubtree(f2);
        assertEquals(1, f2Tree.children.size());
        assertEquals("R2", f2Tree.children.get(0).name);

        // đổi thứ tự
        repo.moveUpDown(f2, -1);
        top.clear();
        for (Node n : repo.loadFull(cid).roots) top.add(n.name);
        assertEquals(java.util.Arrays.asList("F1", "F2", "F1 (copy)", "Top"), top);

        // xóa folder xóa luôn con cháu
        int before = repo.loadTree().size();
        repo.delete(f1);
        assertNull(repo.loadNode(r1.id));
        assertEquals(before - 2, repo.loadTree().size());

        // xóa collection xóa hết node (ON DELETE CASCADE)
        repo.delete(cid);
        assertTrue(repo.loadTree().isEmpty());
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM nodes", null)) {
            c.moveToFirst();
            assertEquals(0, c.getInt(0));
        }
    }

    @Test
    public void updateNodeKeepsUnknownFieldsAndRefreshesListColumns() {
        String id = importRich();
        TreeItem login = byName(repo.loadTree(), "Login");
        Node n = repo.loadNode(login.id);
        n.raw.getAsJsonObject("request").addProperty("method", "PUT");
        n.extract = "tok = data.accessToken";
        repo.updateNode(n);
        assertEquals("PUT", byName(repo.loadTree(), "Login").method);
        Node again = repo.loadNode(login.id);
        assertEquals("tok = data.accessToken", again.extract);
        assertTrue(again.raw.getAsJsonObject("request").has("x-request-extension"));
        assertNotNull(id);
    }

    // ---------------------------------------------------------------- secret + environment

    @Test
    public void secretValuesAreEncryptedAtRestAndDecryptedOnLoad() {
        // environment
        EnvDoc env = EnvDoc.create("Dev", EnvDoc.KIND_ENVIRONMENT);
        env.vars.set("base_url", "https://a");
        env.vars.set("access_token_v1", "tok-PLAINTEXT-123");
        for (KeyValue kv : env.vars.items()) {
            if (kv.key.equals("access_token_v1")) kv.type = "secret";
        }
        envs.save(env);

        String rawInDb = scalar("SELECT raw_json FROM environments WHERE id='" + env.id + "'");
        assertFalse(rawInDb, rawInDb.contains("tok-PLAINTEXT-123"));
        assertTrue(rawInDb.contains("https://a"));
        EnvDoc loaded = envs.get(env.id);
        assertEquals("tok-PLAINTEXT-123", loaded.vars.get("access_token_v1"));
        assertTrue(loaded.vars.isSecret("access_token_v1"));

        // biến secret của collection
        CollectionDoc c = CollectionDoc.create("WithSecret");
        c.vars().set("api_key", "KEY-PLAINTEXT-999");
        c.vars().items().get(0).type = "secret";
        String cid = repo.insertCollection(c);
        String rawCollection = scalar("SELECT raw_json FROM collections WHERE id='" + cid + "'");
        assertFalse(rawCollection, rawCollection.contains("KEY-PLAINTEXT-999"));
        assertEquals("KEY-PLAINTEXT-999", repo.loadCollectionHeader(cid).vars().get("api_key"));
    }

    @Test
    public void environmentSelectionDefaultAndDeleteFallback() {
        EnvDoc selected = envs.getSelected();                    // tự tạo "Default" như bản cũ
        assertEquals("Default", selected.name);
        assertTrue(selected.selected);

        EnvDoc dev = envs.create("Dev");
        EnvDoc dev2 = envs.create("Dev");                        // tên trùng được đánh số
        assertEquals("Dev (2)", dev2.name);
        envs.select(dev.id);
        assertEquals(dev.id, envs.getSelected().id);
        assertEquals(3, envs.listEnvironments().size());

        envs.delete(dev.id);                                     // xóa môi trường đang chọn -> tự chọn cái khác
        assertNotNull(envs.getSelected());
        assertFalse(envs.getSelected().id.equals(dev.id));

        EnvDoc g = envs.getGlobals();
        g.vars.set("refresh_token", "r1");
        envs.save(g);
        assertEquals("r1", envs.getGlobals().vars.get("refresh_token"));
        assertEquals(2, envs.listEnvironments().size());         // globals không lẫn vào danh sách environment
    }

    @Test
    public void disabledVariablesAndUnknownEnvFieldsSurviveSaveAndLoad() throws Exception {
        String envJson = "{\"id\":\"e1\",\"name\":\"Dev\",\"values\":[{\"key\":\"a\",\"value\":\"1\",\"enabled\":true},"
                + "{\"key\":\"b\",\"value\":\"2\",\"enabled\":false}],\"_postman_variable_scope\":\"environment\","
                + "\"x-extra\":{\"k\":[1,2]}}";
        EnvDoc doc = vn.ioc.minipostman.core.importexport.PostmanParser.parse(envJson, "e.json").environment;
        envs.add(doc, true);
        EnvDoc loaded = envs.get(doc.id);
        assertEquals("1", loaded.vars.get("a"));
        assertNull(loaded.vars.get("b"));
        assertEquals(2, loaded.vars.items().size());
        assertTrue(loaded.toJson().has("x-extra"));
    }

    // ---------------------------------------------------------------- history

    @Test
    public void historyKeepsMostRecentEntriesOnly() {
        HistoryRepository h = new HistoryRepository(db);
        for (int i = 0; i < HistoryRepository.MAX_ENTRIES + 20; i++) {
            HistoryRepository.Entry e = new HistoryRepository.Entry();
            e.name = "n" + i;
            e.method = "GET";
            e.url = "http://x/" + i;
            e.timestamp = 1_000 + i;
            e.status = 200;
            h.add(e);
        }
        List<HistoryRepository.Entry> list = h.list();
        assertEquals(HistoryRepository.MAX_ENTRIES, list.size());
        assertEquals("n" + (HistoryRepository.MAX_ENTRIES + 19), list.get(0).name);   // mới nhất đứng đầu
        h.delete(list.get(0).id);
        assertEquals(HistoryRepository.MAX_ENTRIES - 1, h.list().size());
        h.clear();
        assertTrue(h.list().isEmpty());
    }

    // ---------------------------------------------------------------- di chuyển dữ liệu bản cũ

    @Test
    public void legacyDataIsMigratedWithFolderTreeRebuilt() {
        String old = "{\"envs\":[{\"name\":\"Local\",\"vars\":{\"host\":\"10.0.0.5\",\"iasPort\":\"8080\"}},"
                + "{\"name\":\"Prod\",\"vars\":{\"host\":\"prod\"}}],\"activeEnv\":1,\"requests\":["
                + "{\"id\":\"1\",\"folder\":\"Auth\",\"name\":\"Login\",\"method\":\"POST\",\"url\":\"http://{{host}}/login\","
                + "\"headers\":\"Content-Type: application/json\\n// X-Off: 1\",\"body\":\"{\\\"u\\\":1}\","
                + "\"extract\":\"accessToken = data.accessToken\"},"
                + "{\"id\":\"2\",\"folder\":\"Docs / Outgoing\",\"name\":\"List\",\"method\":\"GET\",\"url\":\"http://{{host}}/docs\","
                + "\"headers\":\"\",\"body\":\"\",\"extract\":\"\"},"
                + "{\"id\":\"3\",\"folder\":\"\",\"name\":\"Root req\",\"method\":\"GET\",\"url\":\"http://x\",\"headers\":\"\",\"body\":\"\",\"extract\":\"\"}]}";
        app.getSharedPreferences("store", android.content.Context.MODE_PRIVATE).edit().putString("data", old).commit();

        LegacyMigration.run(app, repo, envs);

        List<EnvDoc> list = envs.listEnvironments();
        assertEquals(2, list.size());
        assertEquals("Prod", envs.getSelected().name);                 // activeEnv = 1
        assertEquals("10.0.0.5", list.get(0).vars.get("host"));

        List<TreeItem> tree = repo.loadTree();
        TreeItem outgoing = byName(tree, "Outgoing");
        assertEquals(byName(tree, "Docs").id, outgoing.parentId);
        TreeItem login = byName(tree, "Login");
        assertEquals(byName(tree, "Auth").id, login.parentId);
        Node loginNode = repo.loadNode(login.id);
        assertEquals("accessToken = data.accessToken", loginNode.extract);
        vn.ioc.minipostman.core.request.RequestModel m = vn.ioc.minipostman.core.request.RequestModel.parse(loginNode.raw);
        assertEquals(2, m.headers.size());
        assertFalse(m.headers.get(1).enabled);                        // dòng "// X-Off" thành header bị tắt
        assertEquals("json", m.rawLanguage);

        // chạy lần hai không nhân đôi dữ liệu
        LegacyMigration.run(app, repo, envs);
        assertEquals(2, envs.listEnvironments().size());
        int collections = 0;
        for (TreeItem t : repo.loadTree()) {
            if ("collection".equals(t.type)) collections++;
        }
        assertEquals(1, collections);
    }

    private String scalar(String sql) {
        SQLiteDatabase d = db.getReadableDatabase();
        try (Cursor c = d.rawQuery(sql, null)) {
            c.moveToFirst();
            return c.getString(0);
        }
    }
}
