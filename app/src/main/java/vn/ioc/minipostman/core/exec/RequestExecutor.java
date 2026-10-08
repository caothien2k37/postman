package vn.ioc.minipostman.core.exec;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.HttpUrl;
import vn.ioc.minipostman.JsonPath;
import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.Strings;
import vn.ioc.minipostman.core.model.KeyValue;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.core.model.VarScope;
import vn.ioc.minipostman.core.net.HttpEngine;
import vn.ioc.minipostman.core.net.HttpSettings;
import vn.ioc.minipostman.core.net.PreparedRequest;
import vn.ioc.minipostman.core.net.ResponseData;
import vn.ioc.minipostman.core.request.RequestModel;
import vn.ioc.minipostman.core.script.JsSandbox;
import vn.ioc.minipostman.core.vars.Redactor;
import vn.ioc.minipostman.core.vars.VariableResolver;

/**
 * Chạy một request đầy đủ theo thứ tự của Postman:
 * pre-request (collection → folder → request) → thay biến → gửi → post-response/tests (cùng thứ tự).
 * Biến do script đặt được ghi vào đúng phạm vi (environment/globals/collection/local); nơi gọi chịu trách
 * nhiệm lưu các phạm vi có cờ changed.
 */
public final class RequestExecutor {

    private final HttpEngine engine;
    private final JsSandbox sandbox;

    public RequestExecutor(HttpEngine engine, JsSandbox sandbox) {
        this.engine = engine;
        this.sandbox = sandbox;
    }

    public HttpEngine engine() {
        return engine;
    }

    public ExecutionResult run(ExecutionInput in) {
        ExecutionResult res = new ExecutionResult();
        VarScope env = in.environment.vars;
        VarScope globals = in.globals.vars;
        VarScope coll = in.collection.vars();
        env.resetModified();
        globals.resetModified();
        coll.resetModified();

        VarScope local = VarScope.empty(VarScope.LOCAL);
        VariableResolver resolver = new VariableResolver(local, in.data, env, coll, globals);
        Redactor red = Redactor.forScopes(env, coll, globals, in.data);
        RequestModel work = in.model.copy();
        ScriptHost host = new ScriptHost(in, work, local, resolver, red);

        // 1. Pre-request
        JsSandbox.Job pre = newJob("prerequest", host, in, red);
        addScripts(pre, in, "prerequest", work.preRequest, "Pre-request");
        if (!pre.scripts.isEmpty()) {
            JsSandbox.Outcome o = sandbox.run(pre);
            merge(res, o);
            if (!o.errors.isEmpty()) {
                res.errors.add(0, "Pre-request script bị lỗi, request không được gửi.");
                finish(res, in, env, globals, coll);
                return res;
            }
        }

        // 2. Thay biến và dựng request
        PreparedRequest p = RequestPreparer.prepare(work, resolver,
                RequestPreparer.effectiveAuth(work, in.collection.raw, in.ancestors));
        res.prepared = p;
        res.warnings.addAll(p.warnings);

        Set<String> missing = new LinkedHashSet<>(VariableResolver.unresolved(p.url));
        for (String[] h : p.headers) {
            missing.addAll(VariableResolver.unresolved(h[0]));
            missing.addAll(VariableResolver.unresolved(h[1]));
        }
        missing.addAll(VariableResolver.unresolved(p.bodyRaw));
        for (String[] f : p.form) {
            missing.addAll(VariableResolver.unresolved(f[0]));
            missing.addAll(VariableResolver.unresolved(f[1]));
        }
        for (String[] q : p.extraQuery) missing.addAll(VariableResolver.unresolved(q[1]));
        if (!missing.isEmpty()) {
            res.errors.add("Biến chưa có trong môi trường \"" + in.environment.name + "\": "
                    + Strings.join(", ", missing));
            finish(res, in, env, globals, coll);
            return res;
        }
        res.requestLine = red.mask(p.method + " " + p.url);

        // 3. Gửi
        ResponseData resp = engine.execute(p, in.settings, in.cancel);
        res.response = resp;
        res.sent = true;
        if (resp.isError()) {
            res.errors.add("Lỗi kết nối: " + red.mask(resp.error));
            finish(res, in, env, globals, coll);
            return res;
        }
        host.response = resp;

        // 4. Post-response / tests
        JsSandbox.Job post = newJob("test", host, in, red);
        addScripts(post, in, "test", work.tests, "Post-response");
        if (!post.scripts.isEmpty()) {
            JsSandbox.Outcome o = sandbox.run(post);
            merge(res, o);
        }

        // 5. "Set biến từ response" kiểu cũ
        if (!Strings.isBlank(in.legacyExtract)) {
            JsonElement json = Json.tryParse(resp.body);
            if (json != null) {
                List<String> set = JsonPath.applyRules(in.legacyExtract, json, env);
                if (!set.isEmpty()) res.console.add("[info] Đã set biến từ response: " + Strings.join(", ", set));
            }
        }
        finish(res, in, env, globals, coll);
        return res;
    }

