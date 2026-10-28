package local.tv.home;

import java.util.ArrayList;
import java.util.List;

/** Country-scoped XMLTV keys, separate from each provider's exact native programme identities. */
final class EpgLookup {

    private EpgLookup() {}

    static String scope(String country, String key) {
        return "xmltv:" + normal(country) + ":" + key;
    }

    /**
     * Names to request from a country's XMLTV files. Engine ids are not XMLTV ids: use the
     * channel's name and the same country/identity rules as the guide's channel grouping.
     * Unknown native countries never borrow the default UK schedule.
     */
    static List<String> keys(String epg, String name, String group, String source, String country) {
        ChannelIdentity identity = ChannelIdentity.of(
            epg,
            name,
            group,
            ChannelIdentity.sourceCountry(source)
        );
        boolean nativeId = epg != null && (epg.startsWith("engine") || epg.startsWith("pluto:"));
        String own = identity.country;
        if (own.isEmpty() && nativeId) return new ArrayList<>();
        if (!own.isEmpty() && !normal(own).equals(normal(country))) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        if (!nativeId && epg != null && epg.contains("@")) {
            String variant = EpgMatch.key(epg.substring(epg.indexOf('@') + 1));
            if (!variant.isEmpty()) {
                // @HD is a quality note; @Scotland/@Spain identifies a different service.
                add(out, EpgMatch.key(epg.substring(0, epg.indexOf('@'))) + variant);
                return out;
            }
        }
        if (!nativeId) add(out, EpgMatch.key(epg));
        add(out, EpgMatch.key(name));
        add(out, EpgMatch.key(identity.key));
        // Match U&Dave/old Dave names in both directions, without losing regional identities.
        for (String key : new ArrayList<>(out)) {
            add(out, EpgMatch.national(key));
            if (key.equals("bbc1")) add(out, "bbcone");
            if (key.equals("bbc2")) add(out, "bbctwo");
            if (key.equals("skysportspremiereleague")) add(out, "skysportspremierleague");
        }
        return out;
    }

    static String country(String epg, String name, String group, String source, String fallback) {
        String own = ChannelIdentity.of(epg, name, group, ChannelIdentity.sourceCountry(source)).country;
        return normal(own.isEmpty() ? fallback : own);
    }

    private static void add(List<String> keys, String key) {
        if (!key.isEmpty() && !keys.contains(key)) keys.add(key);
    }

    private static String normal(String country) {
        return "gb".equals(country) ? "uk" : country;
    }
}
