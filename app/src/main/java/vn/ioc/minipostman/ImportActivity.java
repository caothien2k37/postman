package vn.ioc.minipostman;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import vn.ioc.minipostman.core.Strings;
import vn.ioc.minipostman.core.importexport.ImportException;
import vn.ioc.minipostman.core.importexport.ImportResult;
import vn.ioc.minipostman.core.importexport.PostmanParser;
import vn.ioc.minipostman.core.net.HttpSettings;
import vn.ioc.minipostman.core.net.PreparedRequest;
import vn.ioc.minipostman.core.net.ResponseData;
import vn.ioc.minipostman.databinding.ActivityImportBinding;
import vn.ioc.minipostman.ui.Ui;

/**
 * Import từ file (nhiều file), văn bản dán (JSON hoặc cURL) hoặc URL. Luôn hiển thị bản xem trước (tên, schema,
 * số folder/request/script/biến, cảnh báo) rồi mới ghi; trùng tên thì hỏi Tạo bản sao / Thay thế / Bỏ qua.
 */
public class ImportActivity extends AppCompatActivity {

    private ActivityImportBinding b;
    private Store store;
    private final List<ImportResult> results = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();

    private final ActivityResultLauncher<String[]> openLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), this::onFilesPicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityImportBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        b.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        b.toolbar.setNavigationOnClickListener(v -> finish());
        store = Store.get(this);

        b.btnFile.setOnClickListener(v -> openLauncher.launch(new String[]{"*/*"}));
        b.btnPaste.setOnClickListener(v -> Ui.prompt(this, "Dán JSON hoặc lệnh cURL", clipboardText(),
                "Dán nội dung Postman Collection/Environment (JSON) hoặc lệnh curl…", true,
                text -> parseText(text, "văn bản đã dán")));
        b.btnUrl.setOnClickListener(v -> Ui.prompt(this, "Import từ URL", "https://", "URL của file JSON", false,
                this::parseUrl));
        b.btnImport.setOnClickListener(v -> startCommit());
        renderPreview();
    }

    private String clipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                String s = clip.getItemAt(0).getText().toString();
                if (s.length() < 200_000) return s;
            }
        } catch (RuntimeException ignored) {
            // Clipboard có thể bị chặn: để trống.
        }
        return "";
    }

    // ---------------------------------------------------------------- nạp nguồn

    private void onFilesPicked(final List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        beginLoading("Đang đọc " + uris.size() + " file…");
        store.net(() -> {
            for (Uri uri : uris) {
                String name = PostmanImporter.displayName(getContentResolver(), uri);
                try {
                    String text = PostmanImporter.readUri(getContentResolver(), uri);
                    results.add(PostmanParser.parse(text, name));
                } catch (ImportException | java.io.IOException e) {
                    errors.add(name + ": " + e.getMessage());
                } catch (OutOfMemoryError e) {
                    errors.add(name + ": File quá lớn so với bộ nhớ của máy");
                } catch (RuntimeException | StackOverflowError e) {
                    errors.add(name + ": Không đọc được (" + e.getClass().getSimpleName() + ")");
                }
            }
            store.main(this::endLoading);
        });
    }

    private void parseText(final String text, final String name) {
        if (Strings.isBlank(text)) return;
        beginLoading("Đang phân tích…");
        store.net(() -> {
            try {
                results.add(PostmanParser.parse(text, name));
            } catch (ImportException e) {
                errors.add(name + ": " + e.getMessage());
            } catch (RuntimeException | StackOverflowError e) {
                errors.add(name + ": Không đọc được (" + e.getClass().getSimpleName() + ")");
            }
            store.main(this::endLoading);
        });
    }

    private void parseUrl(final String url) {
        final String u = url.trim();
        String lower = u.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            Ui.toast(this, "URL phải bắt đầu bằng http:// hoặc https://");
            return;
        }
        beginLoading("Đang tải " + u + "…");
        store.net(() -> {
            PreparedRequest p = new PreparedRequest();
            p.url = u;
            p.headers.add(new String[]{"Accept", "application/json, */*"});
            HttpSettings s = store.settings.load();
            ResponseData r = store.engine.execute(p, s, null);
            if (r.isError()) {
                errors.add(u + ": " + r.error);
            } else if (r.code < 200 || r.code >= 300) {
                errors.add(u + ": server trả về " + r.statusLine());
            } else if (r.truncated) {
                errors.add(u + ": file quá lớn");
            } else {
                try {
                    String last = android.net.Uri.parse(u).getLastPathSegment();
                    results.add(PostmanParser.parse(r.body, last == null ? "url.json" : last));
                } catch (ImportException e) {
                    errors.add(u + ": " + e.getMessage());
                } catch (RuntimeException | StackOverflowError e) {
                    errors.add(u + ": Không đọc được (" + e.getClass().getSimpleName() + ")");
                }
            }
            store.main(this::endLoading);
        });
    }

    private void beginLoading(String message) {
        b.loading.setText(message);
        b.loading.setVisibility(View.VISIBLE);
        b.btnImport.setEnabled(false);
    }

    private void endLoading() {
        b.loading.setVisibility(View.GONE);
        renderPreview();
    }

    // ---------------------------------------------------------------- xem trước

    private void renderPreview() {
        LinearLayout box = b.preview;
        box.removeAllViews();
        for (String e : errors) {
            TextView t = text("✗ " + e, R.color.error, 13, true);
            t.setPadding(0, Ui.dp(this, 12), 0, 0);
            box.addView(t);
        }
        for (ImportResult r : results) box.addView(card(r));
        b.btnImport.setEnabled(!results.isEmpty());
        b.btnImport.setText(results.size() > 1 ? "Import now (" + results.size() + " mục)" : "Import now");
    }

    private View card(ImportResult r) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_input);
        int pad = Ui.dp(this, 14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        card.setLayoutParams(lp);

        TextView src = text("Nguồn", R.color.text_secondary, 12, false);
        card.addView(src);
        card.addView(text(r.sourceName, R.color.text, 15, true));

        row(card, r.kind == ImportResult.Kind.ENVIRONMENT || r.kind == ImportResult.Kind.GLOBALS ? "Environment" : "Collection", r.name);
        row(card, "Schema", r.schema);
        if (r.isCollection()) {
            row(card, "Folders", r.folders + (r.maxDepth > 0 ? " (sâu " + r.maxDepth + " cấp)" : ""));
            row(card, "Requests", String.valueOf(r.requests));
            if (r.unknownItems > 0) row(card, "Khác", r.unknownItems + " phần tử giữ nguyên");
            row(card, "Scripts", r.scripts + " events");
            row(card, "Biến", String.valueOf(r.variables));
            row(card, "Ví dụ response", String.valueOf(r.examples));
        } else {
            row(card, "Biến", String.valueOf(r.variables));
        }
        if (!r.warnings.isEmpty()) {
            TextView w = text("Cảnh báo:\n• " + Strings.join("\n• ", r.warnings), R.color.warn, 12, false);
            w.setPadding(0, Ui.dp(this, 10), 0, 0);
            card.addView(w);
        }
        return card;
    }

    private void row(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 6), 0, 0);
        TextView l = text(label, R.color.text_secondary, 13, false);
        row.addView(l, new LinearLayout.LayoutParams(Ui.dp(this, 120), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(text(value, R.color.text, 13, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
    }

    private TextView text(String s, int colorRes, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(getResources().getColor(colorRes, getTheme()));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    // ---------------------------------------------------------------- ghi vào DB

    private void startCommit() {
        if (results.isEmpty()) return;
        b.btnImport.setEnabled(false);
        messages.clear();
        commitNext(0);
    }

    private void commitNext(final int index) {
        if (index >= results.size()) {
            Ui.toast(this, Strings.join("\n", messages));
            setResult(RESULT_OK);
            finish();
            return;
        }
        final ImportResult r = results.get(index);
        store.db(() -> {
            final boolean conflict = PostmanImporter.hasConflict(store, r);
            store.main(() -> {
                if (conflict) askConflict(r, choice -> doCommit(index, r, choice));
                else doCommit(index, r, PostmanImporter.Conflict.COPY);
            });
        });
    }

    private void askConflict(ImportResult r, final java.util.function.Consumer<PostmanImporter.Conflict> then) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Đã có \"" + r.name + "\"")
                .setMessage("Không tự động ghi đè. Bạn muốn xử lý thế nào?")
                .setCancelable(false)
                .setPositiveButton("Tạo bản sao", (d, w) -> then.accept(PostmanImporter.Conflict.COPY))
                .setNeutralButton("Thay thế", (d, w) -> then.accept(PostmanImporter.Conflict.REPLACE))
                .setNegativeButton("Bỏ qua", (d, w) -> then.accept(PostmanImporter.Conflict.SKIP))
                .show();
    }

    private void doCommit(final int index, final ImportResult r, final PostmanImporter.Conflict choice) {
        store.db(() -> {
            String msg;
            try {
                msg = PostmanImporter.commit(store, r, choice);
            } catch (RuntimeException e) {
                msg = "Import \"" + r.name + "\" lỗi, không có gì bị ghi dở: " + e.getMessage();
            }
            final String m = msg;
            store.main(() -> {
                messages.add(m);
                commitNext(index + 1);
            });
        });
    }
}
