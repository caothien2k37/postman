package vn.ioc.minipostman.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import vn.ioc.minipostman.Store;
import vn.ioc.minipostman.data.CollectionRepository;
import vn.ioc.minipostman.data.CollectionRepository.TreeItem;

/**
 * Giữ trạng thái cây collection (mở/đóng, tìm kiếm, lọc method) và dựng danh sách "visible rows".
 * Cây gốc trong DB không bao giờ bị làm phẳng; chỉ danh sách hiển thị mới phẳng.
 */
public class CollectionViewModel extends AndroidViewModel {

    private final Store store;
    private final MutableLiveData<List<TreeRow>> rows = new MutableLiveData<>(Collections.<TreeRow>emptyList());
    private final Set<String> expanded = new HashSet<>();
    private final Set<String> known = new HashSet<>();
    private volatile List<TreeItem> tree = new ArrayList<>();
    private volatile String query = "";
    private volatile String method = "";

    public CollectionViewModel(@NonNull Application app) {
        super(app);
        store = Store.get(app);
    }

    public LiveData<List<TreeRow>> rows() {
        return rows;
    }

    public List<TreeItem> lastTree() {
        return tree;
    }

    public void refresh() {
        store.db(() -> {
            tree = store.collections.loadTree();
            rebuild();
        });
    }

    public void setQuery(String q) {
        query = q == null ? "" : q.trim();
        store.db(this::rebuild);
    }

    public void setMethodFilter(String m) {
        method = m == null ? "" : m;
        store.db(this::rebuild);
    }

    public void toggle(String id) {
        synchronized (expanded) {
            if (!expanded.remove(id)) expanded.add(id);
        }
        store.db(this::rebuild);
    }

    public void expand(String id) {
        synchronized (expanded) {
            expanded.add(id);
        }
    }

    private boolean isExpanded(String id) {
        synchronized (expanded) {
            return expanded.contains(id);
        }
    }

    // ---------------------------------------------------------------- dựng danh sách hiển thị

    private void rebuild() {
        final List<TreeItem> items = tree;
        final String q = query.toLowerCase(Locale.ROOT);
        final String m = method;
        final boolean filtering = !q.isEmpty() || !m.isEmpty();

        Map<String, List<TreeItem>> children = new HashMap<>();
        List<TreeItem> collections = new ArrayList<>();
        for (TreeItem t : items) {
            if (CollectionRepository.TYPE_COLLECTION.equals(t.type)) {
                collections.add(t);
                synchronized (expanded) {
                    if (known.add(t.id)) expanded.add(t.id); // lần đầu thấy collection: mở sẵn
                }
            } else {
                String key = t.parentId != null ? t.parentId : "c:" + t.collectionId;
                List<TreeItem> list = children.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    children.put(key, list);
                }
                list.add(t);
            }
        }

        Builder b = new Builder(children, q, m, filtering);
        List<TreeRow> out = new ArrayList<>();
        for (TreeItem c : collections) {
            List<TreeItem> kids = children.get("c:" + c.id);
            if (filtering && !b.subtreeMatches(c, kids)) continue;
            boolean open = filtering || isExpanded(c.id);
            out.add(new TreeRow(c.id, c.id, null, TreeRow.COLLECTION, c.name, "", "", 0, open,
                    kids != null && !kids.isEmpty(), b.requests(kids)));
            if (open && kids != null) b.addRows(kids, 1, out);
        }
        rows.postValue(out);
    }

    private final class Builder {
        private final Map<String, List<TreeItem>> children;
        private final String q;
        private final String m;
        private final boolean filtering;
        private final Map<String, Boolean> matchMemo = new HashMap<>();
        private final Map<String, Integer> countMemo = new HashMap<>();

        Builder(Map<String, List<TreeItem>> children, String q, String m, boolean filtering) {
            this.children = children;
            this.q = q;
            this.m = m;
            this.filtering = filtering;
        }

        boolean selfMatches(TreeItem t) {
            boolean textOk = q.isEmpty() || t.name.toLowerCase(Locale.ROOT).contains(q)
                    || t.url.toLowerCase(Locale.ROOT).contains(q);
            if ("request".equals(t.type)) return textOk && (m.isEmpty() || m.equalsIgnoreCase(t.method));
            if (!m.isEmpty()) return false;
            return !q.isEmpty() && textOk;
        }

        boolean subtreeMatches(TreeItem t, List<TreeItem> kids) {
            Boolean memo = matchMemo.get(t.id);
            if (memo != null) return memo;
            boolean result = selfMatches(t);
            if (!result && kids != null) {
                for (TreeItem k : kids) {
                    if (subtreeMatches(k, children.get(k.id))) {
                        result = true;
                        break;
                    }
                }
            }
            matchMemo.put(t.id, result);
            return result;
        }

        int requests(List<TreeItem> kids) {
            int n = 0;
            if (kids == null) return 0;
            for (TreeItem k : kids) {
                if ("request".equals(k.type)) {
                    n++;
                } else if ("folder".equals(k.type)) {
                    Integer memo = countMemo.get(k.id);
                    if (memo == null) {
                        memo = requests(children.get(k.id));
                        countMemo.put(k.id, memo);
                    }
                    n += memo;
                }
            }
            return n;
        }

        void addRows(List<TreeItem> kids, int depth, List<TreeRow> out) {
            for (TreeItem t : kids) {
                List<TreeItem> sub = children.get(t.id);
                if (filtering && !subtreeMatches(t, sub)) continue;
                boolean isFolder = "folder".equals(t.type);
                boolean open = isFolder && (filtering || isExpanded(t.id));
                out.add(new TreeRow(t.id, t.collectionId, t.parentId, t.type, t.name, t.method, t.url, depth, open,
                        isFolder && sub != null && !sub.isEmpty(), isFolder ? requests(sub) : 0));
                if (open && sub != null) addRows(sub, depth + 1, out);
            }
        }
    }
}
