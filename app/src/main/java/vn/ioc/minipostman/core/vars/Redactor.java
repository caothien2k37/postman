package vn.ioc.minipostman.core.vars;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import vn.ioc.minipostman.core.model.KeyValue;
import vn.ioc.minipostman.core.model.VarScope;

/** Che giá trị nhạy cảm (secret, token, mật khẩu, JWT) trước khi hiển thị trong console/log/history. */
public final class Redactor {

    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]*");
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i).*(token|secret|password|passwd|pwd|apikey|api_key|api-key|authorization|bearer|session).*");

    private final List<String> secrets = new ArrayList<>();

    public static Redactor forScopes(VarScope... scopes) {
        Redactor r = new Redactor();
        for (VarScope s : scopes) {
            if (s == null) continue;
            for (KeyValue kv : s.items()) {
                if (kv.enabled && (kv.isSecret() || SENSITIVE_NAME.matcher(kv.key).matches())) r.add(kv.value);
            }
        }
        return r;
    }

    public void add(String value) {
        if (value != null && value.length() >= 6 && !secrets.contains(value)) secrets.add(value);
    }

    public String mask(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String out = text;
        for (String s : secrets) {
            if (out.contains(s)) out = out.replace(s, "••••••");
        }
        return JWT.matcher(out).replaceAll("eyJ••••••");
    }
}
