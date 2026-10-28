package local.tv.home;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import org.junit.*;
import org.junit.runner.RunWith;

/** Artwork bytes kept on disk: hits, misses and trimming to the size limit. */
@RunWith(AndroidJUnit4.class)
public class ArtworkDiskCacheTest {

    File folder;

    @Before
    public void setUp() {
        folder = new File(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
            "artwork-test"
        );
        clear();
    }

    @After
    public void clear() {
        File[] files = folder.listFiles();
        if (files != null) for (File file : files) file.delete();
        folder.delete();
    }

    @Test
    public void storesReadsAndTrimsLeastRecentlyUsed() throws Exception {
        ArtworkDiskCache cache = new ArtworkDiskCache(folder, 4000);
        assertNull(cache.get("https://image.example/a.jpg"));
        cache.put("https://image.example/a.jpg", new byte[1000]);
        assertArrayEquals(new byte[1000], cache.get("https://image.example/a.jpg"));
        cache.put("https://image.example/b.jpg", new byte[1000]);
        cache.put("https://image.example/c.jpg", new byte[1000]);
        // Make "a" the most recently used, then go over the limit.
        Thread.sleep(1100);
        assertNotNull(cache.get("https://image.example/a.jpg"));
        cache.put("https://image.example/d.jpg", new byte[1000]);
        cache.put("https://image.example/e.jpg", new byte[1000]);
        cache.trim();
        long total = 0;
        for (File file : folder.listFiles()) total += file.length();
        assertTrue("Within the limit after trimming: " + total, total <= 4000);
        assertNotNull("Recently used artwork is kept", cache.get("https://image.example/a.jpg"));
        assertNull("Oversized files are not stored", putAndGetLarge(cache));
    }

    private byte[] putAndGetLarge(ArtworkDiskCache cache) {
        cache.put("https://image.example/huge.jpg", new byte[2000]);
        return cache.get("https://image.example/huge.jpg");
    }

    @Test
    public void rejectsOversizedExistingFileWithoutAllocatingItsContents() throws Exception {
        ArtworkDiskCache cache = new ArtworkDiskCache(folder, ArtworkDiskCache.MAX_BYTES);
        cache.put("https://image.example/corrupt.jpg", new byte[8]);
        File[] files = folder.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(files[0], "rw")) {
            file.setLength(ArtworkDiskCache.MAX_IMAGE_BYTES + 1L);
        }
        assertNull(cache.get("https://image.example/corrupt.jpg"));
        assertFalse("Oversized cache entry removed", files[0].exists());
    }

    @Test
    public void cacheFilenameRemainsCompatibleWithExistingDownloads() throws Exception {
        String url = "https://image.example/existing.jpg";
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"));
        StringBuilder oldName = new StringBuilder();
        for (int i = 0; i < 16; i++) oldName.append(String.format("%02x", digest[i]));
        folder.mkdirs();
        java.nio.file.Files.write(new File(folder, oldName.toString()).toPath(), new byte[] { 1, 2, 3 });
        ArtworkDiskCache cache = new ArtworkDiskCache(folder, ArtworkDiskCache.MAX_BYTES);
        assertArrayEquals(new byte[] { 1, 2, 3 }, cache.get(url));
    }
}
