package vn.ioc.minipostman.core.net;

import java.util.ArrayList;
import java.util.List;

/** Request đã thay hết biến, sẵn sàng gửi. */
public final class PreparedRequest {

    public static final String BODY_NONE = "none";
    public static final String BODY_RAW = "raw";
    public static final String BODY_URLENCODED = "urlencoded";
    public static final String BODY_FORMDATA = "formdata";

    public String method = "GET";
    public String url = "";
    public final List<String[]> headers = new ArrayList<>();
    /** Param thêm vào URL (API key dạng query, OAuth token dạng query). */
    public final List<String[]> extraQuery = new ArrayList<>();
    public String bodyKind = BODY_NONE;
    public String bodyRaw = "";
    public final List<String[]> form = new ArrayList<>();
    public Boolean followRedirects;
    public final List<String> warnings = new ArrayList<>();

    public String header(String name) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) return h[1];
        }
        return null;
    }

    public void putHeaderIfAbsent(String name, String value) {
        if (header(name) == null) headers.add(new String[]{name, value});
    }

    public void removeHeader(String name) {
        for (int i = headers.size() - 1; i >= 0; i--) {
            if (headers.get(i)[0].equalsIgnoreCase(name)) headers.remove(i);
        }
    }
}
