package local.tv.home;

import android.os.Bundle;
import androidx.test.runner.AndroidJUnitRunner;

/**
 * Runs the device tests with the first-start safety notice already read and the TV layout chosen,
 * so neither covers the screens under test. SafetyNoticeTest and PhoneModeTest show them on purpose.
 */
public class MovieHubTestRunner extends AndroidJUnitRunner {

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean(SafetyNotice.PREF, true)
            .putBoolean(ProviderSettings.ASKED, true)
            .putString(DeviceMode.PREF, DeviceMode.TV)
            .commit();
    }
}
