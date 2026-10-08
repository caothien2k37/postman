package vn.ioc.minipostman.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

import vn.ioc.minipostman.core.model.KeyValue;
import vn.ioc.minipostman.core.model.VarScope;
import vn.ioc.minipostman.core.vars.Redactor;
import vn.ioc.minipostman.core.vars.VariableResolver;

public class VariableResolverTest {

    private static VarScope scope(String name, String... kv) {
        VarScope s = VarScope.empty(name);
        for (int i = 0; i < kv.length; i += 2) s.set(kv[i], kv[i + 1]);
        return s;
    }

    @Test
    public void precedenceIsLocalDataEnvironmentCollectionGlobal() {
        VarScope local = scope("local", "a", "local");
        VarScope data = scope("data", "a", "data", "b", "data");
        VarScope env = scope("environment", "a", "env", "b", "env", "c", "env");
        VarScope coll = scope("collection", "a", "coll", "b", "coll", "c", "coll", "d", "coll");
        VarScope glob = scope("global", "a", "glob", "b", "glob", "c", "glob", "d", "glob", "e", "glob");
        VariableResolver r = new VariableResolver(local, data, env, coll, glob);

        assertEquals("local", r.resolve("{{a}}"));
        assertEquals("data", r.resolve("{{b}}"));
        assertEquals("env", r.resolve("{{c}}"));
        assertEquals("coll", r.resolve("{{d}}"));
        assertEquals("glob", r.resolve("{{e}}"));
        assertEquals("local-data-env-coll-glob", r.resolve("{{a}}-{{b}}-{{c}}-{{d}}-{{e}}"));
    }

    @Test
    public void disabledVariablesAreNeverUsed() {
        VarScope env = VarScope.empty("environment");
        env.set("token", "env-token");
        env.items().get(0).enabled = false;
        VarScope glob = scope("global", "token", "glob-token");
        VariableResolver r = new VariableResolver(env, glob);
        assertEquals("glob-token", r.resolve("{{token}}"));

        VariableResolver only = new VariableResolver(env);
        assertEquals("{{token}}", only.resolve("{{token}}"));
        assertNull(only.get("token"));
    }

    @Test
    public void nestedVariablesAndSpacesAreResolved() {
        VarScope env = scope("environment",
                "host", "10.0.0.5", "iasPort", "8080", "iasUrl", "http://{{host}}:{{ iasPort }}/api/v1/iam");
        VariableResolver r = new VariableResolver(env);
        assertEquals("http://10.0.0.5:8080/api/v1/iam/login", r.resolve("{{iasUrl}}/login"));
    }

    @Test
    public void selfReferenceDoesNotLoopForever() {
        VarScope env = scope("environment", "a", "{{a}}x");
        String out = new VariableResolver(env).resolve("{{a}}");
        assertTrue(out.contains("x"));
    }

    @Test
    public void unresolvedNamesAreReported() {
        VarScope env = scope("environment", "host", "h");
        VariableResolver r = new VariableResolver(env);
        String resolved = r.resolve("{{host}}/{{missing}}?x={{ other }}");
        Set<String> missing = VariableResolver.unresolved(resolved);
        assertEquals(2, missing.size());
        assertTrue(missing.contains("missing"));
        assertTrue(missing.contains("other"));
    }

    @Test
    public void dynamicVariablesAreGenerated() {
        VariableResolver r = new VariableResolver();
        String a = r.resolve("{{$guid}}");
        String b = r.resolve("{{$randomUUID}}");
        assertEquals(36, a.length());
        assertNotEquals(a, b);
        assertTrue(Long.parseLong(r.resolve("{{$timestamp}}")) > 1_600_000_000L);
        int n = Integer.parseInt(r.resolve("{{$randomInt}}"));
        assertTrue(n >= 0 && n <= 1000);
        assertTrue(r.resolve("{{$isoTimestamp}}").endsWith("Z"));
        assertEquals("{{$unknownThing}}", r.resolve("{{$unknownThing}}"));
    }

    @Test
    public void replacementTextWithDollarAndBackslashIsLiteral() {
        VarScope env = scope("environment", "p", "a$1\\b");
        assertEquals("x=a$1\\b", new VariableResolver(env).resolve("x={{p}}"));
    }

    @Test
    public void setAndUnsetTrackTouchedKeys() {
        VarScope s = VarScope.empty("environment");
        assertFalse(s.isModified());
        s.set("k", "v");
        s.set("k", "v2");
        assertEquals("v2", s.get("k"));
        assertEquals(1, s.items().size());
        assertTrue(s.isModified());
        assertTrue(s.touchedKeys().contains("k"));
        s.unset("k");
        assertNull(s.get("k"));
        s.resetModified();
        assertFalse(s.isModified());
    }

    @Test
    public void redactorMasksSecretsAndJwtButNotOrdinaryText() {
        VarScope env = VarScope.empty("environment");
        env.set("access_token_v1", "tok_1234567890abcdef");
        env.set("note", "hello-world-visible");
        KeyValue secret = new KeyValue("other", "s3cr3t-value");
        secret.type = "secret";
        env.items().add(secret);
        Redactor red = Redactor.forScopes(env);

        String out = red.mask("tok=tok_1234567890abcdef s=s3cr3t-value n=hello-world-visible "
                + "jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r");
        assertFalse(out.contains("tok_1234567890abcdef"));
        assertFalse(out.contains("s3cr3t-value"));
        assertFalse(out.contains("eyJzdWIi"));
        assertTrue(out.contains("hello-world-visible"));
    }
}
