package vn.ioc.minipostman;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int PICK_FILE = 1;

    private Store store;
    private Spinner envSpinner;
    private ListView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = Store.get(this);
        envSpinner = findViewById(R.id.envSpinner);
        list = findViewById(R.id.requestList);
        list.setEmptyView(findViewById(R.id.emptyText));

        findViewById(R.id.btnEditEnv).setOnClickListener(v ->
                startActivity(new Intent(this, EnvActivity.class)));
        findViewById(R.id.btnImport).setOnClickListener(v -> pickFile());
        findViewById(R.id.btnNew).setOnClickListener(v -> openRequest(store.newRequest().id));

        envSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos != store.activeEnv) {
                    store.activeEnv = pos;
                    store.save();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        list.setOnItemClickListener((p, v, pos, id) -> openRequest(store.requests.get(pos).id));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showItemMenu(pos);
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<String> names = new ArrayList<>();
        for (Store.Env e : store.envs) names.add(e.name);
        ArrayAdapter<String> ea = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        ea.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        envSpinner.setAdapter(ea);
        store.activeEnvObj();
        envSpinner.setSelection(store.activeEnv);

        list.setAdapter(new ArrayAdapter<Store.Req>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, store.requests) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                Store.Req r = getItem(position);
                ((TextView) v.findViewById(android.R.id.text1)).setText(r.method + "  " + r.name);
                ((TextView) v.findViewById(android.R.id.text2))
                        .setText(r.folder.isEmpty() ? r.url : r.folder + " · " + r.url);
                return v;
            }
        });
    }

    private void openRequest(String id) {
        Intent i = new Intent(this, RequestActivity.class);
        i.putExtra("id", id);
        startActivity(i);
    }

    private void showItemMenu(int pos) {
        Store.Req r = store.requests.get(pos);
        String[] options = {"Nhân bản", "Xóa", "Xóa tất cả request"};
        new AlertDialog.Builder(this)
                .setTitle(r.name)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        store.requests.add(pos + 1, r.copy());
                    } else if (which == 1) {
                        store.requests.remove(pos);
                    } else {
                        store.requests.clear();
                    }
                    store.save();
                    refresh();
                })
                .show();
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try {
            String msg = PostmanImporter.importJson(store, readUri(data.getData()));
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Import lỗi: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private String readUri(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Không mở được file");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
