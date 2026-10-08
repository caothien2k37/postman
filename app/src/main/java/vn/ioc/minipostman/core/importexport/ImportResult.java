package vn.ioc.minipostman.core.importexport;

import java.util.ArrayList;
import java.util.List;

import vn.ioc.minipostman.core.model.CollectionDoc;
import vn.ioc.minipostman.core.model.EnvDoc;

/** Kết quả phân tích một nguồn import, dùng cho màn hình xem trước trước khi ghi vào DB. */
public final class ImportResult {

    public enum Kind { COLLECTION, ENVIRONMENT, GLOBALS, CURL }

    public Kind kind;
    public String sourceName = "";
    public String name = "";
    public String schema = "";
    public int folders;
    public int requests;
    public int unknownItems;
    public int scripts;
    public int variables;
    public int examples;
    public int maxDepth;
    public final List<String> warnings = new ArrayList<>();
    public CollectionDoc collection;
    public EnvDoc environment;

    public boolean isCollection() {
        return kind == Kind.COLLECTION || kind == Kind.CURL;
    }
}
