package vn.ioc.minipostman.ui;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import vn.ioc.minipostman.R;

/** Các tiện ích giao diện nhỏ dùng chung (hộp thoại, màu theo method, định dạng). */
public final class Ui {

    private Ui() {
    }

    /** Không cho Autofill của hệ thống lưu/gợi ý nội dung ô nhập (có thể là token, mật khẩu). */
    public static void noAutofill(View v) {
        if (android.os.Build.VERSION.SDK_INT >= 26) v.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    }

    public static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
    }

    public static void confirm(Context c, String title, String message, String positive, Runnable onYes) {
        new MaterialAlertDialogBuilder(c)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive, (d, w) -> onYes.run())
                .setNegativeButton("Hủy", null)
                .show();
    }

    public static void prompt(Context c, String title, String initial, String hint, boolean multiline,
                              Consumer<String> onOk) {
        final EditText input = new EditText(c);
        noAutofill(input);
        input.setHint(hint);
        input.setText(initial);
        input.setSelection(input.getText().length());
        input.setInputType(multiline
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                : InputType.TYPE_CLASS_TEXT);
        if (multiline) {
            input.setMinLines(6);
            input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
            input.setTypeface(android.graphics.Typeface.MONOSPACE);
        }
        FrameLayout box = new FrameLayout(c);
        int pad = dp(c, 20);
        box.setPadding(pad, dp(c, 8), pad, 0);
        box.addView(input, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(c)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("OK", (d, w) -> onOk.accept(input.getText().toString()))
                .setNegativeButton("Hủy", null)
                .show();
    }

    public static AlertDialog choose(Context c, String title, List<String> labels, IntConsumer onPick) {
        return new MaterialAlertDialogBuilder(c)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, which) -> onPick.accept(which))
                .show();
    }

    public static int methodColor(Context c, String method) {
        int res;
        switch (method == null ? "" : method.toUpperCase(Locale.ROOT)) {
            case "GET": res = R.color.method_get; break;
            case "POST": res = R.color.method_post; break;
            case "PUT": res = R.color.method_put; break;
            case "PATCH": res = R.color.method_patch; break;
            case "DELETE": res = R.color.method_delete; break;
            default: res = R.color.method_other; break;
        }
        return c.getResources().getColor(res, c.getTheme());
    }

    public static int statusColor(Context c, int code) {
        int res = code >= 200 && code < 300 ? R.color.ok : (code >= 400 || code == 0 ? R.color.error : R.color.warn);
        return c.getResources().getColor(res, c.getTheme());
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
