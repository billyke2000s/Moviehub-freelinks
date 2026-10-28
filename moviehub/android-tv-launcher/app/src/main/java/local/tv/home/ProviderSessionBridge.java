package local.tv.home;

import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONObject;

/** One disposable isolated process for a complete DaddyLive lookup. */
final class ProviderSessionBridge implements ProviderBroker.Executor, AutoCloseable {

    private static final ReentrantLock lock = new ReentrantLock();
    private static final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    static final AtomicInteger bindings = new AtomicInteger();
    private final Context app;
    private final CountDownLatch ready = new CountDownLatch(1),
        dead = new CountDownLatch(1);
    private final AtomicBoolean bound = new AtomicBoolean();
    private volatile IBinder binder;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder remote) {
            binder = remote;
            try {
                remote.linkToDeath(dead::countDown, 0);
            } catch (RemoteException e) {
                dead.countDown();
            }
            ready.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            dead.countDown();
            ready.countDown();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            ready.countDown();
        }
    };

    private ProviderSessionBridge(Context context) throws Exception {
        app = context.getApplicationContext();
        Intent intent = new Intent(app, FastProviderService.class);
        if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) throw new IOException(
            "Provider session unavailable"
        );
        bound.set(true);
        bindings.incrementAndGet();
        try {
            if (!ready.await(5, TimeUnit.SECONDS) || binder == null) throw new IOException(
                "Provider startup failed"
            );
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    static Object run(
        Context context,
        String source,
        JSONObject row,
        ProviderBroker.Transport transport,
        NativeHttp http
    ) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Background only");
        lock.lockInterruptibly();
        long previous = http.deadlineNanos;
        try {
            http.check();
            http.deadlineNanos = Math.min(previous, System.nanoTime() + 25_000_000_000L);
            try (ProviderSessionBridge session = new ProviderSessionBridge(context)) {
                return ProviderBroker.run(source, "resolve", row, session, transport, http, null, true);
            }
        } finally {
            http.deadlineNanos = previous;
            lock.unlock();
        }
    }

    @Override
    public String call(String source, String operation, String payload) throws Exception {
        if (!bound.get() || dead.getCount() == 0) throw new IOException("Provider session ended");
        File input = File.createTempFile("provider-fast-in", ".json", app.getCacheDir());
        File output = File.createTempFile("provider-fast-out", ".json", app.getCacheDir());
        ScheduledFuture<?> deadline = null;
        try {
            try (OutputStream out = new FileOutputStream(input)) {
                out.write(
                    new JSONObject()
                        .put("source", source)
                        .put("operation", operation)
                        .put("payload", payload)
                        .toString()
                        .getBytes(StandardCharsets.UTF_8)
                );
            }
            deadline = watchdog.schedule(this::close, 15, TimeUnit.SECONDS);
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
                if (!binder.transact(ProviderService.EVALUATE, data, reply, 0)) throw new IOException(
                    "Provider session protocol failed"
                );
                reply.readException();
            } finally {
                data.recycle();
                reply.recycle();
            }
            if (!bound.get()) throw new IOException("Provider session deadline");
            try (InputStream in = new FileInputStream(output)) {
                return ProviderService.read(in);
            }
        } finally {
            if (deadline != null) deadline.cancel(false);
            input.delete();
            output.delete();
        }
    }

    @Override
    public void close() {
        if (!bound.compareAndSet(true, false)) return;
        app.unbindService(connection);
        try {
            dead.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
