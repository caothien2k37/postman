package vn.ioc.minipostman;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import vn.ioc.minipostman.core.Json;
import vn.ioc.minipostman.core.exec.ExecutionInput;
import vn.ioc.minipostman.core.exec.ExecutionResult;
import vn.ioc.minipostman.core.exec.RequestExecutor;
import vn.ioc.minipostman.core.model.EnvDoc;
import vn.ioc.minipostman.core.model.SecretCodec;
import vn.ioc.minipostman.core.model.VarScope;
import vn.ioc.minipostman.core.net.HttpEngine;
import vn.ioc.minipostman.core.request.RequestModel;
import vn.ioc.minipostman.core.script.JsSandbox;
import vn.ioc.minipostman.data.AppDb;
import vn.ioc.minipostman.data.AppSettings;
import vn.ioc.minipostman.data.CollectionRepository;
import vn.ioc.minipostman.data.EnvRepository;
import vn.ioc.minipostman.data.HistoryRepository;
import vn.ioc.minipostman.data.KeystoreSecretCodec;
import vn.ioc.minipostman.data.LegacyMigration;

/**
 * Điểm truy cập chung tới dữ liệu và bộ chạy request. Thao tác DB chạy tuần tự trên một thread riêng
 * ({@link #db}), request mạng chạy trên pool khác ({@link #net}) để không chặn nhau và không chặn UI.
 */
public final class Store {

    private static Store instance;

    public final AppDb database;
    public final SecretCodec codec;
    public final CollectionRepository collections;
    public final EnvRepository envs;
    public final HistoryRepository history;
    public final AppSettings settings;
    public final HttpEngine engine;
    public final RequestExecutor executor;

    private final ExecutorService dbExec = Executors.newSingleThreadExecutor();
    private final ExecutorService netExec = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static synchronized Store get(Context context) {
        if (instance == null) instance = new Store(context.getApplicationContext());
        return instance;
    }

    /** Dùng trong test để mỗi test có một Store (và DB) mới. */
    static synchronized void resetForTests() {
        instance = null;
    }

    private Store(final Context app) {
        database = new AppDb(app);
        codec = new KeystoreSecretCodec();
        collections = new CollectionRepository(database, codec);
        envs = new EnvRepository(database, codec);
        history = new HistoryRepository(database);
        settings = new AppSettings(app);
        engine = new HttpEngine();
        executor = new RequestExecutor(engine, new JsSandbox());
        // Việc đầu tiên trên thread DB: chuyển dữ liệu của bản cũ (nếu có) trước mọi thao tác khác.
        dbExec.execute(() -> LegacyMigration.run(app, collections, envs));
    }

    /** Chạy việc liên quan DB trên thread nền (tuần tự). */
    public void db(Runnable r) {
        dbExec.execute(r);
    }

    /** Chạy việc mạng/script trên pool riêng. */
    public void net(Runnable r) {
        netExec.execute(r);
    }

    public void main(Runnable r) {
        mainHandler.post(r);
    }

    /**
     * Chạy một request: dùng environment đang chọn + globals, rồi lưu lại các phạm vi biến mà script đã đổi
     * và ghi vào history. Gọi từ thread nền.
     */
    public ExecutionResult execute(CollectionRepository.Context ctx, RequestModel model, String legacyExtract,
                                   VarScope data, int iteration, int iterationCount, HttpEngine.CancelToken cancel) {
        EnvDoc env = envs.getSelected();
        EnvDoc globals = envs.getGlobals();

        ExecutionInput in = new ExecutionInput();
        in.collection = ctx.collection;
        in.ancestors = ctx.ancestors;
        in.requestId = ctx.node.id;
        in.model = model;
        in.legacyExtract = legacyExtract;
        in.environment = env;
        in.globals = globals;
        in.data = data;
        in.iteration = iteration;
        in.iterationCount = iterationCount;
        in.settings = settings.load();
        in.cancel = cancel;

        ExecutionResult res = executor.run(in);

        if (res.envChanged) envs.save(env);
        if (res.globalsChanged) envs.save(globals);
        if (res.collectionChanged) collections.saveCollection(ctx.collection);

        if (res.sent) {
            HistoryRepository.Entry h = new HistoryRepository.Entry();
            h.nodeId = ctx.node.id;
            h.collectionId = ctx.collection.id;
            h.name = model.name;
            h.method = model.method;
            h.url = model.url;
            h.timestamp = System.currentTimeMillis();
            boolean ok = res.response != null && !res.response.isError();
            h.status = ok ? res.response.code : 0;
            h.elapsedMs = ok ? res.response.timeMs : 0;
            h.size = ok ? res.response.size : 0;
            h.requestJson = Json.toJson(model.toItem());
            history.add(h);
        }
        return res;
    }
}
