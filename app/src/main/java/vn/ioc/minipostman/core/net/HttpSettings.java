package vn.ioc.minipostman.core.net;

/** Cài đặt gửi request dùng chung (đọc từ SharedPreferences ở tầng ứng dụng). */
public final class HttpSettings {

    public int connectTimeoutSec = 15;
    public int readTimeoutSec = 60;
    public boolean followRedirects = true;
    /** Mặc định BẬT; tắt là không an toàn nên giao diện phải cảnh báo. */
    public boolean verifySsl = true;
    /** Cho phép script gọi pm.sendRequest (đi qua cầu nối có kiểm soát). */
    public boolean allowScriptRequests = true;

    public HttpSettings copy() {
        HttpSettings s = new HttpSettings();
        s.connectTimeoutSec = connectTimeoutSec;
        s.readTimeoutSec = readTimeoutSec;
        s.followRedirects = followRedirects;
        s.verifySsl = verifySsl;
        s.allowScriptRequests = allowScriptRequests;
        return s;
    }
}
