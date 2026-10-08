package vn.ioc.minipostman.core.net;

import java.util.ArrayList;
import java.util.List;

/** Kết quả một lần gọi HTTP (hoặc lỗi kết nối nếu {@link #error} != null). */
public final class ResponseData {

    public int code;
    public String message = "";
    public String protocol = "";
    public final List<String[]> headers = new ArrayList<>();
    /** name, value, domain, path, flags */
    public final List<String[]> cookies = new ArrayList<>();
    public String body = "";
    public boolean binary;
    public boolean truncated;
    public long timeMs;
    public long size;
    public String contentType = "";
    public String finalUrl = "";
    public String error;

    public static ResponseData failure(String error) {
        ResponseData r = new ResponseData();
        r.error = error;
        return r;
    }

    public boolean isError() {
        return error != null;
    }

    public String header(String name) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) return h[1];
        }
        return null;
    }

    public String statusLine() {
        return code + (message.isEmpty() ? "" : " " + message);
    }
}
