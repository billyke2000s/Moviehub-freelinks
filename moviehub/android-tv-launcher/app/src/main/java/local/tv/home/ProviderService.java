package local.tv.home;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** A disposable isolated UID: no app accounts, databases, files or INTERNET permission. */
public class ProviderService extends Service {

    static final String DESCRIPTOR = "local.tv.home.provider.v1";
    static final int EVALUATE = IBinder.FIRST_CALL_TRANSACTION;

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != EVALUATE) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            try (
                ParcelFileDescriptor input = data.readParcelable(ParcelFileDescriptor.class.getClassLoader());
                ParcelFileDescriptor output = data.readParcelable(
                    ParcelFileDescriptor.class.getClassLoader()
                );
                InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(input);
                OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(output)
            ) {
                JSONObject request = new JSONObject(read(in));
                String result = ProviderScript.evaluate(
                    request.getString("source"),
                    request.getString("operation"),
                    request.getString("payload"),
                    reuseCompiledCode()
                );
                out.write(result.getBytes(StandardCharsets.UTF_8));
                reply.writeNoException();
            } catch (Exception e) {
                StringBuilder diagnostic = new StringBuilder("Provider operation failed");
                Throwable cause = e;
                for (int depth = 0; cause != null && depth < 4; depth++, cause = cause.getCause()) {
                    diagnostic.append(" / ").append(cause.getClass().getSimpleName());
                    if (cause instanceof org.mozilla.javascript.RhinoException) {
                        diagnostic
                            .append(" script line ")
                            .append(((org.mozilla.javascript.RhinoException) cause).lineNumber());
                    }
                    if (cause instanceof android.system.ErrnoException) {
                        diagnostic.append(" errno ").append(((android.system.ErrnoException) cause).errno);
                    }
                    StackTraceElement[] frames = cause.getStackTrace();
                    if (frames.length > 0) diagnostic
                        .append(" at ")
                        .append(frames[0].getClassName())
                        .append('.')
                        .append(frames[0].getMethodName())
                        .append(':')
                        .append(frames[0].getLineNumber());
                }
                // Types and code locations only: never messages, URLs, responses or tokens.
                reply.writeException(new IllegalStateException(diagnostic.toString()));
            }
            return true;
        }
    };

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > ProviderScript.MAX_BYTES) throw new IOException("Provider input limit");
            out.write(buffer, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    protected boolean reuseCompiledCode() {
        return false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Discard the entire heap after every operation, including code that got stuck in a native regex.
        android.os.Process.killProcess(android.os.Process.myPid());
        return false;
    }
}
