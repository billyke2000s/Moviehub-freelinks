package local.tv.home;

import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import java.util.*;

/** Disk-backed IPTV catalogue. Query results are bounded to a single UI page. */
final class ChannelStore extends SQLiteOpenHelper {

    ChannelStore(Context context) {
        super(context, "channels.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE channels (id INTEGER PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL UNIQUE, category TEXT NOT NULL, logo TEXT NOT NULL, epg TEXT NOT NULL)"
        );
        db.execSQL("CREATE INDEX channel_category ON channels(category)");
        db.execSQL("CREATE INDEX channel_name ON channels(name COLLATE NOCASE)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported channel database version");
    }

    void replace(List<Playlist.Channel> items) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("channels", null, null);
            SQLiteStatement s = db.compileStatement(
                "INSERT OR REPLACE INTO channels(name,url,category,logo,epg) VALUES(?,?,?,?,?)"
            );
            for (Playlist.Channel c : items) {
                s.clearBindings();
                s.bindString(1, c.name);
                s.bindString(2, c.url);
                s.bindString(3, c.group);
                s.bindString(4, c.logo);
                s.bindString(5, c.epgId);
                s.executeInsert();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    long count() {
        return DatabaseUtils.queryNumEntries(getReadableDatabase(), "channels");
    }

    List<Playlist.Channel> page(String filter, String search, int offset, int limit) {
        String where = "1=1";
        List<String> args = new ArrayList<>();
        if ("sports".equals(filter)) {
            where += " AND category LIKE ?";
            args.add("%sport%");
        }
        if (search != null && !search.isEmpty()) {
            where += " AND name LIKE ?";
            args.add("%" + search.replace("%", "\\%").replace("_", "\\_") + "%");
        }
        List<Playlist.Channel> result = new ArrayList<>();
        try (
            Cursor c = getReadableDatabase().query(
                "channels",
                new String[] { "name", "url", "category", "logo", "epg" },
                where,
                args.toArray(new String[0]),
                null,
                null,
                "name COLLATE NOCASE",
                "" + Math.max(0, offset) + "," + Math.min(200, Math.max(1, limit))
            )
        ) {
            while (c.moveToNext())
                result.add(
                    new Playlist.Channel(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4)
                    )
                );
        }
        return result;
    }
}
