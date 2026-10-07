package vn.ioc.minipostman;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class RequestActivity extends Activity {

    private static final String[] METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"};
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private Store store;
    private Store.Req req;

    private EditText nameInput;
    private EditText urlInput;
    private EditText headersInput;
    private EditText bodyInput;
    private EditText extractInput;
    private Spinner methodSpinner;
    private TextView statusText;
    private TextView responseText;
    private Button sendButton;
    private ScrollView scroll;
    private String lastResponse = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_request);
        store = Store.get(this);
        req = store.findReq(getIntent().getStringExtra("id"));
        if (req == null) {
            finish();
            return;
        }

        scroll = findViewById(R.id.scroll);
        nameInput = findViewById(R.id.reqName);
        urlInput = findViewById(R.id.url);
        headersInput = findViewById(R.id.headers);
        bodyInput = findViewById(R.id.body);
        extractInput = findViewById(R.id.extract);
        methodSpinner = findViewById(R.id.methodSpinner);
        statusText = findViewById(R.id.status);
        responseText = findViewById(R.id.response);
        sendButton = findViewById(R.id.btnSend);

        ArrayAdapter<String> ma = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, METHODS);
        ma.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        methodSpinner.setAdapter(ma);
        int idx = Arrays.asList(METHODS).indexOf(req.method);
        methodSpinner.setSelection(Math.max(idx, 0));

        nameInput.setText(req.name);
        urlInput.setText(req.url);
        headersInput.setText(req.headers);
        bodyInput.setText(req.body);
        extractInput.setText(req.extract);
        setTitle(req.name);
        ((TextView) findViewById(R.id.envLabel)).setText("Môi trường: " + store.activeEnvObj().name);

        sendButton.setOnClickListener(v -> sendRequest());
        findViewById(R.id.btnCopy).setOnClickListener(v -> copy(lastResponse));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (req != null) saveForm();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }

    private void saveForm() {
        req.name = nameInput.getText().toString();
        req.method = METHODS[methodSpinner.getSelectedItemPosition()];
        req.url = urlInput.getText().toString();
        req.headers = headersInput.getText().toString();
        req.body = bodyInput.getText().toString();
        req.extract = extractInput.getText().toString();
        store.save();
    }

    private void sendRequest() {
        saveForm();
        final String method = req.method;
        final String url = store.resolve(req.url.trim());
        final String headers = store.resolve(req.headers);
        final String body = store.resolve(req.body);

        Set<String> missing = new LinkedHashSet<>();
        missing.addAll(store.unresolved(url));
        missing.addAll(store.unresolved(headers));
        missing.addAll(store.unresolved(body));
        if (!missing.isEmpty()) {
            showResult("Biến chưa có trong môi trường \"" + store.activeEnvObj().name + "\": "
                    + TextUtils.join(", ", missing), "");
            return;
        }

        final Request request;
        try {
            request = build(method, url, headers, body);
        } catch (IllegalArgumentException e) {
            showResult("URL hoặc header không hợp lệ: " + e.getMessage(), "URL sau khi thay biến:\n" + url);
            return;
        }

        sendButton.setEnabled(false);
        statusText.setText("Đang gửi…");
        responseText.setText("");
        final long start = System.currentTimeMillis();

        exec.execute(() -> {
            String status;
            String text;
            Object json = null;
            try (Response resp = CLIENT.newCall(request).execute()) {
                long ms = System.currentTimeMillis() - start;
                ResponseBody rb = resp.body();
                text = rb != null ? rb.string() : "";
                status = "Status: " + resp.code() + " • " + ms + " ms";
                json = JsonPath.parse(text);
                if (json != null) text = JsonPath.pretty(json);
            } catch (IOException e) {
                status = "Lỗi kết nối: " + e.getClass().getSimpleName();
                text = e.getMessage() + "\n\nURL: " + url;
            }
            final String fStatus = status;
            final String fText = text;
            final Object fJson = json;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                sendButton.setEnabled(true);
                String info = fJson != null ? applyExtract(fJson) : "";
                showResult(info.isEmpty() ? fStatus : fStatus + "\n" + info, fText);
            });
        });
    }

    private void showResult(String status, String text) {
        statusText.setText(status);
        responseText.setText(text);
        lastResponse = text;
        scroll.post(() -> scroll.smoothScrollTo(0, statusText.getTop()));
    }

    private static Request build(String method, String url, String headers, String body) {
        Request.Builder rb = new Request.Builder().url(url);
        String contentType = null;
        for (String line : headers.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("//") || l.startsWith("#")) continue;
            int c = l.indexOf(':');
            if (c <= 0) continue;
            String key = l.substring(0, c).trim();
            String value = l.substring(c + 1).trim();
            if (key.equalsIgnoreCase("Content-Type")) contentType = value;
            rb.addHeader(key, value);
        }
        RequestBody reqBody = null;
        if (!method.equals("GET") && !method.equals("HEAD")) {
            MediaType mt = contentType != null
                    ? MediaType.parse(contentType)
                    : (body.isEmpty() ? null : MediaType.parse("application/json; charset=utf-8"));
            reqBody = RequestBody.create(body, mt);
        }
        rb.method(method, reqBody);
        return rb.build();
    }

    /** Mỗi dòng "tenBien = duong.dan.json": lấy giá trị từ response rồi lưu vào môi trường đang chọn. */
    private String applyExtract(Object json) {
        List<String> set = new ArrayList<>();
        for (String line : req.extract.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("//") || l.startsWith("#")) continue;
            int eq = l.indexOf('=');
            if (eq <= 0) continue;
            String var = l.substring(0, eq).trim();
            Object value = JsonPath.eval(json, l.substring(eq + 1));
            if (value == null || var.isEmpty()) continue;
            store.activeEnvObj().vars.put(var, String.valueOf(value));
            set.add(var);
        }
        if (set.isEmpty()) return "";
        store.save();
        return "Đã set biến: " + TextUtils.join(", ", set);
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("response", text));
            Toast.makeText(this, "Đã copy response", Toast.LENGTH_SHORT).show();
        }
    }
}
