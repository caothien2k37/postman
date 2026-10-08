package vn.ioc.minipostman.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import vn.ioc.minipostman.core.script.JsSandbox;
import vn.ioc.minipostman.core.vars.Redactor;

/** Kiểm tra sandbox ở mức thấp: giới hạn thời gian, chặn truy cập Java, cô lập giữa các lần chạy. */
public class JsSandboxTest {

    private static final class StubHost implements JsSandbox.Host {
        final Map<String, String> env = new HashMap<>();

        @Override public String contextJson() {
            return "{\"info\":{\"eventName\":\"prerequest\",\"iteration\":0,\"iterationCount\":1,"
                    + "\"requestName\":\"R\",\"requestId\":\"1\",\"environmentName\":\"E\"}}";
        }
        @Override public String getVar(String scope, String key) { return env.get(scope + ":" + key); }
        @Override public boolean hasVar(String scope, String key) { return env.containsKey(scope + ":" + key); }
        @Override public void setVar(String scope, String key, String value) { env.put(scope + ":" + key, value); }
        @Override public void unsetVar(String scope, String key) { env.remove(scope + ":" + key); }
        @Override public void clearVars(String scope) { env.clear(); }
        @Override public String allVars(String scope) { return "{}"; }
        @Override public String replaceIn(String text) { return text; }
        @Override public String requestJson() { return "{\"method\":\"GET\",\"url\":\"http://x\",\"headers\":[],\"body\":{\"mode\":\"none\",\"raw\":\"\"}}"; }
        @Override public void requestSet(String field, String value) { }
        @Override public void requestHeader(String op, String name, String value) { }
        @Override public String responseBody() { return ""; }
        @Override public String cookie(String name) { return null; }
        @Override public String sendRequest(String specJson) { return "{\"error\":\"stub\"}"; }
    }

    private static JsSandbox.Outcome run(JsSandbox sb, StubHost host, long timeoutMs, String... scripts) {
        JsSandbox.Job job = new JsSandbox.Job("prerequest", host);
        job.timeoutMs = timeoutMs;
        job.redactor = new Redactor();
        int i = 0;
        for (String s : scripts) job.scripts.add(new JsSandbox.Script("script" + (++i), s));
        return sb.run(job);
    }

    @Test
    public void infiniteLoopIsStoppedByTheTimeLimit() {
        JsSandbox sb = new JsSandbox();
        long t0 = System.nanoTime();
        JsSandbox.Outcome o = run(sb, new StubHost(), 300, "while (true) {}");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertFalse(o.errors.isEmpty());
        assertTrue(o.errors.toString(), o.errors.get(0).contains("giây"));
        assertTrue("Dừng quá chậm: " + ms + " ms", ms < 3000);
    }

    @Test
    public void scriptCannotCatchTheTimeout() {
        JsSandbox sb = new JsSandbox();
        JsSandbox.Outcome o = run(sb, new StubHost(), 300,
                "try { while (true) {} } catch (e) { pm.environment.set('survived', 'yes'); }");
        assertFalse(o.errors.isEmpty());
    }

    @Test
    public void runawayRecursionIsReportedNotCrashing() {
        JsSandbox.Outcome o = run(new JsSandbox(), new StubHost(), 5000, "function f(){ return f() + 1; } f();");
        assertFalse(o.errors.isEmpty());
    }

    @Test
    public void javaAndHostObjectsAreNotReachable() {
        StubHost host = new StubHost();
        JsSandbox.Outcome o = run(new JsSandbox(), host, 5000,
                "pm.environment.set('p', typeof Packages);",
                "pm.environment.set('j', typeof java);",
                "pm.environment.set('s', typeof System);",
                "pm.environment.set('o', typeof Runtime);",
                "pm.environment.set('c', typeof importClass);",
                "pm.environment.set('f', (function(){ try { return this.constructor.constructor('return typeof java')(); } catch (e) { return 'err'; } })());",
                "pm.environment.set('g', (function(){ try { return String(pm.environment.getClass()); } catch (e) { return 'err'; } })());",
                "pm.environment.set('h', (function(){ try { return String(__host.getClass()); } catch (e) { return 'err'; } })());");
        assertTrue(o.errors.toString(), o.errors.isEmpty());
        assertEquals("undefined", host.env.get("environment:p"));
        assertEquals("undefined", host.env.get("environment:j"));
        assertEquals("undefined", host.env.get("environment:s"));
        assertEquals("undefined", host.env.get("environment:o"));
        assertEquals("undefined", host.env.get("environment:c"));
        assertTrue(host.env.get("environment:f"), host.env.get("environment:f").equals("undefined") || host.env.get("environment:f").equals("err"));
        assertEquals("err", host.env.get("environment:g"));
        assertEquals("err", host.env.get("environment:h"));
    }

