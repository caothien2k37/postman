package vn.ioc.minipostman;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import vn.ioc.minipostman.core.Strings;
import vn.ioc.minipostman.core.exec.ExecutionResult;
import vn.ioc.minipostman.core.importexport.DataFile;
import vn.ioc.minipostman.core.importexport.ImportException;
import vn.ioc.minipostman.core.model.CollectionDoc;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.core.model.VarScope;
import vn.ioc.minipostman.core.net.HttpEngine;
import vn.ioc.minipostman.core.request.RequestModel;
import vn.ioc.minipostman.core.script.JsSandbox;
import vn.ioc.minipostman.data.CollectionRepository;
import vn.ioc.minipostman.databinding.ActivityRunnerBinding;
import vn.ioc.minipostman.ui.Ui;

/**
 * Collection Runner: chạy lần lượt các request của collection/folder theo thứ tự cây, có số lần lặp,
 * dữ liệu CSV/JSON, biến lưu qua từng bước, tổng hợp đạt/lỗi, dừng giữa chừng và giới hạn tổng số lượt chạy.
 */
public class RunnerActivity extends AppCompatActivity {

    public static final String EXTRA_NODE_ID = "node_id";
    private static final int MAX_EXECUTIONS = 1000;

    private static final class Step {
        final Node node;
        final List<Node> ancestors;

        Step(Node node, List<Node> ancestors) {
            this.node = node;
            this.ancestors = ancestors;
        }
    }

    private static final class Line {
        int iteration;
        String method = "";
        String name = "";
        String summary = "";
        boolean ok;
        String detail = "";
    }

    private ActivityRunnerBinding b;
    private Store store;
    private String nodeId;
    private List<Map<String, String>> data;
    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile HttpEngine.CancelToken cancel;
    private final List<Line> lines = new ArrayList<>();
    private ArrayAdapter<Line> adapter;

    private final ActivityResultLauncher<String[]> dataLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onDataPicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityRunnerBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        b.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        b.toolbar.setNavigationOnClickListener(v -> finish());
        store = Store.get(this);
        nodeId = getIntent().getStringExtra(EXTRA_NODE_ID);

