// Live provider probe: runs each bundled provider module against the real provider services,
// through the same interpreter (ProviderScript) and the same request rules and limits as the
// Android app (ProviderBroker.run / allowed, NativeHttp.exchange), then tries to decode a few
// resolved streams with ffmpeg. It answers "is it us or them": a failure inside MovieHub's own
// rules is reported as OURS, a provider error (HTTP status, dead stream) as PROVIDER.
//
// This is a diagnostic, not a test: results depend on the providers and on the runner's country.
// It never prints stream URLs or tokens, only host names. Usage:
//   RHINO_JAR=… node provider-engine/tools/live-probe.mjs [provider …]
import { readFileSync, writeFileSync, mkdirSync, appendFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import dns from "node:dns/promises";

const pkg = JSON.parse(readFileSync("android-tv-launcher/app/src/main/assets/providers/builtin.json"));
const dir = "build/provider-tests";
mkdirSync(dir, { recursive: true });

// --- Mirrors of the app's rules (keep in step with ProviderBroker.allowed and NativeHttp) ---------
const RESPONSE_LIMIT = 6 * 1024 * 1024; // NativeHttp.exchange
const AGGREGATE_LIMIT = 15 * 1024 * 1024; // ProviderBroker.run
const REQUEST_LIMIT = 64; // ProviderBroker.run
const ROUND_LIMIT = 64; // ProviderBroker.run

function allowed(provider, url) {
    let u;
    try {
        u = new URL(url);
    } catch {
        return false;
    }
    if (u.protocol !== "https:" || u.username || u.password || (u.port && u.port !== "443")) return false;
    const host = u.hostname,
        path = u.pathname;
    switch (provider) {
        case "localnow":
            return (
                host === "localnow.com" ||
                host.endsWith(".localnow.com") ||
                host.endsWith(".localnowapi.com") ||
                host.endsWith(".amdvids.com")
            );
        case "samsung":
            return (
                host === "i.mjh.nz" ||
                (host === "github.com" && path.startsWith("/matthuisman/i.mjh.nz/")) ||
                (host === "raw.githubusercontent.com" &&
                    path.startsWith("/matthuisman/i.mjh.nz/") &&
                    path.endsWith("/SamsungTVPlus/.channels.json.gz")) ||
                host === "jmp2.uk" ||
                host.endsWith(".samsungtvplus.com") ||
                host.endsWith(".cloudfront.net") ||
                host.endsWith(".amagi.tv") ||
                host.endsWith(".samsung.com")
            );
        case "tubi":
            return (
                host === "tubi.io" ||
                host.endsWith(".tubi.io") ||
                host.endsWith(".adrise.io") ||
                host.endsWith(".tubi.video") ||
                host === "tubitv.com"
            );
        case "freeshot":
            return (
                ((host === "www.freeshot.live" || host === "freeshot.live") &&
                    (path.startsWith("/live-tv/") || /^\/embed\/[A-Za-z0-9_-]+\.php$/.test(path))) ||
                (host === "wideiptv.top" && /^\/player\/[A-Za-z0-9_-]+$/.test(path))
            );
        case "zlive":
        case "dlhd":
            return true;
        case "stirr":
            return host === "stirr.com" && path.startsWith("/api/");
        case "tcl":
            return host === "gateway-prod.ideonow.com" && path.startsWith("/api/");
        case "xumo":
            return host === "valencia-app-mds.xumo.com" && path.startsWith("/v2/");
        case "lg":
            return host === "api.lgchannels.com" && path.startsWith("/api/v1.0/");
        case "whale":
            return host === "rlaxx.zeasn.tv" && path.startsWith("/livetv/api/");
        case "vizio":
            return host === "watchfreeplus-epg-prod.smartcasttv.com" && path.startsWith("/api/");
        case "roku":
            return host === "therokuchannel.roku.com" || host === "content.sr.roku.com";
        case "plex":
            return host === "watch.plex.tv" || host === "plex.tv" || host === "epg.provider.plex.tv";
        default:
            return false;
    }
}

class Ours extends Error {}
class Theirs extends Error {}

// --- One interpreter step, with the app's own deadlines and size limits ---------------------
function step(id, operation, input) {
    writeFileSync(dir + "/module.js", pkg.modules[id]);
    writeFileSync(dir + "/input.json", typeof input === "string" ? input : JSON.stringify(input));
    const p = spawnSync(
        "java",
        [
            "-Xmx256m",
            "-cp",
            process.env.RHINO_JAR + ":" + dir,
            "local.tv.home.ProviderProbeCli",
            dir + "/module.js",
            operation,
            dir + "/input.json",
        ],
        { encoding: "utf8", maxBuffer: 64 * 1024 * 1024, timeout: 60000 },
    );
    const elapsed = Number((/ELAPSED (\d+)/.exec(p.stderr || "") || [0, -1])[1]);
    if (p.status !== 0) {
        const message = (/ERROR (.*)/.exec(p.stderr || "") || [0, "interpreter exited " + p.status])[1];
        throw new Ours("interpreter: " + message.slice(0, 400));
    }
    return { value: JSON.parse(p.stdout), elapsed };
}

// --- NativeHttp.exchange, in Node ----------------------------------------------------------
async function exchange(provider, request, stats) {
    let current = request.url;
    let method = request.method || "GET";
    let body = request.body || "";
    const authority = new URL(current).host;
    if (method !== "GET" && method !== "POST") throw new Ours("method " + method + " rejected");
    for (let redirects = 0; redirects < 5; redirects++) {
        if (!allowed(provider, current))
            throw new Ours("host not on the app's allow-list: " + new URL(current).hostname);
        stats.hosts.add(new URL(current).hostname);
        if (method === "POST" && new URL(current).host !== authority)
            throw new Ours("cross-host POST rejected");
        const headers = { "Accept-Encoding": "gzip" };
        for (const [k, v] of Object.entries(request.headers || {})) {
            const lower = k.toLowerCase();
            if (["host", "connection", "content-length", "transfer-encoding"].includes(lower))
                throw new Ours("restricted header " + k);
            if (
                new URL(current).host === authority ||
                ["user-agent", "accept", "accept-language", "origin", "referer"].includes(lower)
            )
                headers[k] = v;
        }
        let res;
        try {
            res = await fetch(current, {
                method,
                headers,
                body: method === "POST" ? body : undefined,
                redirect: "manual",
                signal: AbortSignal.timeout(16000),
            });
        } catch (e) {
            throw new Theirs(
                "network error on " + new URL(current).hostname + ": " + (e.cause?.code || e.name),
            );
        }
        if (res.status >= 300 && res.status < 400 && request.redirect !== "manual") {
            const location = res.headers.get("location");
            if (!location) throw new Theirs("redirect without location");
            current = new URL(location, current).href;
            if (res.status === 303 || ((res.status === 301 || res.status === 302) && method === "POST")) {
                method = "GET";
                body = "";
            }
            continue;
        }
        const buffer = Buffer.from(await res.arrayBuffer());
        stats.maxResponse = Math.max(stats.maxResponse, buffer.length);
        if (buffer.length > RESPONSE_LIMIT)
            throw new Ours(
                "response " +
                    (buffer.length / 1048576).toFixed(1) +
                    " MB over the app's 6 MB limit (" +
                    new URL(current).hostname +
                    ")",
            );
        if (res.status >= 400) stats.httpErrors.push(res.status + " " + new URL(current).hostname);
        if (provider === "freeshot") {
            stats.iframeTrace ||= [];
            stats.iframeTrace.push({
                page: new URL(current).pathname,
                frames: [...buffer.toString("utf8").matchAll(/<iframe\b[^>]*\bsrc=["']([^"']+)["']/gi)]
                    .slice(0, 8)
                    .map((match) => {
                        try {
                            const u = new URL(match[1], current);
                            return {
                                host: u.hostname,
                                path: u.pathname,
                                whitespace: match[1] !== match[1].trim(),
                            };
                        } catch {
                            return { invalid: true };
                        }
                    }),
            });
        }
        const responseHeaders = {};
        const cookies = [];
        res.headers.forEach((v, k) => {
            responseHeaders[k] = v;
        });
        for (const c of res.headers.getSetCookie?.() || []) cookies.push(c);
        return {
            status: res.status,
            url: current,
            headers: responseHeaders,
            cookies,
            text: buffer.toString("utf8"),
            bytes: new URL(current).pathname.endsWith(".gz") ? buffer.toString("base64") : "",
        };
    }
    throw new Theirs("too many redirects");
}

async function special(provider, url) {
    if (url.startsWith("https://provider-timer.invalid/")) {
        const ms = Number(url.slice("https://provider-timer.invalid/".length));
        if (!(ms >= 0 && ms <= 3000)) throw new Ours("timer rejected");
        await new Promise((r) => setTimeout(r, ms));
        return { status: 200, url, headers: {}, text: "" };
    }
    if (provider === "zlive" && url.startsWith("https://provider-dns.invalid/")) {
        const host = decodeURIComponent(url.slice("https://provider-dns.invalid/".length));
        const found = await dns.lookup(host, { all: true });
        return {
            status: 200,
            url,
            headers: {},
            text: JSON.stringify(found.map((a) => ({ address: a.address, family: a.family }))),
        };
    }
    return null;
}

// --- ProviderBroker.run --------------------------------------------------------------------
async function run(id, operation, row, stats) {
    const responses = [];
    const input = {
        now: Date.now(),
        seed: randomBytes(32).toString("base64"),
        row: row || {},
        responses,
    };
    const deadline = Date.now() + (operation === "catalogue" ? 240000 : 45000);
    let requests = 0,
        aggregate = 0;
    for (let round = 0; round < ROUND_LIMIT; round++) {
        if (Date.now() > deadline) throw new Ours("operation over the app's time limit");
        const { value: s, elapsed } = step(id, operation, input);
        stats.steps++;
        stats.slowestStep = Math.max(stats.slowestStep, elapsed);
        if (s.done) return s.value;
        if (!s.requests?.length) throw new Ours("provider module stalled");
        for (const request of s.requests) {
            if (++requests > REQUEST_LIMIT)
                throw new Ours("more than the app's " + REQUEST_LIMIT + " requests per operation");
            stats.requests = requests;
            const record = { request };
            try {
                record.response = (await special(id, request.url)) || (await exchange(id, request, stats));
            } catch (e) {
                if (e instanceof Ours) throw e;
                stats.networkErrors.push(e.message);
                record.error = true;
            }
            if (record.response) aggregate += JSON.stringify(record.response).length;
            stats.aggregate = aggregate;
            if (aggregate > AGGREGATE_LIMIT)
                throw new Ours(
                    "responses total " + (aggregate / 1048576).toFixed(1) + " MB, over the app's 15 MB limit",
                );
            record.completedAt = Date.now();
            responses.push(record);
        }
    }
    throw new Ours("more than the app's " + ROUND_LIMIT + " continuation rounds");
}

// --- FreeLiveSports uses the older single-call protocol (EngineSources) ----------------------
async function freeLiveSports(stats) {
    const meta = step("freelivesports", "metadata", "").value;
    stats.hosts.add(new URL(meta.url).hostname);
    let res;
    try {
        res = await fetch(meta.url, { headers: meta.headers, signal: AbortSignal.timeout(16000) });
    } catch (e) {
        throw new Theirs("network error: " + (e.cause?.code || e.name));
    }
    if (res.status >= 400) throw new Theirs("catalogue HTTP " + res.status);
    const raw = await res.text();
    stats.maxResponse = raw.length;
    return step("freelivesports", "catalogue", raw).value;
}

// --- Decode a few seconds of a resolved stream -------------------------------------------------
function decode(stream) {
    const args = ["-v", "error", "-rw_timeout", "15000000"];
    if (stream.userAgent) args.push("-user_agent", stream.userAgent);
    const extra = [];
    if (stream.referer) extra.push("Referer: " + stream.referer);
    for (const [k, v] of Object.entries(stream.headers || {}))
        if (!/^(user-agent|referer)$/i.test(k)) extra.push(k + ": " + v);
    if (extra.length) args.push("-headers", extra.join("\r\n") + "\r\n");
    args.push("-t", "4", "-i", stream.url, "-map", "0:v:0?", "-map", "0:a:0?", "-f", "null", "-");
    const p = spawnSync("ffmpeg", args, { encoding: "utf8", timeout: 45000 });
    const error = (p.stderr || "")
        .split("\n")
        .map((l) => l.replace(/https?:\/\/[^\s'"]+/g, "<url>").trim())
        .filter(Boolean)
        .slice(-1)[0];
    return {
        ok: p.status === 0,
        error: p.status === 0 ? "" : (error || "ffmpeg exit " + p.status).slice(0, 140),
    };
}

export { run };

if (process.env.MOVIEHUB_PROBE_LIBRARY !== "1") {
    const SKY = /sky\s*sports?/i;
    const results = [];
    const wanted = process.argv.slice(2);
    for (const id of Object.keys(pkg.modules)) {
        if (wanted.length && !wanted.includes(id)) continue;
        const stats = {
            hosts: new Set(),
            requests: 0,
            steps: 0,
            slowestStep: 0,
            maxResponse: 0,
            aggregate: 0,
            httpErrors: [],
            networkErrors: [],
        };
        const result = { id, catalogue: "", channels: 0, sky: 0, samples: [] };
        console.log("== " + id);
        let rows = [];
        try {
            rows =
                id === "freelivesports"
                    ? await freeLiveSports(stats)
                    : await run(id, "catalogue", null, stats);
            if (!Array.isArray(rows) || rows.length === 0) throw new Theirs("empty catalogue");
            result.catalogue = "ok";
            result.channels = rows.length;
            result.sky = rows.filter((r) => SKY.test(r.name || "")).length;
        } catch (e) {
            result.catalogue = (e instanceof Ours ? "OURS: " : "PROVIDER: ") + e.message;
            if (stats.httpErrors.length)
                result.catalogue += " (HTTP " + stats.httpErrors.slice(-3).join(", ") + ")";
            if (stats.networkErrors.length)
                result.catalogue += " (" + stats.networkErrors.slice(-2).join("; ") + ")";
        }
        result.catalogueStats = {
            requests: stats.requests,
            steps: stats.steps,
            slowestStepMs: stats.slowestStep,
            maxResponseKB: Math.round(stats.maxResponse / 1024),
            totalKB: Math.round(stats.aggregate / 1024),
            hosts: [...stats.hosts],
        };
        const samples = [
            ...rows.filter((r) => SKY.test(r.name || "")).slice(0, 4),
            ...rows.filter((r) => !SKY.test(r.name || "")).slice(0, 3),
        ];
        for (const row of id === "zlive" ? [] : samples) {
            const sample = { name: String(row.name).slice(0, 60), resolve: "", play: "" };
            const rstats = {
                ...stats,
                hosts: new Set(),
                httpErrors: [],
                networkErrors: [],
                requests: 0,
                aggregate: 0,
            };
            try {
                const value =
                    id === "freelivesports"
                        ? step("freelivesports", "resolve", row.streamUrl).value
                        : await run(id, "resolve", row, rstats);
                sample.resolve = "ok (" + new URL(value.url).hostname + ")";
                const played = decode(value);
                sample.play = played.ok ? "ok" : "PROVIDER?: " + played.error;
            } catch (e) {
                sample.resolve = (e instanceof Ours ? "OURS: " : "PROVIDER: ") + e.message;
                if (rstats.httpErrors.length)
                    sample.resolve += " (HTTP " + rstats.httpErrors.slice(-3).join(", ") + ")";
            }
            console.log("   " + sample.name + " | " + sample.resolve + " | " + sample.play);
            result.samples.push(sample);
        }
        console.log(
            "   catalogue: " +
                result.catalogue +
                " · " +
                result.channels +
                " channels · " +
                result.sky +
                " Sky Sports",
        );
        results.push(result);
    }

    writeFileSync(dir + "/live-probe.json", JSON.stringify(results, null, 2));
    let md =
        "## Live provider probe\n\nRunner country affects results (GitHub runners are usually in the US).\n\n";
    md +=
        "| Provider | Catalogue | Channels | Sky Sports | Resolved | Played | Notes |\n| --- | --- | --- | --- | --- | --- | --- |\n";
    for (const r of results) {
        const resolved = r.samples.filter((s) => s.resolve.startsWith("ok")).length;
        const played = r.samples.filter((s) => s.play === "ok").length;
        const s = r.catalogueStats;
        md +=
            `| ${r.id} | ${r.catalogue.replace(/\|/g, "/")} | ${r.channels} | ${r.sky} | ${resolved}/${r.samples.length} | ${played}/${r.samples.length} | ` +
            `${s.requests} req, max ${s.maxResponseKB} KB, total ${s.totalKB} KB, slowest step ${s.slowestStepMs} ms |\n`;
    }
    md += "\n### Samples\n\n| Provider | Channel | Resolve | Play |\n| --- | --- | --- | --- |\n";
    for (const r of results)
        for (const s of r.samples)
            md += `| ${r.id} | ${s.name.replace(/\|/g, "/")} | ${s.resolve.replace(/\|/g, "/")} | ${s.play.replace(/\|/g, "/")} |\n`;
    writeFileSync(dir + "/live-probe.md", md);
    if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, md);
    console.log(md);
}
