package vn.ioc.minipostman.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Base64 chuẩn viết tay (java.util.Base64 cần API 26, app hỗ trợ từ API 24). */
public final class Base64Util {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private Base64Util() {
    }

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            sb.append(ALPHABET[b0 >> 2]);
            sb.append(ALPHABET[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < data.length ? ALPHABET[((b1 & 15) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < data.length ? ALPHABET[b2 & 63] : '=');
        }
        return sb.toString();
    }

    public static String encode(String text, Charset cs) {
        return encode(text.getBytes(cs));
    }

    public static String encodeUtf8(String text) {
        return encode(text, StandardCharsets.UTF_8);
    }

    /** Giải mã; bỏ qua khoảng trắng, chấp nhận cả biến thể URL-safe. Ném IllegalArgumentException nếu sai. */
    public static byte[] decode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=' || Character.isWhitespace(c)) continue;
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '+' || c == '-') v = 62;
            else if (c == '/' || c == '_') v = 63;
            else throw new IllegalArgumentException("Ký tự base64 không hợp lệ: " + c);
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
