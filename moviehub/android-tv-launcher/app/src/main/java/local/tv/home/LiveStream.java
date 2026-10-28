package local.tv.home;

import android.content.Context;

/** Finds a live channel's playable link; playback, selection and recovery stay with ExoLive. */
final class LiveStream {

    /** Fixture transport for device tests; never assigned by app code. */
    interface Resolver {
        String resolve(String locator, NativeHttp http) throws Exception;
    }

    static final class Resolution {

        final EngineSources.Stream provider;
        final String url;
        final ProviderRelay relay;

        Resolution(EngineSources.Stream provider, String url, ProviderRelay relay) {
            this.provider = provider;
            this.url = url;
            this.relay = relay;
        }
    }

    static Resolution resolve(Context context, String locator, NativeHttp http, Resolver fixture)
        throws Exception {
        EngineSources.Stream stream = EngineSources.locator(locator)
            ? EngineSources.resolve(context.getApplicationContext(), locator, http)
            : null;
        String resolved =
            stream != null
                ? stream.url
                : fixture == null
                  ? PlutoSource.resolve(locator, http)
                  : fixture.resolve(locator, http);
        ProviderRelay nextRelay =
            stream != null &&
            (ProviderRelay.needed(stream) ||
                locator.contains("/engine/dlhd/") ||
                locator.contains("/engine/zlive/"))
                ? new ProviderRelay(stream)
                : null;
        try {
            http.check();
        } catch (Exception e) {
            if (nextRelay != null) nextRelay.close();
            throw e;
        }
        return new Resolution(stream, resolved, nextRelay);
    }
}
