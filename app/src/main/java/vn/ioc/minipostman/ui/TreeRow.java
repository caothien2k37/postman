package vn.ioc.minipostman.ui;

import java.util.Objects;

/** Một dòng hiển thị của cây (chỉ tồn tại ở tầng giao diện; dữ liệu gốc không bị làm phẳng). */
public final class TreeRow {

    public static final String COLLECTION = "collection";
    public static final String FOLDER = "folder";
    public static final String REQUEST = "request";
    public static final String UNKNOWN = "unknown";

    public final String id;
    public final String collectionId;
    public final String parentId;
    public final String kind;
    public final String name;
    public final String method;
    public final String url;
    public final int depth;
    public final boolean expanded;
    public final boolean expandable;
    public final int requestCount;

    public TreeRow(String id, String collectionId, String parentId, String kind, String name, String method,
                   String url, int depth, boolean expanded, boolean expandable, int requestCount) {
        this.id = id;
        this.collectionId = collectionId;
        this.parentId = parentId;
        this.kind = kind;
        this.name = name;
        this.method = method;
        this.url = url;
        this.depth = depth;
        this.expanded = expanded;
        this.expandable = expandable;
        this.requestCount = requestCount;
    }

    public boolean isContainer() {
        return COLLECTION.equals(kind) || FOLDER.equals(kind);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TreeRow)) return false;
        TreeRow r = (TreeRow) o;
        return id.equals(r.id) && kind.equals(r.kind) && name.equals(r.name) && method.equals(r.method)
                && depth == r.depth && expanded == r.expanded && expandable == r.expandable
                && requestCount == r.requestCount && Objects.equals(parentId, r.parentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, kind, name, method, depth, expanded, expandable, requestCount, parentId);
    }
}
