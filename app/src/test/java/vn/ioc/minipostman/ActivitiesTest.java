package vn.ioc.minipostman;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.fakes.RoboMenuItem;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.List;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import vn.ioc.minipostman.core.Fixtures;
import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.importexport.ImportResult;
import vn.ioc.minipostman.core.model.EnvDoc;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.data.CollectionRepository;
import vn.ioc.minipostman.data.CollectionRepository.TreeItem;
import vn.ioc.minipostman.data.HistoryRepository;
import vn.ioc.minipostman.ui.KeyValueEditor;
import vn.ioc.minipostman.ui.TreeRow;

/** Mở thật từng màn hình (Robolectric) và chạy các luồng chính của đặc tả qua giao diện. */
public class ActivitiesTest extends AppTestBase {

    private MockWebServer server;
    private final List<RecordedRequest> seen = new ArrayList<>();

    @Before
    public void startServer() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                synchronized (seen) {
                    seen.add(r);
                }
                if (r.getPath().startsWith("/auth/login")) {
                    return new MockResponse().addHeader("Content-Type", "application/json")
                            .setBody("{\"success\":true,\"data\":{\"accessToken\":\"tok-abcdef123456\","
                                    + "\"refreshToken\":\"refresh_123456\",\"userId\":12345}}");
                }
                return new MockResponse().addHeader("Content-Type", "application/json").setBody("{\"docs\":[]}");
            }
        });
        server.start();
    }

    @After
    public void stopServer() throws Exception {
        server.shutdown();
    }

    private String baseUrl() {
        String u = server.url("/").toString();
        return u.substring(0, u.length() - 1);
    }

    private String seedRichCollection() {
        ImportResult r = Fixtures.parse(Fixtures.resource("rich_collection.json"));
        return store().collections.importCollection(r.collection, CollectionRepository.ConflictMode.COPY);
    }

    private TreeItem item(String name) {
        for (TreeItem t : store().collections.loadTree()) {
            if (t.name.equals(name)) return t;
        }
        throw new AssertionError("Không có " + name);
    }

    private void useServerEnv() {
        EnvDoc env = store().envs.getSelected();
        env.vars.set("base_url", baseUrl());
        env.vars.set("username", "demo");
        env.vars.set("password", "pw");
        store().envs.save(env);
    }

    private static int rowCount(RecyclerView rv) {
        return rv.getAdapter() == null ? 0 : rv.getAdapter().getItemCount();
    }

    // ---------------------------------------------------------------- màn hình chính

    @Test
    public void mainShowsTreeExpandsAndFilters() {
        seedRichCollection();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        RecyclerView rv = a.findViewById(R.id.tree);
        rv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        rv.layout(0, 0, 1080, 1920);

        // collection + 4 phần tử cấp 1 (Authentication, Document Management, Reports, Legacy kebab item)
        waitFor("cây hiện ra", () -> rowCount(rv) == 5);

        // mở folder Authentication -> thêm Login và Get Current User
        TreeAdapterAccess access = new TreeAdapterAccess(rv);
        access.click("Authentication", a);
        waitFor("folder mở ra", () -> rowCount(rv) == 7);

        // tìm kiếm "login" -> collection > Authentication > Login
        EditText search = a.findViewById(R.id.search);
        search.setText("login");
        waitFor("lọc theo tên", () -> rowCount(rv) == 3);

        // lọc method DELETE (xóa ô tìm kiếm) -> collection > Document Management > Outgoing > Delete Document
        search.setText("");
        Spinner method = a.findViewById(R.id.methodFilter);
        method.setSelection(5); // "DELETE"
        waitFor("lọc theo method", () -> rowCount(rv) == 4);

        // spinner môi trường có "Default" (tự tạo như bản cũ)
        Spinner env = a.findViewById(R.id.envSpinner);
        waitFor("spinner môi trường", () -> env.getAdapter() != null && env.getAdapter().getCount() == 1);
        assertEquals("Default", env.getAdapter().getItem(0));
    }

    private static final class TreeAdapterAccess {
        private final RecyclerView rv;

        TreeAdapterAccess(RecyclerView rv) {
            this.rv = rv;
        }

        void click(String name, MainActivity a) {
            vn.ioc.minipostman.ui.TreeAdapter adapter = (vn.ioc.minipostman.ui.TreeAdapter) rv.getAdapter();
            for (TreeRow r : adapter.getCurrentList()) {
                if (r.name.equals(name)) {
                    a.onClick(r);
                    return;
                }
            }
            throw new AssertionError("Không có dòng " + name);
        }
    }

    // ---------------------------------------------------------------- Request: Login -> token -> lưu

    @Test
    public void sendLoginFromScreenSavesTokenAndRecordsHistory() {
        seedRichCollection();
        useServerEnv();
        Intent i = new Intent(app, RequestActivity.class).putExtra(RequestActivity.EXTRA_NODE_ID, item("Login").id);
        RequestActivity a = Robolectric.buildActivity(RequestActivity.class, i).setup().get();

        EditText url = a.findViewById(R.id.url);
        waitFor("form được nạp", () -> url.getText().toString().equals("{{base_url}}/auth/login?debug=1"));
        assertEquals("Login", ((EditText) a.findViewById(R.id.reqName)).getText().toString());
        assertEquals("POST", ((Spinner) a.findViewById(R.id.methodSpinner)).getSelectedItem());

        a.findViewById(R.id.btnSend).performClick();
        TextView status = a.findViewById(R.id.status);
        waitFor("response hiện ra", () -> status.getText().toString().startsWith("200"));
        assertTrue(status.getText().toString().contains("ms"));

        // pm.environment.set trong script Post-response đã lưu token vào environment đang chọn (bền vững qua DB)
        waitFor("token được lưu", () -> "tok-abcdef123456".equals(store().envs.getSelected().vars.get("access_token_v1")));
        assertEquals("refresh_123456", store().envs.getGlobals().vars.get("refresh_token"));
        assertEquals("12345", store().collections.loadCollectionHeader(item("Login").collectionId).vars().get("user_id"));

        // server nhận đúng request
        RecordedRequest r = seen.get(0);
        assertEquals("POST", r.getMethod());
        assertEquals("/auth/login?debug=1", r.getPath());
        assertTrue(r.getBody().readUtf8().contains("\"username\": \"demo\""));

        // history
        waitFor("history", () -> store().history.list().size() == 1);
        HistoryRepository.Entry h = store().history.list().get(0);
        assertEquals("Login", h.name);
        assertEquals(200, h.status);
        assertFalse(h.requestJson.contains("tok-abcdef123456"));       // chỉ lưu bản còn {{biến}}, không lộ token

        // tab Console không lộ token
        com.google.android.material.tabs.TabLayout tabs = a.findViewById(R.id.responseTabs);
        tabs.getTabAt(4).select();
        shadowOf(android.os.Looper.getMainLooper()).idle();
        String console = ((TextView) a.findViewById(R.id.response)).getText().toString();
        assertFalse(console, console.contains("tok-abcdef123456"));
    }

    @Test
    public void editingMarksDirtyAndSaveWritesBackWithoutLosingUnknownFields() {
        seedRichCollection();
        Intent i = new Intent(app, RequestActivity.class).putExtra(RequestActivity.EXTRA_NODE_ID, item("Login").id);
        ActivityController<RequestActivity> c = Robolectric.buildActivity(RequestActivity.class, i).setup();
        RequestActivity a = c.get();
        EditText url = a.findViewById(R.id.url);
        waitFor("form được nạp", () -> url.getText().length() > 0);
        androidx.appcompat.widget.Toolbar toolbar = a.findViewById(R.id.toolbar);
        assertFalse(String.valueOf(toolbar.getTitle()).contains("●"));

        // Sửa URL -> bảng Params đồng bộ, tiêu đề có dấu ● (chưa lưu)
        url.setText("{{base_url}}/auth/login?debug=2&extra=7");
        assertTrue(String.valueOf(toolbar.getTitle()).contains("●"));
        KeyValueEditor params = a.findViewById(R.id.paramsEditor);
        assertEquals(3, params.getItems().size());                      // debug, extra, skip (đang tắt)

        a.onOptionsItemSelected(new RoboMenuItem(1));                   // Lưu
        waitFor("đã lưu", () -> store().collections.loadNode(item("Login").id).raw
                .getAsJsonObject("request").getAsJsonObject("url").get("raw").getAsString().contains("extra=7"));
        assertFalse(String.valueOf(toolbar.getTitle()).contains("●"));

        JsonObject saved = store().collections.loadNode(item("Login").id).raw;
        assertTrue(saved.getAsJsonObject("request").has("x-request-extension"));
        assertEquals("Kiểu nội dung", saved.getAsJsonObject("request").getAsJsonArray("header").get(0)
                .getAsJsonObject().get("description").getAsString());
        assertEquals(2, saved.getAsJsonArray("event").size());
    }

    @Test
    public void unresolvedVariableIsReportedInConsoleInsteadOfSending() {
        seedRichCollection();           // base_url có ở phạm vi collection; username/password chưa có ở đâu cả
        Intent i = new Intent(app, RequestActivity.class).putExtra(RequestActivity.EXTRA_NODE_ID, item("Login").id);
        RequestActivity a = Robolectric.buildActivity(RequestActivity.class, i).setup().get();
        EditText url = a.findViewById(R.id.url);
        waitFor("form được nạp", () -> url.getText().length() > 0);

        a.findViewById(R.id.btnSend).performClick();
        TextView response = a.findViewById(R.id.response);
        waitFor("báo lỗi biến", () -> response.getText().toString().contains("Biến chưa có"));
        assertEquals(0, server.getRequestCount());
        String text = response.getText().toString();
        assertTrue(text, text.contains("username") && text.contains("password"));
        assertFalse(text, text.contains("base_url"));          // đã được định nghĩa ở phạm vi collection
    }

    // ---------------------------------------------------------------- biến

    @Test
    public void variablesScreenAddsRowAndSavesOnPause() {
        ActivityController<EnvActivity> c = Robolectric.buildActivity(EnvActivity.class).setup();
        EnvActivity a = c.get();
        KeyValueEditor editor = a.findViewById(R.id.varsEditor);
        waitFor("màn hình biến nạp xong", () -> editor.getChildCount() > 0 && a.findViewById(R.id.scopeSpinner) != null
                && ((Spinner) a.findViewById(R.id.scopeSpinner)).getAdapter() != null);

        // bấm "+ Thêm dòng" (nút cuối của editor) rồi gõ key/value
        View add = editor.getChildAt(editor.getChildCount() - 1);
        add.performClick();
        ViewGroup rows = (ViewGroup) editor.getChildAt(0);
        assertEquals(1, rows.getChildCount());
        ((EditText) rows.getChildAt(0).findViewById(R.id.key)).setText("access_token_v1");
        ((EditText) rows.getChildAt(0).findViewById(R.id.value)).setText("abc");

        c.pause();
        waitFor("đã lưu biến", () -> "abc".equals(store().envs.getSelected().vars.get("access_token_v1")));
    }

    // ---------------------------------------------------------------- import

    @Test
    public void importScreenPreviewsThenCommitsPastedCollection() {
        ActivityController<ImportActivity> c = Robolectric.buildActivity(ImportActivity.class).setup();
        ImportActivity a = c.get();
        a.findViewById(R.id.btnPaste).performClick();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        EditText input = findEditText(dialog.getWindow().getDecorView());
        input.setText(Json.toJson(Fixtures.collection("Pasted", Fixtures.nestedFolders(3))));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();

        View importBtn = a.findViewById(R.id.btnImport);
        waitFor("xem trước", () -> importBtn.isEnabled());
        ViewGroup preview = a.findViewById(R.id.preview);
        assertTrue(allText(preview).contains("Pasted"));
        assertTrue(allText(preview).contains("Postman Collection v2.1"));
        assertTrue(allText(preview).contains("3"));                       // 3 folders

        importBtn.performClick();
        waitFor("đã import", () -> store().collections.findCollectionByName("Pasted") != null);
        String id = store().collections.findCollectionByName("Pasted");
        assertEquals(6, store().collections.countRequests(id));
    }

    @Test
    public void importScreenShowsFriendlyErrorForBadText() {
        ImportActivity a = Robolectric.buildActivity(ImportActivity.class).setup().get();
        a.findViewById(R.id.btnPaste).performClick();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        findEditText(dialog.getWindow().getDecorView()).setText("{ không phải json");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ViewGroup preview = a.findViewById(R.id.preview);
        waitFor("báo lỗi", () -> allText(preview).contains("JSON không hợp lệ"));
        assertFalse(a.findViewById(R.id.btnImport).isEnabled());
        assertTrue(store().collections.loadTree().isEmpty());
    }

    private static String allText(View v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof TextView) sb.append(((TextView) v).getText()).append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) sb.append(allText(g.getChildAt(i)));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- history + runner

    @Test
    public void historyListsEntries() {
        HistoryRepository.Entry e = new HistoryRepository.Entry();
        e.name = "Login";
        e.method = "POST";
        e.url = "{{base_url}}/auth/login";
        e.timestamp = System.currentTimeMillis();
        e.status = 200;
        e.elapsedMs = 120;
        e.size = 2048;
        e.requestJson = "{\"name\":\"Login\"}";
        store().history.add(e);
        HistoryActivity a = Robolectric.buildActivity(HistoryActivity.class).setup().get();
        ListView list = a.findViewById(R.id.list);
        waitFor("lịch sử", () -> list.getAdapter().getCount() == 1);
    }

    @Test
    public void runnerRunsAllRequestsWithIterationsAndData() {
        JsonObject login = Fixtures.request("Login", "POST", "{{base_url}}/auth/login");
        JsonObject next = Fixtures.request("Next", "GET", "{{base_url}}/api/x");
        JsonObject nextHeaderReq = next.getAsJsonObject("request");
        com.google.gson.JsonArray hs = new com.google.gson.JsonArray();
        JsonObject h = new JsonObject();
        h.addProperty("key", "Authorization");
        h.addProperty("value", "Bearer {{access_token_v1}}");
        hs.add(h);
        nextHeaderReq.add("header", hs);
        Fixtures.addScript(login, "test",
                "pm.environment.set('access_token_v1', pm.response.json().data.accessToken);",
                "pm.test('ok', function () { pm.response.to.have.status(200); });");
        ImportResult built = Fixtures.parse(Json.toJson(Fixtures.collection("Run me", login, next)));
        store().collections.importCollection(built.collection, CollectionRepository.ConflictMode.COPY);
        useServerEnv();

        String cid = item("Run me").id;
        Intent i = new Intent(app, RunnerActivity.class).putExtra(RunnerActivity.EXTRA_NODE_ID, cid);
        RunnerActivity a = Robolectric.buildActivity(RunnerActivity.class, i).setup().get();
        EditText iterations = a.findViewById(R.id.iterations);
        iterations.setText("2");
        a.findViewById(R.id.btnRun).performClick();

        TextView summary = a.findViewById(R.id.summary);
        waitFor("runner chạy xong", () -> summary.getText().toString().startsWith("Xong"));
        assertTrue(summary.getText().toString(), summary.getText().toString().contains("4 request • 4 đạt • 0 lỗi"));
        assertTrue(summary.getText().toString(), summary.getText().toString().contains("tests 2 đạt / 0 lỗi"));
        assertEquals(4, server.getRequestCount());
        // request "Next" đã dùng token do Login lưu: header Authorization
        boolean sawBearer = false;
        synchronized (seen) {
            for (RecordedRequest r : seen) {
                if ("Bearer tok-abcdef123456".equals(r.getHeader("Authorization"))) sawBearer = true;
            }
        }
        assertTrue(sawBearer);
    }

}