    private JsSandbox.Job newJob(String phase, ScriptHost host, ExecutionInput in, Redactor red) {
        JsSandbox.Job job = new JsSandbox.Job(phase, host);
        job.redactor = red;
        job.allowSendRequest = in.settings.allowScriptRequests;
        return job;
    }

    private static void addScripts(JsSandbox.Job job, ExecutionInput in, String listen, String requestScript, String title) {
        String c = RequestModel.scriptOf(in.collection.raw, listen);
        if (!Strings.isBlank(c)) job.scripts.add(new JsSandbox.Script(title + " (collection)", c));
        for (Node folder : in.ancestors) {
            String f = RequestModel.scriptOf(folder.raw, listen);
            if (!Strings.isBlank(f)) job.scripts.add(new JsSandbox.Script(title + " (folder " + folder.name + ")", f));
        }
        if (!Strings.isBlank(requestScript)) job.scripts.add(new JsSandbox.Script(title + " (request)", requestScript));
    }

    private static void merge(ExecutionResult res, JsSandbox.Outcome o) {
        res.tests.addAll(o.tests);
        res.console.addAll(o.console);
        res.errors.addAll(o.errors);
        if (o.nextRequestSet) {
            res.nextRequestSet = true;
            res.nextRequest = o.nextRequest;
        }
    }

    private static void finish(ExecutionResult res, ExecutionInput in, VarScope env, VarScope globals, VarScope coll) {
        res.envChanged = env.isModified();
        res.globalsChanged = globals.isModified();
        res.collectionChanged = coll.isModified();
        for (String k : env.touchedKeys()) res.changedVars.add("environment: " + k);
        for (String k : globals.touchedKeys()) res.changedVars.add("globals: " + k);
        for (String k : coll.touchedKeys()) res.changedVars.add("collection: " + k);
    }

    // ---------------------------------------------------------------- cầu nối script

    private final class ScriptHost implements JsSandbox.Host {
        private final ExecutionInput in;
        private final RequestModel work;
        private final VarScope local;
        private final VariableResolver resolver;
        private final Redactor red;
        ResponseData response;

        ScriptHost(ExecutionInput in, RequestModel work, VarScope local, VariableResolver resolver, Redactor red) {
            this.in = in;
            this.work = work;
            this.local = local;
            this.resolver = resolver;
            this.red = red;
        }

        private VarScope scope(String name) {
            switch (name) {
                case "environment": return in.environment.vars;
                case "globals": return in.globals.vars;
                case "collectionVariables": return in.collection.vars();
                case "iterationData": return in.data;
                default: return local;
            }
        }

        @Override
        public String contextJson() {
            JsonObject root = new JsonObject();
            JsonObject info = new JsonObject();
            info.addProperty("eventName", response == null ? "prerequest" : "test");
            info.addProperty("iteration", in.iteration);
            info.addProperty("iterationCount", in.iterationCount);
            info.addProperty("requestName", work.name);
            info.addProperty("requestId", in.requestId);
            info.addProperty("environmentName", in.environment.name);
            root.add("info", info);
            if (response != null) {
                JsonObject r = new JsonObject();
                r.addProperty("code", response.code);
                r.addProperty("status", response.message);
                r.addProperty("time", response.timeMs);
                r.addProperty("size", response.size);
                JsonArray hs = new JsonArray();
                for (String[] h : response.headers) {
                    JsonArray pair = new JsonArray();
                    pair.add(h[0]);
                    pair.add(h[1]);
                    hs.add(pair);
                }
                r.add("headers", hs);
                root.add("response", r);
            }
            return Json.toJson(root);
        }

        @Override
        public String getVar(String scopeName, String key) {
            if (scopeName.equals("variables")) return resolver.get(key);
            VarScope s = scope(scopeName);
            return s == null ? null : s.get(key);
        }

        @Override
        public boolean hasVar(String scopeName, String key) {
            return getVar(scopeName, key) != null;
        }

        @Override
        public void setVar(String scopeName, String key, String value) {
            if (scopeName.equals("iterationData")) return;
            VarScope s = scopeName.equals("variables") ? local : scope(scopeName);
            if (s != null) s.set(key, value);
        }

        @Override
        public void unsetVar(String scopeName, String key) {
            if (scopeName.equals("iterationData")) return;
            VarScope s = scopeName.equals("variables") ? local : scope(scopeName);
            if (s != null) s.unset(key);
        }

        @Override
        public void clearVars(String scopeName) {
            if (scopeName.equals("iterationData")) return;
            VarScope s = scopeName.equals("variables") ? local : scope(scopeName);
            if (s != null) s.clear();
        }

        @Override
        public String allVars(String scopeName) {
            Map<String, String> m;
            if (scopeName.equals("variables")) m = resolver.snapshot();
            else {
                VarScope s = scope(scopeName);
                m = s == null ? new java.util.LinkedHashMap<String, String>() : s.toMap();
            }
            JsonObject o = new JsonObject();
            for (Map.Entry<String, String> e : m.entrySet()) o.addProperty(e.getKey(), e.getValue());
            return Json.toJson(o);
        }

