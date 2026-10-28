package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;

/** Which iptv-org playlist Live TV uses without a provider. No network. */
@RunWith(AndroidJUnit4.class)
public class FreeChannelsTest {

    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SharedPreferences prefs = context.getSharedPreferences("ui", 0);
    String before;

    @Before
    public void save() {
        before = prefs.getString(FreeChannels.PREF, null);
    }

    @After
    public void restore() {
        if (before == null) prefs.edit().remove(FreeChannels.PREF).commit();
        else prefs.edit().putString(FreeChannels.PREF, before).commit();
    }

    @Test
    public void automaticOnlyWithoutAProviderAndChosenCountriesAlways() {
        prefs.edit().remove(FreeChannels.PREF).commit();
        assertEquals(
            "The full list by default",
            FreeChannels.ROOT + "index.m3u",
            FreeChannels.playlist(context, false)
        );
        assertEquals("A provider replaces automatic free channels", "", FreeChannels.playlist(context, true));
        prefs.edit().putString(FreeChannels.PREF, "uk").commit();
        assertEquals(FreeChannels.ROOT + "countries/uk.m3u", FreeChannels.playlist(context, true));
        prefs.edit().putString(FreeChannels.PREF, "all").commit();
        assertEquals(FreeChannels.ROOT + "index.m3u", FreeChannels.playlist(context, false));
        prefs.edit().putString(FreeChannels.PREF, "off").commit();
        assertEquals("", FreeChannels.playlist(context, false));
    }
}
