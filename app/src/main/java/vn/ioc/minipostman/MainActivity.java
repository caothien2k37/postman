package vn.ioc.minipostman;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import vn.ioc.minipostman.core.importexport.PostmanExporter;
import vn.ioc.minipostman.core.model.CollectionDoc;
import vn.ioc.minipostman.core.model.EnvDoc;
import vn.ioc.minipostman.core.model.KeyValue;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.data.CollectionRepository;
import vn.ioc.minipostman.databinding.ActivityMainBinding;
import vn.ioc.minipostman.ui.CollectionViewModel;
import vn.ioc.minipostman.ui.SettingsDialog;
import vn.ioc.minipostman.ui.TreeAdapter;
import vn.ioc.minipostman.ui.TreeRow;
import vn.ioc.minipostman.ui.Ui;

/** Màn hình chính: cây collection nhiều cấp, tìm kiếm, lọc method, chọn environment. */
public class MainActivity extends AppCompatActivity implements TreeAdapter.Listener {

    private static final int MENU_IMPORT = 1;
    private static final int MENU_VARS = 2;
    private static final int MENU_HISTORY = 3;
    private static final int MENU_SETTINGS = 4;

    private static final List<String> METHOD_FILTER =
            Arrays.asList("Tất cả", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private ActivityMainBinding b;
    private Store store;
    private CollectionViewModel vm;
    private TreeAdapter adapter;
    private List<EnvDoc> envList = new ArrayList<>();
    private int shownEnvIndex = -1;
    private String pendingExportText;

    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), this::writeExport);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        store = Store.get(this);
        vm = new ViewModelProvider(this).get(CollectionViewModel.class);

        adapter = new TreeAdapter(this);
        b.tree.setLayoutManager(new LinearLayoutManager(this));
        b.tree.setAdapter(adapter);
        vm.rows().observe(this, rows -> {
            adapter.submitList(rows);
            boolean empty = rows.isEmpty() && b.search.getText().length() == 0
                    && b.methodFilter.getSelectedItemPosition() <= 0;
            b.emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        });

        b.search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                vm.setQuery(s.toString());
            }
        });
        ArrayAdapter<String> mf = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, METHOD_FILTER);
        mf.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        b.methodFilter.setAdapter(mf);
        b.methodFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                vm.setMethodFilter(pos == 0 ? "" : METHOD_FILTER.get(pos));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        b.btnVars.setOnClickListener(v -> startActivity(new Intent(this, EnvActivity.class)));
        b.fab.setOnClickListener(v -> showAddMenu());
    }

    @Override
    protected void onResume() {
        super.onResume();
        vm.refresh();
        refreshEnvs();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_IMPORT, 0, "Import…");
        menu.add(0, MENU_VARS, 1, "Biến (Environment / Global)");
        menu.add(0, MENU_HISTORY, 2, "Lịch sử");
        menu.add(0, MENU_SETTINGS, 3, "Cài đặt chung");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_IMPORT:
                startActivity(new Intent(this, ImportActivity.class));
                return true;
            case MENU_VARS:
                startActivity(new Intent(this, EnvActivity.class));
                return true;
            case MENU_HISTORY:
                startActivity(new Intent(this, HistoryActivity.class));
                return true;
            case MENU_SETTINGS:
                SettingsDialog.show(this, store, null);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    // ---------------------------------------------------------------- environment

    private void refreshEnvs() {
        store.db(() -> {
            EnvDoc selected = store.envs.getSelected(); // tạo "Default" nếu chưa có
            final List<EnvDoc> all = store.envs.listEnvironments();
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id.equals(selected.id)) idx = i;
            }
            final int sel = idx;
            store.main(() -> bindEnvSpinner(all, sel));
        });
    }

    private void bindEnvSpinner(final List<EnvDoc> all, int selected) {
        envList = all;
        List<String> names = new ArrayList<>();
        for (EnvDoc e : all) names.add(e.name);
        b.envSpinner.setOnItemSelectedListener(null);
        ArrayAdapter<String> ea = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        ea.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        b.envSpinner.setAdapter(ea);
        b.envSpinner.setSelection(selected, false);
        shownEnvIndex = selected;
        b.envSpinner.post(() -> b.envSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == shownEnvIndex || pos >= envList.size()) return;
                shownEnvIndex = pos;
                final String envId = envList.get(pos).id;
                store.db(() -> store.envs.select(envId));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        }));
    }

    // ---------------------------------------------------------------- nút "+ Thêm"

    private void showAddMenu() {
        Ui.choose(this, "Thêm", Arrays.asList("Request mới", "Collection mới", "Import file / cURL…"), which -> {
            if (which == 0) newRequestSomewhere();
            else if (which == 1) {
                Ui.prompt(this, "Collection mới", "My Collection", "Tên collection", false, name -> {
                    final String n = name.trim().isEmpty() ? "My Collection" : name.trim();
                    store.db(() -> {
                        store.collections.createCollection(n);
                        vm.refresh();
                    });
                });
            } else {
                startActivity(new Intent(this, ImportActivity.class));
            }
        });
    }

    private void newRequestSomewhere() {
        final Map<String, String> paths = CollectionRepository.containerPaths(vm.lastTree());
        if (paths.isEmpty()) {
            Ui.prompt(this, "Request mới", "Request mới", "Tên request", false, name -> store.db(() -> {
                String cid = store.collections.createCollection("My Collection");
                Node n = store.collections.createRequest(cid, null, name.trim().isEmpty() ? "Request mới" : name.trim());
                store.main(() -> openRequest(n.id));
            }));
            return;
        }
        final List<String> ids = new ArrayList<>(paths.keySet());
        Ui.choose(this, "Lưu request vào", new ArrayList<>(paths.values()), which -> createRequestIn(ids.get(which)));
    }

    private void createRequestIn(final String containerId) {
        Ui.prompt(this, "Request mới", "Request mới", "Tên request", false, name -> {
            final String n = name.trim().isEmpty() ? "Request mới" : name.trim();
            store.db(() -> {
                String collectionId = collectionIdOf(containerId);
                String parent = containerId.equals(collectionId) ? null : containerId;
                Node node = store.collections.createRequest(collectionId, parent, n);
                vm.expand(containerId);
                store.main(() -> openRequest(node.id));
            });
        });
    }

    private String collectionIdOf(String id) {
        for (CollectionRepository.TreeItem t : vm.lastTree()) {
            if (t.id.equals(id)) return t.collectionId;
        }
        return id;
    }

    private void openRequest(String nodeId) {
        Intent i = new Intent(this, RequestActivity.class);
        i.putExtra(RequestActivity.EXTRA_NODE_ID, nodeId);
        startActivity(i);
    }

    // ---------------------------------------------------------------- tương tác với cây

    @Override
    public void onClick(TreeRow row) {
        if (row.isContainer()) vm.toggle(row.id);
        else if (TreeRow.REQUEST.equals(row.kind)) openRequest(row.id);
        else Ui.toast(this, "Phần tử này không phải request; dữ liệu vẫn được giữ nguyên khi export.");
    }

    @Override
    public void onLongClick(final TreeRow row) {
        final List<String> labels = new ArrayList<>();
        if (TreeRow.REQUEST.equals(row.kind)) labels.add("Mở");
        if (row.isContainer()) {
            labels.add("Thêm request");
            labels.add("Thêm folder");
            labels.add("Chạy tất cả request (Runner)");
        }
        labels.add("Đổi tên");
        labels.add("Nhân bản");
        if (!TreeRow.COLLECTION.equals(row.kind)) labels.add("Di chuyển…");
        labels.add("Lên");
        labels.add("Xuống");
        if (TreeRow.COLLECTION.equals(row.kind)) labels.add("Biến của collection");
        labels.add("Export…");
        labels.add("Xóa");

        Ui.choose(this, row.name.isEmpty() ? "(không tên)" : row.name, labels, which -> {
            switch (labels.get(which)) {
                case "Mở": openRequest(row.id); break;
                case "Thêm request": createRequestIn(row.id); break;
                case "Thêm folder": createFolder(row); break;
                case "Chạy tất cả request (Runner)": runAll(row); break;
                case "Đổi tên": rename(row); break;
                case "Nhân bản": store.db(() -> {
                    store.collections.duplicate(row.id);
                    vm.refresh();
                }); break;
                case "Di chuyển…": move(row); break;
                case "Lên": reorder(row, -1); break;
                case "Xuống": reorder(row, 1); break;
                case "Biến của collection": {
                    Intent i = new Intent(this, EnvActivity.class);
                    i.putExtra(EnvActivity.EXTRA_COLLECTION_ID, row.id);
                    startActivity(i);
                    break;
                }
                case "Export…": export(row); break;
                case "Xóa": delete(row); break;
                default: break;
            }
        });
    }

    private void createFolder(final TreeRow container) {
        Ui.prompt(this, "Folder mới", "Folder mới", "Tên folder", false, name -> {
            final String n = name.trim().isEmpty() ? "Folder mới" : name.trim();
            store.db(() -> {
                String parent = TreeRow.COLLECTION.equals(container.kind) ? null : container.id;
                store.collections.createFolder(container.collectionId, parent, n);
                vm.expand(container.id);
                vm.refresh();
            });
        });
    }

    private void runAll(TreeRow row) {
        Intent i = new Intent(this, RunnerActivity.class);
        i.putExtra(RunnerActivity.EXTRA_NODE_ID, row.id);
        startActivity(i);
    }

    private void rename(final TreeRow row) {
        Ui.prompt(this, "Đổi tên", row.name, "Tên mới", false, name -> {
            if (name.trim().isEmpty()) return;
            store.db(() -> {
                store.collections.rename(row.id, name.trim());
                vm.refresh();
            });
        });
    }

    private void delete(final TreeRow row) {
        String what = row.isContainer() ? "\"" + row.name + "\" và toàn bộ " + row.requestCount + " request bên trong"
                : "request \"" + row.name + "\"";
        Ui.confirm(this, "Xóa?", "Xóa " + what + "? Không thể hoàn tác.", "Xóa", () -> store.db(() -> {
            store.collections.delete(row.id);
            vm.refresh();
        }));
    }

    private void reorder(final TreeRow row, final int delta) {
        store.db(() -> {
            store.collections.moveUpDown(row.id, delta);
            vm.refresh();
        });
    }

    private void move(final TreeRow row) {
        final List<CollectionRepository.TreeItem> tree = vm.lastTree();
        final Set<String> banned = new HashSet<>();
        banned.add(row.id);
        boolean grew = true;
        while (grew) { // loại chính nó và mọi con cháu khỏi danh sách đích
            grew = false;
            for (CollectionRepository.TreeItem t : tree) {
                if (t.parentId != null && banned.contains(t.parentId) && banned.add(t.id)) grew = true;
            }
        }
        Map<String, String> all = CollectionRepository.containerPaths(tree);
        final Map<String, String> targets = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (!banned.contains(e.getKey())) targets.put(e.getKey(), e.getValue());
        }
        if (targets.isEmpty()) {
            Ui.toast(this, "Không có nơi nào để di chuyển tới.");
            return;
        }
        final List<String> ids = new ArrayList<>(targets.keySet());
        Ui.choose(this, "Di chuyển tới (đặt ở cuối)", new ArrayList<>(targets.values()), which -> {
            final String target = ids.get(which);
            store.db(() -> {
                String collectionId = collectionIdOf(target);
                String parent = target.equals(collectionId) ? null : target;
                boolean ok = store.collections.move(row.id, collectionId, parent);
                if (!ok) store.main(() -> Ui.toast(this, "Không thể di chuyển vào chính nó."));
                vm.expand(target);
                vm.refresh();
            });
        });
    }

    // ---------------------------------------------------------------- export

    private void export(final TreeRow row) {
        store.db(() -> {
            final CollectionDoc doc = store.collections.loadFull(row.collectionId);
            if (doc == null) return;
            final Node node = TreeRow.COLLECTION.equals(row.kind) ? null : find(doc.roots, row.id);
            boolean hasSecret = false;
            for (KeyValue kv : doc.vars().items()) {
                if (kv.isSecret()) hasSecret = true;
            }
            final boolean secrets = hasSecret;
            store.main(() -> {
                if (secrets) {
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("Collection có biến secret")
                            .setMessage("Có đưa giá trị secret vào file export không? File export là văn bản thường, "
                                    + "ai có file đều đọc được.")
                            .setPositiveButton("Có, kèm giá trị", (d, w) -> doExport(row, doc, node, true))
                            .setNeutralButton("Không, để trống", (d, w) -> doExport(row, doc, node, false))
                            .setNegativeButton("Hủy", null)
                            .show();
                } else {
                    doExport(row, doc, node, true);
                }
            });
        });
    }

    private void doExport(TreeRow row, CollectionDoc doc, Node node, boolean includeSecrets) {
        if (!includeSecrets) {
            for (KeyValue kv : doc.vars().items()) {
                if (kv.isSecret()) kv.value = "";
            }
        }
        pendingExportText = node == null ? PostmanExporter.exportCollection(doc) : PostmanExporter.exportNode(doc, node);
        String base = (row.name.isEmpty() ? "collection" : row.name).replaceAll("[\\\\/:*?\"<>|]", "_");
        exportLauncher.launch(base + ".postman_collection.json");
    }

    private static Node find(List<Node> nodes, String id) {
        for (Node n : nodes) {
            if (n.id.equals(id)) return n;
            Node f = find(n.children, id);
            if (f != null) return f;
        }
        return null;
    }

    private void writeExport(final Uri uri) {
        final String text = pendingExportText;
        pendingExportText = null;
        if (uri == null || text == null) return;
        store.db(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Không ghi được file");
                out.write(text.getBytes(StandardCharsets.UTF_8));
                store.main(() -> Ui.toast(this, "Đã xuất file (" + Ui.formatSize(text.length()) + ")"));
            } catch (IOException e) {
                store.main(() -> Ui.toast(this, "Xuất file lỗi: " + e.getMessage()));
            }
        });
    }
}
