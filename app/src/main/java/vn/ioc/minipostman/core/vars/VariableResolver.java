package vn.ioc.minipostman.core.vars;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vn.ioc.minipostman.core.model.VarScope;

/**
 * Thay {{biến}} theo thứ tự ưu tiên của Postman: Local > Data > Environment > Collection > Global.
 * Biến bị tắt không dùng để thay thế. Hỗ trợ biến lồng biến và biến động {{$timestamp}}, {{$guid}}...
 */
public final class VariableResolver {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*\\}\\}");
    private static final int MAX_PASSES = 10;
    private static final Random RANDOM = new Random();

    private final List<VarScope> chain;

    /** @param highestFirst các scope theo thứ tự ưu tiên giảm dần; phần tử null bị bỏ qua. */
    public VariableResolver(VarScope... highestFirst) {
        List<VarScope> l = new java.util.ArrayList<>();
        for (VarScope s : highestFirst) {
            if (s != null) l.add(s);
        }
        this.chain = l;
    }

    public String get(String key) {
        String dyn = dynamic(key);
        if (dyn != null) return dyn;
        for (VarScope s : chain) {
            String v = s.get(key);
            if (v != null) return v;
        }
        return null;
    }

    public String resolve(String text) {
        if (text == null || text.isEmpty()) return "";
        String cur = text;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            Matcher m = VAR.matcher(cur);
            StringBuffer sb = new StringBuffer();
            boolean changed = false;
            while (m.find()) {
                String val = get(m.group(1));
                if (val != null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(val));
                    changed = true;
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                }
            }
            m.appendTail(sb);
            cur = sb.toString();
            if (!changed) break;
        }
        return cur;
    }

    /** Các {{tên}} còn sót lại trong chuỗi ĐÃ resolve (tức biến chưa được định nghĩa). */
    public static Set<String> unresolved(String resolvedText) {
        Set<String> out = new LinkedHashSet<>();
        if (resolvedText == null) return out;
        Matcher m = VAR.matcher(resolvedText);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Tất cả biến đang có hiệu lực (scope ưu tiên cao ghi đè scope thấp). */
    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) m.putAll(chain.get(i).toMap());
        return m;
    }

    static String dynamic(String key) {
        if (key.isEmpty() || key.charAt(0) != '$') return null;
        switch (key) {
            case "$timestamp":
                return String.valueOf(System.currentTimeMillis() / 1000);
            case "$isoTimestamp": {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                return f.format(new Date());
            }
            case "$guid":
            case "$randomUUID":
                return UUID.randomUUID().toString();
            case "$randomInt":
                return String.valueOf(RANDOM.nextInt(1001));
            default:
                return null;
        }
    }
}
