package vn.ioc.minipostman.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.Test;

import vn.ioc.minipostman.core.importexport.ImportResult;
import vn.ioc.minipostman.core.importexport.PostmanExporter;
import vn.ioc.minipostman.core.model.CollectionDoc;
import vn.ioc.minipostman.core.model.EnvDoc;
import vn.ioc.minipostman.core.model.Node;

public class RoundTripTest {

    @Test
    public void importExportIsIdenticalIncludingOrderNumbersAndUnknownFields() {
        String original = Fixtures.resource("rich_collection.json");
        ImportResult r = Fixtures.parse(original);

        String exported = PostmanExporter.exportCollection(r.collection);

        // So sánh dạng compact: khớp tuyệt đối cả thứ tự khóa, số (12345678901234567890, 1.0), unicode, trường lạ.
        String a = Json.toJson(Json.parse(original));
        String b = Json.toJson(Json.parse(exported));
        assertEquals(a, b);
        assertTrue(exported.contains("12345678901234567890"));
        assertTrue(exported.contains("page=1.0"));
        assertTrue(exported.contains("x-request-extension"));
        assertTrue(exported.contains("x-folder-extension"));
        assertTrue(exported.contains("x-unknown-kind"));
        assertTrue(exported.contains("😀"));
    }

    @Test
    public void reImportOfExportIsStable() {
        ImportResult first = Fixtures.parse(Fixtures.resource("rich_collection.json"));
        String exported1 = PostmanExporter.exportCollection(first.collection);
        ImportResult second = Fixtures.parse(exported1);
        String exported2 = PostmanExporter.exportCollection(second.collection);
        assertEquals(exported1, exported2);

        assertEquals(first.folders, second.folders);
        assertEquals(first.requests, second.requests);
        assertEquals(first.scripts, second.scripts);
        assertEquals(first.variables, second.variables);
        assertEquals(first.examples, second.examples);
    }

    @Test
    public void exportKeepsItemKeyPositionAndFolderChildrenOrder() {
        ImportResult r = Fixtures.parse(Fixtures.resource("rich_collection.json"));
        JsonObject out = PostmanExporter.collectionJson(r.collection);
        java.util.Iterator<String> keys = out.keySet().iterator();
        assertEquals("info", keys.next());
        assertEquals("item", keys.next());
        JsonArray items = out.getAsJsonArray("item");
        assertEquals("Authentication", items.get(0).getAsJsonObject().get("name").getAsString());
        JsonArray auth = items.get(0).getAsJsonObject().getAsJsonArray("item");
        assertEquals("Login", auth.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("Get Current User", auth.get(1).getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void editingOneNodeOnlyChangesThatNode() {
        ImportResult r = Fixtures.parse(Fixtures.resource("rich_collection.json"));
        Node login = r.collection.roots.get(0).children.get(0);
        login.setName("Login v2");
        String before = Json.toJson(Json.parse(Fixtures.resource("rich_collection.json")));
        String after = Json.toJson(PostmanExporter.collectionJson(r.collection));
        assertEquals(before.replace("\"name\":\"Login\"", "\"name\":\"Login v2\""), after);
    }

    @Test
    public void collectionVariablesRoundTripKeepsTypeAndDisabled() {
        ImportResult r = Fixtures.parse(Fixtures.resource("rich_collection.json"));
        CollectionDoc doc = r.collection;
        assertEquals("https://api.example.com", doc.vars().get("base_url"));
        assertEquals(null, doc.vars().get("unused")); // disabled
        doc.vars().set("user_id", "12345");
        JsonObject out = PostmanExporter.collectionJson(doc);
        JsonArray vars = out.getAsJsonArray("variable");
        assertEquals(3, vars.size());
        assertEquals(true, vars.get(1).getAsJsonObject().get("disabled").getAsBoolean());
        assertEquals("user_id", vars.get(2).getAsJsonObject().get("key").getAsString());
    }

    @Test
    public void environmentExportRespectsSecretChoice() throws Exception {
        String env = "{\"id\":\"e1\",\"name\":\"Dev\",\"values\":["
                + "{\"key\":\"base_url\",\"value\":\"https://a\",\"type\":\"default\",\"enabled\":true},"
                + "{\"key\":\"tok\",\"value\":\"s3cr3t\",\"type\":\"secret\",\"enabled\":true}],"
                + "\"_postman_variable_scope\":\"environment\",\"x-extra\":{\"k\":1}}";
        EnvDoc doc = vn.ioc.minipostman.core.importexport.PostmanParser.parse(env, "e.json").environment;

        String with = PostmanExporter.exportEnvironment(doc, true);
        String without = PostmanExporter.exportEnvironment(doc, false);
        assertTrue(with.contains("s3cr3t"));
        assertTrue(!without.contains("s3cr3t"));
        assertTrue(without.contains("https://a"));
        assertTrue(without.contains("x-extra"));

        JsonElement back = Json.parse(with);
        assertEquals("environment", back.getAsJsonObject().get("_postman_variable_scope").getAsString());
    }

    @Test
    public void exportOfSingleFolderIsAValidCollection() {
        ImportResult r = Fixtures.parse(Fixtures.resource("rich_collection.json"));
        Node docs = r.collection.roots.get(1);
        String exported = PostmanExporter.exportNode(r.collection, docs);
        ImportResult again = Fixtures.parse(exported);
        assertEquals("Document Management", again.name);
        assertEquals(1 + 1, again.folders);
        assertEquals(5, again.requests);
    }
}
