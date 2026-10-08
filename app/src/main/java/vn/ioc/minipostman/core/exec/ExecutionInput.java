package vn.ioc.minipostman.core.exec;

import java.util.ArrayList;
import java.util.List;

import vn.ioc.minipostman.core.model.CollectionDoc;
import vn.ioc.minipostman.core.model.EnvDoc;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.core.model.VarScope;
import vn.ioc.minipostman.core.net.HttpEngine;
import vn.ioc.minipostman.core.net.HttpSettings;
import vn.ioc.minipostman.core.request.RequestModel;

/** Mọi thứ cần để chạy một request: ngữ cảnh cây, phạm vi biến, cài đặt. */
public final class ExecutionInput {

    public CollectionDoc collection;
    /** Các folder cha, từ ngoài vào trong (raw không chứa con). */
    public List<Node> ancestors = new ArrayList<>();
    public String requestId = "";
    public RequestModel model;
    /** Quy tắc "Set biến từ response" kiểu cũ; để trống nếu không dùng. */
    public String legacyExtract = "";
    public EnvDoc environment;
    public EnvDoc globals;
    /** Dữ liệu của lượt lặp hiện tại (Collection Runner); có thể null. */
    public VarScope data;
    public int iteration;
    public int iterationCount = 1;
    public HttpSettings settings = new HttpSettings();
    public HttpEngine.CancelToken cancel;
}
