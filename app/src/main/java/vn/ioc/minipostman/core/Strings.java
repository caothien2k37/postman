package vn.ioc.minipostman.core;

import java.util.Iterator;

/** Tiện ích chuỗi tự viết để không phụ thuộc API Java mới (minSdk 24). */
public final class Strings {

    private Strings() {
    }

    public static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String nz(String s) {
        return s == null ? "" : s;
    }

    public static String join(String sep, Iterable<?> items) {
        StringBuilder sb = new StringBuilder();
        Iterator<?> it = items.iterator();
        while (it.hasNext()) {
            sb.append(String.valueOf(it.next()));
            if (it.hasNext()) sb.append(sep);
        }
        return sb.toString();
    }

    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s == null ? "" : s;
        return s.substring(0, max);
    }

    /** Tách text thành các dòng (giữ dòng cuối rỗng), chấp nhận \r\n. */
    public static String[] lines(String text) {
        return nz(text).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    public static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(Math.max(n, 0));
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
