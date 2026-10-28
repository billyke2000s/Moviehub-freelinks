package local.tv.home;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import java.util.*;
import local.tv.home.ChannelDatabase.Programme;
import local.tv.home.ChannelDatabase.Row;

/** Programme identities, queries and atomic guide snapshots for the channel database. */
final class ChannelGuideStore {

    private final ChannelDatabase database;

    ChannelGuideStore(ChannelDatabase database) {
        this.database = database;
    }

    private SQLiteDatabase getReadableDatabase() {
        return database.getReadableDatabase();
    }

    private SQLiteDatabase getWritableDatabase() {
        return database.getWritableDatabase();
    }

    private final java.util.concurrent.atomic.AtomicLong guideRevision =
        new java.util.concurrent.atomic.AtomicLong();

    long guideRevision() {
        return guideRevision.get();
    }

    /** Cheap identity snapshot; adding a backup of an existing channel needs no XMLTV re-download. */
    Set<String> guideSnapshot() {
        Set<String> keys = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT dkey FROM channels", null)) {
            while (c.moveToNext()) keys.add(c.getString(0));
        }
        return keys;
    }

    /** Canonical ids published by Moviehub-epg → the local exact/native or scoped lookup key. */
    Map<String, String> combinedGuideKeys() {
        Map<String, String> keys = new HashMap<>();
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT epg,name,grp,source FROM channels", null)) {
            while (rows.moveToNext()) {
                String epg = rows.getString(0),
                    name = rows.getString(1),
                    group = rows.getString(2),
                    source = rows.getString(3);
                if (epg.startsWith("engine") || epg.startsWith("pluto:")) {
                    String canonical = epg.replaceAll("\\.gb$", ".uk");
                    keys.put(canonical, EpgMatch.key(epg));
                }
                String own = EpgLookup.country(epg, name, group, source, "uk");
                for (String key : EpgLookup.keys(epg, name, group, source, own)) {
                    String scoped = EpgLookup.scope(own, key);
                    keys.put(scoped, scoped);
                }
            }
        }
        return keys;
    }

    /** Every plain key the saved channels can be found under in a guide for {@code country}. */
    Set<String> guideKeys(String country) {
        Set<String> keys = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT epg,name,grp,source FROM channels", null)) {
            while (c.moveToNext())
                keys.addAll(
                    EpgLookup.keys(c.getString(0), c.getString(1), c.getString(2), c.getString(3), country)
                );
        }
        return keys;
    }

    /** Replaces the whole programme guide in one transaction; a failed download keeps the old one. */
    private static boolean knownEngineGuide(String prefix) {
        for (String id : ProviderBroker.IDS) if (prefix.equals(AdapterSources.source(id) + ":")) return true;
        return false;
    }

    class GuideWriter {

        private final SQLiteDatabase db = getWritableDatabase();
        private final SQLiteStatement map, programme;
        private boolean committed;
        private final boolean complete;
        int programmes;

        GuideWriter() {
            this(false);
        }

        /** Native provider guides refresh independently; XMLTV refreshes retain those listings. */
        GuideWriter(boolean nativePluto) {
            this(nativePluto ? "nativepluto:" : null);
        }

        GuideWriter(String prefix) {
            this(prefix, false);
        }

        /** The combined public guide replaces all retired built-in/native mappings atomically. */
        GuideWriter(String prefix, boolean complete) {
            this.complete = complete;
            if (
                prefix != null &&
                !prefix.equals("nativepluto:") &&
                !prefix.equals("enginefls:") &&
                !knownEngineGuide(prefix)
            ) {
                throw new IllegalArgumentException("Unknown native guide");
            }
            db.beginTransactionNonExclusive();
            if (complete) {
                db.execSQL("DROP TABLE IF EXISTS temp.combined_old_map");
                db.execSQL("DROP TABLE IF EXISTS temp.combined_old_programmes");
                db.execSQL("DROP TABLE IF EXISTS temp.combined_retained_map");
                db.execSQL("CREATE TEMP TABLE combined_old_map AS SELECT * FROM epg_map");
                db.execSQL(
                    "CREATE TEMP TABLE combined_old_programmes AS SELECT * FROM programmes WHERE stop>? AND start<?",
                    new Object[] {
                        System.currentTimeMillis() - EpgImporter.BEHIND_MS,
                        System.currentTimeMillis() + EpgImporter.AHEAD_MS,
                    }
                );
            }
            String where = complete
                ? null
                : prefix == null
                  ? "channel NOT LIKE 'nativepluto:%' AND channel NOT LIKE 'engine%:%'"
                  : "channel LIKE '" + prefix + "%'";
            db.delete("programmes", where, null);
            db.delete("epg_map", where, null);
            map = db.compileStatement("INSERT INTO epg_map(chkey,channel) VALUES(?,?)");
            programme = db.compileStatement(
                "INSERT INTO programmes(channel,start,stop,title,descr,category,flags) VALUES(?,?,?,?,?,?,?)"
            );
        }

        void match(String key, String channel) {
            map.bindString(1, key);
            map.bindString(2, channel);
            map.executeInsert();
        }

        void programme(
            String channel,
            long start,
            long stop,
            String title,
            String description,
            String category,
            String flags
        ) {
            programme.bindString(1, channel);
            programme.bindLong(2, start);
            programme.bindLong(3, stop);
            programme.bindString(4, title);
            programme.bindString(5, description == null ? "" : description);
            programme.bindString(6, category == null ? "" : category);
            programme.bindString(7, flags == null ? "" : flags);
            programme.executeInsert();
            programmes++;
        }

        void commit() {
            db.setTransactionSuccessful();
            committed = true;
        }

        /** A cloud source may fail while the TV still has usable native/private cached listings. */
        void retainMissing() {
            if (!complete) throw new IllegalStateException("Only a complete guide can retain missing keys");
            db.execSQL(
                "CREATE TEMP TABLE combined_retained_map AS SELECT DISTINCT old.chkey,old.channel FROM combined_old_map old " +
                    "WHERE NOT EXISTS (SELECT 1 FROM epg_map fresh JOIN programmes p ON p.channel=fresh.channel " +
                    "WHERE fresh.chkey=old.chkey AND p.stop>? AND p.start<?)",
                new Object[] { System.currentTimeMillis(), System.currentTimeMillis() + EpgImporter.AHEAD_MS }
            );
            db.execSQL(
                "INSERT INTO epg_map SELECT old.chkey,old.channel FROM combined_retained_map old " +
                    "WHERE NOT EXISTS (SELECT 1 FROM epg_map m WHERE m.chkey=old.chkey AND m.channel=old.channel)"
            );
            db.execSQL(
                "INSERT INTO programmes SELECT old.* FROM combined_old_programmes old " +
                    "WHERE old.channel IN (SELECT channel FROM combined_retained_map) AND NOT EXISTS " +
                    "(SELECT 1 FROM programmes fresh WHERE fresh.channel=old.channel AND fresh.start<old.stop AND fresh.stop>old.start)"
            );
        }

        /** Ends the transaction; without commit() nothing changes. */
        void finish() {
            map.close();
            programme.close();
            if (complete) {
                db.execSQL("DROP TABLE IF EXISTS temp.combined_old_map");
                db.execSQL("DROP TABLE IF EXISTS temp.combined_old_programmes");
                db.execSQL("DROP TABLE IF EXISTS temp.combined_retained_map");
            }
            db.endTransaction();
            if (committed) guideRevision.incrementAndGet();
        }
    }

    /** Programmes on this channel overlapping [from, to), in time order (at most 12). */
    List<Programme> programmes(Row row, String country, long from, long to) {
        return programmes(row, country, from, to, 12);
    }

    /**
     * The guide country a channel's listings come from: its own when MovieHub downloads a guide
     * for it (an Irish "RTEOne.ie" uses the Irish guide), else {@code fallback}. A channel of
     * another country therefore still gets no listings, rather than a same-named UK channel's.
     */
    static String guideCountry(String epgId, String fallback) {
        String own = EpgMatch.country(epgId);
        return EpgImporter.GUIDE_COUNTRIES.contains(own) ? own : fallback;
    }

    List<Programme> programmes(Row row, String country, long from, long to, int limit) {
        // Exact provider listings always win when they overlap this window. If absent, a
        // country-confirmed channel may use XMLTV by name; never attach another country's guide.
        List<String> keys = new ArrayList<>();
        if (
            row.epg.startsWith("enginefls:") ||
            knownEngineGuide(row.epg.substring(0, row.epg.indexOf(':') + 1)) ||
            row.epg.matches("pluto:[0-9a-f]{24}\\.[a-z]{2}")
        ) keys.add(EpgMatch.key(row.epg));
        String own = EpgLookup.country(row.epg, row.name, row.group, row.source, country);
        for (String key : EpgLookup.keys(row.epg, row.name, row.group, row.source, own))
            keys.add(EpgLookup.scope(own, key));
        List<Programme> upcoming = null;
        long now = System.currentTimeMillis();
        long anchor = from <= now && now < to ? now : from;
        for (String key : keys) {
            List<Programme> found = programmes(key, from, to, limit);
            if (found.isEmpty()) continue;
            for (Programme programme : found)
                if (programme.start <= anchor && programme.stop > anchor) return found;
            if (upcoming == null) upcoming = found;
        }
        return upcoming == null ? new ArrayList<>() : upcoming;
    }

    private List<Programme> programmes(String key, long from, long to, int limit) {
        List<Programme> out = new ArrayList<>();
        try (
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT p.start,p.stop,p.title,p.descr,p.category,p.flags FROM epg_map m " +
                    "JOIN programmes p ON p.channel=m.channel WHERE m.chkey=? AND p.start<? AND p.stop>? " +
                    "ORDER BY p.start LIMIT " +
                    Math.max(1, limit),
                new String[] { key, String.valueOf(to), String.valueOf(from) }
            )
        ) {
            while (c.moveToNext())
                out.add(
                    new Programme(
                        c.getLong(0),
                        c.getLong(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4),
                        c.getString(5)
                    )
                );
        }
        return out;
    }
}
