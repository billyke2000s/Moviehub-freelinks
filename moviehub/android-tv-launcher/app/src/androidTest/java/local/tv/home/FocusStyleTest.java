package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;

/** Focus styling: wide rows do not grow into their neighbours; the top-bar app icon has no tile. */
@RunWith(AndroidJUnit4.class)
public class FocusStyleTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    MainActivity activity;
    boolean autoUpdates;

    @Before
    public void launch() {
        Context context = remote.getTargetContext();
        autoUpdates = context.getSharedPreferences("ui", 0).getBoolean("autoUpdates", true);
        context.getSharedPreferences("ui", 0).edit().putBoolean("autoUpdates", false).commit();
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.waitForIdleSync();
    }

    @After
    public void cleanup() {
        remote.runOnMainSync(() -> activity.finish());
        remote
            .getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean("autoUpdates", autoUpdates)
            .commit();
    }

    @Test
    public void wideRowsHighlightWithoutGrowingAndSmallItemsGrow() {
        remote.runOnMainSync(() -> {
            activity.screen("Focus", () -> {});
            Button small = activity.button("Small", () -> {});
            LinearLayout wide = activity.vertical();
            wide.addView(activity.text("A full-width list row", 16));
            activity.focus(wide);
            activity.page.addView(small, new LinearLayout.LayoutParams(activity.dp(120), activity.dp(40)));
            activity.page.addView(wide, new LinearLayout.LayoutParams(-1, activity.dp(88)));
        });
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            View small = activity.page.getChildAt(0),
                wide = activity.page.getChildAt(1);
            activity.styleFocus(small, true);
            activity.styleFocus(wide, true);
            assertTrue("Small focused items grow", small.getScaleX() > 1f);
            assertEquals("Wide rows do not grow past their neighbours", 1f, wide.getScaleX(), 0f);
            assertTrue("Focused items draw above neighbours", wide.getTranslationZ() > 0);
            activity.styleFocus(wide, false);
            assertEquals(0f, wide.getTranslationZ(), 0f);
        });
    }

    @Test
    public void topBarAppIconHasNoTileAndGlowsWhenFocused() {
        remote.runOnMainSync(() -> {
            AppDock.App app = new AppDock.App(
                HomeScreen.MEMORY_CLEAN,
                new ComponentName("fixture.memoryclean", "Main"),
                activity.getDrawable(android.R.drawable.sym_def_app_icon)
            );
            activity.dock.apps.add(app);
            assertSame(app, activity.dock.findApp("MemoryClean"));
            assertNull("Name must match exactly", activity.dock.findApp("memoryclean"));
            View icon = activity.dock.iconShortcut(app);
            assertTrue(icon instanceof ImageView);
            assertNull("No box behind the icon", icon.getBackground());
            ((ViewGroup) activity.page.getChildAt(0)).addView(
                icon,
                new LinearLayout.LayoutParams(activity.dp(38), activity.dp(38))
            );
            assertTrue(icon.requestFocus());
            assertTrue(icon.getScaleX() > 1f);
            assertNotNull("Soft glow while focused", icon.getBackground());
        });
    }

    @Test
    public void homeBackdropIsFullBleedAndCrossFades() {
        remote.runOnMainSync(() -> {
            BackdropView backdrop = activity.homeShelf.backdrop;
            View root = activity.getWindow().getDecorView().findViewById(android.R.id.content);
            assertEquals("Artwork spans the whole screen width", root.getWidth(), backdrop.getWidth());
            backdrop.setBackgroundColor(0xff182337);
            assertNull("Loader placeholder colour is ignored", backdrop.getBackground());
            android.graphics.Bitmap first = android.graphics.Bitmap.createBitmap(
                160,
                90,
                android.graphics.Bitmap.Config.ARGB_8888
            );
            android.graphics.Bitmap second = android.graphics.Bitmap.createBitmap(
                160,
                90,
                android.graphics.Bitmap.Config.ARGB_8888
            );
            backdrop.setImageBitmap(first);
            assertNotNull(backdrop.getDrawable());
            backdrop.setImageDrawable(null);
            assertNotNull("Previous artwork stays while the next loads", backdrop.getDrawable());
            backdrop.setImageBitmap(second);
            assertTrue(
                "Changing artwork cross-fades",
                backdrop.getDrawable() instanceof android.graphics.drawable.TransitionDrawable
            );
            float[] values = new float[9];
            backdrop.getImageMatrix().getValues(values);
            assertEquals("Pinned to the top", 0f, values[android.graphics.Matrix.MTRANS_Y], 0.5f);
            float scaledWidth = 160 * values[android.graphics.Matrix.MSCALE_X];
            assertEquals(
                "Pinned to the right",
                backdrop.getWidth(),
                values[android.graphics.Matrix.MTRANS_X] + scaledWidth,
                1f
            );
            backdrop.clear();
            assertNull(backdrop.getDrawable());
        });
    }

    @Test
    public void backdropsAreNotHiddenBehindASolidPage() {
        remote.runOnMainSync(() -> {
            activity.fixedScreen(null, () -> {}, true);
            assertTrue("Live TV pages are shown inside the stage", activity.stage.onScreen());
            activity.homeScreen.home();
            assertFalse("Pages outside Live TV are shown on their own", activity.stage.onScreen());
        });
        remote.waitForIdleSync();
        remote.runOnMainSync(() ->
            assertNull("Nothing solid covers Home's artwork", solidCover(activity.homeShelf.backdrop))
        );
        BackdropView[] list = new BackdropView[1];
        remote.runOnMainSync(() -> {
            activity.screen("List", () -> {});
            list[0] = activity.fullBleedBackdrop(true);
        });
        remote.waitForIdleSync();
        remote.runOnMainSync(() ->
            assertNull("Nothing solid covers a list page's artwork", solidCover(list[0]))
        );
    }

    /** A screen-sized view with an opaque colour drawn above {@code backdrop}, or null. */
    View solidCover(View backdrop) {
        ViewGroup layers = (ViewGroup) backdrop.getParent();
        assertTrue("The screen has been laid out", layers.getWidth() > 0 && layers.getHeight() > 0);
        for (int i = layers.indexOfChild(backdrop) + 1; i < layers.getChildCount(); i++) {
            View found = solidCover(layers.getChildAt(i), layers.getWidth(), layers.getHeight());
            if (found != null) return found;
        }
        return null;
    }

    View solidCover(View node, int width, int height) {
        if (node.getVisibility() != View.VISIBLE) return null;
        android.graphics.drawable.Drawable background = node.getBackground();
        if (
            background instanceof android.graphics.drawable.ColorDrawable &&
            android.graphics.Color.alpha(((android.graphics.drawable.ColorDrawable) background).getColor()) ==
                255 &&
            node.getWidth() >= width * 0.9 &&
            node.getHeight() >= height * 0.9
        ) return node;
        if (node instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) node).getChildCount(); i++) {
            View found = solidCover(((ViewGroup) node).getChildAt(i), width, height);
            if (found != null) return found;
        }
        return null;
    }

    int countText(View node, String value) {
        int count = node instanceof TextView && value.contentEquals(((TextView) node).getText()) ? 1 : 0;
        if (node instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) node).getChildCount(); i++) count +=
            countText(((ViewGroup) node).getChildAt(i), value);
        return count;
    }

    View findBackdrop(View node) {
        if (node instanceof BackdropView) return node;
        if (node instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) node).getChildCount(); i++) {
            View found = findBackdrop(((ViewGroup) node).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    @Test
    public void detailsPageIsFullBleedWithOneSynopsisAndPillActions() throws Exception {
        String overview = "A family drama told across three generations.";
        org.json.JSONObject title = new org.json.JSONObject()
            .put("id", 77)
            .put("media_type", "movie")
            .put("title", "Fixture film")
            .put("overview", overview)
            .put("release_date", "2020-01-01")
            .put("genres", new org.json.JSONArray().put(new org.json.JSONObject().put("name", "Drama")))
            .put(
                "credits",
                new org.json.JSONObject().put(
                    "cast",
                    new org.json.JSONArray().put(
                        new org.json.JSONObject().put("name", "Actor One").put("character", "Lead")
                    )
                )
            );
        remote.runOnMainSync(() -> {
            activity.screen("", () -> {});
            try {
                activity.titleDetails.renderDetails(title);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            assertNotNull("Artwork fills the screen behind the page", findBackdrop(root));
            assertEquals("Synopsis appears once", 1, countText(root, overview));
            View primary = activity.getCurrentFocus();
            assertTrue("The main action is focused", primary instanceof Button);
            assertEquals("▶  Play", ((Button) primary).getText().toString());
            assertTrue("The main action uses the standard selected button style", primary.isSelected());
            assertEquals("Cast is listed", 1, countText(root, "Actor One"));
            assertEquals("Genres in the kicker", 1, countText(root, "MOVIE  ·  DRAMA"));
        });
        UiEvidence.capture("details-redesign");
    }
}
