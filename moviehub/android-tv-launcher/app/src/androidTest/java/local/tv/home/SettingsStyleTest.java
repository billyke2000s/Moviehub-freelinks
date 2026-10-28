package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Every Settings tab uses the same card style as Accounts. */
@RunWith(AndroidJUnit4.class)
public class SettingsStyleTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    MainActivity activity;
    SharedPreferences prefs;
    boolean autoUpdates, clock24;

    @Before
    public void launch() {
        Context context = remote.getTargetContext();
        prefs = context.getSharedPreferences("ui", 0);
        autoUpdates = prefs.getBoolean("autoUpdates", true);
        clock24 = prefs.getBoolean("clock24", true);
        prefs.edit().putBoolean("autoUpdates", false).commit();
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.waitForIdleSync();
    }

    @After
    public void cleanup() {
        remote.runOnMainSync(() -> activity.finish());
        prefs.edit().putBoolean("autoUpdates", autoUpdates).putBoolean("clock24", clock24).commit();
    }

    View find(View node, String label) {
        if (node instanceof TextView && label.contentEquals(((TextView) node).getText())) return node;
        if (node instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) node).getChildCount(); i++) {
            View found = find(((ViewGroup) node).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }

    /** The scrolling body that holds the selected tab's cards. */
    LinearLayout body() {
        View title = find(activity.getWindow().getDecorView(), "Settings");
        ViewGroup page = (ViewGroup) title.getParent().getParent();
        LinearLayout layout = (LinearLayout) page.getChildAt(1);
        ScrollView scroll = (ScrollView) layout.getChildAt(1);
        return (LinearLayout) scroll.getChildAt(0);
    }

    void assertCards(String tab) {
        LinearLayout body = body();
        assertTrue(tab + " has a heading and cards", body.getChildCount() >= 2);
        assertTrue(tab + " starts with a heading", body.getChildAt(0) instanceof TextView);
        for (int i = 1; i < body.getChildCount(); i++) {
            View child = body.getChildAt(i);
            assertFalse(tab + ": no bare switches", child instanceof Switch);
            assertFalse(tab + ": no full-width buttons", child instanceof Button);
            assertTrue(tab + ": every setting is a card", child instanceof LinearLayout);
            LinearLayout card = (LinearLayout) child;
            assertEquals(tab + ": cards are rows", LinearLayout.HORIZONTAL, card.getOrientation());
            assertTrue(tab + ": title and description", card.getChildAt(0) instanceof LinearLayout);
        }
    }

    /** Free channels: one page with plain sections, a card per service, and focus kept on rebuild. */
    @Test
    public void freeChannelsIsOnePageWithSectionsAndACardPerService() {
        // Its own sidebar tab, not a card under Accounts.
        remote.runOnMainSync(() -> activity.settingsScreens.settings());
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            View button = find(activity.getWindow().getDecorView(), FreeChannelsPage.TAB);
            assertNotNull("Free channels tab", button);
            while (!button.hasOnClickListeners()) button = (View) button.getParent();
            button.performClick();
        });
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            View decor = activity.getWindow().getDecorView();
            for (String heading : new String[] { "Public channel lists", "Free services", "Advanced" })
                assertNotNull("Section " + heading, find(decor, heading));
            for (String card : new String[] {
                "Refresh channels",
                "Channel lists",
                "Pluto TV",
                "FreeLiveSports",
            })
                assertNotNull("Card " + card, find(decor, card));
            for (String label : AdapterSources.LABELS) assertNotNull("Service " + label, find(decor, label));
            for (String gone : new String[] { "Local provider engine", "Runtime 2" })
                assertNull("Engine jargon removed: " + gone, find(decor, gone));
        });
        UiEvidence.capture("settings-free-channels");
        remote.runOnMainSync(() -> new FreeChannelsPage(activity).show("Pluto TV"));
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            View focus = activity.getCurrentFocus();
            assertTrue("A card button has the focus", focus instanceof Button);
            ViewGroup card = (ViewGroup) focus.getParent();
            assertNotNull("Focus stays on the Pluto TV card", find(card, "Pluto TV"));
        });
    }

    @Test
    public void everyTabUsesTheAccountCardStyle() {
        remote.runOnMainSync(() -> activity.settingsScreens.settings());
        remote.waitForIdleSync();
        for (String tab : new String[] { "Accounts", "Appearance", "Playback", "System" }) {
            remote.runOnMainSync(() -> {
                View button = find(activity.getWindow().getDecorView(), tab);
                while (!button.hasOnClickListeners()) button = (View) button.getParent();
                button.performClick();
            });
            remote.waitForIdleSync();
            remote.runOnMainSync(() -> assertCards(tab));
            UiEvidence.capture("settings-" + tab.toLowerCase(Locale.ROOT));
        }
    }

    @Test
    public void providerFailureCardWrapsAndKeepsEveryDescriptionLineInsideTheCard() {
        String description =
            "Last refresh failed: Provider processing timed out. Saved channels kept.\nNo successful refresh";
        final TextView[] detail = new TextView[1];
        final LinearLayout[] card = new LinearLayout[1];
        remote.runOnMainSync(() -> {
            LinearLayout body = activity.settingsScreens.settingsLayout(
                "Provider diagnostics",
                activity.homeScreen::home,
                "Accounts"
            );
            activity.settingsScreens.settingCard(
                body,
                "DaddyLive",
                description,
                "On",
                true,
                "Turn off",
                () -> {}
            );
            card[0] = (LinearLayout) body.getChildAt(0);
            detail[0] = (TextView) ((LinearLayout) card[0].getChildAt(0)).getChildAt(1);
        });
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            assertEquals(description, detail[0].getText().toString());
            assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, card[0].getLayoutParams().height);
            android.text.Layout lines = detail[0].getLayout();
            assertNotNull(lines);
            for (int i = 0; i < lines.getLineCount(); i++) assertEquals(
                "Failure details hidden",
                0,
                lines.getEllipsisCount(i)
            );
            View copy = (View) detail[0].getParent();
            assertTrue(
                "Description below card",
                copy.getTop() + detail[0].getBottom() <= card[0].getHeight() - card[0].getPaddingBottom()
            );
        });
        UiEvidence.capture("provider-error-card");
    }

    @Test
    public void toggleCardFlipsTheSettingAndItsLabels() {
        prefs.edit().putBoolean("clock24", true).commit();
        remote.runOnMainSync(() -> activity.settingsScreens.settings());
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            View tab = find(activity.getWindow().getDecorView(), "Appearance");
            while (!tab.hasOnClickListeners()) tab = (View) tab.getParent();
            tab.performClick();
        });
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            LinearLayout card = (LinearLayout) find(body(), "24-hour clock").getParent().getParent();
            TextView status = (TextView) card.getChildAt(1);
            Button action = (Button) card.getChildAt(2);
            assertEquals("On", status.getText().toString());
            assertEquals("Turn off", action.getText().toString());
            action.performClick();
            assertFalse(prefs.getBoolean("clock24", true));
            assertEquals("Off", status.getText().toString());
            assertEquals("Turn on", action.getText().toString());
        });
    }
}
