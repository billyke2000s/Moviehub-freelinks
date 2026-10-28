package local.tv.home;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A satellite-box style parental lock for Live TV: a four-digit PIN, and locked channels and
 * guide sections that ask for it before they play. Only a hash of the PIN is stored. A forgotten
 * PIN is cleared by clearing the app's data.
 */
final class ParentalLock {

    static final String PIN = "livePinHash",
        CHANNELS = "lockedChannels",
        SECTIONS = "lockedSections";

    private ParentalLock() {}

    static SharedPreferences prefs(Context activity) {
        return activity.getSharedPreferences("ui", Context.MODE_PRIVATE);
    }

    static boolean hasPin(Context activity) {
        return !prefs(activity).getString(PIN, "").isEmpty();
    }

    /** Copies of a channel share its lock: by duplicate key when it has one, else its stream key. */
    static String id(ChannelDatabase.Row row) {
        return row.dkey.isEmpty() ? "key:" + row.key : row.dkey;
    }

    static boolean locked(Context activity, ChannelDatabase.Row row) {
        if (!hasPin(activity)) return false;
        SharedPreferences prefs = prefs(activity);
        return (
            prefs.getStringSet(CHANNELS, Collections.emptySet()).contains(id(row)) ||
            prefs.getStringSet(SECTIONS, Collections.emptySet()).contains(row.section)
        );
    }

    static boolean channelLocked(Context activity, ChannelDatabase.Row row) {
        return prefs(activity).getStringSet(CHANNELS, Collections.emptySet()).contains(id(row));
    }

    static void setChannel(Context activity, ChannelDatabase.Row row, boolean lock) {
        toggle(activity, CHANNELS, id(row), lock);
    }

    static boolean sectionLocked(Context activity, String section) {
        return prefs(activity).getStringSet(SECTIONS, Collections.emptySet()).contains(section);
    }

    static void setSection(Context activity, String section, boolean lock) {
        toggle(activity, SECTIONS, section, lock);
    }

    private static void toggle(Context activity, String pref, String value, boolean on) {
        Set<String> values = new HashSet<>(prefs(activity).getStringSet(pref, Collections.emptySet()));
        if (on) values.add(value);
        else values.remove(value);
        prefs(activity).edit().putStringSet(pref, values).apply();
    }

    /**
     * Keeps locked channels locked when their duplicate keys change (database version 9). A lock
     * is never removed by this: the old key stays too, in case a list brings it back.
     */
    static void rekey(Context context, java.util.Map<String, String> moved) {
        Set<String> locked = prefs(context).getStringSet(CHANNELS, Collections.emptySet());
        if (locked.isEmpty()) return;
        Set<String> values = new HashSet<>(locked);
        for (String key : locked) {
            String now = moved.get(key);
            if (now != null && !now.isEmpty()) values.add(now);
        }
        if (!values.equals(locked)) prefs(context).edit().putStringSet(CHANNELS, values).commit();
    }

    static void setPin(Context activity, String pin) {
        prefs(activity).edit().putString(PIN, hash(pin)).apply();
    }

    /** Turns the lock off: forgets the PIN and every locked channel and section. */
    static void clear(Context activity) {
        prefs(activity).edit().remove(PIN).remove(CHANNELS).remove(SECTIONS).apply();
    }

    static boolean matches(Context activity, String pin) {
        return hasPin(activity) && hash(pin).equals(prefs(activity).getString(PIN, ""));
    }

    static boolean valid(String pin) {
        return pin.matches("\\d{4}");
    }

    private static String hash(String pin) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                ("moviehub-live-pin:" + pin).getBytes(StandardCharsets.UTF_8)
            );
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Interface for a PIN typed into {@link #ask}. */
    interface Entered {
        void accept(String pin);
    }

    /**
     * A panel asking for four digits (the remote's number keys type them). {@code entered} gets
     * the digits when OK is pressed; {@code cancelled} runs when the panel closes without them.
     */
    static void ask(Activity activity, String title, String message, Entered entered, Runnable cancelled) {
        EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setTextSize(28);
        field.setTextColor(0xfff8f8fc);
        field.setHint("····");
        field.setHintTextColor(0x88f8f8fc);
        field.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(4) });
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        boolean[] done = { false };
        android.app.Dialog[] panel = new android.app.Dialog[1];
        Runnable submit = () -> {
            done[0] = true;
            if (panel[0] != null) panel[0].dismiss();
            entered.accept(field.getText().toString());
        };
        field.setOnEditorActionListener((v, action, event) -> {
            submit.run();
            return true;
        });
        field.setOnKeyListener((v, key, event) -> {
            boolean ok = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
            if (ok && event.getAction() == KeyEvent.ACTION_UP) submit.run();
            return ok;
        });
        panel[0] = SidePanel.of(activity, title)
            .message(message)
            .content(field)
            .action("OK", () -> {
                if (!done[0]) {
                    done[0] = true;
                    entered.accept(field.getText().toString());
                }
            })
            .action("Cancel", null)
            // Checked after the panel's own action has run, so OK is never taken for Back.
            .onDismiss(() ->
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (!done[0] && cancelled != null) {
                        done[0] = true;
                        cancelled.run();
                    }
                })
            )
            .show();
        field.requestFocus();
    }

    /** Asks for the PIN; runs {@code unlocked} when it is right. Wrong or cancelled runs {@code refused}. */
    static void check(Activity activity, String what, Runnable unlocked, Runnable refused) {
        ask(
            activity,
            "Enter your PIN",
            what + " is locked. Type your four-digit PIN with the remote's number keys.",
            pin -> {
                if (matches(activity, pin)) unlocked.run();
                else {
                    Toast.makeText(activity, "Wrong PIN", Toast.LENGTH_SHORT).show();
                    if (refused != null) refused.run();
                }
            },
            refused
        );
    }

    /** Asks for a new PIN twice, then saves it. */
    static void choosePin(Activity activity, Runnable saved) {
        ask(
            activity,
            "Choose a PIN",
            "Choose four digits. You will need them to watch locked channels and to change the lock.",
            first -> {
                if (!valid(first)) {
                    Toast.makeText(activity, "The PIN must be four digits", Toast.LENGTH_SHORT).show();
                    return;
                }
                ask(
                    activity,
                    "Confirm your PIN",
                    "Type the same four digits again.",
                    second -> {
                        if (!first.equals(second)) {
                            Toast.makeText(activity, "The PINs did not match", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        setPin(activity, first);
                        Toast.makeText(activity, "PIN saved", Toast.LENGTH_SHORT).show();
                        if (saved != null) saved.run();
                    },
                    null
                );
            },
            null
        );
    }
}
