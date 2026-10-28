package local.tv.home;

import android.graphics.*;
import android.os.*;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import java.util.*;
import java.util.concurrent.*;

/** Loads attached artwork first, including recycled cards originally bound off screen. */
final class Artwork {

    interface Fetcher {
        Bitmap fetch(String url) throws Exception;
    }

    /** Decoded images in memory: an eighth of the app's memory, between 12 and 64 MB. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
        (int) Math.max(12L << 20, Math.min(64L << 20, Runtime.getRuntime().maxMemory() / 8))
    ) {
        @Override
        protected int sizeOf(String key, Bitmap image) {
            return image.getByteCount();
        }
    };
    /** Downloads at once; artwork comes from a CDN, so a few in parallel fill a row quickly. */
    static final int PARALLEL = 4;
    /** Artwork kept on disk between sessions; set once by the activity. */
    static volatile ArtworkDiskCache disk;
    private final ThreadPoolExecutor downloads = (ThreadPoolExecutor) Executors.newFixedThreadPool(PARALLEL);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakHashMap<ImageView, Binding> bindings = new WeakHashMap<>();
    private final LinkedHashSet<String> pending = new LinkedHashSet<>();
    private final Set<String> running = new HashSet<>();
    private final Map<String, Integer> failures = new HashMap<>();
    private final Fetcher fetcher;
    private boolean closed;

    Artwork() {
        this(Artwork::download);
    }

    Artwork(Fetcher fetcher) {
        this.fetcher = fetcher;
        downloads.setKeepAliveTime(30, TimeUnit.SECONDS);
        downloads.allowCoreThreadTimeOut(true);
    }

    private final class Binding implements View.OnAttachStateChangeListener {

        String url;

        @Override
        public void onViewAttachedToWindow(View view) {
            if (!closed) request((ImageView) view, url);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            pump();
        }
    }

    void load(ImageView view, String url) {
        if (closed) return;
        Binding binding = bindings.get(view);
        if (binding == null) {
            binding = new Binding();
            bindings.put(view, binding);
            view.addOnAttachStateChangeListener(binding);
        }
        binding.url = url;
        view.setTag(url);
        view.setBackgroundColor(0xff182337);
        view.setImageDrawable(null);
        request(view, url);
    }

    private void request(ImageView view, String url) {
        if (closed || url == null || !url.equals(view.getTag())) return;
        Bitmap image = CACHE.get(url);
        if (image != null) {
            view.setImageBitmap(image);
            return;
        }
        if (view.isAttachedToWindow()) {
            pending.add(url);
            pump();
        }
    }

    private boolean visible(String url) {
        for (Map.Entry<ImageView, Binding> entry : bindings.entrySet())
            if (
                entry.getKey().isAttachedToWindow() &&
                url.equals(entry.getValue().url) &&
                url.equals(entry.getKey().getTag())
            ) return true;
        return false;
    }

    private void pump() {
        if (closed) return;
        Iterator<String> queued = pending.iterator();
        while (queued.hasNext()) {
            String url = queued.next();
            if (!visible(url) || running.contains(url)) {
                queued.remove();
                continue;
            }
            if (running.size() >= PARALLEL) continue;
            queued.remove();
            running.add(url);
            downloads.execute(() -> {
                Bitmap result = null;
                try {
                    result = CACHE.get(url);
                    if (result == null) result = fetcher.fetch(url);
                    if (result != null) CACHE.put(url, result);
                } catch (Exception e) {
                    Diagnostics.recovered("Artwork download", e);
                    /* Bounded retries while this artwork is visible. */
                }
                Bitmap ready = result;
                main.post(() -> complete(url, ready));
            });
        }
    }

    private void complete(String url, Bitmap image) {
        if (closed) return;
        running.remove(url);
        if (image != null) {
            failures.remove(url);
            for (Map.Entry<ImageView, Binding> entry : bindings.entrySet()) {
                ImageView view = entry.getKey();
                if (
                    view.isAttachedToWindow() && url.equals(entry.getValue().url) && url.equals(view.getTag())
                ) view.setImageBitmap(image);
            }
        } else if (visible(url)) {
            int attempt = failures.getOrDefault(url, 0) + 1;
            failures.put(url, attempt);
            if (attempt <= 2) main.postDelayed(() -> {
                if (!closed && visible(url)) {
                    pending.add(url);
                    pump();
                }
            }, attempt * 700L);
        }
        failures.keySet().removeIf(key -> !visible(key));
        pump();
    }

    private static Bitmap download(String url) throws Exception {
        ArtworkDiskCache store = disk;
        byte[] bytes = store == null ? null : store.get(url);
        boolean fresh = bytes == null;
        if (fresh) {
            long started = Timings.start();
            bytes = Network.bytes(url, null, 3 * 1024 * 1024, false);
            Timings.record("Artwork download", started);
        }
        BitmapFactory.Options size = new BitmapFactory.Options();
        size.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, size);
        if (
            size.outWidth <= 0 || size.outHeight <= 0 || size.outWidth > 8000 || size.outHeight > 8000
        ) return null;
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = 1;
        while (size.outWidth / decode.inSampleSize > 1280 || size.outHeight / decode.inSampleSize > 720)
            decode.inSampleSize *= 2;
        Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decode);
        // Store only what decoded as an image, so a broken download is not kept.
        if (image != null && fresh && store != null) store.put(url, bytes);
        return image;
    }

    void close() {
        closed = true;
        downloads.shutdownNow();
        main.removeCallbacksAndMessages(null);
        for (Map.Entry<ImageView, Binding> entry : bindings.entrySet())
            entry.getKey().removeOnAttachStateChangeListener(entry.getValue());
        bindings.clear();
        pending.clear();
        running.clear();
        failures.clear();
    }

    static void trimMemory(boolean critical) {
        if (critical) CACHE.evictAll();
        else CACHE.trimToSize(CACHE.maxSize() / 2);
    }
}
