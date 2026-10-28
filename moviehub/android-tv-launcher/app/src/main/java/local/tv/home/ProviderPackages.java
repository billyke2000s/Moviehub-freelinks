package local.tv.home;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.*;

/** Authenticated A/B packages. A failed check never replaces the current package. */
final class ProviderPackages {

    private static final Object LOCK = new Object();

    private ProviderPackages() {}

    /** Instrumentation fixture only; never assigned by app code. */
    static JSONObject testTrust;

    private static AtomicFile file(Context c, String slot) {
        return new AtomicFile(new File(c.getFilesDir(), "provider-" + slot + ".json"));
    }

    static String asset(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open("providers/" + name)) {
            return ProviderService.read(in);
        }
    }

    static JSONObject trust(Context c) throws Exception {
        return testTrust != null ? testTrust : new JSONObject(asset(c, "trust.json"));
    }

    static boolean configured(Context c) {
        try {
            return !trust(c).getString("publicKey").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    static JSONObject validate(byte[] bytes) throws Exception {
        if (bytes.length > ProviderSignature.MAX_PACKAGE) throw new IOException("Provider package too large");
        JSONObject pkg = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (
            (pkg.getInt("runtime") != 1 && pkg.getInt("runtime") != 2) ||
            pkg.getLong("version") < 1 ||
            !pkg.getString("upstream").matches("[a-f0-9]{40}")
        ) throw new IOException("Incompatible provider package");
        JSONObject modules = pkg.getJSONObject("modules");
        // freelivesports plus at most one module per supported provider.
        if (
            !modules.has("freelivesports") || modules.length() > ProviderBroker.IDS.length + 1
        ) throw new IOException("Missing provider baseline");
        for (java.util.Iterator<String> keys = modules.keys(); keys.hasNext(); ) {
            String id = keys.next();
            if (
                (!id.equals("freelivesports") &&
                    (pkg.getInt("runtime") != 2 || !ProviderBroker.supported(id))) ||
                modules.getString(id).length() > 128 * 1024
            ) throw new IOException("Unsupported provider capability");
        }
        return pkg;
    }

    private static JSONObject unpack(Context c, byte[] envelope) throws Exception {
        if (envelope.length > 2 * 1024 * 1024) throw new IOException("Provider envelope too large");
        JSONObject signed = new JSONObject(new String(envelope, StandardCharsets.UTF_8));
        byte[] payload = Base64.getDecoder().decode(signed.getString("payload"));
        byte[] sig = Base64.getDecoder().decode(signed.getString("signature"));
        ProviderSignature.verify(payload, sig, Base64.getDecoder().decode(trust(c).getString("publicKey")));
        return validate(payload);
    }

    static JSONObject current(Context c) throws Exception {
        synchronized (LOCK) {
            try {
                return unpack(c, file(c, "active").readFully());
            } catch (Exception ignored) {
                try {
                    return unpack(c, file(c, "previous").readFully());
                } catch (Exception alsoIgnored) {
                    return validate(asset(c, "builtin.json").getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    static String module(Context c) throws Exception {
        return current(c).getJSONObject("modules").getString("freelivesports");
    }

    static String module(Context c, String id) throws Exception {
        JSONObject pkg = current(c);
        JSONObject modules = pkg.getJSONObject("modules");
        // Older signed packages discard advertised ZLive source routes. Keep the APK's
        // compatible implementation until a signed update explicitly supports this schema.
        JSONObject capabilities = pkg.optJSONObject("capabilities");
        if (
            id.equals("zlive") && (capabilities == null || capabilities.optInt("zliveSources") < 1)
        ) return builtinModule(c, id);
        if (modules.has(id)) return modules.getString(id);
        // Older signed runtime-1 packages cannot remove the newer APK's built-in capabilities.
        return validate(asset(c, "builtin.json").getBytes(StandardCharsets.UTF_8))
            .getJSONObject("modules")
            .getString(id);
    }

    /** Parser-specific optimizations apply only to the exact APK-bundled transform. */
    static String builtinModule(Context c, String id) throws Exception {
        return validate(asset(c, "builtin.json").getBytes(StandardCharsets.UTF_8))
            .getJSONObject("modules")
            .getString(id);
    }

    private static void write(AtomicFile file, byte[] bytes) throws IOException {
        FileOutputStream out = file.startWrite();
        try {
            out.write(bytes);
            file.finishWrite(out);
        } catch (IOException e) {
            file.failWrite(out);
            throw e;
        }
    }

    static void install(Context c, byte[] payload, byte[] signature) throws Exception {
        synchronized (LOCK) {
            byte[] key = Base64.getDecoder().decode(trust(c).getString("publicKey"));
            ProviderSignature.verify(payload, signature, key);
            JSONObject pkg = validate(payload);
            long high = Math.max(
                current(c).getLong("version"),
                c.getSharedPreferences("providerEngine", 0).getLong("highWater", 1)
            );
            if (pkg.getLong("version") <= high) throw new IOException("Provider downgrade rejected");
            // Health-check the actual Android runtime before committing any state.
            String module = pkg.getJSONObject("modules").getString("freelivesports");
            new JSONObject(ProviderBridge.call(c, module, "metadata", ""));
            JSONArray sample = new JSONArray(
                ProviderBridge.call(
                    c,
                    module,
                    "catalogue",
                    "[{\"_id\":\"health\",\"name\":\"Health\",\"url\":\"https://example.com/a.m3u8\"}]"
                )
            );
            if (sample.length() != 1 || !sample.getJSONObject(0).getString("channelId").equals("health")) {
                throw new IOException("Provider health check rejected");
            }
            JSONObject resolved = new JSONObject(
                ProviderBridge.call(c, module, "resolve", "https://example.com/a.m3u8?cb=[CB]")
            );
            if (resolved.getString("url").contains("[CB]")) throw new IOException(
                "Provider resolver health check rejected"
            );
            JSONObject allModules = pkg.getJSONObject("modules");
            for (String id : ProviderBroker.IDS)
                if (allModules.has(id)) {
                    JSONObject metadata = new JSONObject(
                        ProviderBridge.call(c, allModules.getString(id), "metadata", "")
                    );
                    if (
                        !id.equals(metadata.getString("provider")) || metadata.getInt("runtime") != 2
                    ) throw new IOException("Provider metadata rejected");
                }
            byte[] envelope = new JSONObject()
                .put("payload", Base64.getEncoder().encodeToString(payload))
                .put("signature", Base64.getEncoder().encodeToString(signature))
                .toString()
                .getBytes(StandardCharsets.UTF_8);
            byte[] old = null;
            try {
                byte[] candidate = file(c, "active").readFully();
                unpack(c, candidate);
                old = candidate;
            } catch (Exception ignored) {
                // A corrupt active slot must not replace the verified previous slot or block repair.
            }
            if (old != null) write(file(c, "previous"), old);
            // Persist the network high-water before activation; local explicit rollback cannot lower it.
            if (
                !c
                    .getSharedPreferences("providerEngine", 0)
                    .edit()
                    .putLong("highWater", pkg.getLong("version"))
                    .commit()
            ) {
                throw new IOException("Provider version state failed");
            }
            write(file(c, "active"), envelope);
        }
    }

    static void rollback(Context c) throws Exception {
        synchronized (LOCK) {
            try {
                byte[] previous = file(c, "previous").readFully();
                unpack(c, previous);
                write(file(c, "active"), previous);
            } catch (FileNotFoundException e) {
                file(c, "active").delete(); // Trusted APK-bundled version; high-water retained.
            }
            c.getSharedPreferences("ui", 0).edit().putBoolean("providerAutoUpdates", false).apply();
        }
    }
}
