package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;

/** Programme reminders and the parental lock's storage (no screens). */
@RunWith(AndroidJUnit4.class)
public class LiveExtrasTest {

    final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    final ChannelDatabase.Row bbc = new ChannelDatabase.Row(
        1,
        "BBC One",
        "UK",
        "",
        "key-bbc",
        "BBCOne.uk",
        "uk:bbcone",
        101,
        "Entertainment"
    );

    @Before
    @After
    public void clean() {
        context.getSharedPreferences("ui", 0).edit().remove(Reminders.PREF).commit();
        ParentalLock.clear(context);
    }

    static ChannelDatabase.Programme programme(String title, long start) {
        return new ChannelDatabase.Programme(start, start + 30 * 60000, title, "", "");
    }

    @Test
    public void remindersComeDueOnceAndOldOnesAreDropped() {
        long now = System.currentTimeMillis();
        ChannelDatabase.Programme soon = programme("News", now + 30000),
            later = programme("Film", now + 3600000),
            missed = programme("Quiz", now - 10 * 60000);
        Reminders.set(context, bbc, soon, true);
        Reminders.set(context, bbc, later, true);
        Reminders.set(context, bbc, missed, true);
        assertTrue(Reminders.has(context, bbc, soon));
        Reminders.Reminder due = Reminders.due(context, now);
        assertNotNull("Due a minute before it starts", due);
        assertEquals("News", due.title);
        assertEquals(101, due.number);
        assertNull("Shown once; the missed one is dropped", Reminders.due(context, now));
        assertEquals(1, Reminders.all(context).size());
        Reminders.set(context, bbc, later, false);
        assertTrue(Reminders.all(context).isEmpty());
    }

    @Test
    public void lockedChannelsAndSectionsNeedThePin() {
        assertFalse("Nothing is locked without a PIN", ParentalLock.locked(context, bbc));
        assertFalse(ParentalLock.valid("12a4"));
        ParentalLock.setPin(context, "1234");
        assertTrue(ParentalLock.matches(context, "1234"));
        assertFalse(ParentalLock.matches(context, "4321"));
        assertFalse(
            "Only a hash is stored",
            context.getSharedPreferences("ui", 0).getString(ParentalLock.PIN, "").contains("1234")
        );
        ParentalLock.setChannel(context, bbc, true);
        assertTrue(ParentalLock.locked(context, bbc));
        ParentalLock.setChannel(context, bbc, false);
        assertFalse(ParentalLock.locked(context, bbc));
        ParentalLock.setSection(context, "Entertainment", true);
        assertTrue("A locked section locks its channels", ParentalLock.locked(context, bbc));
        ParentalLock.clear(context);
        assertFalse(ParentalLock.locked(context, bbc));
    }
}
