// Sustained diagnostic only. Resolve using the actual bundled interpreter, then decode BOTH
// tracks through production ProviderRelay/PlaylistSession/ProviderPngSegments on the JVM.
// No provider URLs, tokens, pages or captured media are printed or uploaded.
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { writeFileSync } from "node:fs";

process.env.MOVIEHUB_PROBE_LIBRARY = "1";
const { run } = await import("./live-probe.mjs");
const report = [];
const stats = () => ({
    hosts: new Set(),
    requests: 0,
    steps: 0,
    slowestStep: 0,
    maxResponse: 0,
    aggregate: 0,
    httpErrors: [],
    networkErrors: [],
});

async function inspect(provider, row, forceRenew = false) {
    const result = {
        provider,
        channel: String(row.name).slice(0, 80),
        seconds: 150,
        forcedRenewal: forceRenew,
    };
    let relay;
    const resolutionStats = stats();
    try {
        const stream = await run(provider, "resolve", row, resolutionStats);
        relay = spawn(
            "java",
            [
                "-Xmx192m",
                // Desktop HttpURLConnection otherwise silently drops Origin; Android sends it.
                "-Dsun.net.http.allowRestrictedHeaders=true",
                "-cp",
                process.env.JSON_JAR + ":build/provider-tests",
                "local.tv.home.ProviderRelayProbe",
            ],
            { stdio: ["pipe", "pipe", "pipe"] },
        );
        const messages = createInterface({ input: relay.stdout });
        const ready = new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error("Relay deadline")), 20000);
            messages.once("line", (line) => {
                clearTimeout(timer);
                try {
                    resolve(JSON.parse(line).relay);
                } catch {
                    reject(new Error("Relay protocol"));
                }
            });
            relay.once("exit", () => {
                clearTimeout(timer);
                reject(new Error("Relay exited"));
            });
        });
        const relayErrors = {};
        relay.stderr.on("data", (bytes) => {
            for (const line of bytes.toString().split("\n")) {
                const kind = /: ([A-Za-z0-9_]+)$/.exec(line.trim());
                if (kind) relayErrors[kind[1]] = (relayErrors[kind[1]] || 0) + 1;
            }
        });
        if (forceRenew && stream.playlistSession) stream.playlistSession.expiresAtMs = 0;
        relay.stdin.write(JSON.stringify(stream) + "\n");
        const url = await ready;
        const tracks = [0, 1].map(() => ({ frames: 0, unique: 0, start: null, end: null, maxGap: 0 }));
        const bases = [];
        const hashes = [new Set(), new Set()];
        let warnings = 0;
        const decoder = spawn(
            "ffmpeg",
            [
                "-v",
                "warning",
                "-rw_timeout",
                "20000000",
                "-allowed_extensions",
                "ALL",
                "-i",
                url,
                "-t",
                "150",
                "-map",
                "0:v:0",
                "-map",
                "0:a:0",
                "-f",
                "framemd5",
                "pipe:1",
            ],
            { stdio: ["ignore", "pipe", "pipe"] },
        );
        const lines = createInterface({ input: decoder.stdout });
        lines.on("line", (line) => {
            const base = /^#tb (\d+): (\d+)\/(\d+)/.exec(line);
            if (base) bases[Number(base[1])] = Number(base[2]) / Number(base[3]);
            if (line.startsWith("#")) return;
            const values = line.split(",").map((value) => value.trim());
            const index = Number(values[0]);
            if (values.length < 6 || !tracks[index] || !bases[index]) return;
            const track = tracks[index];
            const time = Number(values[2]) * bases[index];
            const end = time + Number(values[3]) * bases[index];
            if (track.end !== null) track.maxGap = Math.max(track.maxGap, time - track.end);
            if (track.start === null) track.start = time;
            track.end = end;
            track.frames++;
            hashes[index].add(values[5]);
        });
        decoder.stderr.on("data", (bytes) => {
            warnings += bytes.toString().split("\n").filter(Boolean).length;
        });
        const code = await new Promise((resolve) => {
            const timer = setTimeout(() => decoder.kill("SIGKILL"), 240000);
            decoder.once("close", (value) => {
                clearTimeout(timer);
                resolve(value);
            });
        });
        result.exit = code;
        result.tracks = tracks.map((track, i) => ({
            frames: track.frames,
            unique: hashes[i].size,
            duration: track.end === null ? 0 : +(track.end - track.start).toFixed(3),
            maxGap: +track.maxGap.toFixed(3),
        }));
        result.warningLines = warnings;
        result.relayErrors = relayErrors;
        result.bothDecoded =
            code === 0 && result.tracks.every((track) => track.duration >= 145 && track.frames > 10);
    } catch (error) {
        result.failure = error.constructor.name;
        const safe = [
            "FreeShot page unavailable",
            "FreeShot player unavailable",
            "Invalid FreeShot player configuration",
            "FreeShot stream scope rejected",
            "Invalid FreeShot token",
            "Invalid FreeShot operation or channel",
            "operation over the app's time limit",
            "host not on the app's allow-list",
        ];
        result.reason = safe.find((value) => String(error.message).includes(value)) || "Unclassified";
        if (provider === "freeshot") {
            result.channelPath = row.channelId;
            result.trace = resolutionStats.iframeTrace || [];
        }
    } finally {
        if (relay) {
            relay.stdin.end("\n");
            setTimeout(() => relay.kill("SIGKILL"), 1000).unref();
        }
    }
    return result;
}

if (process.env.SPORTS_PROBE_ONLY !== "freeshot")
    try {
        const rows = await run("dlhd", "catalogue", null, stats());
        const selected = [
            rows.find((row) => /sky.*sports.*main.*event/i.test(row.name || "")),
            rows.find((row) => /sky.*sports.*football/i.test(row.name || "")),
        ].filter(Boolean);
        for (const row of selected) {
            const result = await inspect("dlhd", row);
            report.push(result);
            console.log(JSON.stringify(result));
        }
        if (!selected.length) report.push({ failure: "No matching catalogue rows" });
    } catch (error) {
        report.push({ failure: error.constructor.name });
    }
try {
    const rows = await run("freeshot", "catalogue", null, stats());
    const row = rows.find((value) => /sky.*sports.*main.*event/i.test(value.name || ""));
    if (!row) report.push({ provider: "freeshot", failure: "No Main Event catalogue row" });
    else report.push(await inspect("freeshot", row, true));
} catch (error) {
    report.push({ provider: "freeshot", failure: error.constructor.name });
}
writeFileSync("build/provider-tests/dlhd-playback.json", JSON.stringify(report, null, 2));
console.log("DLHD_PLAYBACK_RESULT " + JSON.stringify(report));
