package vn.ioc.minipostman;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import vn.ioc.minipostman.core.importexport.PostmanExporter;
import vn.ioc.minipostman.core.model.CollectionDoc;
import vn.ioc.minipostman.core.model.EnvDoc;
import vn.ioc.minipostman.data.CollectionRepository;
import vn.ioc.minipostman.databinding.ActivityEnvBinding;
import vn.ioc.minipostman.ui.Ui;

/**
 * Quản lý biến theo phạm vi: Environment (nhiều môi trường, chuyển đổi, import/export), Global và Collection.
 * Mọi thay đổi được lưu tự động khi đổi tab/môi trường hoặc rời màn hình; biến secret được mã hóa khi lưu.
 */
public class EnvActivity extends AppCompatActivity {

    public static final String EXTRA_COLLECTION_ID = "collection_id";

    private static final int TAB_ENV = 0;
    private static final int TAB_GLOBAL = 1;
    private static final int TAB_COLLECTION = 2;

    private ActivityEnvBinding b;
    private Store store;

    private List<EnvDoc> envs = new ArrayList<>();
    private EnvDoc globals;
    private List<CollectionRepository.TreeItem> collections = new ArrayList<>();
    private CollectionDoc collection;       // collection đang chọn ở tab Collection (chỉ phần "đầu")

