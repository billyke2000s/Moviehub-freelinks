package local.tv.home;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;

final class UiEvidence {

    static void capture(String name) {
        try {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                android.os.SystemClock.sleep(400);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
            Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if (image == null) return;
            File directory = new File(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),
                "screenshots"
            );
            directory.mkdirs();
            try (OutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
                image.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            image.recycle();
            String[] commands = {
                "mkdir -p /sdcard/Download/moviehub-test-screenshots",
                "cp " +
                    new File(directory, name + ".png").getAbsolutePath() +
                    " /sdcard/Download/moviehub-test-screenshots/" +
                    name +
                    ".png",
            };
            for (String command : commands)
                try (
                    ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation()
                        .executeShellCommand(command);
                    InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)
                ) {
                    while (in.read() != -1) {}
                }
        } catch (Exception ignored) {}
    }
}
