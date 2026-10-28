package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;

/** The encrypted account store with its in-memory cache. */
@RunWith(AndroidJUnit4.class)
public class SecretsTest {

    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @After
    public void clear() {
        new Secrets(context).remove("secrets-test");
    }

    @Test
    public void storesEncryptedAndServesRepeatReadsFromMemory() throws Exception {
        Secrets one = new Secrets(context);
        one.put("secrets-test", "value-1");
        String onDisk = context.getSharedPreferences("accounts", 0).getString("secrets-test", "");
        assertFalse("Stored encrypted, never in plain text", onDisk.contains("value-1"));
        assertEquals("value-1", new Secrets(context).get("secrets-test"));
        one.put("secrets-test", "value-2");
        assertEquals(
            "A new value replaces the cached one",
            "value-2",
            new Secrets(context).get("secrets-test")
        );
        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) one.get("secrets-test");
        long perRead = (System.nanoTime() - start) / 200;
        assertTrue("Cached reads skip the Keystore: " + perRead + " ns", perRead < 1_000_000);
        one.remove("secrets-test");
        assertEquals("", one.get("secrets-test"));
    }
}
