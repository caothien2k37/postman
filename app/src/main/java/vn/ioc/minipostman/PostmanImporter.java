package vn.ioc.minipostman;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import vn.ioc.minipostman.core.importexport.ImportResult;
import vn.ioc.minipostman.core.importexport.PostmanParser;
import vn.ioc.minipostman.core.model.EnvDoc;
import vn.ioc.minipostman.core.model.KeyValue;
import vn.ioc.minipostman.core.model.Node;
import vn.ioc.minipostman.core.request.RequestModel;
import vn.ioc.minipostman.data.CollectionRepository;

/** Điều phối import ở tầng ứng dụng: đọc nguồn (file/URI), xử lý trùng tên và ghi vào DB. */
public final class PostmanImporter {

    /** Cách xử lý khi trùng tên (không bao giờ tự động ghi đè). */
    public enum Conflict { COPY, REPLACE, SKIP }

    private static final String DEFAULT_EXTRACT = "accessToken = data.accessToken\nsessionId = data.sessionId";

    private PostmanImporter() {
    }

    /** Đọc nội dung một URI (SAF), từ chối file lớn hơn giới hạn. */
    public static String readUri(ContentResolver cr, Uri uri) throws IOException {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Không mở được file");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            long limit = PostmanParser.MAX_CHARS * 2L;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) {
                    throw new IOException("File quá lớn (tối đa " + (PostmanParser.MAX_CHARS / 1024 / 1024) + " MB)");
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (RuntimeException ignored) {
            // Một số provider không hỗ trợ truy vấn tên.
        }
        String last = uri.getLastPathSegment();
        return last == null ? "import.json" : last;
    }

    /** Có trùng tên với dữ liệu đang có không (gọi từ thread DB). */
    public static boolean hasConflict(Store store, ImportResult r) {
        if (r.isCollection()) return store.collections.findCollectionByName(r.name) != null;
        if (r.kind == ImportResult.Kind.ENVIRONMENT) return store.envs.findByName(r.name) != null;
        return false;
    }

    /** Ghi kết quả import vào DB và trả về thông báo cho người dùng (gọi từ thread DB). */
    public static String commit(Store store, ImportResult r, Conflict conflict) {
        boolean exists = hasConflict(store, r);
        if (exists && conflict == Conflict.SKIP) return "Bỏ qua \"" + r.name + "\" (đã tồn tại)";

        if (r.isCollection()) {
            applyLegacyDefaults(r.collection.roots);
            store.collections.importCollection(r.collection,
                    conflict == Conflict.REPLACE ? CollectionRepository.ConflictMode.REPLACE
                            : CollectionRepository.ConflictMode.COPY);
            return "Đã import \"" + r.collection.name + "\": " + r.folders + " folder, " + r.requests + " request"
                    + (r.scripts > 0 ? ", " + r.scripts + " script" : "");
        }

        if (r.kind == ImportResult.Kind.GLOBALS) {
            EnvDoc g = store.envs.getGlobals();
            int n = 0;
            for (KeyValue kv : r.environment.vars.items()) {
                if (!kv.enabled || kv.key.isEmpty()) continue;
                g.vars.set(kv.key, kv.value);
                if (kv.isSecret()) {
                    for (KeyValue mine : g.vars.items()) {
                        if (mine.key.equals(kv.key)) mine.type = "secret";
                    }
                }
                n++;
            }
            store.envs.save(g);
            return "Đã nhập " + n + " biến vào Globals";
        }

        EnvDoc doc = r.environment;
        if (exists && conflict == Conflict.REPLACE) {
            EnvDoc old = store.envs.findByName(r.name);
            if (old != null) {
                doc.id = old.id;
                doc.selected = old.selected;
                store.envs.save(doc);
                store.envs.select(doc.id);
            }
        } else {
            store.envs.add(doc, true);
        }
        return "Đã import environment \"" + doc.name + "\" (" + r.variables + " biến) và chọn làm môi trường hiện tại";
    }

    /**
     * Như bản 1.x: request có "login", "token" hoặc "đăng nhập" trong tên/URL mà chưa có script post-response
     * được điền sẵn quy tắc "Set biến từ response". Chỉ lưu ở cột extract, không đổi JSON Postman.
     */
    private static void applyLegacyDefaults(List<Node> nodes) {
        for (Node n : nodes) {
            if (n.type == Node.Type.REQUEST && n.extract.isEmpty()
                    && RequestModel.scriptOf(n.raw, "test").trim().isEmpty()) {
                String lower = (n.url() + " " + n.name).toLowerCase(Locale.ROOT);
                if (lower.contains("login") || lower.contains("token") || lower.contains("đăng nhập")) {
                    n.extract = DEFAULT_EXTRACT;
                }
            }
            applyLegacyDefaults(n.children);
        }
    }
}
