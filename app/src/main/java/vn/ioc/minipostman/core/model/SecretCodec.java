package vn.ioc.minipostman.core.model;

/** Mã hóa/giải mã giá trị bí mật khi lưu xuống đĩa (bản Android dùng Android Keystore). */
public interface SecretCodec {

    /** Mã hóa; kết quả phải nhận ra được bởi {@link #decrypt}. */
    String encrypt(String plain);

    /** Giải mã; giá trị chưa được mã hóa (dữ liệu cũ) được trả nguyên. */
    String decrypt(String stored);

    /** Codec không mã hóa gì, dùng cho test. */
    SecretCodec NONE = new SecretCodec() {
        @Override
        public String encrypt(String plain) {
            return plain;
        }

        @Override
        public String decrypt(String stored) {
            return stored;
        }
    };
}
