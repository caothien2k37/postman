package vn.ioc.minipostman.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.List;

import vn.ioc.minipostman.core.model.KeyValue;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.core.request.AuthSpec;
import vn.ioc.minipostman.core.request.RequestModel;
import vn.ioc.minipostman.core.request.UrlParts;

public class RequestModelTest {

    private static JsonObject login() {
        Node n = Fixtures.parse(Fixtures.resource("rich_collection.json")).collection.roots.get(0).children.get(0);
        return n.raw;
    }

    @Test
    public void untouchedModelWritesBackIdenticalItem() {
        JsonObject raw = login();
        RequestModel m = RequestModel.parse(raw);
        assertFalse(m.isDirty());
        assertEquals(Json.toJson(raw), Json.toJson(m.toItem()));
    }

    @Test
    public void parsesAllSectionsOfLogin() {
        RequestModel m = RequestModel.parse(login());
        assertEquals("Login", m.name);
        assertEquals("POST", m.method);
        assertEquals("{{base_url}}/auth/login?debug=1", m.url);
        assertEquals(2, m.query.size());
        assertFalse(m.query.get(1).enabled);
        assertEquals(2, m.headers.size());
        assertFalse(m.headers.get(1).enabled);
        assertEquals("raw", m.bodyMode);
        assertEquals("json", m.rawLanguage);
        assertEquals(AuthSpec.NOAUTH, m.authType);
        assertTrue(m.preRequest.startsWith("pm.variables.set('trace', 'abc');\n\n//"));
        assertTrue(m.tests.contains("pm.environment.set"));
    }

    @Test
    public void editingHeadersKeepsEverythingElseAndExtraHeaderFields() {
        JsonObject raw = login();
        RequestModel m = RequestModel.parse(raw);
        m.headers.get(0).value = "application/json; charset=utf-8";
        m.headers.add(new KeyValue("X-New", "1"));
        assertTrue(m.isDirty());

        JsonObject out = m.toItem();
        JsonObject req = out.getAsJsonObject("request");
        JsonArray hs = req.getAsJsonArray("header");
        assertEquals(3, hs.size());
        assertEquals("application/json; charset=utf-8", hs.get(0).getAsJsonObject().get("value").getAsString());
        assertEquals("Kiểu nội dung", hs.get(0).getAsJsonObject().get("description").getAsString());
        assertEquals(true, hs.get(1).getAsJsonObject().get("disabled").getAsBoolean());
        // phần không sửa giữ nguyên tuyệt đối
        assertEquals(Json.toJson(raw.getAsJsonObject("request").get("url")), Json.toJson(req.get("url")));
        assertEquals(Json.toJson(raw.getAsJsonObject("request").get("body")), Json.toJson(req.get("body")));
        assertEquals(Json.toJson(raw.get("response")), Json.toJson(out.get("response")));
        assertTrue(req.has("x-request-extension"));
    }

    @Test
    public void urlTextAndParamsStayInSyncAndKeepDisabledParams() {
        RequestModel m = RequestModel.parse(login());

        m.setUrlText("{{base_url}}/auth/login?debug=2&extra=7");
        assertEquals(3, m.query.size()); // debug, extra + skip (đang tắt)
        assertEquals("2", m.query.get(0).value);
        assertEquals("extra", m.query.get(1).key);
        assertFalse(m.query.get(2).enabled);

        m.query.get(1).enabled = false;
        m.syncUrlFromQuery();
        assertEquals("{{base_url}}/auth/login?debug=2", m.url);

        JsonObject url = m.toItem().getAsJsonObject("request").getAsJsonObject("url");
        assertEquals("{{base_url}}/auth/login?debug=2", url.get("raw").getAsString());
        assertEquals(3, url.getAsJsonArray("query").size());
        assertEquals("{{base_url}}", url.getAsJsonArray("host").get(0).getAsString());
        assertEquals("login", url.getAsJsonArray("path").get(1).getAsString());
    }

    @Test
    public void authWriteBackAndInheritRemoval() {
        RequestModel m = RequestModel.parse(login());
        m.authType = AuthSpec.BEARER;
        m.authParams.put("token", "{{access_token_v1}}");
        JsonObject auth = m.toItem().getAsJsonObject("request").getAsJsonObject("auth");
        assertEquals("bearer", auth.get("type").getAsString());
        JsonObject p = auth.getAsJsonArray("bearer").get(0).getAsJsonObject();
        assertEquals("token", p.get("key").getAsString());
        assertEquals("{{access_token_v1}}", p.get("value").getAsString());
        assertEquals("string", p.get("type").getAsString());

        m.authType = AuthSpec.INHERIT;
        assertFalse(m.toItem().getAsJsonObject("request").has("auth"));
    }

