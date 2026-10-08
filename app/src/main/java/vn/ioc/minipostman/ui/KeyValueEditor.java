package vn.ioc.minipostman.ui;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

import vn.ioc.minipostman.R;
import vn.ioc.minipostman.core.model.KeyValue;

/**
 * Bảng key/value có checkbox bật-tắt, dùng cho Params, Headers, form body và biến.
 * Thao tác trực tiếp trên danh sách {@link KeyValue} được truyền vào (danh sách "sống").
 */
public class KeyValueEditor extends LinearLayout {

    private List<KeyValue> items = new ArrayList<>();
    private Runnable onChange;
    private boolean showSecret;
    private String keyHint = "Key";
    private String valueHint = "Value";
    private final LinearLayout rows;
    private final MaterialButton addButton;

    public KeyValueEditor(Context context) {
        this(context, null);
    }

    public KeyValueEditor(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        rows = new LinearLayout(context);
        rows.setOrientation(VERTICAL);
        addView(rows, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        addButton = new MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle);
        addButton.setText("+ Thêm dòng");
        addButton.setOnClickListener(v -> {
            KeyValue kv = new KeyValue("", "");
            if (showSecret) kv.type = "default";
            items.add(kv);
            addRow(kv);
            changed();
        });
        addView(addButton, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public void setOnChange(Runnable r) {
        this.onChange = r;
    }

    public void setHints(String key, String value) {
        keyHint = key;
        valueHint = value;
    }

    /** Hiện cột 🔒 để đánh dấu biến secret (ẩn giá trị, lưu mã hóa). */
    public void setShowSecret(boolean show) {
        showSecret = show;
    }

    public void setAddLabel(String label) {
        addButton.setText(label);
    }

    public List<KeyValue> getItems() {
        return items;
    }

    public void setItems(List<KeyValue> list) {
        items = list;
        rows.removeAllViews();
        for (KeyValue kv : items) addRow(kv);
    }

    private void changed() {
        if (onChange != null) onChange.run();
    }

    private void addRow(final KeyValue kv) {
        final View row = LayoutInflater.from(getContext()).inflate(R.layout.row_kv, rows, false);
        final CheckBox enabled = row.findViewById(R.id.enabled);
        final EditText key = row.findViewById(R.id.key);
        final EditText value = row.findViewById(R.id.value);
        final CheckBox secret = row.findViewById(R.id.secret);

        key.setHint(keyHint);
        value.setHint(valueHint);
        enabled.setChecked(kv.enabled);
        key.setText(kv.key);
        value.setText(kv.value);
        if (showSecret) {
            secret.setVisibility(VISIBLE);
            secret.setChecked(kv.isSecret());
            applyMask(value, kv.isSecret());
        }

        enabled.setOnCheckedChangeListener((b, checked) -> {
            kv.enabled = checked;
            changed();
        });
        key.addTextChangedListener(new SimpleWatcher() {
            @Override
            void on(String s) {
                kv.key = s;
                changed();
            }
        });
        value.addTextChangedListener(new SimpleWatcher() {
            @Override
            void on(String s) {
                kv.value = s;
                changed();
            }
        });
        secret.setOnCheckedChangeListener((b, checked) -> {
            kv.type = checked ? "secret" : "default";
            applyMask(value, checked);
            changed();
        });
        row.findViewById(R.id.delete).setOnClickListener(v -> {
            items.remove(kv);
            rows.removeView(row);
            changed();
        });
        rows.addView(row);
    }

    private static void applyMask(EditText value, boolean masked) {
        value.setTransformationMethod(masked ? PasswordTransformationMethod.getInstance() : null);
        value.setSelection(value.getText().length());
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        abstract void on(String s);

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            on(s.toString());
        }
    }
}
