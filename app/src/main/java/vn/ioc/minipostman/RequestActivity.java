package vn.ioc.minipostman;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.Strings;
import vn.ioc.minipostman.core.exec.ExecutionResult;
import vn.ioc.minipostman.core.model.KeyValue;
import vn.ioc.minipostman.core.net.HttpEngine;
import vn.ioc.minipostman.core.net.ResponseData;
import vn.ioc.minipostman.core.request.AuthSpec;
import vn.ioc.minipostman.core.request.RequestModel;
import vn.ioc.minipostman.core.script.JsSandbox;
import vn.ioc.minipostman.data.CollectionRepository;
import vn.ioc.minipostman.databinding.ActivityRequestBinding;
import vn.ioc.minipostman.ui.SettingsDialog;
import vn.ioc.minipostman.ui.Ui;

/** Request Builder (Params/Auth/Headers/Body/Scripts/Settings) và Response Viewer (Body/Headers/Cookies/Tests/Console). */
public class RequestActivity extends AppCompatActivity {

    public static final String EXTRA_NODE_ID = "node_id";

    private static final int MENU_SAVE = 1;
    private static final int MENU_SETTINGS = 2;
    private static final int MAX_SHOWN_CHARS = 200_000;

    private static final String[] AUTH_KEYS = {AuthSpec.INHERIT, AuthSpec.NOAUTH, AuthSpec.BEARER, AuthSpec.BASIC,
            AuthSpec.APIKEY, AuthSpec.OAUTH2};
    private static final String[] AUTH_LABELS = {"Inherit auth from parent", "No Auth", "Bearer Token", "Basic Auth",
            "API Key", "OAuth 2.0 (dùng Access Token có sẵn)"};
    private static final String[] BODY_KEYS = {RequestModel.BODY_NONE, RequestModel.BODY_RAW,
            RequestModel.BODY_URLENCODED, RequestModel.BODY_FORMDATA, RequestModel.BODY_GRAPHQL, RequestModel.BODY_FILE};
    private static final String[] BODY_LABELS = {"none", "raw", "x-www-form-urlencoded", "form-data", "GraphQL",
            "binary / file (chưa hỗ trợ gửi)"};

    private ActivityRequestBinding b;
    private Store store;
    private CollectionRepository.Context ctx;
    private RequestModel model;
    private String extractText = "";
    private String savedExtract = "";
    private boolean suppress;
    private boolean sending;
    private HttpEngine.CancelToken cancel;

    private ExecutionResult last;
    private int responseTab;
    private boolean pretty = true;
    private String fullBodyText = "";

