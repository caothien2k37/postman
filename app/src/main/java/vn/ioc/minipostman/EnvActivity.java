package vn.ioc.minipostman;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

public class EnvActivity extends Activity {

    private Store store;
    private EditText nameInput;
    private EditText varsInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_env);
        setTitle("Môi trường");
        store = Store.get(this);
        nameInput = findViewById(R.id.envName);
        varsInput = findViewById(R.id.envVars);

        findViewById(R.id.btnSaveEnv).setOnClickListener(v -> {
            saveForm();
            Toast.makeText(this, "Đã lưu", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnNewEnv).setOnClickListener(v -> {
            saveForm();
            store.envs.add(new Store.Env("Env " + (store.envs.size() + 1)));
            store.activeEnv = store.envs.size() - 1;
            store.save();
            bind();
        });
        findViewById(R.id.btnDeleteEnv).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Xóa môi trường \"" + store.activeEnvObj().name + "\"?")
                .setPositiveButton("Xóa", (d, w) -> {
                    if (store.envs.size() > 1) {
                        store.envs.remove(store.activeEnv);
                        store.activeEnv = 0;
                    } else {
                        store.activeEnvObj().vars.clear();
                    }
                    store.save();
                    bind();
                })
                .setNegativeButton("Hủy", null)
                .show());
        bind();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bind();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveForm();
    }

    private void bind() {
        Store.Env e = store.activeEnvObj();
        ((TextView) findViewById(R.id.envTitle)).setText(
                "Đang sửa môi trường " + (store.activeEnv + 1) + "/" + store.envs.size()
                        + " (đổi môi trường ở màn hình chính)");
        nameInput.setText(e.name);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> en : e.vars.entrySet()) {
            sb.append(en.getKey()).append(" = ").append(en.getValue()).append('\n');
        }
        varsInput.setText(sb.toString());
    }

    private void saveForm() {
        Store.Env e = store.activeEnvObj();
        String name = nameInput.getText().toString().trim();
        if (!name.isEmpty()) e.name = name;
        e.vars.clear();
        for (String line : varsInput.getText().toString().split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int eq = l.indexOf('=');
            if (eq <= 0) continue;
            String key = l.substring(0, eq).trim();
            if (!key.isEmpty()) e.vars.put(key, l.substring(eq + 1).trim());
        }
        store.save();
    }
}
