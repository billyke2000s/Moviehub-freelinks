package local.tv.home;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Shares only the checked cached update APK with the system installer. */
public class UpdateApkProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    private File apk(Uri uri) throws FileNotFoundException {
        if (
            !"/update.apk".equals(uri.getPath()) ||
            !uri.getAuthority().equals(getContext().getPackageName() + ".updates")
        ) throw new FileNotFoundException();
        return new File(getContext().getCacheDir(), "updates/update.apk");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(apk(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File file = apk(uri);
            String[] columns =
                projection == null
                    ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }
                    : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) row[i] = columns[i].equals(OpenableColumns.DISPLAY_NAME)
                ? "home.apk"
                : columns[i].equals(OpenableColumns.SIZE)
                  ? file.length()
                  : null;
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
