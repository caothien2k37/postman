package vn.ioc.minipostman;

import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.function.BooleanSupplier;

/** Nền chung cho test Robolectric: mỗi test có Store/DB mới và có hàm chờ việc nền + main looper. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public abstract class AppTestBase {

    protected Application app;

    @Before
    public void baseSetUp() {
        Store.resetForTests();
        app = RuntimeEnvironment.getApplication();
    }

    protected Store store() {
        return Store.get(app);
    }

    /** Chờ điều kiện đúng, vừa chờ vừa xử lý hàng đợi main looper (kết quả từ thread nền được post về đó). */
    protected static void waitFor(String what, BooleanSupplier condition) {
        long end = System.currentTimeMillis() + 20_000;
        while (true) {
            shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return;
            if (System.currentTimeMillis() > end) fail("Hết thời gian chờ: " + what);
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Bị ngắt khi chờ: " + what);
            }
        }
    }

    protected static EditText findEditText(View root) {
        if (root instanceof EditText) return (EditText) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText e = findEditText(g.getChildAt(i));
                if (e != null) return e;
            }
        }
        return null;
    }
}