    @Test
    public void v20StyleAuthObjectIsRead() {
        JsonObject req = Fixtures.request("R", "GET", "http://x");
        JsonObject auth = new JsonObject();
        auth.addProperty("type", "bearer");
        JsonObject bearer = new JsonObject();
        bearer.addProperty("token", "abc");
        auth.add("bearer", bearer);
        req.getAsJsonObject("request").add("auth", auth);
        RequestModel m = RequestModel.parse(req);
        assertEquals(AuthSpec.BEARER, m.authType);
        assertEquals("abc", m.authParams.get("token"));
    }

    @Test
    public void scriptsWriteBackKeepIdsAndRemoveWhenEmpty() {
        RequestModel m = RequestModel.parse(login());
        m.tests = "pm.test('x', function(){});\n\n";
        JsonObject out = m.toItem();
        JsonArray events = out.getAsJsonArray("event");
        assertEquals(2, events.size());
        JsonObject test = events.get(1).getAsJsonObject();
        assertEquals("test-login-id", test.getAsJsonObject("script").get("id").getAsString());
        JsonArray exec = test.getAsJsonObject("script").getAsJsonArray("exec");
        assertEquals(3, exec.size());
        assertEquals("", exec.get(2).getAsString());

        m.preRequest = "";
        m.tests = "";
        JsonObject cleared = m.toItem();
        assertFalse(cleared.has("event"));
    }

    @Test
    public void commitMakesLaterEditsBuildOnSavedState() {
        RequestModel m = RequestModel.parse(login());
        m.method = "PUT";
        JsonObject first = m.commit();
        assertEquals("PUT", first.getAsJsonObject("request").get("method").getAsString());
        assertFalse(m.isDirty());

        m.name = "Renamed";
        JsonObject second = m.toItem();
        assertEquals("Renamed", second.get("name").getAsString());
        assertEquals("PUT", second.getAsJsonObject("request").get("method").getAsString()); // thay đổi cũ không mất
    }

    @Test
    public void requestGivenAsPlainUrlStringIsSupported() {
        JsonObject item = new JsonObject();
        item.addProperty("name", "Plain");
        item.addProperty("request", "http://host/x?a=1");
        RequestModel m = RequestModel.parse(item);
        assertEquals("GET", m.method);
        assertEquals("http://host/x?a=1", m.url);
        assertEquals(Json.toJson(item), Json.toJson(m.toItem()));

        m.method = "DELETE";
        JsonObject req = m.toItem().getAsJsonObject("request");
        assertEquals("DELETE", req.get("method").getAsString());
        assertEquals("http://host/x?a=1", req.getAsJsonObject("url").get("raw").getAsString());
    }

    @Test
    public void bodyModesWriteBack() {
        RequestModel m = RequestModel.parse(Fixtures.request("R", "POST", "http://x"));
        m.bodyMode = RequestModel.BODY_URLENCODED;
        m.urlencoded.add(new KeyValue("a", "1"));
        m.urlencoded.add(new KeyValue("", ""));
        JsonObject body = m.toItem().getAsJsonObject("request").getAsJsonObject("body");
        assertEquals("urlencoded", body.get("mode").getAsString());
        assertEquals(1, body.getAsJsonArray("urlencoded").size());

        m.bodyMode = RequestModel.BODY_RAW;
        m.bodyRaw = "{\"a\":1}";
        m.rawLanguage = "json";
        body = m.toItem().getAsJsonObject("request").getAsJsonObject("body");
        assertEquals("{\"a\":1}", body.get("raw").getAsString());
        assertEquals("json", body.getAsJsonObject("options").getAsJsonObject("raw").get("language").getAsString());

        m.bodyMode = RequestModel.BODY_NONE;
        assertFalse(m.toItem().getAsJsonObject("request").has("body"));
    }

    @Test
    public void pathVariablesAreDetectedAndWritten() {
        RequestModel m = RequestModel.parse(Fixtures.request("R", "GET", "http://x/api/:id/items/:itemId"));
        assertEquals(List.of("id", "itemId"), m.pathVariableNames());
        m.ensurePathVars();
        m.pathVars.get(0).value = "5";
        JsonObject url = m.toItem().getAsJsonObject("request").getAsJsonObject("url");
        assertEquals(2, url.getAsJsonArray("variable").size());
        assertEquals("5", url.getAsJsonArray("variable").get(0).getAsJsonObject().get("value").getAsString());
    }

    @Test
    public void urlPartsSplitHostPortAndPathWithVariables() {
        JsonObject u = UrlParts.toUrlObject("{{host}}:{{port}}/api/v1?x=1", UrlParts.parseQuery("x=1"), null);
        assertEquals("{{host}}", u.getAsJsonArray("host").get(0).getAsString());
        assertEquals("{{port}}", u.get("port").getAsString());
        assertEquals(2, u.getAsJsonArray("path").size());

        JsonObject u2 = UrlParts.toUrlObject("https://api.example.com:8443/a/b#frag", UrlParts.parseQuery(""), null);
        assertEquals("https", u2.get("protocol").getAsString());
        assertEquals(3, u2.getAsJsonArray("host").size());
        assertEquals("8443", u2.get("port").getAsString());
        assertNull(u2.get("query"));
    }
}
