package local.tv.home;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class CatalogueCacheTest {

    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicInteger loads = new AtomicInteger();
        CatalogueCache cache = new CatalogueCache(2, 100, 10, clock::get);
        CatalogueCache.Loader load = () -> "value" + loads.incrementAndGet();
        check(cache.get("a", load).equals("value1"), "Initial response");
        check(cache.get("a", load).equals("value1"), "Reuse fresh response");
        check(loads.get() == 1, "No repeated request while fresh");
        clock.set(10);
        check(cache.get("a", load).equals("value2"), "Expiry fetches a new response");
        cache.get("b", load);
        cache.get("a", load);
        cache.get("c", load);
        check(cache.get("a", load).equals("value2"), "Recently visited response survives eviction");
        check(cache.get("b", load).equals("value5"), "Oldest response is evicted");

        CatalogueCache tiny = new CatalogueCache(10, 8, 10, clock::get);
        AtomicInteger large = new AtomicInteger();
        tiny.get("a", () -> "large" + large.incrementAndGet());
        tiny.get("a", () -> "large" + large.incrementAndGet());
        check(large.get() == 2, "Oversized responses are returned without retention");
        CatalogueCache bytes = new CatalogueCache(10, 12, 10, clock::get);
        bytes.get("a", () -> "aaa");
        bytes.get("b", () -> "bbb");
        check(bytes.get("a", () -> "new").equals("new"), "Byte budget evicts before entry limit");

        try {
            cache.get("failure", () -> {
                throw new IOException("fixture");
            });
            throw new AssertionError("Expected fixture failure");
        } catch (IOException expected) {
            check(expected.getMessage().equals("fixture"), "Original failure is preserved");
        }
        check(cache.get("failure", () -> "recovered").equals("recovered"), "Failures are not cached");

        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            CatalogueCache shared = new CatalogueCache();
            CountDownLatch ready = new CountDownLatch(4),
                start = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            List<Future<String>> readers = new ArrayList<>();
            for (int i = 0; i < 4; i++) readers.add(
                workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start timeout");
                    return shared.get("same", () -> {
                        calls.incrementAndGet();
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Release timeout");
                        return "shared";
                    });
                })
            );
            check(ready.await(5, TimeUnit.SECONDS), "Concurrent readers started");
            start.countDown();
            check(entered.await(5, TimeUnit.SECONDS), "Request started");
            check(shared.get("other", () -> "independent").equals("independent"), "Other keys do not block");
            release.countDown();
            for (Future<String> reader : readers)
                check(
                    reader.get(5, TimeUnit.SECONDS).equals("shared"),
                    "Shared response reaches each caller"
                );
            check(calls.get() == 1, "Concurrent identical lookups make one request");

            CountDownLatch blocked = new CountDownLatch(1),
                complete = new CountDownLatch(1);
            Future<String> running = workers.submit(() ->
                shared.get("pending", () -> {
                    blocked.countDown();
                    if (!complete.await(5, TimeUnit.SECONDS)) throw new AssertionError("Clear timeout");
                    return "old";
                })
            );
            check(blocked.await(5, TimeUnit.SECONDS), "In-flight request started");
            shared.clear();
            complete.countDown();
            check(
                running.get(5, TimeUnit.SECONDS).equals("old"),
                "Memory trim does not cancel current reader"
            );
            check(shared.get("pending", () -> "new").equals("new"), "Cleared request does not refill cache");
        } finally {
            workers.shutdownNow();
        }
        System.out.println(checks + " catalogue cache checks passed");
    }
}