        adapter = new ArrayAdapter<Line>(this, 0, lines) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                TextView t = convertView instanceof TextView ? (TextView) convertView : new TextView(getContext());
                Line l = getItem(position);
                t.setPadding(Ui.dp(getContext(), 12), Ui.dp(getContext(), 10), Ui.dp(getContext(), 12), Ui.dp(getContext(), 10));
                t.setText((l.ok ? "✓ " : "✗ ") + "#" + (l.iteration + 1) + "  " + l.method + " " + l.name + "\n" + l.summary);
                t.setTextColor(getResources().getColor(l.ok ? R.color.text : R.color.error, getTheme()));
                t.setTextSize(13);
                return t;
            }
        };
        b.list.setAdapter(adapter);
        b.list.setOnItemClickListener((p, v, pos, id) -> new MaterialAlertDialogBuilder(this)
                .setTitle(lines.get(pos).name)
                .setMessage(lines.get(pos).detail.isEmpty() ? "(không có chi tiết)" : lines.get(pos).detail)
                .setPositiveButton("Đóng", null)
                .show());

        b.btnData.setOnClickListener(v -> dataLauncher.launch(new String[]{"*/*"}));
        b.btnRun.setOnClickListener(v -> {
            if (running) stop();
            else start();
        });

        store.db(() -> {
            final String envName = store.envs.getSelected().name;
            String label = "(không rõ)";
            for (CollectionRepository.TreeItem t : store.collections.loadTree()) {
                if (t.id.equals(nodeId)) label = t.name;
            }
            final String l = label;
            store.main(() -> {
                b.target.setText("Chạy: " + l);
                b.envInfo.setText("Môi trường: " + envName + " (đổi ở màn hình chính). Biến do script đặt được lưu qua từng bước.");
            });
        });
    }

    private void onDataPicked(final Uri uri) {
        if (uri == null) return;
        store.net(() -> {
            try {
                String text = PostmanImporter.readUri(getContentResolver(), uri);
                final String name = PostmanImporter.displayName(getContentResolver(), uri);
                final List<Map<String, String>> rows = DataFile.parse(text, name);
                store.main(() -> {
                    data = rows;
                    b.dataInfo.setText("Dữ liệu: " + name + " — " + rows.size() + " dòng (mỗi dòng là một lượt lặp)");
                    b.iterations.setText(String.valueOf(rows.size()));
                });
            } catch (ImportException | java.io.IOException e) {
                store.main(() -> Ui.toast(this, "Không đọc được dữ liệu: " + e.getMessage()));
            }
        });
    }

    private void stop() {
        stopRequested = true;
        HttpEngine.CancelToken c = cancel;
        if (c != null) c.cancel();
        b.btnRun.setEnabled(false);
        b.summary.setText("Đang dừng…");
    }

    private void start() {
        int n = 1;
        try {
            n = Integer.parseInt(b.iterations.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            // dùng mặc định 1
        }
        final int iterations = Math.max(1, Math.min(n, 1000));
        final List<Map<String, String>> rows = data;
        running = true;
        stopRequested = false;
        lines.clear();
        adapter.notifyDataSetChanged();
        b.btnRun.setText("Stop");
        b.summary.setText("Đang chạy…");

        store.net(() -> {
            int reqRun = 0;
            int reqOk = 0;
            int testPass = 0;
            int testFail = 0;
            String stopReason = "";
            try {
                CollectionRepository.TreeItem target = null;
                for (CollectionRepository.TreeItem t : store.collections.loadTree()) {
                    if (t.id.equals(nodeId)) target = t;
                }
                if (target == null) {
                    finishRun("Không tìm thấy mục cần chạy.", 0, 0, 0, 0);
                    return;
                }
                CollectionDoc doc = store.collections.loadFull(target.collectionId);
                List<Step> steps = new ArrayList<>();
                if (CollectionRepository.TYPE_COLLECTION.equals(target.type)) {
                    collect(doc.roots, new ArrayList<Node>(), steps);
                } else {
                    collectFrom(doc.roots, nodeId, new ArrayList<Node>(), steps);
                }
                if (steps.isEmpty()) {
                    finishRun("Không có request nào để chạy.", 0, 0, 0, 0);
                    return;
                }

                int executions = 0;
                outer:
                for (int iter = 0; iter < iterations && !stopRequested; iter++) {
                    VarScope scope = (rows != null && iter < rows.size()) ? VarScope.fromMap(VarScope.DATA, rows.get(iter)) : null;
                    int i = 0;
                    while (i < steps.size() && !stopRequested) {
                        if (++executions > MAX_EXECUTIONS) {
                            stopReason = "Dừng vì vượt giới hạn " + MAX_EXECUTIONS + " lượt gọi.";
                            break outer;
                        }
                        Step s = steps.get(i);
                        CollectionRepository.Context ctx = new CollectionRepository.Context();
                        ctx.collection = doc;
                        ctx.ancestors = s.ancestors;
                        ctx.node = s.node;
                        cancel = new HttpEngine.CancelToken();
                        ExecutionResult r = store.execute(ctx, RequestModel.parse(s.node.raw), s.node.extract, scope, iter,
                                iterations, cancel);

                        reqRun++;
                        if (r.ok()) reqOk++;
                        testPass += r.passed();
                        testFail += r.failed();
                        final Line line = describe(iter, s.node, r);
                        final int rr = reqRun;
                        final int ro = reqOk;
                        final int tp = testPass;
                        final int tf = testFail;
                        store.main(() -> {
                            lines.add(line);
                            adapter.notifyDataSetChanged();
                            b.list.setSelection(lines.size() - 1);
                            b.summary.setText("Đang chạy… " + rr + " request • " + ro + " đạt • tests " + tp + "/" + (tp + tf));
                        });

                        if (r.nextRequestSet) {
                            if (r.nextRequest == null) {
                                stopReason = "Dừng theo script (setNextRequest(null)).";
                                break outer;
                            }
                            int jump = -1;
                            for (int k = 0; k < steps.size(); k++) {
                                if (steps.get(k).node.name.equals(r.nextRequest)) {
                                    jump = k;
                                    break;
                                }
                            }
                            if (jump >= 0) {
                                i = jump;
                                continue;
                            }
                        }
                        i++;
                    }
                }
                if (stopRequested && stopReason.isEmpty()) stopReason = "Đã dừng theo yêu cầu.";
            } catch (RuntimeException e) {
                stopReason = "Lỗi: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            finishRun(stopReason, reqRun, reqOk, testPass, testFail);
        });
    }

    private void finishRun(final String reason, final int run, final int ok, final int tp, final int tf) {
        store.main(() -> {
            running = false;
            cancel = null;
            b.btnRun.setEnabled(true);
            b.btnRun.setText("Run");
            String s = run + " request • " + ok + " đạt • " + (run - ok) + " lỗi • tests " + tp + " đạt / " + tf + " lỗi";
            b.summary.setText(reason.isEmpty() ? "Xong: " + s : s + "\n" + reason);
        });
    }

    private static Line describe(int iter, Node node, ExecutionResult r) {
        Line l = new Line();
        l.iteration = iter;
        l.method = node.method();
        l.name = node.name.isEmpty() ? node.url() : node.name;
        l.ok = r.ok();
        StringBuilder detail = new StringBuilder();
        if (r.response != null && !r.response.isError()) {
            l.summary = r.response.statusLine() + " • " + r.response.timeMs + " ms";
            if (!r.tests.isEmpty()) l.summary += " • tests " + r.passed() + "/" + r.tests.size();
        } else if (!r.errors.isEmpty()) {
            l.summary = r.errors.get(0);
        } else {
            l.summary = "Không gửi được";
        }
        if (!r.requestLine.isEmpty()) detail.append("→ ").append(r.requestLine).append("\n\n");
        for (JsSandbox.TestResult t : r.tests) {
            detail.append(t.passed ? "✓ " : "✗ ").append(t.name);
            if (!t.message.isEmpty()) detail.append("\n    ").append(t.message);
            detail.append('\n');
        }
        for (String e : r.errors) detail.append("✗ ").append(e).append('\n');
        for (String w : r.warnings) detail.append("⚠ ").append(w).append('\n');
        if (!r.console.isEmpty()) detail.append('\n').append(Strings.join("\n", r.console));
        l.detail = detail.toString();
        return l;
    }

    private static void collect(List<Node> nodes, List<Node> ancestors, List<Step> out) {
        for (Node n : nodes) {
            if (n.type == Node.Type.REQUEST) {
                out.add(new Step(n, new ArrayList<>(ancestors)));
            } else if (n.isFolder()) {
                ancestors.add(n);
                collect(n.children, ancestors, out);
                ancestors.remove(ancestors.size() - 1);
            }
        }
    }

    /** Tìm nút id trong cây rồi gom các request của nó (hoặc chính nó nếu là request). */
    private static boolean collectFrom(List<Node> nodes, String id, List<Node> ancestors, List<Step> out) {
        for (Node n : nodes) {
            if (n.id.equals(id)) {
                if (n.type == Node.Type.REQUEST) {
                    out.add(new Step(n, new ArrayList<>(ancestors)));
                } else if (n.isFolder()) {
                    ancestors.add(n);
                    collect(n.children, ancestors, out);
                    ancestors.remove(ancestors.size() - 1);
                }
                return true;
            }
            if (n.isFolder()) {
                ancestors.add(n);
                boolean found = collectFrom(n.children, id, ancestors, out);
                ancestors.remove(ancestors.size() - 1);
                if (found) return true;
            }
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRequested = true;
        HttpEngine.CancelToken c = cancel;
        if (c != null) c.cancel();
    }
}
