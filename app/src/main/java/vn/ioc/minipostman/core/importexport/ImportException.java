package vn.ioc.minipostman.core.importexport;

/** Lỗi import có thông báo thân thiện, hiển thị thẳng cho người dùng. */
public class ImportException extends Exception {

    public ImportException(String message) {
        super(message);
    }

    public ImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
