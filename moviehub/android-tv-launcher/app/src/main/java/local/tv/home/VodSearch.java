package local.tv.home;

import android.content.Context;
import android.os.SystemClock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** One bounded search continues collecting backups after the first stream has started. */
final class VodSearch implements AutoCloseable {

    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger pending = new AtomicInteger(1);
    private final SourceWindow<SourceRepository.Source> window;
    private final int target;
    private final long deadline;
    private final List<java.util.function.Consumer<SourceRepository.Source>> observers = new ArrayList<>();
    private static volatile VodSearch current;
    private final Map<String, String> outcomes = new ConcurrentHashMap<>();
    private final Map<String, String> hostOutcomes = new ConcurrentHashMap<>();

    VodSearch(Context context, JSONObject query, SourceRepository repo, boolean premiumize, int quality) {
        VodSearch previous = current;
        if (previous != null) previous.close();
        current = this;
        long started = SystemClock.elapsedRealtime();
        // One entry per website; discovery and host resolution have separate bounded budgets.
        deadline = started + 420000;
        pending.set(premiumize ? 2 : 1);
        try {
            query.put("maxQuality", quality);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid source quality", error);
        }
        window = new SourceWindow<>(started, source ->
            SourceRanking.readyRank(
                source.ranking == null ? source.title : source.ranking,
                quality,
                source.probeMbps,
                source.responseMs,
                source.confirmed
            )
        );
        // Good enough to start at once: the preferred quality, but 1080p when 4K is preferred.
        target = Math.min(quality, 1080);
        Context app = context.getApplicationContext();
        // Runtime extraction and imports must never hold the UI thread.
        workers.submit(() -> {
            try {
                List<String> ids = FreeVodProviders.ids(app, query.optInt("season", -1) >= 0);
                for (String id : ids) outcomes.put(id, "Queued");
                for (String id : ids) {
                    if (cancelled.get()) break;
                    pending.incrementAndGet();
                    workers.submit(() -> {
                        try {
                            if (expired()) outcomes.put(id, "Search ended before starting");
                            else {
                                outcomes.put(id, "Searching");
                                JSONObject report = new JSONObject(
                                    FreeVodProviders.search(app, id, query, cancelled, this::found)
                                );
                                outcomes.put(id, providerOutcome(report));
                                JSONArray hosts = report.optJSONArray("hostAttempts");
                                if (hosts != null) for (int i = 0; i < hosts.length(); i++) {
                                    JSONObject host = hosts.getJSONObject(i);
                                    String state = host.optString("status");
                                    host.put("stage", "hosts");
                                    String label = state.equals("complete")
                                        ? "Playable links found"
                                        : state.equals("extracted")
                                          ? "Host links extracted"
                                          : providerOutcome(host);
                                    hostOutcomes.put(id + " → " + host.optString("provider"), label);
                                }
                            }
                        } catch (Exception error) {
                            outcomes.put(id, "Adapter error: " + error.getClass().getSimpleName());
                            Diagnostics.recovered("Free provider search", error);
                        } finally {
                            if (pending.decrementAndGet() == 0) workers.shutdown();
                        }
                    });
                }
            } catch (Exception error) {
                Diagnostics.recovered("Loading free providers", error);
            } finally {
                pending.decrementAndGet();
                if (pending.get() == 0) workers.shutdown();
            }
        });
        if (premiumize) {
            workers.submit(() -> {
                try {
                    List<SourceRepository.Source> sources = repo.torrentio(
                        query.getString("imdb"),
                        query.getInt("season"),
                        query.getInt("episode")
                    );
                    sources.sort(
                        Comparator.comparingInt((SourceRepository.Source source) ->
                            SourceRanking.rank(source.title, quality)
                        ).reversed()
                    );
                    int attempted = 0;
                    for (SourceRepository.Source source : sources) {
                        if (expired() || attempted >= 5) break;
                        if (source.cache != SourceRepository.Cache.CACHED) continue;
                        attempted++;
                        JSONArray files = repo.direct(source);
                        int pick = AutoPlayback.pick(
                            files,
                            query.getInt("season"),
                            query.getInt("episode"),
                            query.getString("title")
                        );
                        if (pick >= 0) found(
                            new SourceRepository.Source(source.title, source.uri, files, new JSONObject())
                        );
                    }
                } catch (Exception error) {
                    Diagnostics.recovered("Optional cloud search", error);
                } finally {
                    if (pending.decrementAndGet() == 0) workers.shutdown();
                }
            });
        }
    }

