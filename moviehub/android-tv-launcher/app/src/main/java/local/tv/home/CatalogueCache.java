package local.tv.home;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.LongSupplier;

/** Short-lived public catalogue responses, with one fetch per concurrent identical request. */
final class CatalogueCache {

    interface Loader {
        String load() throws Exception;
    }

    private static final class Entry {

        final String body;
        final long storedAt, weight;

        Entry(String body, long storedAt, long weight) {
            this.body = body;
            this.storedAt = storedAt;
            this.weight = weight;
        }
    }

    private static final class Flight {

        final FutureTask<String> task;
        final long generation;

        Flight(Loader loader, long generation) {
            task = new FutureTask<>(loader::load);
            this.generation = generation;
        }
    }

    private final int maxEntries;
    private final long maxBytes, ttlMs;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, Flight> running = new java.util.HashMap<>();
    private long weight, generation;

    CatalogueCache() {
        this(64, 2 * 1024 * 1024, 60_000, () -> System.nanoTime() / 1_000_000);
    }

    CatalogueCache(int maxEntries, long maxBytes, long ttlMs, LongSupplier clock) {
        if (maxEntries < 1 || maxBytes < 1 || ttlMs < 1) throw new IllegalArgumentException();
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    String get(String key, Loader loader) throws Exception {
        Flight flight;
        boolean owner;
        synchronized (this) {
            Entry cached = entries.get(key);
            if (cached != null) {
                if (clock.getAsLong() - cached.storedAt < ttlMs) return cached.body;
                entries.remove(key);
                weight -= cached.weight;
            }
            flight = running.get(key);
            owner = flight == null;
            if (owner) {
                flight = new Flight(loader, generation);
                running.put(key, flight);
            }
        }
        try {
            // No cache lock is held while fetching or waiting. Unrelated requests stay independent.
            if (owner) flight.task.run();
            String body = flight.task.get();
            if (owner && body != null) synchronized (this) {
                long bytes = 2L * (key.length() + body.length());
                if (flight.generation == generation && bytes <= maxBytes) {
                    entries.put(key, new Entry(body, clock.getAsLong(), bytes));
                    weight += bytes;
                    Iterator<Entry> oldest = entries.values().iterator();
                    while (entries.size() > maxEntries || weight > maxBytes) {
                        weight -= oldest.next().weight;
                        oldest.remove();
                    }
                }
            }
            return body;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } finally {
            if (owner) synchronized (this) {
                running.remove(key, flight);
            }
        }
    }

    synchronized void clear() {
        entries.clear();
        weight = 0;
        // In-flight readers may finish, but cannot refill a cache cleared for memory pressure.
        generation++;
    }
}