        @Override
        public String replaceIn(String text) {
            return resolver.resolve(text);
        }

        @Override
        public String requestJson() {
            JsonObject o = new JsonObject();
            o.addProperty("method", work.method);
            o.addProperty("url", work.url);
            JsonArray hs = new JsonArray();
            for (KeyValue h : work.headers) {
                if (!h.enabled) continue;
                JsonArray pair = new JsonArray();
                pair.add(h.key);
                pair.add(h.value);
                hs.add(pair);
            }
            o.add("headers", hs);
            JsonObject body = new JsonObject();
            body.addProperty("mode", work.bodyMode);
            body.addProperty("raw", work.bodyRaw);
            o.add("body", body);
            return Json.toJson(o);
        }

        @Override
        public void requestSet(String field, String value) {
            switch (field) {
                case "method":
                    work.method = value.toUpperCase(java.util.Locale.ROOT);
                    break;
                case "url":
                    work.setUrlText(value);
                    break;
                case "body":
                    if (RequestModel.BODY_NONE.equals(work.bodyMode)) work.bodyMode = RequestModel.BODY_RAW;
                    work.bodyRaw = value;
                    break;
                default:
                    break;
            }
        }

        @Override
        public void requestHeader(String op, String name, String value) {
            switch (op) {
                case "add":
                    work.headers.add(new KeyValue(name, value));
                    break;
                case "upsert": {
                    boolean found = false;
                    for (KeyValue h : work.headers) {
                        if (h.key.equalsIgnoreCase(name)) {
                            h.value = value;
                            h.enabled = true;
                            found = true;
                            break;
                        }
                    }
                    if (!found) work.headers.add(new KeyValue(name, value));
                    break;
                }
                case "remove":
                    for (int i = work.headers.size() - 1; i >= 0; i--) {
                        if (work.headers.get(i).key.equalsIgnoreCase(name)) work.headers.remove(i);
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public String responseBody() {
            return response == null ? "" : response.body;
        }

        @Override
        public String cookie(String name) {
            if (response == null) return null;
            HttpUrl url = HttpUrl.parse(response.finalUrl);
            return url == null ? null : engine.cookieJar().value(url, name);
        }

        @Override
        public String sendRequest(String specJson) {
            JsonObject out = new JsonObject();
            try {
                JsonObject spec = Json.parseObject(specJson);
                PreparedRequest p = new PreparedRequest();
                p.method = Json.str(spec, "method", "GET").toUpperCase(java.util.Locale.ROOT);
                p.url = resolver.resolve(Json.str(spec, "url"));
                String lower = p.url.toLowerCase(java.util.Locale.ROOT);
                if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
                    throw new IllegalArgumentException("pm.sendRequest chỉ hỗ trợ http/https");
                }
                JsonArray hs = Json.arr(spec, "headers");
                if (hs != null) {
                    for (JsonElement e : hs) {
                        JsonArray pair = e.getAsJsonArray();
                        p.headers.add(new String[]{resolver.resolve(pair.get(0).getAsString()),
                                resolver.resolve(pair.get(1).getAsString())});
                    }
                }
                JsonObject body = Json.obj(spec, "body");
                if (body != null) {
                    String mode = Json.str(body, "mode");
                    if ("raw".equals(mode)) {
                        p.bodyKind = PreparedRequest.BODY_RAW;
                        p.bodyRaw = resolver.resolve(Json.str(body, "raw"));
                    } else if ("urlencoded".equals(mode) || "formdata".equals(mode)) {
                        p.bodyKind = "urlencoded".equals(mode) ? PreparedRequest.BODY_URLENCODED : PreparedRequest.BODY_FORMDATA;
                        JsonArray fields = Json.arr(body, "fields");
                        if (fields != null) {
                            for (JsonElement e : fields) {
                                JsonArray pair = e.getAsJsonArray();
                                p.form.add(new String[]{resolver.resolve(pair.get(0).getAsString()),
                                        resolver.resolve(pair.get(1).getAsString())});
                            }
                        }
                    }
                }
                ResponseData r = engine.execute(p, in.settings, in.cancel);
                if (r.isError()) {
                    out.addProperty("error", red.mask(r.error));
                } else {
                    out.addProperty("code", r.code);
                    out.addProperty("status", r.message);
                    out.addProperty("time", r.timeMs);
                    out.addProperty("body", r.body);
                    JsonArray rh = new JsonArray();
                    for (String[] h : r.headers) {
                        JsonArray pair = new JsonArray();
                        pair.add(h[0]);
                        pair.add(h[1]);
                        rh.add(pair);
                    }
                    out.add("headers", rh);
                }
            } catch (RuntimeException e) {
                out.addProperty("error", String.valueOf(e.getMessage()));
            }
            return Json.toJson(out);
        }
    }
}