    static String providerOutcome(JSONObject report) {
        String status = report.optString("status");
        switch (status) {
            case "complete":
                return report.optJSONArray("sources").length() + " playable links";
            case "no_matches":
                return "No matching links returned";
            case "local_library":
                return "Kodi library only";
            case "requires_api_key":
                return "Requires an API key";
            case "interaction_required":
                return "Requires browser verification";
            case "preparing":
                return "Host is preparing this file";
            case "unsupported_type":
                return "Does not search this media type";
            case "timeout":
                return "Search timed out";
            case "rejected_media":
                return "Links rejected (short video or invalid media)";
            case "resolution_failed":
                return "Host links could not be resolved";
            case "unsupported_host":
                return "No supported anonymous host";
            case "gaierror":
                return "Domain lookup failed";
            case "SSLError":
                return "TLS connection failed";
            case "ConnectTimeout":
            case "ReadTimeout":
            case "Timeout":
                return "Connection timed out";
            case "ConnectionError":
                return "Connection failed";
            default:
                return status.matches("http_[0-9]{3}")
                    ? (java.util.Arrays.asList("hosts", "resolution", "media").contains(
                          report.optString("stage")
                      )
                          ? "Playback host returned HTTP "
                          : "Website returned HTTP ") + status.substring(5)
                    : "Adapter could not complete";
        }
    }

    String report() {
        StringBuilder text = new StringBuilder();
        for (String id : new TreeSet<>(outcomes.keySet())) {
            if (text.length() > 0) text.append("\n");
            text.append(id).append(": ").append(outcomes.get(id));
        }
        for (String id : new TreeSet<>(hostOutcomes.keySet())) {
            if (text.length() > 0) text.append("\n");
            text.append(id).append(": ").append(hostOutcomes.get(id));
        }
        return text.length() == 0 ? "Loading bundled providers…" : text.toString();
    }

    String progress() {
        int complete = 0;
        for (String outcome : outcomes.values())
            if (!outcome.equals("Queued") && !outcome.equals("Searching")) complete++;
        return complete + "/" + outcomes.size() + " websites checked";
    }

    private boolean expired() {
        if (SystemClock.elapsedRealtime() >= deadline) close();
        return cancelled.get();
    }

    static String sourceKey(SourceRepository.Source source) {
        return SourcesScreen.provider(source.title) + "\n" + source.uri;
    }

    private synchronized void found(SourceRepository.Source source) {
        if (expired() || !window.add(sourceKey(source), source)) return;
        for (var observer : observers) observer.accept(source);
    }

    synchronized void observe(java.util.function.Consumer<SourceRepository.Source> observer) {
        for (var source : snapshot()) observer.accept(source);
        if (!cancelled.get()) observers.add(observer);
    }

    List<SourceRepository.Source> snapshot() {
        return window.snapshot();
    }

    SourceRepository.Source selected() {
        return window.pick(SystemClock.elapsedRealtime(), finished(), target, VodSearch::startQuality);
    }

    /** A camera copy never counts as good enough to stop waiting for a better source. */
    static int startQuality(SourceRepository.Source source) {
        String label = source.ranking == null ? source.title : source.ranking;
        return SourceRanking.camera(label) ? 0 : SourceRanking.resolution(label);
    }

    boolean finished() {
        return expired() || pending.get() == 0;
    }

    public synchronized void close() {
        cancelled.set(true);
        observers.clear();
        workers.shutdownNow();
        if (current == this) current = null;
    }
}
