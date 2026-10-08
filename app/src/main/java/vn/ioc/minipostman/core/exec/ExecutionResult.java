package vn.ioc.minipostman.core.exec;

import java.util.ArrayList;
import java.util.List;

import vn.ioc.minipostman.core.net.PreparedRequest;
import vn.ioc.minipostman.core.net.ResponseData;
import vn.ioc.minipostman.core.script.JsSandbox;

/** Kết quả chạy một request, gồm cả script, test và các biến đã thay đổi. */
public final class ExecutionResult {

    public boolean sent;
    public ResponseData response;
    public PreparedRequest prepared;
    /** Dòng "METHOD url" đã thay biến và che token, dùng cho console/history. */
    public String requestLine = "";
    public final List<JsSandbox.TestResult> tests = new ArrayList<>();
    public final List<String> console = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    /** Ví dụ "environment: accessToken". */
    public final List<String> changedVars = new ArrayList<>();
    public boolean envChanged;
    public boolean globalsChanged;
    public boolean collectionChanged;
    public boolean nextRequestSet;
    public String nextRequest;

    public int passed() {
        int n = 0;
        for (JsSandbox.TestResult t : tests) {
            if (t.passed) n++;
        }
        return n;
    }

    public int failed() {
        return tests.size() - passed();
    }

    /** Request thành công khi đã gửi, không lỗi script/kết nối và mọi test đều đạt. */
    public boolean ok() {
        return sent && errors.isEmpty() && response != null && !response.isError() && failed() == 0;
    }
}
