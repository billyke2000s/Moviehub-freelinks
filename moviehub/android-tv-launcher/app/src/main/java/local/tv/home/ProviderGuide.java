package local.tv.home;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;

/**
 * Programme listings for providers whose catalogue carries none, from the public per-region XMLTV
 * files at i.mjh.nz (rebuilt daily from each provider's own guide). Channels match by the
 * provider's own channel id, never by name. {@link Spec} says, per provider, where the files are,
 * which regions to try and how a guide id becomes MovieHub's stored id.
 */
final class ProviderGuide {

    /** One provider's guide files. */
    static final class Spec {

        final String provider, base;
        /** Tried in order; the next only when the last covered under half the channels. */
        final String[] regions;
        final java.util.function.UnaryOperator<String> idKey;

        Spec(String provider, String base, String[] regions, java.util.function.UnaryOperator<String> idKey) {
            this.provider = provider;
            this.base = base;
            this.regions = regions;
            this.idKey = idKey;
        }
    }

    /**
     * Plex: its own guide is one request per channel per day (about 700 a refresh). The ids are
     * compound "&lt;server&gt;-&lt;channel&gt;"; the part after the last dash is the stable id
     * MovieHub stores. The UK file first; the US one for a viewer whose Plex lineup is American
     * (Plex chooses the lineup from where the viewer is).
     */
    static final Spec PLEX = new Spec("plex", "", new String[] { "gb", "us" }, ProviderGuide::stableId);

    /**
     * Samsung TV Plus (GB): the adapter drops the catalogue's inline listings, and the guide ids
     * are the channel ids MovieHub stores ("GBBC2300003ZQ"), so they match unchanged.
     */
    static final Spec SAMSUNG = new Spec("samsung", "", new String[] { "gb" }, id -> id);

    /** The guide for a provider, or null when its catalogue brings its own listings or none exist. */
    static Spec forProvider(String id) {
        if (id.equals("plex")) return PLEX;
        if (id.equals("samsung")) return SAMSUNG;
        return null;
    }

    /** Compressed download limit; the US file is about 2 MB. */
    static final long MAX_BYTES = 20L * 1024 * 1024;
    /** Tests replace the download; null downloads from the spec's base address. */
    static volatile Opener opener;

    interface Opener {
        InputStream open(String region) throws Exception;
    }

    /** One region's matched listings, keyed by MovieHub's channel key ("engineplex:&lt;id&gt;"). */
    static final class Listings {

        final Set<String> channels = new HashSet<>();
        final List<Object[]> programmes = new ArrayList<>();
    }

    private ProviderGuide() {}

    /** The stable channel id in a compound Plex id: "abc-123" is "123". */
    static String stableId(String compound) {
        int dash = compound.lastIndexOf('-');
        return dash < 0 ? compound : compound.substring(dash + 1);
    }

    /**
     * Downloads listings for the provider's catalogue {@code rows} and stores them. Returns how
     * many channels got listings. A failed or empty download keeps the previous listings.
     */
    static int refresh(Spec spec, ChannelDatabase db, JSONArray rows, ChannelDatabase.Cancel cancel)
        throws Exception {
        String prefix = AdapterSources.source(spec.provider) + ":";
        Set<String> wanted = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            wanted.add(prefix + rows.getJSONObject(i).getString("channelId"));
        }
        if (wanted.isEmpty()) return 0;
        Listings best = null;
        Exception failure = null;
        for (String region : spec.regions) {
            cancel.check();
            try (InputStream in = open(spec, region)) {
                Listings found = read(spec, in, wanted, prefix, System.currentTimeMillis(), cancel);
                if (best == null || found.channels.size() > best.channels.size()) best = found;
                if (found.channels.size() * 2 >= wanted.size()) break;
            } catch (Exception e) {
                cancel.check();
                failure = e;
            }
        }
        if (best == null || best.programmes.isEmpty()) {
            if (failure != null) throw failure;
            return 0;
        }
        write(db, prefix, best);
        return best.channels.size();
    }

    private static InputStream open(Spec spec, String region) throws Exception {
        Opener test = opener;
        if (test != null) return test.open(region);
        throw new java.io.IOException("Per-provider guide downloads are retired; use CombinedEpg");
    }

    /** Reads one XMLTV file (gzipped or not), keeping programmes from two hours ago to a day ahead. */
    static Listings read(
        Spec spec,
        InputStream raw,
        Set<String> wanted,
        String prefix,
        long now,
        ChannelDatabase.Cancel cancel
    ) throws Exception {
        InputStream in = new BufferedInputStream(raw, 64 * 1024);
        in.mark(2);
        boolean gzip = in.read() == 0x1f && in.read() == 0x8b;
        in.reset();
        if (gzip) in = new GZIPInputStream(in, 64 * 1024);
        Listings out = new Listings();
        // XMLTV id → MovieHub key, for the programmes that follow the channel list.
        Map<String, String> keys = new LinkedHashMap<>();
        EpgImporter.parse(
            in,
            wanted,
            now - EpgImporter.BEHIND_MS,
            now + EpgImporter.AHEAD_MS,
            new EpgImporter.Sink() {
                public void match(String key, String channel) {
                    keys.put(channel, key);
                    out.channels.add(key);
                }

                public void programme(
                    String channel,
                    long start,
                    long stop,
                    String title,
                    String description,
                    String category,
                    String flags
                ) throws Exception {
                    cancel.check();
                    String key = keys.get(channel);
                    if (key == null) return;
                    out.programmes.add(new Object[] {
                        key,
                        start,
                        stop,
                        title,
                        description,
                        category,
                        flags,
                    });
                }
            },
            id -> prefix + spec.idKey.apply(id)
        );
        return out;
    }

    /** Replaces the provider's listings in one transaction, as its own inline guide would. */
    static void write(ChannelDatabase db, String prefix, Listings listings) throws Exception {
        ChannelDatabase.GuideWriter writer = db.new GuideWriter(prefix);
        try {
            for (String key : listings.channels) writer.match(EpgMatch.key(key), key);
            for (Object[] p : listings.programmes) {
                if (writer.programmes >= 50000) break;
                writer.programme(
                    (String) p[0],
                    (Long) p[1],
                    (Long) p[2],
                    (String) p[3],
                    (String) p[4],
                    (String) p[5],
                    (String) p[6]
                );
            }
            writer.commit();
        } finally {
            writer.finish();
        }
    }
}
