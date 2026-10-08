package vn.ioc.minipostman.ui;

import android.content.Context;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import vn.ioc.minipostman.Store;
import vn.ioc.minipostman.core.net.HttpSettings;

/** Cài đặt chung: timeout, redirect, kiểm tra SSL, pm.sendRequest, cookie. Lựa chọn kém an toàn có cảnh báo. */
public final class SettingsDialog {

    private SettingsDialog() {
    }

    public static void show(final Context c, final Store store, final Runnable onSaved) {
        final HttpSettings s = store.settings.load();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 20);
        box.setPadding(pad, Ui.dp(c, 8), pad, 0);

        final EditText connect = number(c, "Timeout kết nối (giây)", s.connectTimeoutSec, box);
        final EditText read = number(c, "Timeout đọc/ghi (giây)", s.readTimeoutSec, box);
        final CheckBox follow = check(c, "Tự theo redirect (3xx)", s.followRedirects, box);
        final CheckBox ssl = check(c, "Kiểm tra chứng chỉ SSL/TLS (khuyến nghị bật)", s.verifySsl, box);
        final CheckBox script = check(c, "Cho phép script gọi pm.sendRequest", s.allowScriptRequests, box);

        TextView note = new TextView(c);
        note.setText("Tắt kiểm tra SSL chỉ nên dùng với server thử nghiệm tự ký trong mạng tin cậy: kẻ tấn công có thể "
                + "đọc/sửa dữ liệu (kể cả token). Script của collection import về chỉ gọi mạng qua pm.sendRequest "
                + "(tối đa 10 lần mỗi script); tắt tùy chọn trên nếu bạn không tin collection.");
        note.setTextSize(12);
        note.setPadding(0, Ui.dp(c, 8), 0, 0);
        box.addView(note);

        new MaterialAlertDialogBuilder(c)
                .setTitle("Cài đặt chung")
                .setView(box)
                .setPositiveButton("Lưu", (d, w) -> {
                    final HttpSettings n = s.copy();
                    n.connectTimeoutSec = parse(connect, n.connectTimeoutSec);
                    n.readTimeoutSec = parse(read, n.readTimeoutSec);
                    n.followRedirects = follow.isChecked();
                    n.verifySsl = ssl.isChecked();
                    n.allowScriptRequests = script.isChecked();
                    if (!n.verifySsl && s.verifySsl) {
                        Ui.confirm(c, "Tắt kiểm tra SSL?",
                                "Kết nối HTTPS sẽ chấp nhận mọi chứng chỉ, kể cả giả mạo. Bạn chắc chắn muốn tắt?",
                                "Vẫn tắt", () -> save(store, n, onSaved));
                    } else {
                        save(store, n, onSaved);
                    }
                })
                .setNeutralButton("Xóa cookie", (d, w) -> {
                    store.engine.cookieJar().clear();
                    Ui.toast(c, "Đã xóa cookie của phiên làm việc");
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private static void save(Store store, HttpSettings n, Runnable onSaved) {
        store.settings.save(n);
        if (onSaved != null) onSaved.run();
    }

    private static int parse(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static EditText number(Context c, String label, int value, LinearLayout box) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(12);
        box.addView(t);
        EditText e = new EditText(c);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        box.addView(e);
        return e;
    }

    private static CheckBox check(Context c, String label, boolean value, LinearLayout box) {
        CheckBox cb = new CheckBox(c);
        cb.setText(label);
        cb.setChecked(value);
        box.addView(cb);
        return cb;
    }
}
