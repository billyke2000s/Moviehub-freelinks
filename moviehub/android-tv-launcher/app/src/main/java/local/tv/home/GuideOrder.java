package local.tv.home;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lays out the whole channel list the way a UK satellite guide does. UK channels take the numbers
 * the satellite guide uses (101 BBC One …); other UK channels follow in their section's block
 * (entertainment after 182, movies in the 300s, sport in the 400s, news in the 500s, kids in the
 * 600s …); UK radio is in the 900s; and channels from other countries are numbered from 1001,
 * country by country (Ireland, the United States, Canada, Australia, New Zealand, then the rest
 * alphabetically), in an International section.
 *
 * <p>When no channel is one the satellite guide knows (a provider with other channels, or test
 * fixtures), channels are simply numbered from 101 in list order.
 */
final class GuideOrder {

    /** One channel (or one group of copies), in list order. {@link #plan} fills in the rest. */
    static final class Entry {

        final String name, epg, group;
        /** From a free public list (Free-TV, iptv-org) rather than the viewer's own provider. */
        final boolean free;
        int number;
        String section = "",
            country = "";

        Entry(String name, String epg, String group, boolean free) {
            this.name = name == null ? "" : name;
            this.epg = epg == null ? "" : epg;
            this.group = group == null ? "" : group;
            this.free = free;
        }
    }

    static final String RADIO = "Radio",
        INTERNATIONAL = "International";
    static final int FIRST_RADIO = 901,
        LAST_RADIO = 999,
        FIRST_INTERNATIONAL = 1001;

    /** Where UK channels without a satellite number go: first and last number for each section. */
    private static final Map<String, int[]> BLOCKS = new HashMap<>();

    static {
        BLOCKS.put("Entertainment", new int[] { 183, 299 });
        BLOCKS.put("Documentaries", new int[] { 183, 299 });
        BLOCKS.put("Movies", new int[] { 325, 349 });
        BLOCKS.put("Music", new int[] { 359, 399 });
        BLOCKS.put("Sports", new int[] { 425, 499 });
        BLOCKS.put("News", new int[] { 517, 579 });
        BLOCKS.put("Religion", new int[] { 587, 599 });
        BLOCKS.put("Kids", new int[] { 625, 659 });
        BLOCKS.put("Shopping", new int[] { 675, 699 });
    }

    /** UK channels left over when a block is full, then other regions and red-button streams. */
    private static final int[] OVERFLOW = { 700, 799 },
        REGIONS = { 801, 899 };
    /** Plain keys of the main channels whose regional versions go in the 800s. */
    private static final List<String> MAIN_CHANNELS = Arrays.asList(
        "bbcone",
        "bbctwo",
        "itv1",
        "stv",
        "channel4",
        "channel5"
    );

    /** Countries listed first in International, in this order. */
    private static final List<String> FIRST_COUNTRIES = Arrays.asList("ie", "us", "ca", "au", "nz");

    private GuideOrder() {}

