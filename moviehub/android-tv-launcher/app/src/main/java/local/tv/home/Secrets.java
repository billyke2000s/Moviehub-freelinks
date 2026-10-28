package local.tv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Account data is encrypted with a device-bound Android Keystore key. */
final class Secrets {

    private final SharedPreferences prefs;

    Secrets(Context context) {
        prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE);
    }

    /**
     * The Keystore key and decrypted values, kept in memory for the life of the app. Opening the
     * Android Keystore and decrypting through it is slow on TV boxes, and it used to happen on every
     * TMDB and Premiumize request. Values stay encrypted on disk as before.
     */
    private static SecretKey cachedKey;
    private static final java.util.Map<String, String> VALUES =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static synchronized SecretKey key() throws Exception {
        if (cachedKey != null) return cachedKey;
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias("tv-home-accounts")) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(
                new KeyGenParameterSpec.Builder(
                    "tv-home-accounts",
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            );
            generator.generateKey();
        }
        cachedKey = (SecretKey) store.getKey("tv-home-accounts", null);
        return cachedKey;
    }

    void put(String name, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String encoded =
            Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(
                cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Base64.NO_WRAP
            );
        if (!prefs.edit().putString(name, encoded).commit()) {
            VALUES.remove(name);
            throw new Exception("Account could not be saved");
        }
        VALUES.put(name, value);
    }

    String get(String name) throws Exception {
        String known = VALUES.get(name);
        if (known != null) return known;
        String encoded = prefs.getString(name, "");
        if (encoded.isEmpty()) return "";
        String[] parts = encoded.split(":", 2);
        if (parts.length != 2) throw new Exception("Account needs reconnecting");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        );
        String value = new String(
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
            java.nio.charset.StandardCharsets.UTF_8
        );
        VALUES.put(name, value);
        return value;
    }

    void remove(String name) {
        VALUES.remove(name);
        prefs.edit().remove(name).apply();
    }

    boolean has(String name) {
        return !prefs.getString(name, "").isEmpty();
    }
}