    @Test
    public void standardLibraryIsSealedAndRunsAreIsolated() {
        JsSandbox sb = new JsSandbox();
        StubHost host = new StubHost();
        JsSandbox.Outcome first = run(sb, host, 5000,
                "try { Array.prototype.hacked = 1; } catch (e) {}",
                "try { Object.prototype.polluted = true; } catch (e) {}",
                "globalLeak = 42;");
        assertTrue(first.errors.toString(), first.errors.isEmpty());

        JsSandbox.Outcome second = run(sb, host, 5000,
                "pm.environment.set('a', String([].hacked));",
                "pm.environment.set('b', String({}.polluted));",
                "pm.environment.set('c', typeof globalLeak);");
        assertTrue(second.errors.toString(), second.errors.isEmpty());
        assertEquals("undefined", host.env.get("environment:a"));
        assertEquals("undefined", host.env.get("environment:b"));
        assertEquals("undefined", host.env.get("environment:c"));
    }

    @Test
    public void constDeclarationsDoNotClashBetweenScripts() {
        StubHost host = new StubHost();
        JsSandbox.Outcome o = run(new JsSandbox(), host, 5000,
                "const response = {v: 'one'}; pm.environment.set('x1', response.v);",
                "const response = {v: 'two'}; pm.environment.set('x2', response.v);");
        assertTrue(o.errors.toString(), o.errors.isEmpty());
        assertEquals("one", host.env.get("environment:x1"));
        assertEquals("two", host.env.get("environment:x2"));
    }

    @Test
    public void modernSyntaxCommonInPostmanScriptsWorks() {
        StubHost host = new StubHost();
        JsSandbox.Outcome o = run(new JsSandbox(), host, 5000,
                "const items = [1, 2, 3].map(n => n * 2);\n"
                        + "let total = 0; items.forEach(n => { total += n; });\n"
                        + "const msg = `total=${total}`;\n"
                        + "pm.environment.set('msg', msg);\n"
                        + "pm.environment.set('b64', btoa('user:pass') + '|' + atob('dXNlcjpwYXNz'));");
        assertTrue(o.errors.toString(), o.errors.isEmpty());
        assertEquals("total=12", host.env.get("environment:msg"));
        assertEquals("dXNlcjpwYXNz|user:pass", host.env.get("environment:b64"));
    }

    @Test
    public void requireIsRejectedWithClearMessage() {
        JsSandbox.Outcome o = run(new JsSandbox(), new StubHost(), 5000, "const _ = require('lodash');");
        assertFalse(o.errors.isEmpty());
        assertTrue(o.errors.toString(), o.errors.get(0).contains("require('lodash')"));
    }

    @Test
    public void syntaxErrorsAreReportedWithLineNumber() {
        JsSandbox.Outcome o = run(new JsSandbox(), new StubHost(), 5000, "var a = 1;\nvar b = ;");
        assertFalse(o.errors.isEmpty());
        assertTrue(o.errors.toString(), o.errors.get(0).contains("script1"));
        assertTrue(o.errors.toString(), o.errors.get(0).contains("dòng 2"));
    }

    @Test
    public void setNextRequestIsReported() {
        JsSandbox.Outcome o = run(new JsSandbox(), new StubHost(), 5000, "postman.setNextRequest('Other');");
        assertTrue(o.nextRequestSet);
        assertEquals("Other", o.nextRequest);

        JsSandbox.Outcome stop = run(new JsSandbox(), new StubHost(), 5000, "pm.execution.setNextRequest(null);");
        assertTrue(stop.nextRequestSet);
        assertEquals(null, stop.nextRequest);
    }
}