    static void plan(List<Entry> entries) {
        Set<Integer> used = new HashSet<>();
        boolean[] home = new boolean[entries.size()],
            radio = new boolean[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            entry.country = country(entry.epg, entry.name, entry.group);
            // A provider's channel with no country is taken as UK; a free list's is not (those
            // lists cover every country, so an unknown one is most likely foreign).
            home[i] = entry.country.equals("uk") || (entry.country.isEmpty() && !entry.free);
            // A UK channel's feed for another country ("Cartoon Network CEE Poland") is foreign.
            String abroad = home[i] ? foreignCountry(entry.name) : null;
            if (abroad != null) {
                entry.country = abroad;
                home[i] = false;
            }
            radio[i] = isRadio(entry);
            int sky = radio[i] ? 0 : SkyNumbers.number(entry.epg, entry.name, home[i]);
            if (sky > 0 && used.add(sky)) entry.number = sky;
        }
        if (used.isEmpty()) {
            // Nothing the satellite guide knows: keep list order.
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                entry.number = 101 + i;
                entry.section = radio[i] ? RADIO : SkyNumbers.section(0, entry.name, entry.group);
            }
            return;
        }
        List<Integer> international = new ArrayList<>();
        Map<String, Integer> next = new HashMap<>();
        List<String> sections = Arrays.asList(SkyNumbers.SECTIONS);
        int[] genre = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.number > 0) {
                entry.section = SkyNumbers.section(entry.number, entry.name, entry.group);
                continue;
            }
            if (!home[i]) {
                entry.section = radio[i] ? RADIO : INTERNATIONAL;
                // Within a country: TV by section, radio last.
                genre[i] = radio[i] ? 99 : sections.indexOf(SkyNumbers.section(0, entry.name, entry.group));
                international.add(i);
                continue;
            }
            entry.section = radio[i] ? RADIO : SkyNumbers.section(0, entry.name, entry.group);
            // Regional versions of the main channels and the red-button streams sit in the 800s,
            // as the satellite guide kept other regions out of the way.
            boolean regional = !radio[i] && isRegional(entry);
            int[] block = radio[i]
                ? new int[] { FIRST_RADIO, LAST_RADIO }
                : regional
                  ? REGIONS
                  : BLOCKS.get(entry.section);
            int number = free(block, used, next, radio[i] ? RADIO : regional ? "regions" : entry.section);
            if (number == 0) number = free(OVERFLOW, used, next, "overflow");
            if (number == 0) international.add(0, i);
            // more UK channels than numbers: first after 1000
            else entry.number = number;
        }
        // Other countries: country by country, sections in guide order within each.
        Map<String, String> countryNames = new HashMap<>();
        international.sort((a, b) -> {
            Entry x = entries.get(a),
                y = entries.get(b);
            if (home[a] != home[b]) return home[a] ? -1 : 1;
            int byCountry = compareCountries(x.country, y.country, countryNames);
            if (byCountry != 0) return byCountry;
            int bySection = Integer.compare(genre[a], genre[b]);
            return bySection != 0 ? bySection : Integer.compare(a, b);
        });
        int number = FIRST_INTERNATIONAL;
        for (int index : international) {
            while (used.contains(number)) number++;
            entries.get(index).number = number++;
        }
    }

    /** The next unused number in {@code block}, or 0 when it is full. */
    private static int free(int[] block, Set<Integer> used, Map<String, Integer> next, String name) {
        int number = next.containsKey(name) ? next.get(name) : block[0];
        while (number <= block[1] && used.contains(number)) number++;
        if (number > block[1]) return 0;
        used.add(number);
        next.put(name, number + 1);
        return number;
    }

    private static int compareCountries(String a, String b, Map<String, String> names) {
        int x = FIRST_COUNTRIES.indexOf(a),
            y = FIRST_COUNTRIES.indexOf(b);
        if (x >= 0 || y >= 0) return Integer.compare(x < 0 ? 99 : x, y < 0 ? 99 : y);
        return countryName(a, names).compareToIgnoreCase(countryName(b, names));
    }

    private static String countryName(String code, Map<String, String> cache) {
        String name = cache.get(code);
        if (name == null) {
            name = name(code);
            cache.put(code, name);
        }
        return name;
    }

    /** "France" for "fr"; "United Kingdom" for "uk"; the code itself when unknown. */
    static String name(String code) {
        if (code == null || code.isEmpty()) return "Other";
        String iso = code.equals("uk") ? "GB" : code.toUpperCase(Locale.ROOT);
        String name = new Locale("", iso).getDisplayCountry(Locale.UK);
        return name.isEmpty() || name.equals(iso) ? code.toUpperCase(Locale.ROOT) : name;
    }

    private static final java.util.regex.Pattern PREFIX = java.util.regex.Pattern.compile(
        "^\\s*([A-Za-z]{2,3})\\s*[:|]"
    );
    private static Map<String, String> byName;
    private static Set<String> isoCodes;
    private static final Map<String, String> groupCountries = new HashMap<>();

    /** Country codes by English country name ("italy" → "it"), for lists grouped by country. */
    private static synchronized Map<String, String> byName() {
        if (byName == null) {
            byName = new HashMap<>();
            isoCodes = new HashSet<>();
            for (String iso : Locale.getISOCountries()) {
                String code = iso.equals("GB") ? "uk" : iso.toLowerCase(Locale.ROOT);
                isoCodes.add(code);
                byName.put(new Locale("", iso).getDisplayCountry(Locale.UK).toLowerCase(Locale.ROOT), code);
            }
            // Names lists use that differ from the standard ones.
            String[][] aliases = {
                { "uk", "uk" },
                { "united kingdom", "uk" },
                { "great britain", "uk" },
                { "usa", "us" },
                { "united states", "us" },
                { "czech republic", "cz" },
                { "turkey", "tr" },
                { "turkiye", "tr" },
                { "trinidad", "tt" },
                { "korea", "kr" },
                { "south korea", "kr" },
                { "russia", "ru" },
                { "vietnam", "vn" },
                { "ivory coast", "ci" },
                { "macedonia", "mk" },
                { "north macedonia", "mk" },
                { "bosnia", "ba" },
                { "kosovo", "xk" },
                { "taiwan", "tw" },
                { "hong kong", "hk" },
                { "uae", "ae" },
            };
            for (String[] alias : aliases) byName.put(alias[0], alias[1]);
        }
        return byName;
    }

    /**
     * The country a group names: the whole group ("Italy") or a country named in it ("VOD Italy",
     * "?? / Japan"); null when none.
     */
    private static synchronized String groupCountry(String group) {
        String text = " " + group.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").trim() + " ";
        if (text.trim().isEmpty()) return null;
        if (groupCountries.containsKey(text)) return groupCountries.get(text);
        Map<String, String> names = byName();
        String found = names.get(text.trim());
        if (found == null) {
            String best = "";
            for (Map.Entry<String, String> item : names.entrySet()) {
                String name = item.getKey();
                if (name.length() > best.length() && name.length() >= 4 && text.contains(" " + name + " ")) {
                    best = name;
                    found = item.getValue();
                }
            }
        }
        groupCountries.put(text, found);
        return found;
    }

    /**
     * A channel's country: from a provider's name prefix ("FR: TF1", "UK | BBC One"), else its
     * guide id ("TF1.fr"), else a country-named group ("Italy"); "" when none says. The United
     * Kingdom is "uk", as iptv-org writes it.
     */
    static String country(String epg, String name, String group) {
        java.util.regex.Matcher prefix = PREFIX.matcher(name == null ? "" : name);
        if (prefix.find()) {
            String code = prefix.group(1).toLowerCase(Locale.ROOT);
            if (code.equals("gb")) code = "uk";
            if (code.equals("usa")) code = "us";
            byName();
            if (isoCodes.contains(code)) return code;
        }
        String fromId = EpgMatch.country(epg);
        if (!fromId.isEmpty()) return fromId.equals("gb") ? "uk" : fromId;
        String fromGroup = group == null ? null : groupCountry(group);
        return fromGroup == null ? "" : fromGroup;
    }

    /** "BBC One Scotland", "ITV1 Granada", "BBC Red Button 3": other regions and extra streams. */
    static boolean isRegional(Entry entry) {
        String key = EpgMatch.key(entry.name);
        if (key.contains("redbutton")) return true;
        for (String main : MAIN_CHANNELS)
            if (key.startsWith(main) && key.length() > main.length() && !key.startsWith(main + "+")) {
                String rest = key.substring(main.length());
                // "ITV2" is a channel of its own, not a region of ITV.
                if (!Character.isDigit(rest.charAt(0))) return true;
            }
        return false;
    }

    /** Words that name the UK, so they never make a channel foreign. */
    private static final List<String> HOME_NAMES = Arrays.asList(
        "united kingdom",
        "uk",
        "great britain",
        "england",
        "scotland",
        "wales",
        "northern ireland",
        "jersey",
        "guernsey",
        "isle of man"
    );

    /** Another country named in a UK channel's name ("Cartoon Network CEE Poland" → "pl"), or null. */
    static String foreignCountry(String name) {
        String found = groupCountry(name);
        if (found == null || found.equals("uk")) return null;
        String text = " " + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ") + " ";
        for (String home : HOME_NAMES) if (text.contains(" " + home + " ")) return null;
        return found;
    }

    /** Radio stations (BBC Radio 1, BBC World Service …), kept apart from TV channels. */
    static boolean isRadio(Entry entry) {
        String text = (" " + entry.name + " " + entry.group + " ")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ");
        String id = entry.epg.toLowerCase(Locale.ROOT);
        boolean television =
            text.contains(" tv ") || text.contains("tele") || id.contains("tele") || id.contains("tv");
        // "Today FM", "98FM", "FM104" and "Spin 103.8" are radio too.
        boolean fm =
            text.contains(" fm ") ||
            text.matches(".* (\\d+fm|fm\\d+) .*") ||
            entry.name.matches(".*\\b\\d{2,3}\\.\\d\\b.*");
        return !television && (text.contains(" radio ") || id.contains("radio") || fm);
    }
}
