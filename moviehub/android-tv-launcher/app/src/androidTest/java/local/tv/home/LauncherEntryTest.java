package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** MovieHub can be opened as an ordinary app on phones and TVs, and chosen as the home screen. */
@RunWith(AndroidJUnit4.class)
public class LauncherEntryTest {

    final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private boolean listed(String category) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
            .addCategory(category)
            .setPackage(context.getPackageName());
        List<ResolveInfo> found = context.getPackageManager().queryIntentActivities(intent, 0);
        for (ResolveInfo item : found) if (item.activityInfo.name.endsWith(".MainActivity")) return true;
        return false;
    }

    @Test
    public void opensFromThePhoneAppDrawerTheTvAppListAndAsHome() {
        assertTrue("Phone and tablet app drawer", listed(Intent.CATEGORY_LAUNCHER));
        assertTrue("Android TV app list", listed(Intent.CATEGORY_LEANBACK_LAUNCHER));
        assertTrue("Home screen", listed(Intent.CATEGORY_HOME));
    }
}