    private int tab = TAB_ENV;
    private int envIndex;
    private int collectionIndex;
    private boolean dirty;
    private boolean suppress;
    private String pendingExport;

    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), this::writeExport);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityEnvBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        b.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        b.toolbar.setNavigationOnClickListener(v -> finish());
        store = Store.get(this);

        for (String t : new String[]{"Environment", "Global", "Collection"}) {
            b.tabs.addTab(b.tabs.newTab().setText(t));
        }
        if (getIntent().hasExtra(EXTRA_COLLECTION_ID)) {
            tab = TAB_COLLECTION;
            TabLayout.Tab t = b.tabs.getTabAt(TAB_COLLECTION);
            if (t != null) t.select();
        }
        b.tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab t) {
                if (suppress) return;
                saveCurrent();
                tab = t.getPosition();
                render();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab t) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab t) {
            }
        });

        b.btnNewEnv.setOnClickListener(v -> Ui.prompt(this, "Environment mới", "", "Tên", false, name -> {
            saveCurrent();
            final String n = name.trim().isEmpty() ? "Environment" : name.trim();
            store.db(() -> {
                EnvDoc e = store.envs.create(n);
                store.envs.select(e.id);
                loadAll(e.id);
            });
        }));
        b.btnRenameEnv.setOnClickListener(v -> {
            final EnvDoc e = currentEnv();
            if (e == null) return;
            Ui.prompt(this, "Đổi tên environment", e.name, "Tên", false, name -> {
                if (name.trim().isEmpty()) return;
                e.setName(name.trim());
                dirty = true;
                saveCurrent();
                loadAll(e.id);
            });
        });
        b.btnDupEnv.setOnClickListener(v -> {
            final EnvDoc e = currentEnv();
            if (e == null) return;
            saveCurrent();
            store.db(() -> {
                EnvDoc copy = e.duplicate(store.envs.uniqueName(e.name + " (copy)"));
                store.envs.add(copy, true);
                loadAll(copy.id);
            });
        });
        b.btnDeleteEnv.setOnClickListener(v -> {
            final EnvDoc e = currentEnv();
            if (e == null) return;
            Ui.confirm(this, "Xóa environment?", "Xóa \"" + e.name + "\" và " + e.vars.items().size() + " biến của nó?", "Xóa",
                    () -> {
                        dirty = false;
                        store.db(() -> {
                            store.envs.delete(e.id);
                            loadAll(null);
                        });
                    });
        });
        b.btnExportEnv.setOnClickListener(v -> exportEnv());

        loadAll(null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrent();
    }

    // ---------------------------------------------------------------- nạp / lưu

    private EnvDoc currentEnv() {
        return envIndex >= 0 && envIndex < envs.size() ? envs.get(envIndex) : null;
    }

    /** Nạp lại toàn bộ dữ liệu từ DB rồi vẽ; selectEnvId = environment cần chọn sau khi nạp. */
    private void loadAll(final String selectEnvId) {
        store.db(() -> {
            final List<EnvDoc> e = store.envs.listEnvironments();
            final EnvDoc sel = store.envs.getSelected();
            final EnvDoc g = store.envs.getGlobals();
            final List<CollectionRepository.TreeItem> cols = new ArrayList<>();
            for (CollectionRepository.TreeItem t : store.collections.loadTree()) {
                if (CollectionRepository.TYPE_COLLECTION.equals(t.type)) cols.add(t);
            }
            final List<EnvDoc> all = e.isEmpty() ? store.envs.listEnvironments() : e;
            store.main(() -> {
                envs = all;
                globals = g;
                collections = cols;
                String want = selectEnvId != null ? selectEnvId : sel.id;
                envIndex = 0;
                for (int i = 0; i < envs.size(); i++) {
                    if (envs.get(i).id.equals(want)) envIndex = i;
                }
                String wantCollection = getIntent().getStringExtra(EXTRA_COLLECTION_ID);
                if (wantCollection != null) {
                    for (int i = 0; i < collections.size(); i++) {
                        if (collections.get(i).id.equals(wantCollection)) collectionIndex = i;
                    }
                    getIntent().removeExtra(EXTRA_COLLECTION_ID);
                }
                render();
            });
        });
    }

    private void loadCollection() {
        if (collectionIndex < 0 || collectionIndex >= collections.size()) {
            collection = null;
            bindEditor();
            return;
        }
        final String id = collections.get(collectionIndex).id;
        store.db(() -> {
            final CollectionDoc header = store.collections.loadCollectionHeader(id); // chỉ phần "đầu" + biến, không nạp cây
            store.main(() -> {
                collection = header;
                bindEditor();
            });
        });
    }

    /** Ghi dữ liệu đang sửa (nếu có thay đổi) xuống DB. */
    private void saveCurrent() {
        if (!dirty) return;
        dirty = false;
        final int t = tab;
        final EnvDoc env = currentEnv();
        final EnvDoc g = globals;
        final CollectionDoc c = collection;
        store.db(() -> {
            if (t == TAB_ENV && env != null) store.envs.save(env);
            else if (t == TAB_GLOBAL && g != null) store.envs.save(g);
            else if (t == TAB_COLLECTION && c != null) store.collections.saveCollection(c);
        });
    }

    // ---------------------------------------------------------------- hiển thị

    private void render() {
        suppress = true;
        TabLayout.Tab t = b.tabs.getTabAt(tab);
        if (t != null && !t.isSelected()) t.select();
        suppress = false;

        boolean envTab = tab == TAB_ENV;
        b.envButtons.setVisibility(envTab ? View.VISIBLE : View.GONE);
        b.scopeSpinner.setVisibility(tab == TAB_GLOBAL ? View.GONE : View.VISIBLE);
        b.varsEditor.setShowSecret(true);

        if (tab == TAB_ENV) {
            b.hint.setText("Environment đang chọn được dùng khi gửi request. Biến loại 🔒 được ẩn và lưu mã hóa. "
                    + "Biến bị bỏ chọn (tắt) sẽ không được dùng để thay thế.");
            List<String> names = new ArrayList<>();
            for (EnvDoc e : envs) names.add(e.name);
            spinner(names, envIndex, pos -> {
                saveCurrent();
                envIndex = pos;
                final String id = envs.get(pos).id;
                store.db(() -> store.envs.select(id));
                bindEditor();
            });
            bindEditor();
        } else if (tab == TAB_GLOBAL) {
            b.hint.setText("Globals dùng chung cho mọi collection và môi trường; ưu tiên thấp nhất "
                    + "(Local > Data > Environment > Collection > Global).");
            bindEditor();
        } else {
            b.hint.setText("Biến của collection được lưu trong chính collection (và có trong file export).");
            List<String> names = new ArrayList<>();
            for (CollectionRepository.TreeItem c : collections) names.add(c.name);
            if (names.isEmpty()) names.add("(chưa có collection)");
            spinner(names, collectionIndex, pos -> {
                saveCurrent();
                collectionIndex = pos;
                loadCollection();
            });
            loadCollection();
        }
    }

    private void bindEditor() {
        suppress = true;
        if (tab == TAB_ENV) {
            EnvDoc e = currentEnv();
            b.varsEditor.setItems(e == null ? new ArrayList<>() : e.vars.items());
            b.btnRenameEnv.setEnabled(e != null);
        } else if (tab == TAB_GLOBAL) {
            b.varsEditor.setItems(globals == null ? new ArrayList<>() : globals.vars.items());
        } else {
            b.varsEditor.setItems(collection == null ? new ArrayList<>() : collection.vars().items());
        }
        b.varsEditor.setHints("Biến", "Giá trị");
        b.varsEditor.setOnChange(() -> {
            if (!suppress) {
                dirty = true;
                if (tab == TAB_COLLECTION && collection != null) collection.vars().markModified();
            }
        });
        suppress = false;
    }

    private void spinner(List<String> items, int selected, final java.util.function.IntConsumer onPick) {
        b.scopeSpinner.setOnItemSelectedListener(null);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        b.scopeSpinner.setAdapter(a);
        b.scopeSpinner.setSelection(Math.max(0, Math.min(selected, items.size() - 1)), false);
        final int[] shown = {Math.max(0, Math.min(selected, items.size() - 1))};
        b.scopeSpinner.post(() -> b.scopeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == shown[0]) return;
                shown[0] = pos;
                onPick.accept(pos);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        }));
    }

    // ---------------------------------------------------------------- export environment

    private void exportEnv() {
        final EnvDoc e = currentEnv();
        if (e == null) return;
        saveCurrent();
        if (PostmanExporter.hasSecrets(e)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Environment có biến secret")
                    .setMessage("Có đưa giá trị secret vào file export không? File export là văn bản thường, ai có file đều đọc được.")
                    .setPositiveButton("Có, kèm giá trị", (d, w) -> doExportEnv(e, true))
                    .setNeutralButton("Không, để trống", (d, w) -> doExportEnv(e, false))
                    .setNegativeButton("Hủy", null)
                    .show();
        } else {
            doExportEnv(e, true);
        }
    }

    private void doExportEnv(EnvDoc e, boolean includeSecrets) {
        pendingExport = PostmanExporter.exportEnvironment(e, includeSecrets);
        exportLauncher.launch(e.name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".postman_environment.json");
    }

    private void writeExport(final Uri uri) {
        final String text = pendingExport;
        pendingExport = null;
        if (uri == null || text == null) return;
        store.db(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Không ghi được file");
                out.write(text.getBytes(StandardCharsets.UTF_8));
                store.main(() -> Ui.toast(this, "Đã xuất environment"));
            } catch (IOException ex) {
                store.main(() -> Ui.toast(this, "Xuất file lỗi: " + ex.getMessage()));
            }
        });
    }
}
