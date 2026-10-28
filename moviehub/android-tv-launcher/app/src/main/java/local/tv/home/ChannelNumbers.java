package local.tv.home;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import java.util.ArrayList;
import java.util.List;

/** Numbers the saved channels and files them in guide sections; see {@link SkyNumbers}. */
final class ChannelNumbers {

    private ChannelNumbers() {}

    /**
     * Gives every channel its number, guide section and country (see {@link GuideOrder}). Copies
     * of one channel share them; the preferred copy is marked main=1. Runs after every import, so numbers stay the same while the
     * lists do.
     */
    static void renumber(SQLiteDatabase db) {
        List<Long> ids = new ArrayList<>();
        List<String> dkeys = new ArrayList<>();
        List<GuideOrder.Entry> entries = new ArrayList<>();
        try (
            Cursor c = db.rawQuery(
                "SELECT _id,name,epg,grp,dkey,source," +
                    ChannelDatabase.RANK +
                    " AS rank FROM channels GROUP BY " +
                    ChannelDatabase.GROUP +
                    " ORDER BY rank",
                null
            )
        ) {
            while (c.moveToNext()) {
                ids.add(c.getLong(0));
                dkeys.add(c.getString(4));
                String source = c.getString(5);
                boolean free = source.equals("fta") || source.equals("free");
                entries.add(new GuideOrder.Entry(c.getString(1), c.getString(2), c.getString(3), free));
            }
        }
        GuideOrder.plan(entries);
        db.beginTransactionNonExclusive();
        try (
            SQLiteStatement main = db.compileStatement("UPDATE channels SET main=1 WHERE _id=?");
            SQLiteStatement byKey = db.compileStatement(
                "UPDATE channels SET number=?,section=?,country=? WHERE dkey=?"
            );
            SQLiteStatement byId = db.compileStatement(
                "UPDATE channels SET number=?,section=?,country=? WHERE _id=?"
            )
        ) {
            // The row each group was read from is its preferred copy (the guide shows that one).
            db.execSQL("UPDATE channels SET main=0 WHERE main<>0");
            for (int i = 0; i < entries.size(); i++) {
                main.bindLong(1, ids.get(i));
                main.executeUpdateDelete();
                GuideOrder.Entry entry = entries.get(i);
                boolean shared = !dkeys.get(i).isEmpty();
                SQLiteStatement update = shared ? byKey : byId;
                update.bindLong(1, entry.number);
                update.bindString(2, entry.section);
                update.bindString(3, entry.country);
                if (shared) update.bindString(4, dkeys.get(i));
                else update.bindLong(4, ids.get(i));
                update.executeUpdateDelete();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
