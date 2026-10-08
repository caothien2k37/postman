package vn.ioc.minipostman.core.script;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import vn.ioc.minipostman.core.Base64Util;
import vn.ioc.minipostman.core.vars.Redactor;

/**
 * Sandbox chạy script Postman (pm.*) bằng Rhino ở chế độ interpreter:
 * - không có Packages/java.* (initSafeStandardObjects + ClassShutter từ chối mọi lớp Java);
 * - giới hạn thời gian thực thi qua bộ đếm lệnh (Error nên script không bắt được) và độ sâu đệ quy;
 * - mọi truy cập biến/mạng đi qua {@link Host}, không có filesystem/Android API;
 * - console và thông báo lỗi được che token/secret qua {@link Redactor}.
 */
public final class JsSandbox {

    public static final int MAX_SCRIPT_CHARS = 512 * 1024;
    private static final int MAX_CONSOLE_LINES = 500;
    private static final int MAX_LINE_CHARS = 20_000;
    private static final int MAX_SEND_REQUESTS = 10;
    private static final Object DEADLINE = new Object();

    public static final class Script {
        public final String label;
        public final String source;

        public Script(String label, String source) {
            this.label = label;
            this.source = source;
        }
    }

    public static final class TestResult {
        public final String name;
        public final boolean passed;
        public final String message;

        public TestResult(String name, boolean passed, String message) {
            this.name = name;
            this.passed = passed;
            this.message = message;
        }
    }

    /** Cầu nối có kiểm soát từ JavaScript sang trạng thái Java. */
    public interface Host {
        String contextJson();

        String getVar(String scope, String key);

        boolean hasVar(String scope, String key);

        void setVar(String scope, String key, String value);

        void unsetVar(String scope, String key);

        void clearVars(String scope);

        String allVars(String scope);

        String replaceIn(String text);

        String requestJson();

        void requestSet(String field, String value);

        void requestHeader(String op, String name, String value);

        String responseBody();

        String cookie(String name);

        /** Nhận spec JSON, trả JSON {code,status,time,headers,body} hoặc {error}. */
        String sendRequest(String specJson);
    }

    public static final class Job {
        public final String phase;
        public final List<Script> scripts = new ArrayList<>();
        public final Host host;
        public Redactor redactor = new Redactor();
        public long timeoutMs = 5000;
        public boolean allowSendRequest = true;

        public Job(String phase, Host host) {
            this.phase = phase;
            this.host = host;
        }
    }

    public static final class Outcome {
        public final List<TestResult> tests = new ArrayList<>();
        public final List<String> console = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public boolean nextRequestSet;
        public String nextRequest;
    }

    private interface Fn {
        Object call(Object[] args);
    }

    /** Dừng script chạy quá thời gian. Là Error để mã JS không thể try/catch rồi chạy tiếp. */
    private static final class ScriptTimeout extends Error {
        ScriptTimeout() {
            super("timeout", null, false, false);
        }
    }

