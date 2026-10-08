package vn.ioc.minipostman.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import vn.ioc.minipostman.core.importexport.CurlParser;
import vn.ioc.minipostman.core.importexport.ImportException;
import vn.ioc.minipostman.core.importexport.ImportResult;
import vn.ioc.minipostman.core.importexport.PostmanParser;
import vn.ioc.minipostman.core.model.EnvDoc;
import vn.ioc.minipostman.core.model.Node;

public class PostmanParserTest {

    private static void flatten(List<Node> nodes, List<Node> out) {
        for (Node n : nodes) {
            out.add(n);
            flatten(n.children, out);
        }
    }

    @Test
    public void nestedFoldersKeepParentChildAndOrder() {
        JsonObject root = Fixtures.collection("Deep", Fixtures.nestedFolders(6));
        ImportResult r = Fixtures.parse(Json.toJson(root));

        assertEquals(ImportResult.Kind.COLLECTION, r.kind);
        assertEquals("Deep", r.name);
        assertEquals(6, r.folders);
        assertEquals(12 + 0, r.requests);
        assertEquals(6, r.maxDepth);

        Node n = r.collection.roots.get(0);
        for (int level = 1; level <= 5; level++) {
            assertEquals("L" + level, n.name);
            assertTrue(n.isFolder());
            assertEquals("L" + level + "-a", n.children.get(0).name);
            assertEquals("L" + (level + 1), n.children.get(1).name);
            assertEquals("L" + level + "-b", n.children.get(2).name);
            n = n.children.get(1);
        }
        assertEquals("L6", n.name);
        assertEquals(2, n.children.size());
        // raw của folder không chứa con (con nằm ở children)
        assertEquals(0, r.collection.roots.get(0).raw.getAsJsonArray("item").size());
    }

    @Test
    public void twoHundredRequestsKeepNamesMethodsAndOrder() {
        String[] methods = {"GET", "POST", "PUT", "DELETE"};
        List<JsonObject> folders = new ArrayList<>();
        int idx = 0;
        for (int f = 0; f < 10; f++) {
            List<JsonObject> reqs = new ArrayList<>();
            for (int i = 0; i < 20; i++, idx++) {
                reqs.add(Fixtures.request("API " + idx, methods[idx % 4], "http://host/api/" + idx));
            }
            folders.add(Fixtures.folder("Folder " + f, reqs.toArray(new JsonObject[0])));
        }
        ImportResult r = Fixtures.parse(Json.toJson(Fixtures.collection("Big", folders.toArray(new JsonObject[0]))));

        assertEquals(10, r.folders);
        assertEquals(200, r.requests);
        List<Node> flat = new ArrayList<>();
        flatten(r.collection.roots, flat);
        int expected = 0;
        for (Node n : flat) {
            if (n.type != Node.Type.REQUEST) continue;
            assertEquals("API " + expected, n.name);
            assertEquals(methods[expected % 4], n.method());
            assertEquals("http://host/api/" + expected, n.url());
            expected++;
        }
        assertEquals(200, expected);
    }

    @Test
    public void richCollectionStatsAndTypes() {
        ImportResult r = Fixtures.parse(Fixtures.resource("rich_collection.json"));
        assertEquals("My Backend Collection", r.name);
        assertEquals("Postman Collection v2.1", r.schema);
        assertEquals(4, r.folders);          // Authentication, Document Management, Outgoing Documents, Reports
        assertEquals(7, r.requests);
        assertEquals(1, r.unknownItems);
        assertEquals(2 + 1 + 1, r.scripts);  // Login (2) + folder Authentication (1) + collection (1)
        assertEquals(2, r.variables);
        assertEquals(1, r.examples);

        List<Node> flat = new ArrayList<>();
        flatten(r.collection.roots, flat);
        Node unknown = null;
        for (Node n : flat) {
            if (n.type == Node.Type.UNKNOWN) unknown = n;
        }
        assertNotNull(unknown);
        assertEquals("Legacy kebab item", unknown.name);
        assertTrue(unknown.raw.has("x-unknown-kind"));
    }