    private final ActivityResultLauncher<String> saveResponseLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("*/*"), this::writeResponse);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityRequestBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        b.toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        b.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        store = Store.get(this);

        for (String t : new String[]{"Params", "Auth", "Headers", "Body", "Scripts", "Settings"}) {
            b.tabs.addTab(b.tabs.newTab().setText(t));
        }
        b.tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showPanel(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        for (String t : new String[]{"Body", "Headers", "Cookies", "Tests", "Console"}) {
            b.responseTabs.addTab(b.responseTabs.newTab().setText(t));
        }
        b.responseTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                responseTab = tab.getPosition();
                renderResponse();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        b.btnPretty.setOnClickListener(v -> {
            pretty = true;
            renderResponse();
        });
        b.btnRaw.setOnClickListener(v -> {
            pretty = false;
            renderResponse();
        });
        b.btnCopy.setOnClickListener(v -> copy(currentResponseText()));
        b.btnSaveResponse.setOnClickListener(v -> saveResponseLauncher.launch("response" + guessExtension()));
        b.btnSend.setOnClickListener(v -> onSendClicked());
        b.btnGlobalSettings.setOnClickListener(v -> SettingsDialog.show(this, store, this::updateSettingsSummary));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!isDirty()) {
                    finish();
                    return;
                }
                new MaterialAlertDialogBuilder(RequestActivity.this)
                        .setTitle("Chưa lưu thay đổi")
                        .setMessage("Lưu thay đổi của request này trước khi thoát?")
                        .setPositiveButton("Lưu", (d, w) -> {
                            save();
                            finish();
                        })
                        .setNeutralButton("Bỏ thay đổi", (d, w) -> finish())
                        .setNegativeButton("Ở lại", null)
                        .show();
            }
        });

        final String nodeId = getIntent().getStringExtra(EXTRA_NODE_ID);
        store.db(() -> {
            final CollectionRepository.Context c = nodeId == null ? null : store.collections.loadContext(nodeId);
            final String envName = store.envs.getSelected().name;
            store.main(() -> {
                if (c == null) {
                    Ui.toast(this, "Không tìm thấy request (có thể đã bị xóa).");
                    finish();
                    return;
                }
                ctx = c;
                model = RequestModel.parse(c.node.raw);
                extractText = c.node.extract;
                savedExtract = c.node.extract;
                b.envLabel.setText("Môi trường: " + envName + "   •   " + c.collection.name);
                bind();
            });
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SAVE, 0, "Lưu").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, MENU_SETTINGS, 1, "Cài đặt chung");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_SAVE) {
            save();
            return true;
        }
        if (item.getItemId() == MENU_SETTINGS) {
            SettingsDialog.show(this, store, this::updateSettingsSummary);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---------------------------------------------------------------- hiển thị dữ liệu vào form

    private void bind() {
        suppress = true;
        b.reqName.setText(model.name);

        List<String> methods = new ArrayList<>(Arrays.asList(RequestModel.METHODS));
        if (!methods.contains(model.method)) methods.add(model.method);
        spinner(b.methodSpinner, methods, methods.indexOf(model.method), pos -> {
            model.method = methods.get(pos);
            onModelChanged();
        });

        b.url.setText(model.url);
        model.ensurePathVars();
        b.paramsEditor.setHints("param", "giá trị");
        b.paramsEditor.setItems(model.query);
        b.paramsEditor.setOnChange(() -> {
            if (suppress) return;
            model.syncUrlFromQuery();
            suppress = true;
            b.url.setText(model.url);
            suppress = false;
            onModelChanged();
        });
        b.pathVarsEditor.setHints("biến", "giá trị");
        b.pathVarsEditor.setAddLabel("+ Thêm path variable");
        b.pathVarsEditor.setItems(model.pathVars);
        b.pathVarsEditor.setOnChange(this::onModelChanged);

        b.headersEditor.setHints("Header", "Giá trị");
        b.headersEditor.setItems(model.headers);
        b.headersEditor.setOnChange(this::onModelChanged);

        bindBody();
        bindAuth();

        b.preScript.setText(model.preRequest);
        b.testScript.setText(model.tests);
        b.extract.setText(extractText);

        List<String> redirect = Arrays.asList("Theo Cài đặt chung", "Luôn theo redirect", "Không theo redirect");
        int ri = model.followRedirects == null ? 0 : (model.followRedirects ? 1 : 2);
        spinner(b.redirectMode, redirect, ri, pos -> {
            model.followRedirects = pos == 0 ? null : (pos == 1);
            onModelChanged();
        });
        updateSettingsSummary();

        watch(b.reqName, s -> model.name = s);
        watch(b.url, s -> {
            model.setUrlText(s);
            model.ensurePathVars();
            b.paramsEditor.setItems(model.query);
            b.pathVarsEditor.setItems(model.pathVars);
        });
        watch(b.preScript, s -> model.preRequest = s);
        watch(b.testScript, s -> model.tests = s);
        watch(b.extract, s -> extractText = s);
        suppress = false;

        showPanel(0);
        showResponseTabCounts();
        renderResponse();
        updateTitle();
    }

    private void bindBody() {
        int idx = Arrays.asList(BODY_KEYS).indexOf(model.bodyMode);
        List<String> labels = new ArrayList<>(Arrays.asList(BODY_LABELS));
        if (idx < 0) {
            labels.add("Khác: " + model.bodyMode + " (giữ nguyên)");
            idx = labels.size() - 1;
        }
        final List<String> bodyLabels = labels;
        spinner(b.bodyMode, bodyLabels, idx, pos -> {
            if (pos < BODY_KEYS.length) model.bodyMode = BODY_KEYS[pos];
            applyBodyVisibility();
            onModelChanged();
        });
        List<String> langs = Arrays.asList(RequestModel.RAW_LANGUAGES);
        List<String> langList = new ArrayList<>(langs);
        if (!langList.contains(model.rawLanguage)) langList.add(model.rawLanguage);
        spinner(b.rawLanguage, langList, langList.indexOf(model.rawLanguage), pos -> {
            model.rawLanguage = langList.get(pos);
            validateJson();
            onModelChanged();
        });
        b.bodyRaw.setText(model.bodyRaw);
        watch(b.bodyRaw, s -> {
            model.bodyRaw = s;
            validateJson();
        });
        b.urlencodedEditor.setHints("key", "value");
        b.urlencodedEditor.setItems(model.urlencoded);
        b.urlencodedEditor.setOnChange(this::onModelChanged);
        b.formdataEditor.setHints("key", "value (trường file bị bỏ qua khi gửi)");
        b.formdataEditor.setItems(model.formdata);
        b.formdataEditor.setOnChange(this::onModelChanged);
        b.graphqlQuery.setText(model.graphqlQuery);
        b.graphqlVars.setText(model.graphqlVariables);
        watch(b.graphqlQuery, s -> model.graphqlQuery = s);
        watch(b.graphqlVars, s -> model.graphqlVariables = s);
        b.btnFormat.setOnClickListener(v -> {
            String pretty = Json.prettyText(b.bodyRaw.getText().toString());
            if (pretty == null) {
                Ui.toast(this, "Không phải JSON hợp lệ nên không format được.");
            } else {
                b.bodyRaw.setText(pretty);
            }
        });
        applyBodyVisibility();
    }

    private void applyBodyVisibility() {
        String mode = model.bodyMode;
        boolean raw = RequestModel.BODY_RAW.equals(mode);
        b.rawLanguage.setVisibility(raw ? View.VISIBLE : View.GONE);
        b.btnFormat.setVisibility(raw ? View.VISIBLE : View.GONE);
        b.bodyRaw.setVisibility(raw ? View.VISIBLE : View.GONE);
        b.urlencodedEditor.setVisibility(RequestModel.BODY_URLENCODED.equals(mode) ? View.VISIBLE : View.GONE);
        b.formdataEditor.setVisibility(RequestModel.BODY_FORMDATA.equals(mode) ? View.VISIBLE : View.GONE);
        b.graphqlBox.setVisibility(RequestModel.BODY_GRAPHQL.equals(mode) ? View.VISIBLE : View.GONE);
        if (RequestModel.BODY_NONE.equals(mode)) b.bodyNote.setText("Request này không gửi body.");
        else if (RequestModel.BODY_FILE.equals(mode)) b.bodyNote.setText("Body dạng file/binary chưa hỗ trợ gửi từ Android; dữ liệu gốc vẫn được giữ khi export.");
        else if (!raw && !RequestModel.BODY_URLENCODED.equals(mode) && !RequestModel.BODY_FORMDATA.equals(mode)
                && !RequestModel.BODY_GRAPHQL.equals(mode)) b.bodyNote.setText("Kiểu body \"" + mode + "\" chưa hỗ trợ; dữ liệu được giữ nguyên.");
        else b.bodyNote.setText("");
        validateJson();
    }

    private void validateJson() {
        if (!RequestModel.BODY_RAW.equals(model.bodyMode) || !"json".equals(model.rawLanguage)) {
            if (!RequestModel.BODY_RAW.equals(model.bodyMode)) return;
            b.bodyNote.setText("");
            return;
        }
        String t = model.bodyRaw;
        if (t.trim().isEmpty()) {
            b.bodyNote.setText("");
        } else if (t.length() > 200_000) {
            b.bodyNote.setText("Body lớn: bỏ qua kiểm tra JSON tự động.");
        } else if (Json.tryParse(t) != null) {
            b.bodyNote.setText("✓ JSON hợp lệ");
        } else {
            b.bodyNote.setText("✗ JSON chưa hợp lệ (nếu có {{biến}} ngoài dấu nháy thì cũng báo lỗi này).");
        }
    }

    // ---------------------------------------------------------------- auth

    private void bindAuth() {
        int idx = Arrays.asList(AUTH_KEYS).indexOf(model.authType);
        List<String> labels = new ArrayList<>(Arrays.asList(AUTH_LABELS));
        if (idx < 0) {
            labels.add("Khác: " + model.authType + " (giữ nguyên, chưa hỗ trợ gửi)");
            idx = labels.size() - 1;
        }
        spinner(b.authType, labels, idx, pos -> {
            if (pos >= AUTH_KEYS.length) return;
            String key = AUTH_KEYS[pos];
            if (!key.equals(model.authType)) {
                model.authType = key;
                model.authParams.clear();
                rebuildAuthFields();
                onModelChanged();
            }
        });
        rebuildAuthFields();
    }

    private void rebuildAuthFields() {
        LinearLayout box = b.authFields;
        box.removeAllViews();
        String type = model.authType;
        b.authNote.setText("");
        switch (type) {
            case AuthSpec.BEARER:
                authField(box, "token", "Token (có thể dùng {{access_token_v1}})", true);
                break;
            case AuthSpec.BASIC:
                authField(box, "username", "Username", false);
                authField(box, "password", "Password", true);
                break;
            case AuthSpec.APIKEY:
                authField(box, "key", "Key (tên header/param)", false);
                authField(box, "value", "Value", true);
                authChoice(box, "in", "Thêm vào", Arrays.asList("header", "query"), "header");
                break;
            case AuthSpec.OAUTH2:
                authField(box, "accessToken", "Access Token", true);
                authField(box, "headerPrefix", "Header prefix (mặc định Bearer)", false);
                authChoice(box, "addTokenTo", "Thêm token vào", Arrays.asList("header", "queryParams"), "header");
                b.authNote.setText("Chỉ dùng Access Token đã có sẵn; chưa hỗ trợ lấy token tự động qua luồng OAuth.");
                break;
            case AuthSpec.INHERIT:
                b.authNote.setText("Dùng auth của folder gần nhất hoặc của collection.");
                break;
            case AuthSpec.NOAUTH:
                b.authNote.setText("Không gửi thông tin xác thực.");
                break;
            default:
                b.authNote.setText("Kiểu auth \"" + type + "\" chưa hỗ trợ khi gửi; cấu hình vẫn được giữ nguyên để export.");
                break;
        }
    }

    private void authField(LinearLayout box, final String key, String label, boolean sensitive) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        box.addView(t);
        EditText e = new EditText(this);
        Ui.noAutofill(e);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        e.setSingleLine(true);
        e.setText(model.authParams.containsKey(key) ? model.authParams.get(key) : "");
        if (sensitive) e.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                model.authParams.put(key, s.toString());
                onModelChanged();
            }
        });
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void authChoice(LinearLayout box, final String key, String label, final List<String> options, String def) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        box.addView(t);
        Spinner sp = new Spinner(this);
        String cur = model.authParams.containsKey(key) ? model.authParams.get(key) : def;
        int idx = Math.max(options.indexOf(cur), 0);
        box.addView(sp);
        spinner(sp, options, idx, pos -> {
            String chosen = options.get(pos);
            String before = model.authParams.containsKey(key) ? model.authParams.get(key) : def;
            if (!chosen.equals(before)) {
                model.authParams.put(key, chosen);
                onModelChanged();
            }
        });
    }

    // ---------------------------------------------------------------- tab / trạng thái chỉnh sửa

    private void showPanel(int index) {
        View[] panels = {b.panelParams, b.panelAuth, b.panelHeaders, b.panelBody, b.panelScripts, b.panelSettings};
        for (int i = 0; i < panels.length; i++) panels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
    }

    private void updateSettingsSummary() {
        var s = store.settings.load();
        b.settingsSummary.setText("Cài đặt chung: timeout kết nối " + s.connectTimeoutSec + "s, đọc/ghi " + s.readTimeoutSec
                + "s • redirect " + (s.followRedirects ? "bật" : "tắt") + " • kiểm tra SSL "
                + (s.verifySsl ? "bật" : "TẮT (không an toàn)") + " • pm.sendRequest "
                + (s.allowScriptRequests ? "cho phép" : "chặn"));
    }

    private void onModelChanged() {
        if (suppress) return;
        updateTitle();
    }

    private boolean isDirty() {
        return model != null && (model.isDirty() || !extractText.equals(savedExtract));
    }

    private void updateTitle() {
        if (model == null) return;
        String n = model.name.isEmpty() ? "Request" : model.name;
        b.toolbar.setTitle(n + (isDirty() ? "  ●" : ""));
    }

    private void watch(EditText e, final Consumer<String> apply) {
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppress) return;
                apply.accept(s.toString());
                updateTitle();
            }
        });
    }

    private void spinner(final Spinner sp, List<String> items, int selected, final java.util.function.IntConsumer onUserPick) {
        sp.setOnItemSelectedListener(null);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setSelection(Math.max(selected, 0), false);
        final int[] shown = {Math.max(selected, 0)};
        sp.post(() -> sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == shown[0]) return;
                shown[0] = pos;
                onUserPick.accept(pos);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        }));
    }

    // ---------------------------------------------------------------- lưu / gửi

    private void save() {
        if (model == null || ctx == null) return;
        final JsonObject item = model.commit();
        ctx.node.raw = item;
        ctx.node.name = model.name;
        ctx.node.extract = extractText;
        savedExtract = extractText;
        updateTitle();
        store.db(() -> {
            store.collections.updateNode(ctx.node);
            store.main(() -> Ui.toast(this, "Đã lưu"));
        });
    }

    private void onSendClicked() {
        if (sending) {
            if (cancel != null) cancel.cancel();
            return;
        }
        if (model == null || ctx == null) return;
        sending = true;
        cancel = new HttpEngine.CancelToken();
        b.btnSend.setText("Hủy");
        b.status.setText("Đang gửi…");
        b.status.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
        final HttpEngine.CancelToken token = cancel;
        final RequestModel snapshot = model;
        final String extract = extractText;
        store.net(() -> {
            ExecutionResult res;
            try {
                res = store.execute(ctx, snapshot, extract, null, 0, 1, token);
            } catch (RuntimeException e) {
                res = new ExecutionResult();
                res.errors.add("Lỗi nội bộ: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            final ExecutionResult fin = res;
            store.main(() -> {
                sending = false;
                b.btnSend.setText("Send");
                last = fin;
                showResponseTabCounts();
                // Không gửi được → mở tab Console để thấy lý do
                if (fin.response == null || fin.response.isError() || !fin.errors.isEmpty()) {
                    TabLayout.Tab t = b.responseTabs.getTabAt(4);
                    if (t != null && b.responseTabs.getSelectedTabPosition() != 4) t.select();
                    else renderResponse();
                } else {
                    renderResponse();
                }
                if (!fin.changedVars.isEmpty()) {
                    Ui.toast(this, "Đã set biến: " + Strings.join(", ", fin.changedVars));
                }
                scrollToResponse();
            });
        });
    }

    private void scrollToResponse() {
        b.scroll.post(() -> b.scroll.smoothScrollTo(0, b.status.getTop()));
    }

    // ---------------------------------------------------------------- hiển thị response

    private void showResponseTabCounts() {
        TabLayout.Tab tests = b.responseTabs.getTabAt(3);
        if (tests == null) return;
        if (last != null && !last.tests.isEmpty()) tests.setText("Tests (" + last.passed() + "/" + last.tests.size() + ")");
        else tests.setText("Tests");
    }

    private void renderResponse() {
        b.responseTools.setVisibility(responseTab == 0 ? View.VISIBLE : View.GONE);
        if (last == null) {
            b.status.setText("Chưa gửi request.");
            b.response.setText("");
            return;
        }
        ResponseData r = last.response;
        if (r == null) {
            b.status.setText("⚠ Request chưa được gửi");
            b.status.setTextColor(Ui.statusColor(this, 0));
        } else if (r.isError()) {
            b.status.setText("⚠ Lỗi kết nối");
            b.status.setTextColor(Ui.statusColor(this, 0));
        } else {
            String s = r.statusLine() + "  •  " + r.timeMs + " ms  •  " + Ui.formatSize(r.size)
                    + (r.truncated ? " (đã cắt)" : "");
            if (!last.tests.isEmpty()) s += "\nTests: " + last.passed() + " đạt, " + last.failed() + " lỗi";
            b.status.setText(s);
            b.status.setTextColor(Ui.statusColor(this, r.code));
        }

        String text;
        switch (responseTab) {
            case 1:
                text = r == null || r.isError() ? "" : HttpEngine.headersText(r.headers);
                break;
            case 2:
                text = cookiesText(r);
                break;
            case 3:
                text = testsText();
                break;
            case 4:
                text = consoleText();
                break;
            default:
                text = bodyText(r);
                break;
        }
        fullBodyText = text;
        b.response.setText(text.length() > MAX_SHOWN_CHARS
                ? text.substring(0, MAX_SHOWN_CHARS) + "\n… (đã cắt bớt khi hiển thị; nút Copy lấy toàn bộ)" : text);
        int on = getResources().getColor(R.color.primary, getTheme());
        int off = getResources().getColor(R.color.text_secondary, getTheme());
        b.btnPretty.setTextColor(pretty ? on : off);
        b.btnRaw.setTextColor(pretty ? off : on);
    }

    private String bodyText(ResponseData r) {
        if (r == null) return Strings.join("\n", last.errors);
        if (r.isError()) return r.error;
        if (r.binary) return "[Dữ liệu nhị phân " + Ui.formatSize(r.size) + ", " + r.contentType + "]";
        if (r.body.isEmpty()) return "(response không có body)";
        if (pretty) {
            String p = Json.prettyText(r.body);
            if (p != null) return p;
        }
        return r.body;
    }

    private String cookiesText(ResponseData r) {
        if (r == null || r.isError() || r.cookies.isEmpty()) return "(không có cookie nào được server đặt)";
        StringBuilder sb = new StringBuilder();
        for (String[] c : r.cookies) {
            sb.append(c[0]).append(" = ").append(c[1]).append("\n   domain=").append(c[2]).append("  path=").append(c[3]);
            if (!c[4].isEmpty()) sb.append("  ").append(c[4]);
            sb.append('\n');
        }
        return sb.toString();
    }

    private String testsText() {
        if (last.tests.isEmpty()) return "(không có test nào; thêm pm.test(...) vào tab Scripts → Post-response)";
        StringBuilder sb = new StringBuilder();
        sb.append("✓ ").append(last.passed()).append(" đạt   ✗ ").append(last.failed()).append(" lỗi\n\n");
        for (JsSandbox.TestResult t : last.tests) {
            sb.append(t.passed ? "✓ " : "✗ ").append(t.name);
            if (!t.message.isEmpty()) sb.append("\n    ").append(t.message);
            sb.append('\n');
        }
        return sb.toString();
    }

    private String consoleText() {
        StringBuilder sb = new StringBuilder();
        if (!last.requestLine.isEmpty()) sb.append("→ ").append(last.requestLine).append("\n");
        for (String e : last.errors) sb.append("✗ ").append(e).append('\n');
        for (String w : last.warnings) sb.append("⚠ ").append(w).append('\n');
        for (String c : last.console) sb.append(c).append('\n');
        if (!last.changedVars.isEmpty()) sb.append("• Đã set biến: ").append(Strings.join(", ", last.changedVars)).append('\n');
        if (sb.length() == 0) return "(console trống)";
        return sb.toString();
    }

    private String currentResponseText() {
        return fullBodyText;
    }

    private String guessExtension() {
        ResponseData r = last == null ? null : last.response;
        String ct = r == null ? "" : r.contentType.toLowerCase(java.util.Locale.ROOT);
        if (ct.contains("json")) return ".json";
        if (ct.contains("xml")) return ".xml";
        if (ct.contains("html")) return ".html";
        return ".txt";
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("response", text));
            Ui.toast(this, "Đã copy");
        }
    }

    private void writeResponse(final Uri uri) {
        if (uri == null || last == null || last.response == null) return;
        final String body = last.response.body;
        store.db(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Không ghi được file");
                out.write(body.getBytes(StandardCharsets.UTF_8));
                store.main(() -> Ui.toast(this, "Đã lưu response"));
            } catch (IOException e) {
                store.main(() -> Ui.toast(this, "Lưu file lỗi: " + e.getMessage()));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cancel != null && sending) cancel.cancel();
    }
}
