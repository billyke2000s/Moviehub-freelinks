package local.tv.home;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * The channel database's tables, indexes and upgrades between versions; see
 * {@link ChannelDatabase}. Every step is safe to run twice, as a database whose version was rolled
 * back may already have it.
 */
final class ChannelSchema {

    private ChannelSchema() {}

    /** Creates every table and index of a new database. */
    static void create(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE channels(_id INTEGER PRIMARY KEY,source TEXT NOT NULL,position INTEGER NOT NULL,name TEXT NOT NULL,grp TEXT NOT NULL,logo TEXT NOT NULL,epg TEXT NOT NULL,key TEXT NOT NULL,url BLOB NOT NULL)"
        );
        db.execSQL("CREATE INDEX channels_order ON channels(source,position)");
        db.execSQL("CREATE INDEX channels_group ON channels(grp,source,position)");
        db.execSQL("CREATE INDEX channels_key ON channels(key)");
        db.execSQL("CREATE VIRTUAL TABLE channel_search USING fts4(name,grp)");
        db.execSQL("CREATE TABLE favourite_keys(key TEXT PRIMARY KEY)");
        createGuideTables(db);
        addDuplicateKey(db);
        addNumbers(db);
        addColumn(db, "channels", "source_label", "TEXT NOT NULL DEFAULT ''");
    }

    /**
     * The same channel from several lists (BBC One from Free-TV and from iptv-org) shares a
     * duplicate key: its country and plain name. The guide shows it once; the others are backups.
     */
    private static void addDuplicateKey(SQLiteDatabase db) {
        // Safe to run twice (a database whose version was rolled back already has the column).
        boolean present = false;
        try (Cursor columns = db.rawQuery("PRAGMA table_info(channels)", null)) {
            while (columns.moveToNext()) if ("dkey".equals(columns.getString(1))) present = true;
        }
        if (!present) db.execSQL("ALTER TABLE channels ADD COLUMN dkey TEXT NOT NULL DEFAULT ''");
        db.execSQL("CREATE INDEX IF NOT EXISTS channels_dkey ON channels(dkey)");
    }

    /** Adds a column if it is missing (a database whose version was rolled back may have it). */
    private static void addColumn(SQLiteDatabase db, String table, String column, String type) {
        try (Cursor columns = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (columns.moveToNext()) if (column.equals(columns.getString(1))) return;
        }
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    /** Channel numbers, guide sections (version 5) and countries (version 6); filled in by {@link ChannelNumbers#renumber}. */
    private static void addNumbers(SQLiteDatabase db) {
        addColumn(db, "channels", "number", "INTEGER NOT NULL DEFAULT 0");
        addColumn(db, "channels", "section", "TEXT NOT NULL DEFAULT ''");
        db.execSQL("CREATE INDEX IF NOT EXISTS channels_number ON channels(number)");
        // 1 on each channel's preferred copy (version 8); filled in by ChannelNumbers.renumber.
        addColumn(db, "channels", "main", "INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE INDEX IF NOT EXISTS channels_main ON channels(main,number)");
        addColumn(db, "channels", "country", "TEXT NOT NULL DEFAULT ''");
        addColumn(db, "programmes", "descr", "TEXT NOT NULL DEFAULT ''");
        addColumn(db, "programmes", "category", "TEXT NOT NULL DEFAULT ''");
        addColumn(db, "programmes", "flags", "TEXT NOT NULL DEFAULT ''");
    }

    /**
     * Programme guide: programmes by guide channel id, and which plain channel keys (see
     * {@link EpgMatch}) each guide channel matches.
     */
    private static void createGuideTables(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS programmes(channel TEXT NOT NULL,start INTEGER NOT NULL,stop INTEGER NOT NULL,title TEXT NOT NULL)"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS programmes_time ON programmes(channel,start)");
        db.execSQL("CREATE TABLE IF NOT EXISTS epg_map(chkey TEXT NOT NULL,channel TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS epg_map_match ON epg_map(chkey)");
    }

    /**
     * Brings a database from version {@code from} up to date; see the version notes below. Returns
     * the duplicate keys that changed (old to new), so locks kept by duplicate key can follow.
     */
    static java.util.Map<String, String> upgrade(SQLiteDatabase db, int from) {
        java.util.Map<String, String> moved = new java.util.HashMap<>();
        if (from < 2) db.execSQL("CREATE TABLE IF NOT EXISTS favourite_keys(key TEXT PRIMARY KEY)");
        if (from < 3) createGuideTables(db);
        if (from < 4) addDuplicateKey(db); // filled in by the next import
        if (from < 6) {
            addNumbers(db);
            ChannelNumbers.renumber(db);
        }
        // Version 7 already has programme details, but not the preferred-copy column (main).
        // Add it before renumber() compiles UPDATE channels SET main=1.
        if (from < 8) addNumbers(db);
        if (from >= 6 && from < 8) ChannelNumbers.renumber(db); // marks the preferred copies (main)
        // Version 9: duplicate keys recognise the same channel across lists and providers
        // ("Sky Sports F1 (720p)" and "Sky Sports F1 UK"); see ChannelIdentity. Recomputed from
        // the saved name, guide id, group and source; stream keys, favourites and URLs are untouched.
        if (from < 9) {
            moved.putAll(rekey(db));
            ChannelNumbers.renumber(db);
        }
        if (from < 10) addColumn(db, "channels", "source_label", "TEXT NOT NULL DEFAULT ''");
        return moved;
    }

    /** Recomputes every channel's duplicate key; returns the keys that changed (old to new). */
    static java.util.Map<String, String> rekey(SQLiteDatabase db) {
        java.util.Map<String, String> moved = new java.util.HashMap<>();
        db.beginTransactionNonExclusive();
        try (
            Cursor c = db.rawQuery("SELECT _id,epg,name,grp,source,dkey,key FROM channels", null);
            android.database.sqlite.SQLiteStatement update = db.compileStatement(
                "UPDATE channels SET dkey=? WHERE _id=?"
            )
        ) {
            while (c.moveToNext()) {
                String now = ChannelDatabase.duplicateKey(
                    c.getString(1),
                    c.getString(2),
                    c.getString(3),
                    c.getString(4)
                );
                String before = c.getString(5);
                if (now.equals(before)) continue;
                // A channel without a duplicate key was locked by its stream key ("key:…").
                String stream = "key:" + c.getString(6);
                moved.put(before.isEmpty() ? stream : before, now.isEmpty() ? stream : now);
                update.bindString(1, now);
                update.bindLong(2, c.getLong(0));
                update.executeUpdateDelete();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return moved;
    }
}
