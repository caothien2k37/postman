package vn.ioc.minipostman.core.net;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

/** Gửi {@link PreparedRequest} bằng OkHttp (đồng bộ, gọi từ thread nền) và trả {@link ResponseData}. */
public final class HttpEngine {

    /** Giới hạn đọc body để không tràn bộ nhớ. */
    public static final long MAX_BODY_BYTES = 5L * 1024 * 1024;

    /** Cho phép hủy request đang chạy từ thread khác. */
    public static final class CancelToken {
        private volatile Call call;
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
            Call c = call;
            if (c != null) c.cancel();
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    private final MemoryCookieJar cookies = new MemoryCookieJar();
    private final OkHttpClient base = new OkHttpClient.Builder().cookieJar(cookies).build();
    private volatile OkHttpClient insecure;

    public MemoryCookieJar cookieJar() {
        return cookies;
    }

    public ResponseData execute(PreparedRequest p, HttpSettings settings, CancelToken token) {
        HttpUrl parsed = HttpUrl.parse(normalizeUrl(p.url));
        if (parsed == null) return ResponseData.failure("URL không hợp lệ: " + p.url);
        if (!p.extraQuery.isEmpty()) {
            HttpUrl.Builder ub = parsed.newBuilder();
            for (String[] q : p.extraQuery) ub.addQueryParameter(q[0], q[1]);
            parsed = ub.build();
        }

        Request.Builder rb = new Request.Builder().url(parsed);
        try {
            for (String[] h : p.headers) rb.addHeader(h[0], h[1]);
        } catch (IllegalArgumentException e) {
            return ResponseData.failure("Header không hợp lệ: " + e.getMessage());
        }
        if (p.header("User-Agent") == null) rb.header("User-Agent", "MiniPostman/2.0");
        if (p.header("Accept") == null) rb.header("Accept", "*/*");

        String method = p.method.toUpperCase(Locale.ROOT);
        RequestBody body = null;
        if (!method.equals("GET") && !method.equals("HEAD")) {
            body = buildBody(p);
            if (body == null && requiresBody(method)) body = RequestBody.create(new byte[0], null);
        }
        try {
            rb.method(method, body);
        } catch (IllegalArgumentException e) {
            return ResponseData.failure("Request không hợp lệ: " + e.getMessage());
        }

        HttpSettings s = settings != null ? settings : new HttpSettings();
        OkHttpClient.Builder cb = (s.verifySsl ? base : insecureClient()).newBuilder()
                .connectTimeout(s.connectTimeoutSec, TimeUnit.SECONDS)
                .readTimeout(s.readTimeoutSec, TimeUnit.SECONDS)
                .writeTimeout(s.readTimeoutSec, TimeUnit.SECONDS);
        boolean follow = p.followRedirects != null ? p.followRedirects : s.followRedirects;
        cb.followRedirects(follow).followSslRedirects(follow);

        Call call = cb.build().newCall(rb.build());
        if (token != null) {
            token.call = call;
            if (token.cancelled) call.cancel();
        }

        long start = System.nanoTime();
        try (Response r = call.execute()) {
            ResponseData out = new ResponseData();
            out.code = r.code();
            out.message = r.message();
            out.protocol = String.valueOf(r.protocol());
            out.finalUrl = r.request().url().toString();
            for (int i = 0; i < r.headers().size(); i++) {
                out.headers.add(new String[]{r.headers().name(i), r.headers().value(i)});
            }
            for (Cookie c : Cookie.parseAll(r.request().url(), r.headers())) {
                String flags = (c.secure() ? "Secure " : "") + (c.httpOnly() ? "HttpOnly" : "");
                out.cookies.add(new String[]{c.name(), c.value(), c.domain(), c.path(), flags.trim()});
            }

            ResponseBody rbody = r.body();
            byte[] bytes = new byte[0];
            MediaType ct = rbody != null ? rbody.contentType() : null;
            if (rbody != null && !method.equals("HEAD")) {
                BufferedSource src = rbody.source();
                src.request(MAX_BODY_BYTES + 1);
                Buffer buf = src.getBuffer();
                long total = buf.size();
                if (total > MAX_BODY_BYTES) {
                    out.truncated = true;
                    total = MAX_BODY_BYTES;
                }
                bytes = buf.readByteArray(total);
            }
            out.timeMs = (System.nanoTime() - start) / 1_000_000;
            out.size = bytes.length;
            out.contentType = ct != null ? ct.toString() : "";
            if (isTextual(ct, bytes)) {
                Charset cs = ct != null ? ct.charset(StandardCharsets.UTF_8) : StandardCharsets.UTF_8;
                out.body = new String(bytes, cs);
            } else {
                out.binary = true;
            }
            return out;
        } catch (IOException e) {
            if (token != null && token.cancelled) return ResponseData.failure("Đã hủy");
            String msg = e.getMessage();
            return ResponseData.failure(e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg));
        } catch (RuntimeException e) {
            return ResponseData.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static RequestBody buildBody(PreparedRequest p) {
        switch (p.bodyKind) {
            case PreparedRequest.BODY_RAW: {
                String ct = p.header("Content-Type");
                MediaType mt = ct != null ? MediaType.parse(ct) : null;
                return RequestBody.create(p.bodyRaw, mt);
            }
            case PreparedRequest.BODY_URLENCODED: {
                FormBody.Builder fb = new FormBody.Builder();
                for (String[] kv : p.form) fb.add(kv[0], kv[1]);
                return fb.build();
            }
            case PreparedRequest.BODY_FORMDATA: {
                MultipartBody.Builder mb = new MultipartBody.Builder().setType(MultipartBody.FORM);
                for (String[] kv : p.form) mb.addFormDataPart(kv[0], kv[1]);
                if (p.form.isEmpty()) mb.addFormDataPart("", "");
                return mb.build();
            }
            default:
                return null;
        }
    }

    private static boolean requiresBody(String method) {
        return method.equals("POST") || method.equals("PUT") || method.equals("PATCH")
                || method.equals("PROPPATCH") || method.equals("REPORT");
    }

    /** Thiếu scheme thì mặc định http:// (như Postman). */
    static String normalizeUrl(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return u;
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return u;
        if (u.contains("://")) return u;
        return "http://" + u;
    }

    private static boolean isTextual(MediaType ct, byte[] bytes) {
        if (ct != null) {
            String type = ct.type().toLowerCase(Locale.ROOT);
            String sub = ct.subtype().toLowerCase(Locale.ROOT);
            if (type.equals("text")) return true;
            if (type.equals("image") || type.equals("audio") || type.equals("video")) return false;
            if (sub.contains("json") || sub.contains("xml") || sub.contains("javascript") || sub.contains("html")
                    || sub.contains("x-www-form-urlencoded") || sub.contains("yaml") || sub.contains("graphql")
                    || sub.contains("csv")) {
                return true;
            }
            if (sub.contains("octet-stream") || sub.contains("pdf") || sub.contains("zip") || sub.contains("gzip")) {
                return false;
            }
        }
        int n = Math.min(bytes.length, 1000);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) return false;
        }
        return true;
    }

    private OkHttpClient insecureClient() {
        OkHttpClient c = insecure;
        if (c != null) return c;
        synchronized (this) {
            if (insecure == null) {
                try {
                    final X509TrustManager trustAll = new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    };
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, new TrustManager[]{trustAll}, new SecureRandom());
                    insecure = base.newBuilder()
                            .sslSocketFactory(ctx.getSocketFactory(), trustAll)
                            .hostnameVerifier((host, session) -> true)
                            .build();
                } catch (Exception e) {
                    throw new IllegalStateException("Không tạo được client bỏ qua SSL", e);
                }
            }
            return insecure;
        }
    }

    /** Tiện ích: danh sách header dạng text "Name: value" (dùng cho hiển thị). */
    public static String headersText(List<String[]> headers) {
        StringBuilder sb = new StringBuilder();
        for (String[] h : headers) sb.append(h[0]).append(": ").append(h[1]).append('\n');
        return sb.toString();
    }
}