    private static final class SafeFactory extends ContextFactory {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            cx.setMaximumInterpreterStackDepth(400);
            cx.setInstructionObserverThreshold(10_000);
            cx.setClassShutter(new ClassShutter() {
                @Override
                public boolean visibleToScripts(String fullClassName) {
                    return false;
                }
            });
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Object dl = cx.getThreadLocal(DEADLINE);
            if (dl instanceof Long && System.nanoTime() > (Long) dl) throw new ScriptTimeout();
        }
    }

    private final SafeFactory factory = new SafeFactory();
    private final String preludeSource;
    private ScriptableObject shared;
    private org.mozilla.javascript.Script prelude;

    public JsSandbox() {
        this(loadPrelude());
    }

    public JsSandbox(String preludeSource) {
        this.preludeSource = preludeSource;
    }

    private static String loadPrelude() {
        ClassLoader cl = JsSandbox.class.getClassLoader();
        InputStream in = cl != null ? cl.getResourceAsStream("pm_prelude.js") : null;
        if (in == null) in = JsSandbox.class.getResourceAsStream("/pm_prelude.js");
        if (in == null) throw new IllegalStateException("Thiếu tài nguyên pm_prelude.js");
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được pm_prelude.js", e);
        }
    }

    private synchronized void ensureShared(Context cx) {
        if (shared == null) {
            shared = cx.initSafeStandardObjects(null, true);
            prelude = cx.compileString(preludeSource, "pm_prelude.js", 1, null);
        }
    }

    public Outcome run(Job job) {
        Outcome out = new Outcome();
        for (Script s : job.scripts) {
            if (s.source.length() > MAX_SCRIPT_CHARS) {
                out.errors.add(s.label + ": script quá lớn (> " + (MAX_SCRIPT_CHARS / 1024) + " KB)");
                return out;
            }
        }
        Context cx = factory.enterContext();
        try {
            ensureShared(cx);
            cx.putThreadLocal(DEADLINE, System.nanoTime() + job.timeoutMs * 1_000_000L);

            Scriptable scope = newScope(cx, shared);
            int[] sendCount = {0};
            installHost(cx, scope, job, out, sendCount);
            prelude.exec(cx, scope);

            for (Script s : job.scripts) {
                Scriptable child = newScope(cx, scope);
                try {
                    cx.evaluateString(child, s.source, s.label, 1, null);
                } catch (RhinoException e) {
                    out.errors.add(describe(s.label, e, job.redactor));
                    break;
                }
            }
            if (out.errors.isEmpty()) {
                try {
                    Object flush = ScriptableObject.getProperty(scope, "__flush");
                    if (flush instanceof org.mozilla.javascript.Function) {
                        ((org.mozilla.javascript.Function) flush).call(cx, scope, scope, new Object[0]);
                    }
                    collectLegacyTests(scope, out);
                } catch (RhinoException e) {
                    out.errors.add(describe("script", e, job.redactor));
                }
            }
        } catch (ScriptTimeout e) {
            out.errors.add("Script chạy quá " + (job.timeoutMs / 1000.0) + " giây nên đã bị dừng.");
        } catch (StackOverflowError e) {
            out.errors.add("Script đệ quy quá sâu nên đã bị dừng.");
        } catch (RhinoException e) {
            out.errors.add(describe("script", e, job.redactor));
        } finally {
            Context.exit();
        }
        return out;
    }

    private static Scriptable newScope(Context cx, Scriptable parentOrShared) {
        Scriptable s = cx.newObject(parentOrShared);
        s.setPrototype(parentOrShared);
        s.setParentScope(null);
        return s;
    }

    private static void collectLegacyTests(Scriptable scope, Outcome out) {
        Object t = ScriptableObject.getProperty(scope, "tests");
        if (!(t instanceof Scriptable)) return;
        Scriptable tests = (Scriptable) t;
        for (Object id : tests.getIds()) {
            String name = String.valueOf(id);
            Object v = tests.get(name, tests);
            out.tests.add(new TestResult(name, Context.toBoolean(v), ""));
        }
    }

    private static String describe(String label, RhinoException e, Redactor red) {
        String text;
        if (e instanceof JavaScriptException) {
            Object v = ((JavaScriptException) e).getValue();
            if (v instanceof Scriptable) {
                Object n = ScriptableObject.getProperty((Scriptable) v, "name");
                Object m = ScriptableObject.getProperty((Scriptable) v, "message");
                text = (n instanceof String ? n + ": " : "") + (m == Scriptable.NOT_FOUND ? Context.toString(v) : Context.toString(m));
            } else {
                text = Context.toString(v);
            }
        } else if (e instanceof EcmaError) {
            text = ((EcmaError) e).getName() + ": " + ((EcmaError) e).getErrorMessage();
        } else {
            text = e.details();
        }
        String where = e.sourceName() != null && e.lineNumber() > 0 ? " (dòng " + e.lineNumber() + ")" : "";
        return red.mask(label + ": " + text + where);
    }

    // ---------------------------------------------------------------- cầu nối host

    private void installHost(Context cx, Scriptable scope, final Job job, final Outcome out, final int[] sendCount) {
        final Host host = job.host;
        Scriptable h = cx.newObject(scope);
        def(h, scope, "context", a -> host.contextJson());
        def(h, scope, "log", a -> {
            if (out.console.size() < MAX_CONSOLE_LINES) {
                String line = "[" + str(a, 0) + "] " + str(a, 1);
                if (line.length() > MAX_LINE_CHARS) line = line.substring(0, MAX_LINE_CHARS) + "…";
                out.console.add(job.redactor.mask(line));
            }
            return Undefined.instance;
        });
        def(h, scope, "getVar", a -> host.getVar(str(a, 0), str(a, 1)));
        def(h, scope, "hasVar", a -> host.hasVar(str(a, 0), str(a, 1)));
        def(h, scope, "setVar", a -> {
            host.setVar(str(a, 0), str(a, 1), str(a, 2));
            return Undefined.instance;
        });
        def(h, scope, "unsetVar", a -> {
            host.unsetVar(str(a, 0), str(a, 1));
            return Undefined.instance;
        });
        def(h, scope, "clearVars", a -> {
            host.clearVars(str(a, 0));
            return Undefined.instance;
        });
        def(h, scope, "allVars", a -> host.allVars(str(a, 0)));
        def(h, scope, "replaceIn", a -> host.replaceIn(str(a, 0)));
        def(h, scope, "request", a -> host.requestJson());
        def(h, scope, "requestSet", a -> {
            host.requestSet(str(a, 0), str(a, 1));
            return Undefined.instance;
        });
        def(h, scope, "requestHeader", a -> {
            host.requestHeader(str(a, 0), str(a, 1), str(a, 2));
            return Undefined.instance;
        });
        def(h, scope, "responseBody", a -> host.responseBody());
        def(h, scope, "cookie", a -> host.cookie(str(a, 0)));
        def(h, scope, "testResult", a -> {
            out.tests.add(new TestResult(str(a, 0), a.length > 1 && Context.toBoolean(a[1]), job.redactor.mask(str(a, 2))));
            return Undefined.instance;
        });
        def(h, scope, "setNextRequest", a -> {
            out.nextRequestSet = true;
            out.nextRequest = (a.length == 0 || a[0] == null || a[0] == Undefined.instance) ? null : str(a, 0);
            return Undefined.instance;
        });
        def(h, scope, "sendRequest", a -> {
            if (!job.allowSendRequest) {
                return "{\"error\":\"pm.sendRequest đã bị tắt trong Cài đặt\"}";
            }
            if (++sendCount[0] > MAX_SEND_REQUESTS) {
                return "{\"error\":\"Vượt quá " + MAX_SEND_REQUESTS + " lần pm.sendRequest trong một script\"}";
            }
            return host.sendRequest(str(a, 0));
        });
        def(h, scope, "btoa", a -> Base64Util.encode(str(a, 0).getBytes(StandardCharsets.ISO_8859_1)));
        def(h, scope, "atob", a -> {
            try {
                return new String(Base64Util.decode(str(a, 0)), StandardCharsets.ISO_8859_1);
            } catch (IllegalArgumentException e) {
                throw Context.reportRuntimeError("atob: chuỗi base64 không hợp lệ");
            }
        });
        ScriptableObject.putProperty(scope, "__host", h);
    }

    private static void def(Scriptable target, Scriptable scope, String name, final Fn fn) {
        BaseFunction f = new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable sc, Scriptable thisObj, Object[] args) {
                try {
                    return fn.call(args);
                } catch (RhinoException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw Context.reportRuntimeError(name + ": " + e.getMessage());
                }
            }

            @Override
            public String getFunctionName() {
                return name;
            }
        };
        org.mozilla.javascript.ScriptRuntime.setFunctionProtoAndParent(f, scope);
        ScriptableObject.putProperty(target, name, f);
    }

    private static String str(Object[] a, int i) {
        if (i >= a.length || a[i] == null || a[i] == Undefined.instance) return "";
        return Context.toString(a[i]);
    }
}
