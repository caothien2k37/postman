package vn.ioc.minipostman.data;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import vn.ioc.minipostman.core.Base64Util;
import vn.ioc.minipostman.core.model.SecretCodec;

/** AES-256-GCM với khóa nằm trong Android Keystore (không thể trích xuất ra khỏi thiết bị). */
public final class KeystoreSecretCodec implements SecretCodec {

    private static final String TAG = "SecretCodec";
    private static final String ALIAS = "minipostman_secret_key";
    private static final String PREFIX = "enc1:";

    private SecretKey key;

    private synchronized SecretKey key() throws Exception {
        if (key != null) return key;
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            kg.generateKey();
        }
        key = (SecretKey) ks.getKey(ALIAS, null);
        return key;
    }

    @Override
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain == null ? "" : plain;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64Util.encode(c.getIV()) + ":" + Base64Util.encode(ct);
        } catch (Exception e) {
            // Keystore hỏng trên một số máy: lưu thẳng còn hơn mất dữ liệu (không log giá trị).
            Log.w(TAG, "Không mã hóa được giá trị secret: " + e.getClass().getSimpleName());
            return plain;
        }
    }

    @Override
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored == null ? "" : stored;
        try {
            String[] parts = stored.substring(PREFIX.length()).split(":", 2);
            byte[] iv = Base64Util.decode(parts[0]);
            byte[] ct = Base64Util.decode(parts[1]);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Không giải mã được giá trị secret: " + e.getClass().getSimpleName());
            return "";
        }
    }
}
