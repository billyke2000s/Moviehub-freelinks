package local.tv.home;

import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Background-only request/reply. File descriptors avoid Binder's one-MiB transaction limit. */
final class ProviderBridge {

    private static final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private static final Object LOCK = new Object();

    private ProviderBridge() {}

    static String call(Context context, String source, String operation, String payload) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Background only");
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            CountDownLatch ready = new CountDownLatch(1);
            IBinder[] remote = new IBinder[1];
            CountDownLatch dead = new CountDownLatch(1);
            ServiceConnection connection = new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    remote[0] = binder;
                    try {
                        binder.linkToDeath(dead::countDown, 0);
                    } catch (RemoteException e) {
                        dead.countDown();
                    }
                    ready.countDown();
                }

                public void onServiceDisconnected(ComponentName name) {
                    ready.countDown();
                }

                public void onNullBinding(ComponentName name) {
                    ready.countDown();
                }
            };
            File input = File.createTempFile("provider-in", ".json", app.getCacheDir());
            File output = File.createTempFile("provider-out", ".json", app.getCacheDir());
            java.util.concurrent.atomic.AtomicBoolean bound = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable disconnect = () -> {
                if (bound.compareAndSet(true, false)) app.unbindService(connection);
            };
            ScheduledFuture<?> timeout = null;
            try {
                byte[] request = new JSONObject()
                    .put("source", source)
                    .put("operation", operation)
                    .put("payload", payload)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
                if (request.length > ProviderScript.MAX_BYTES) throw new IOException("Provider input limit");
                try (OutputStream out = new FileOutputStream(input)) {
                    out.write(request);
                }
                if (
                    !app.bindService(
                        new Intent(app, ProviderService.class),
                        connection,
                        Context.BIND_AUTO_CREATE
                    )
                ) {
                    throw new IOException("Provider service unavailable");
                }
                bound.set(true);
                timeout = watchdog.schedule(
                    disconnect,
                    operation.equals("resolve") ? 15 : 10,
                    TimeUnit.SECONDS
                );
                if (!ready.await(5, TimeUnit.SECONDS) || remote[0] == null) throw new IOException(
                    "Provider startup failed"
                );
                Parcel data = Parcel.obtain(),
                    reply = Parcel.obtain();
                try (
                    ParcelFileDescriptor in = ParcelFileDescriptor.open(
                        input,
                        ParcelFileDescriptor.MODE_READ_ONLY
                    );
                    ParcelFileDescriptor out = ParcelFileDescriptor.open(
                        output,
                        ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE
                    )
                ) {
                    data.writeInterfaceToken(ProviderService.DESCRIPTOR);
                    data.writeParcelable(in, 0);
                    data.writeParcelable(out, 0);
                    if (!remote[0].transact(ProviderService.EVALUATE, data, reply, 0)) throw new IOException(
                        "Provider protocol failed"
                    );
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
                try (InputStream in = new FileInputStream(output)) {
                    return ProviderService.read(in);
                }
            } finally {
                if (timeout != null) timeout.cancel(false);
                disconnect.run();
                if (remote[0] != null) {
                    try {
                        dead.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                input.delete();
                output.delete();
            }
        }
    }
}
