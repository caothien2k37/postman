package vn.ioc.minipostman;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.data.HistoryRepository;
import vn.ioc.minipostman.databinding.ActivityHistoryBinding;
import vn.ioc.minipostman.databinding.RowHistoryBinding;
import vn.ioc.minipostman.ui.Ui;

/** Lịch sử các lượt gọi gần đây: mở lại hoặc khôi phục đúng bản request đã gửi. */
public class HistoryActivity extends AppCompatActivity {

    private static final int MENU_CLEAR = 1;
    private static final String RESTORED_COLLECTION = "Khôi phục từ lịch sử";

    private ActivityHistoryBinding b;
    private Store store;
    private final List<HistoryRepository.Entry> entries = new ArrayList<>();
    private ArrayAdapter<HistoryRepository.Entry> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityHistoryBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        b.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        b.toolbar.setNavigationOnClickListener(v -> finish());
        store = Store.get(this);

        adapter = new ArrayAdapter<HistoryRepository.Entry>(this, 0, entries) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                RowHistoryBinding r = convertView == null
                        ? RowHistoryBinding.inflate(LayoutInflater.from(getContext()), parent, false)
                        : RowHistoryBinding.bind(convertView);
                HistoryRepository.Entry e = getItem(position);
                r.method.setText(e.method);
                r.method.setTextColor(Ui.methodColor(getContext(), e.method));
                r.name.setText(e.name.isEmpty() ? "(không tên)" : e.name);
                r.url.setText(e.url);
                r.status.setText(e.status == 0 ? "Lỗi" : String.valueOf(e.status));
                r.status.setTextColor(Ui.statusColor(getContext(), e.status));
                r.meta.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(e.timestamp))
                        + (e.status == 0 ? "" : String.format(Locale.US, "  •  %d ms  •  %s", e.elapsedMs, Ui.formatSize(e.size))));
                return r.getRoot();
            }
        };
        b.list.setAdapter(adapter);
        b.list.setEmptyView(b.empty);
        b.list.setOnItemClickListener((p, v, pos, id) -> open(entries.get(pos)));
        b.list.setOnItemLongClickListener((p, v, pos, id) -> {
            final HistoryRepository.Entry e = entries.get(pos);
            Ui.confirm(this, "Xóa khỏi lịch sử?", e.method + " " + e.name, "Xóa", () -> store.db(() -> {
                store.history.delete(e.id);
                reload();
            }));
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_CLEAR, 0, "Xóa tất cả");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_CLEAR) {
            Ui.confirm(this, "Xóa toàn bộ lịch sử?", "Không thể hoàn tác.", "Xóa", () -> store.db(() -> {
                store.history.clear();
                reload();
            }));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void reload() {
        store.db(() -> {
            final List<HistoryRepository.Entry> list = store.history.list();
            store.main(() -> {
                entries.clear();
                entries.addAll(list);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void open(final HistoryRepository.Entry e) {
        store.db(() -> {
            final boolean exists = e.nodeId != null && store.collections.loadNode(e.nodeId) != null;
            store.main(() -> {
                if (!exists) {
                    restore(e);
                    return;
                }
                Ui.choose(this, e.name, Arrays.asList("Mở request hiện tại", "Khôi phục đúng bản đã gửi (tạo request mới)"),
                        which -> {
                            if (which == 0) startRequest(e.nodeId);
                            else restore(e);
                        });
            });
        });
    }

    private void restore(final HistoryRepository.Entry e) {
        store.db(() -> {
            try {
                JsonObject raw = Json.parseObject(e.requestJson);
                Node n = new Node();
                n.type = Node.Type.REQUEST;
                n.raw = raw;
                n.name = Json.str(raw, "name", e.name);
                String cid = store.collections.findCollectionByName(RESTORED_COLLECTION);
                if (cid == null) cid = store.collections.createCollection(RESTORED_COLLECTION);
                store.collections.addSubtree(cid, null, n);
                final String id = n.id;
                store.main(() -> startRequest(id));
            } catch (RuntimeException ex) {
                store.main(() -> Ui.toast(this, "Không khôi phục được request này."));
            }
        });
    }

    private void startRequest(String nodeId) {
        Intent i = new Intent(this, RequestActivity.class);
        i.putExtra(RequestActivity.EXTRA_NODE_ID, nodeId);
        startActivity(i);
    }
}
