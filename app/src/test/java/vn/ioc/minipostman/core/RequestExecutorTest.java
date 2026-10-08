package vn.ioc.minipostman.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import vn.ioc.minipostman.core.exec.ExecutionInput;
import vn.ioc.minipostman.core.exec.ExecutionResult;
import vn.ioc.minipostman.core.exec.RequestExecutor;
import vn.ioc.minipostman.core.importexport.ImportResult;
import vn.ioc.minipostman.core.model.CollectionDoc;
import vn.ioc.minipostman.core.model.EnvDoc;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.core.net.HttpEngine;
import vn.ioc.minipostman.core.request.RequestModel;
import vn.ioc.minipostman.core.script.JsSandbox;

public class RequestExecutorTest {

    private static final String LOGIN_JSON = "{\"success\":true,\"data\":{\"accessToken\":\"tok-1234567890\","
            + "\"refreshToken\":\"refresh_123456\",\"userId\":12345}}";

    private MockWebServer server;
    private RequestExecutor executor;
    private final List<RecordedRequest> seen = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                synchronized (seen) {
                    seen.add(r);
                }
                String path = r.getPath();
                if (path.startsWith("/auth/login")) {
                    return new MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json")
                            .addHeader("Set-Cookie", "sid=abc123; Path=/").setBody(LOGIN_JSON);
                }
                if (path.startsWith("/echo")) {
                    return new MockResponse().setResponseCode(201).addHeader("Content-Type", "application/json")
                            .setBody("{\"ok\":true,\"items\":[1,2,3],\"name\":\"demo\"}");
                }
                if (path.startsWith("/text")) {
                    return new MockResponse().setResponseCode(200).addHeader("Content-Type", "text/plain").setBody("hello");
                }
                if (path.startsWith("/fail")) {
                    return new MockResponse().setResponseCode(500).setBody("{\"error\":\"x\"}");
                }
                return new MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json")
                        .setBody("{\"docs\":[]}");
            }
        });
        server.start();
        executor = new RequestExecutor(new HttpEngine(), new JsSandbox());
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private String base() {
        String u = server.url("/").toString();
        return u.substring(0, u.length() - 1);
    }

    private EnvDoc env(String... kv) {
        EnvDoc e = EnvDoc.create("Development", EnvDoc.KIND_ENVIRONMENT);
        e.vars.set("base_url", base());
        for (int i = 0; i < kv.length; i += 2) e.vars.set(kv[i], kv[i + 1]);
        e.vars.resetModified();
        return e;
    }

    private static Node find(List<Node> nodes, String name, List<Node> path) {
        for (Node n : nodes) {
            if (n.name.equals(name)) return n;
            if (n.isFolder()) {
                path.add(n);
                Node f = find(n.children, name, path);
                if (f != null) return f;
                path.remove(path.size() - 1);
            }
        }
        return null;
    }

    private ExecutionInput input(CollectionDoc c, String requestName, EnvDoc env, EnvDoc globals) {
        List<Node> path = new ArrayList<>();
        Node n = find(c.roots, requestName, path);
        assertNotNull("Không thấy request " + requestName, n);
        ExecutionInput in = new ExecutionInput();
        in.collection = c;
        in.ancestors = path;
        in.model = RequestModel.parse(n.raw);
        in.environment = env;
        in.globals = globals;
        in.requestId = n.id;
        return in;
    }

    private static EnvDoc globals() {
        return EnvDoc.create("Globals", EnvDoc.KIND_GLOBALS);
    }

    private RecordedRequest last() {
        synchronized (seen) {
            return seen.get(seen.size() - 1);
        }
    }

    // ------------------------------------------------------------------ luồng chính của đặc tả

    @Test
    public void loginSavesTokenInRightScopesThenNextRequestUsesIt() throws Exception {
        ImportResult imported = Fixtures.parse(Fixtures.resource("rich_collection.json"));
        CollectionDoc c = imported.collection;
        EnvDoc env = env("username", "demo", "password", "pw");
        EnvDoc glob = globals();

        // 1) POST Login
        ExecutionResult login = executor.run(input(c, "Login", env, glob));
        assertTrue(login.errors.toString(), login.errors.isEmpty());
        assertTrue(login.sent);
        assertEquals(200, login.response.code);

        assertEquals("tok-1234567890", env.vars.get("access_token_v1"));
        assertEquals("refresh_123456", glob.vars.get("refresh_token"));
        assertEquals("12345", c.vars().get("user_id"));
        assertTrue(login.envChanged);
        assertTrue(login.globalsChanged);
        assertTrue(login.collectionChanged);
        assertTrue(login.changedVars.contains("environment: access_token_v1"));

        RecordedRequest r1 = seen.get(0);
        assertEquals("POST", r1.getMethod());
        assertEquals("/auth/login?debug=1", r1.getPath());                      // param bị tắt không được gửi
        assertEquals("{\n  \"username\": \"demo\",\n  \"password\": \"pw\"\n}", r1.getBody().readUtf8());
        assertTrue(r1.getHeader("Content-Type").startsWith("application/json"));
        assertNull(r1.getHeader("X-Disabled"));
        assertNull(r1.getHeader("Authorization"));                               // auth noauth ở request

        // Biến được serialize lại đúng vào dữ liệu để lưu bền vững
        assertEquals("access_token_v1", env.toJson().getAsJsonArray("values").get(env.vars.items().size() - 1)
                .getAsJsonObject().get("key").getAsString());
        c.syncVars();
        assertEquals(3, c.raw.getAsJsonArray("variable").size());

        // 2) Request khác dùng {{access_token_v1}} qua auth Bearer kế thừa từ collection
        ExecutionResult list = executor.run(input(c, "List Documents", env, glob));
        assertTrue(list.errors.toString(), list.errors.isEmpty());
        RecordedRequest r2 = last();
        assertEquals("Bearer tok-1234567890", r2.getHeader("Authorization"));
        assertEquals("/api/documents/7?page=1.0", r2.getPath());                  // path variable :id = 7
    }

    @Test
    public void folderNoAuthStopsInheritance() {
        CollectionDoc c = Fixtures.parse(Fixtures.resource("rich_collection.json")).collection;
        EnvDoc env = env("access_token_v1", "tok-abcdef123");
        ExecutionResult res = executor.run(input(c, "Get Current User", env, globals()));
        assertTrue(res.errors.toString(), res.errors.isEmpty());
        assertNull(last().getHeader("Authorization"));
    }

    @Test
    public void scriptsRunCollectionThenFolderThenRequestAndCanEditTheRequest() throws Exception {
        JsonObject req = Fixtures.request("R", "GET", "{{base_url}}/echo");
        addScript(req, "prerequest",
                "pm.variables.set('v', pm.variables.get('v') + 'R');",
                "pm.request.headers.upsert({key: 'X-Trace', value: pm.variables.get('v')});",
                "pm.request.headers.add('X-Raw: 1');");
        addScript(req, "test", "console.log('post-R');");
        JsonObject folder = Fixtures.folder("F", req);
        addScript(folder, "prerequest", "pm.variables.set('v', pm.variables.get('v') + 'F');");
        addScript(folder, "test", "console.log('post-F');");
        JsonObject root = Fixtures.collection("C", folder);
        addScript(root, "prerequest", "pm.variables.set('v', 'C');");
        addScript(root, "test", "console.log('post-C');");

        CollectionDoc c = Fixtures.parse(Json.toJson(root)).collection;
        ExecutionResult res = executor.run(input(c, "R", env(), globals()));
        assertTrue(res.errors.toString(), res.errors.isEmpty());
        assertEquals("CFR", last().getHeader("X-Trace"));
        assertEquals("1", last().getHeader("X-Raw"));
        assertEquals(List.of("[log] post-C", "[log] post-F", "[log] post-R"), res.console);
    }

    @Test
    public void preRequestErrorStopsTheRequest() {
        JsonObject req = Fixtures.request("R", "GET", "{{base_url}}/echo");
        addScript(req, "prerequest", "throw new Error('boom');");
        CollectionDoc c = Fixtures.parse(Json.toJson(Fixtures.collection("C", req))).collection;
        ExecutionResult res = executor.run(input(c, "R", env(), globals()));
        assertFalse(res.sent);
        assertEquals(0, server.getRequestCount());
        assertTrue(res.errors.toString(), res.errors.toString().contains("boom"));
    }

    @Test
    public void missingVariableBlocksSendingAndNamesTheVariable() {
        JsonObject req = Fixtures.request("R", "GET", "{{base_url}}/echo/{{nope}}?x={{alsoMissing}}");
        CollectionDoc c = Fixtures.parse(Json.toJson(Fixtures.collection("C", req))).collection;
        ExecutionResult res = executor.run(input(c, "R", env(), globals()));
        assertFalse(res.sent);
        assertEquals(0, server.getRequestCount());
        String err = res.errors.toString();
        assertTrue(err, err.contains("nope") && err.contains("alsoMissing") && err.contains("Development"));
    }

    @Test
    public void connectionErrorIsReportedNotThrown() {
        JsonObject req = Fixtures.request("R", "GET", "http://127.0.0.1:1/unreachable");
        CollectionDoc c = Fixtures.parse(Json.toJson(Fixtures.collection("C", req))).collection;
        ExecutionInput in = input(c, "R", env(), globals());
        in.settings.connectTimeoutSec = 2;
        ExecutionResult res = executor.run(in);
        assertTrue(res.sent);
        assertTrue(res.response.isError());
        assertFalse(res.errors.isEmpty());
    }

    // ------------------------------------------------------------------ test assertions

    private static final String ASSERT_SCRIPT = String.join("\n",
            "pm.test('status ok', function () { pm.response.to.have.status(201); pm.response.to.be.success; pm.response.to.not.be.error; });",
            "pm.test('status wrong', function () { pm.response.to.have.status(200); });",
            "pm.test('header', function () { pm.response.to.have.header('Content-Type'); pm.expect(pm.response.headers.get('content-type')).to.include('json'); });",
            "pm.test('json body', function () { var j = pm.response.json(); pm.expect(j.ok).to.be.true; pm.expect(j.items).to.have.lengthOf(3); pm.expect(j.items).to.include(2); pm.expect(j).to.have.property('name', 'demo'); });",
            "pm.test('chains', function () { pm.expect({a:{b:1}}).to.have.property('a').that.is.an('object'); pm.expect({a:{b:1}}).to.have.nested.property('a.b', 1); pm.expect({a:1,b:2}).to.have.all.keys('a','b'); pm.expect({a:1,b:2}).to.include.keys('a'); pm.expect([1,2,3]).to.have.length.above(2); });",
            "pm.test('negations', function () { pm.expect(1).to.not.equal(2); pm.expect([1]).to.not.include(5); pm.expect(null).to.not.be.ok; pm.expect('a').to.not.be.a('number'); });",
            "pm.test('types', function () { pm.expect('a').to.be.a('string'); pm.expect([]).to.be.an('array'); pm.expect({}).to.be.an('object'); pm.expect(null).to.be.null; pm.expect(undefined).to.be.undefined; pm.expect(0).to.exist; pm.expect([]).to.be.empty; pm.expect('').to.be.empty; });",
            "pm.test('numbers', function () { pm.expect(5).to.be.above(4).and.below(6); pm.expect(5).to.be.within(1, 10); pm.expect(0.1+0.2).to.be.closeTo(0.3, 0.001); pm.expect(5).to.be.at.least(5); pm.expect(5).to.be.at.most(5); });",
            "pm.test('strings', function () { pm.expect('hello world').to.match(/wor/); pm.expect('abc').to.have.string('b'); pm.expect('x').to.be.oneOf(['x','y']); });",
            "pm.test('deep', function () { pm.expect({a:[1,{b:2}]}).to.eql({a:[1,{b:2}]}); pm.expect({a:1}).to.deep.equal({a:1}); });",
            "pm.test('deep fail', function () { pm.expect({a:1}).to.eql({a:2}); });",
            "pm.test('eq fail message', function () { pm.expect(1).to.equal(2); });",
            "pm.test('throws', function () { pm.expect(function () { throw new Error('bad'); }).to.throw('bad'); });",
            "pm.test('thrown error is a failure', function () { undefinedFunction(); });",
            "pm.test.skip('skipped one', function () {});");

    @Test
    public void pmTestAndExpectBehaveLikePostman() {
        JsonObject req = Fixtures.request("R", "GET", "{{base_url}}/echo");
        addScript(req, "test", ASSERT_SCRIPT.split("\n"));
        CollectionDoc c = Fixtures.parse(Json.toJson(Fixtures.collection("C", req))).collection;
        ExecutionResult res = executor.run(input(c, "R", env(), globals()));
        assertTrue(res.errors.toString(), res.errors.isEmpty());

        Map<String, JsSandbox.TestResult> byName = new HashMap<>();
        for (JsSandbox.TestResult t : res.tests) byName.put(t.name, t);
        for (String ok : new String[]{"status ok", "header", "json body", "chains", "negations", "types", "numbers",
                "strings", "deep", "throws", "skipped one"}) {
            assertNotNull(ok, byName.get(ok));
            assertTrue(ok + " -> " + byName.get(ok).message, byName.get(ok).passed);
        }
        for (String bad : new String[]{"status wrong", "deep fail", "eq fail message", "thrown error is a failure"}) {
            assertNotNull(bad, byName.get(bad));
            assertFalse(bad, byName.get(bad).passed);
        }
        assertEquals("expected 1 to equal 2", byName.get("eq fail message").message);
        assertTrue(byName.get("status wrong").message.contains("200") && byName.get("status wrong").message.contains("201"));
        assertEquals(res.tests.size() - 4, res.passed());
        assertEquals(4, res.failed());
        assertFalse(res.ok());
    }

    @Test
    public void legacyPostmanApisStillWork() {
        JsonObject req = Fixtures.request("R", "GET", "{{base_url}}/echo");
        addScript(req, "test",
                "tests['Status code is 201'] = responseCode.code === 201;",
                "tests['Body has name'] = responseBody.indexOf('demo') !== -1;",
                "tests['Fails'] = false;",
                "postman.setEnvironmentVariable('legacy', 'yes');",
                "postman.setGlobalVariable('lg', JSON.parse(responseBody).name);",
                "var order = []; setTimeout(function(){ order.push('b'); pm.environment.set('order', order.join('')); }, 20);",
                "setTimeout(function(){ order.push('a'); }, 5);");
        CollectionDoc c = Fixtures.parse(Json.toJson(Fixtures.collection("C", req))).collection;
        EnvDoc env = env();
        EnvDoc glob = globals();
        ExecutionResult res = executor.run(input(c, "R", env, glob));
        assertTrue(res.errors.toString(), res.errors.isEmpty());
        assertEquals("yes", env.vars.get("legacy"));
        assertEquals("demo", glob.vars.get("lg"));
        assertEquals("ab", env.vars.get("order"));
        Map<String, Boolean> t = new HashMap<>();
        for (JsSandbox.TestResult r : res.tests) t.put(r.name, r.passed);
        assertEquals(Boolean.TRUE, t.get("Status code is 201"));
        assertEquals(Boolean.TRUE, t.get("Body has name"));
        assertEquals(Boolean.FALSE, t.get("Fails"));
    }

    @Test
    public void consoleNeverShowsTokens() {
        JsonObject req = Fixtures.request("R", "POST", "{{base_url}}/auth/login");
        addScript(req, "test",
                "const r = pm.response.json();",
                "pm.environment.set('access_token_v1', r.data.accessToken);",
                "console.log('token is', pm.environment.get('access_token_v1'));",
                "console.log('jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r');",
                "console.log('plain visible text');");
        CollectionDoc c = Fixtures.parse(Json.toJson(Fixtures.collection("C", req))).collection;
        EnvDoc env = env();
        // token cũ phải bị che; token mới được set trong script cũng nằm trong biến tên "token" nên bị che ở dòng kế tiếp
        env.vars.set("access_token_v1", "tok-1234567890");
        ExecutionResult res = executor.run(input(c, "R", env, globals()));
        String console = String.join("\n", res.console);
        assertFalse(console, console.contains("tok-1234567890"));
        assertFalse(console, console.contains("eyJzdWIi"));
        assertTrue(console, console.contains("plain visible text"));
    }

    @Test
    public void sendRequestGoesThroughControlledBridge() {
        JsonObject req = Fixtures.request("R", "GET", "{{base_url}}/echo");
        addScript(req, "test",
                "pm.sendRequest({url: pm.environment.get('base_url') + '/text', method: 'GET'}, function (err, res) {",
                "  pm.environment.set('inner', err ? 'ERR ' + err.message : res.code + ':' + res.text());",
                "});");
        CollectionDoc c = Fixtures.parse(Json.toJson(Fixtures.collection("C", req))).collection;

        EnvDoc env = env();
        ExecutionResult res = executor.run(input(c, "R", env, globals()));
        assertTrue(res.errors.toString(), res.errors.isEmpty());
        assertEquals("200:hello", env.vars.get("inner"));
        assertEquals(2, server.getRequestCount());

        EnvDoc env2 = env();
        ExecutionInput in = input(c, "R", env2, globals());
        in.settings.allowScriptRequests = false;
        executor.run(in);
        assertTrue(env2.vars.get("inner").startsWith("ERR"));
        assertEquals(3, server.getRequestCount());      // chỉ request chính được gửi
    }

    // ------------------------------------------------------------------ gửi request: body/auth/cookie

    @Test
    public void bodiesAuthAndCookiesAreSentCorrectly() throws Exception {
        JsonObject form = Fixtures.request("Form", "POST", "{{base_url}}/echo");
        JsonObject body = new JsonObject();
        body.addProperty("mode", "urlencoded");
        JsonArray ue = new JsonArray();
        ue.add(kv("title", "Báo cáo {{who}}", false));
        ue.add(kv("skip", "me", true));
        body.add("urlencoded", ue);
        form.getAsJsonObject("request").add("body", body);

        JsonObject basic = Fixtures.request("Basic", "GET", "{{base_url}}/echo");
        JsonObject auth = new JsonObject();
        auth.addProperty("type", "basic");
        JsonArray params = new JsonArray();
        params.add(kv("username", "alice", false));
        params.add(kv("password", "p:w", false));
        auth.add("basic", params);
        basic.getAsJsonObject("request").add("auth", auth);

        JsonObject apiKey = Fixtures.request("Key", "GET", "{{base_url}}/echo?a=1");
        JsonObject ka = new JsonObject();
        ka.addProperty("type", "apikey");
        JsonArray kp = new JsonArray();
        kp.add(kv("key", "api_key", false));
        kp.add(kv("value", "K-1", false));
        kp.add(kv("in", "query", false));
        ka.add("apikey", kp);
        apiKey.getAsJsonObject("request").add("auth", ka);

        JsonObject login = Fixtures.request("Login", "POST", "{{base_url}}/auth/login");
        JsonObject raw = Fixtures.request("Raw", "PUT", "{{base_url}}/echo");
        JsonObject rb = new JsonObject();
        rb.addProperty("mode", "raw");
        rb.addProperty("raw", "{\"x\":\"{{who}}\"}");
        JsonObject opts = new JsonObject();
        JsonObject ro = new JsonObject();
        ro.addProperty("language", "json");
        opts.add("raw", ro);
        rb.add("options", opts);
        raw.getAsJsonObject("request").add("body", rb);

        CollectionDoc c = Fixtures.parse(Json.toJson(Fixtures.collection("C", form, basic, apiKey, login, raw))).collection;
        EnvDoc env = env("who", "Nam");
        EnvDoc glob = globals();

        executor.run(input(c, "Form", env, glob));
        RecordedRequest r = last();
        assertTrue(r.getHeader("Content-Type").startsWith("application/x-www-form-urlencoded"));
        assertEquals("title=B%C3%A1o%20c%C3%A1o%20Nam", r.getBody().readUtf8());   // UTF-8, trường bị tắt không gửi

        executor.run(input(c, "Basic", env, glob));
        assertEquals("Basic YWxpY2U6cDp3", last().getHeader("Authorization"));   // alice:p:w

        executor.run(input(c, "Key", env, glob));
        assertEquals("/echo?a=1&api_key=K-1", last().getPath());

        executor.run(input(c, "Login", env, glob));          // server đặt cookie sid
        executor.run(input(c, "Raw", env, glob));
        r = last();
        assertEquals("sid=abc123", r.getHeader("Cookie"));
        assertTrue(r.getHeader("Content-Type").startsWith("application/json"));
        assertEquals("{\"x\":\"Nam\"}", r.getBody().readUtf8());
        assertEquals("PUT", r.getMethod());
    }

    @Test
    public void quickExtractRulesOfOlderVersionStillSetVariables() {
        JsonObject req = Fixtures.request("R", "POST", "{{base_url}}/auth/login");
        CollectionDoc c = Fixtures.parse(Json.toJson(Fixtures.collection("C", req))).collection;
        EnvDoc env = env();
        ExecutionInput in = input(c, "R", env, globals());
        in.legacyExtract = "accessToken = data.accessToken\n# comment\nuid = data.userId\nmissing = data.nope.deeper";
        ExecutionResult res = executor.run(in);
        assertEquals("tok-1234567890", env.vars.get("accessToken"));
        assertEquals("12345", env.vars.get("uid"));
        assertNull(env.vars.get("missing"));
        assertTrue(res.envChanged);
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject kv(String key, String value, boolean disabled) {
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        o.addProperty("value", value);
        if (disabled) o.addProperty("disabled", true);
        return o;
    }

    /** Thêm event script (listen) vào item/folder/collection. */
    static void addScript(JsonObject owner, String listen, String... lines) {
        JsonArray events = owner.has("event") ? owner.getAsJsonArray("event") : new JsonArray();
        JsonObject ev = new JsonObject();
        ev.addProperty("listen", listen);
        JsonObject script = new JsonObject();
        script.addProperty("type", "text/javascript");
        JsonArray exec = new JsonArray();
        for (String l : lines) exec.add(l);
        script.add("exec", exec);
        ev.add("script", script);
        events.add(ev);
        owner.add("event", events);
    }
}
