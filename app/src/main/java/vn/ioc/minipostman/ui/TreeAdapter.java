package vn.ioc.minipostman.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import vn.ioc.minipostman.databinding.RowNodeBinding;

/** Danh sách các nút đang hiển thị của cây collection (ListAdapter + DiffUtil). */
public final class TreeAdapter extends ListAdapter<TreeRow, TreeAdapter.VH> {

    public interface Listener {
        void onClick(TreeRow row);

        void onLongClick(TreeRow row);
    }

    private static final DiffUtil.ItemCallback<TreeRow> DIFF = new DiffUtil.ItemCallback<TreeRow>() {
        @Override
        public boolean areItemsTheSame(@NonNull TreeRow a, @NonNull TreeRow b) {
            return a.id.equals(b.id);
        }

        @Override
        public boolean areContentsTheSame(@NonNull TreeRow a, @NonNull TreeRow b) {
            return a.equals(b);
        }
    };

    private final Listener listener;

    public TreeAdapter(Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(RowNodeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final TreeRow r = getItem(position);
        RowNodeBinding b = h.b;
        int base = Ui.dp(b.getRoot().getContext(), 6);
        int indent = Ui.dp(b.getRoot().getContext(), 16) * r.depth;
        b.getRoot().setPadding(base + indent, 0, b.getRoot().getPaddingRight(), 0);

        b.arrow.setText(r.expandable ? (r.expanded ? "▾" : "▸") : "");
        b.name.setText(r.name.isEmpty() ? (r.isContainer() ? "(không tên)" : (r.url.isEmpty() ? "(không tên)" : r.url)) : r.name);

        if (TreeRow.COLLECTION.equals(r.kind)) {
            b.method.setText("📦");
            b.method.setTextColor(b.name.getCurrentTextColor());
            b.name.setTypeface(null, Typeface.BOLD);
            b.count.setText(String.valueOf(r.requestCount));
        } else if (TreeRow.FOLDER.equals(r.kind)) {
            b.method.setText("📁");
            b.method.setTextColor(b.name.getCurrentTextColor());
            b.name.setTypeface(null, Typeface.NORMAL);
            b.count.setText(String.valueOf(r.requestCount));
        } else if (TreeRow.REQUEST.equals(r.kind)) {
            b.method.setText(r.method);
            b.method.setTextColor(Ui.methodColor(b.getRoot().getContext(), r.method));
            b.name.setTypeface(null, Typeface.NORMAL);
            b.count.setText("");
        } else {
            b.method.setText("?");
            b.method.setTextColor(Ui.methodColor(b.getRoot().getContext(), ""));
            b.name.setTypeface(null, Typeface.ITALIC);
            b.count.setText("");
        }

        b.getRoot().setOnClickListener(v -> listener.onClick(r));
        b.getRoot().setOnLongClickListener((View v) -> {
            listener.onLongClick(r);
            return true;
        });
    }

    static final class VH extends RecyclerView.ViewHolder {
        final RowNodeBinding b;

        VH(RowNodeBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