    @Test
    public void warnsAboutUnsupportedFeaturesButStillImports() {
        JsonObject req = Fixtures.request("Digest", "GET", "http://x/");
        JsonObject auth = new JsonObject();
        auth.addProperty("type", "digest");
        req.getAsJsonObject("request").add("auth", auth);
        JsonObject withScript = Fixtures.request("Crypto", "GET", "http://x/");
        JsonArray ev = new JsonArray();
        JsonObject e = new JsonObject();
        e.addProperty("listen", "prerequest");
        JsonObject script = new JsonObject();
        JsonArray exec = new JsonArray();
        exec.add("const CryptoJS = require('crypto-js');");
        script.add("exec", exec);
        e.add("script", script);
        ev.add(e);
        withScript.add("event", ev);

        ImportResult r = Fixtures.parse(Json.toJson(Fixtures.collection("W", req, withScript)));
        assertEquals(2, r.requests);
        String all = String.join("\n", r.warnings);
        assertTrue(all, all.contains("digest"));
        assertTrue(all, all.contains("require()"));
        assertTrue(all, all.contains("CryptoJS"));
    }

    @Test
    public void invalidInputGivesFriendlyErrors() {
        String[] bad = {"", "   ", "not json at all", "{\"a\":", "[1,2,3]", "{\"foo\":1}"};
        for (String b : bad) {
            try {
                PostmanParser.parse(b, "x.json");
                fail("Phải lỗi với: " + b);
            } catch (ImportException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
    }

    @Test
    public void openApiAndCollectionV1AreRejectedExplicitly() throws Exception {
        try {
            PostmanParser.parse("{\"openapi\":\"3.0.0\",\"paths\":{}}", "api.json");
            fail();
        } catch (ImportException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("OpenAPI"));
        }
        try {
            PostmanParser.parse("{\"requests\":[],\"order\":[],\"name\":\"old\"}", "old.json");
            fail();
        } catch (ImportException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("v1"));
        }
    }

    @Test
    public void bomAndWrappedCollectionAreAccepted() throws Exception {
        String plain = Json.toJson(Fixtures.collection("Wrapped", Fixtures.request("R", "GET", "http://x")));
        ImportResult withBom = PostmanParser.parse("﻿" + plain, "a.json");
        assertEquals("Wrapped", withBom.name);

        ImportResult wrapped = PostmanParser.parse("{\"collection\":" + plain + "}", "a.json");
        assertEquals(1, wrapped.requests);
    }

    @Test
    public void v20SchemaIsAcceptedWithNote() throws Exception {
        JsonObject root = Fixtures.collection("Old", Fixtures.request("R", "GET", "http://x"));
        root.getAsJsonObject("info").addProperty("schema",
                "https://schema.getpostman.com/json/collection/v2.0.0/collection.json");
        ImportResult r = PostmanParser.parse(Json.toJson(root), "old.json");
        assertEquals("Postman Collection v2.0", r.schema);
        assertFalse(r.warnings.isEmpty());
    }

    @Test
    public void missingInfoGetsDefaultNameFromFile() throws Exception {
        ImportResult r = PostmanParser.parse("{\"item\":[{\"name\":\"R\",\"request\":{\"method\":\"GET\",\"url\":\"http://x\"}}]}",
                "/sdcard/Download/my.postman_collection.json");
        assertEquals("my", r.name);
        assertEquals("info", r.collection.raw.keySet().iterator().next());
    }

    @Test
    public void environmentWithSecretIsParsed() throws Exception {
        String env = "{\"id\":\"e1\",\"name\":\"Development\",\"values\":["
                + "{\"key\":\"base_url\",\"value\":\"https://api.example.com\",\"type\":\"default\",\"enabled\":true},"
                + "{\"key\":\"access_token_v1\",\"value\":\"\",\"type\":\"secret\",\"enabled\":true},"
                + "{\"key\":\"off\",\"value\":\"1\",\"enabled\":false}],"
                + "\"_postman_variable_scope\":\"environment\",\"_postman_exported_at\":\"2024-01-01T00:00:00.000Z\"}";
        ImportResult r = PostmanParser.parse(env, "dev.postman_environment.json");
        assertEquals(ImportResult.Kind.ENVIRONMENT, r.kind);
        assertEquals("Development", r.name);
        assertEquals(3, r.variables);
        assertEquals("https://api.example.com", r.environment.vars.get("base_url"));
        assertFalse(r.environment.vars.has("off"));          // biến tắt không dùng
        assertTrue(r.environment.vars.isSecret("access_token_v1"));
        assertFalse(r.warnings.isEmpty());
    }

