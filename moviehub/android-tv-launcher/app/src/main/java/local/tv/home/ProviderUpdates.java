package local.tv.home;

import android.content.Context;
import java.io.*;
import java.net.*;

/** Checks only a pinned HTTPS release feed; authentication is the detached package signature. */
final class ProviderUpdates {

    private ProviderUpdates() {}

    static void check(Context c, boolean manual) throws Exception {
        if (!ProviderPackages.configured(c)) return;
        if (
            !manual &&
            (!c.getSharedPreferences("ui", 0).getBoolean("providerAutoUpdates", true) ||
                System.currentTimeMillis() -
                    c.getSharedPreferences("providerEngine", 0).getLong("checked", 0) <
                    12 * 3600_000L)
        ) return;
        c.getSharedPreferences("providerEngine", 0)
            .edit()
            .putLong("checked", System.currentTimeMillis())
            .apply();
        String feed = ProviderPackages.trust(c).getString("feed");
        // Only the application's fixed release location can supply remote packages.
        if (
            !feed.equals("https://github.com/billyke2000s/moviehub/releases/download/provider-engine-latest/")
        ) {
            throw new IOException("Provider feed rejected");
        }
        byte[] payload = download(feed + "providers.json", ProviderSignature.MAX_PACKAGE);
        byte[] sig = download(feed + "providers.sig", 1024);
        // Authenticate even an identical/stale version before deciding there is nothing to do.
        ProviderSignature.verify(
            payload,
            sig,
            java.util.Base64.getDecoder().decode(ProviderPackages.trust(c).getString("publicKey"))
        );
        long high = Math.max(
            ProviderPackages.current(c).getLong("version"),
            c.getSharedPreferences("providerEngine", 0).getLong("highWater", 1)
        );
        if (ProviderPackages.validate(payload).getLong("version") <= high) return;
        ProviderPackages.install(c, payload, sig);
    }

    private static byte[] download(String address, int max) throws Exception {
        URI uri = URI.create(address);
        for (int hop = 0; hop < 5; hop++) {
            String host = uri.getHost();
            if (
                !"https".equals(uri.getScheme()) ||
                uri.getUserInfo() != null ||
                uri.getPort() != -1 ||
                host == null ||
                !(
                    host.equals("github.com") ||
                    host.equals("release-assets.githubusercontent.com") ||
                    host.equals("objects.githubusercontent.com")
                )
            ) {
                throw new IOException("Provider download host rejected");
            }
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing provider redirect");
                    uri = uri.resolve(location);
                    continue;
                }
                if (status != 200) throw new IOException("Provider download unavailable");
                try (
                    InputStream in = connection.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()
                ) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (
                            Thread.currentThread().isInterrupted() || out.size() + n > max
                        ) throw new IOException("Provider download limit");
                        out.write(buffer, 0, n);
                    }
                    return out.toByteArray();
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Provider redirect limit");
    }
}