    @Test
    public void globalsAreRecognised() throws Exception {
        ImportResult r = PostmanParser.parse("{\"name\":\"Globals\",\"values\":[{\"key\":\"a\",\"value\":\"1\",\"enabled\":true}],"
                + "\"_postman_variable_scope\":\"globals\"}", "g.json");
        assertEquals(ImportResult.Kind.GLOBALS, r.kind);
        assertEquals(EnvDoc.KIND_GLOBALS, r.environment.kind);
    }

    @Test
    public void tooDeepTreeIsRejectedInsteadOfCrashing() {
        JsonObject inner = Fixtures.request("leaf", "GET", "http://x");
        for (int i = 0; i < 200; i++) inner = Fixtures.folder("F" + i, inner);
        try {
            PostmanParser.parse(Json.toJson(Fixtures.collection("Deep", inner)), "deep.json");
            fail("Phải từ chối cây quá sâu");
        } catch (ImportException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("quá sâu"));
        }
    }

    @Test
    public void absurdJsonNestingIsRejectedBeforeParsing() {
        StringBuilder sb = new StringBuilder("{\"item\":");
        for (int i = 0; i < 100_000; i++) sb.append('[');
        try {
            PostmanParser.parse(sb.toString(), "bomb.json");
            fail();
        } catch (ImportException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("quá sâu"));
        }
    }

    @Test
    public void curlBecomesRequest() throws Exception {
        String cmd = "curl -X POST 'https://api.example.com/auth/login?debug=1' \\\n"
                + "  -H 'Content-Type: application/json' \\\n"
                + "  -H \"Authorization: Bearer abc\" \\\n"
                + "  -d '{\"username\":\"demo\",\"password\":\"x y\"}' --compressed -L";
        ImportResult r = PostmanParser.parse(cmd, "clipboard");
        assertEquals(ImportResult.Kind.CURL, r.kind);
        Node n = r.collection.roots.get(0);
        assertEquals("POST", n.method());
        assertEquals("https://api.example.com/auth/login?debug=1", n.url());
        JsonObject req = n.raw.getAsJsonObject("request");
        assertEquals(2, req.getAsJsonArray("header").size());
        JsonObject body = req.getAsJsonObject("body");
        assertEquals("raw", body.get("mode").getAsString());
        assertEquals("{\"username\":\"demo\",\"password\":\"x y\"}", body.get("raw").getAsString());
        assertEquals("json", body.getAsJsonObject("options").getAsJsonObject("raw").get("language").getAsString());
        assertEquals("api.example.com", req.getAsJsonObject("url").getAsJsonArray("host").get(0).getAsString()
                + "." + req.getAsJsonObject("url").getAsJsonArray("host").get(1).getAsString()
                + "." + req.getAsJsonObject("url").getAsJsonArray("host").get(2).getAsString());
    }

    @Test
    public void curlDefaultsAndBasicAuthAndChromeStyleQuoting() throws Exception {
        ImportResult get = CurlParser.parse("curl https://example.com/a");
        assertEquals("GET", get.collection.roots.get(0).method());

        ImportResult post = CurlParser.parse("curl example.com/p -d 'a=1&b=2' -u user:pa:ss");
        Node n = post.collection.roots.get(0);
        assertEquals("POST", n.method());
        assertEquals("http://example.com/p", n.url());
        JsonObject auth = n.raw.getAsJsonObject("request").getAsJsonObject("auth");
        assertEquals("basic", auth.get("type").getAsString());
        assertEquals("pa:ss", auth.getAsJsonArray("basic").get(1).getAsJsonObject().get("value").getAsString());

        ImportResult chrome = CurlParser.parse("curl 'https://x.io/q' -H $'X-Note: line1\\nline2' --data-raw $'{\"a\":\"it\\'s\"}'");
        JsonObject req = chrome.collection.roots.get(0).raw.getAsJsonObject("request");
        assertEquals("X-Note", req.getAsJsonArray("header").get(0).getAsJsonObject().get("key").getAsString());
        assertEquals("line1\nline2", req.getAsJsonArray("header").get(0).getAsJsonObject().get("value").getAsString());
        assertEquals("{\"a\":\"it's\"}", req.getAsJsonObject("body").get("raw").getAsString());
    }
}
