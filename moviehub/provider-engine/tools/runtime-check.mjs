import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { deflateSync, gzipSync } from "node:zlib";
import { createHash, createHmac, createDecipheriv } from "node:crypto";
import assert from "node:assert/strict";
const pkg = JSON.parse(readFileSync("provider-engine/dist/providers.json"));
const dir = "build/provider-tests";
mkdirSync(dir, { recursive: true });
let checks = 0;
function call(id, operation, input) {
    writeFileSync(dir + "/module.js", pkg.modules[id]);
    writeFileSync(dir + "/input.json", JSON.stringify(input));
    const p = spawnSync(
        "java",
        [
            "-Xmx96m",
            "-cp",
            process.env.RHINO_JAR + ":" + dir,
            "local.tv.home.ProviderRuntimeCli",
            dir + "/module.js",
            operation,
            dir + "/input.json",
        ],
        { encoding: "utf8", maxBuffer: 12 * 1024 * 1024, timeout: 12000 },
    );
    if (p.status !== 0) throw new Error(id + ": " + p.stderr);
    return JSON.parse(p.stdout);
}
let lastInput;
function drive(id, operation, row, respond, history = []) {
    const input = { now: 1792440000000, seed: "fixture-seed-123", responses: [], row: row || {}, history };
    for (let n = 0; n < 64; n++) {
        const r = call(id, operation, input);
        if (r.done) {
            lastInput = input;
            return r.value;
        }
        assert.ok(r.requests.length > 0);
        for (const req of r.requests) {
            const answer = respond(req, input.responses.length);
            input.responses.push({
                request: req,
                response: {
                    status: 200,
                    url: req.url,
                    headers: {},
                    cookies: [],
                    text: typeof answer === "string" ? answer : JSON.stringify(answer),
                    ...answer?.transport,
                },
            });
        }
    }
    throw new Error("Continuation limit");
}
for (const id of Object.keys(pkg.modules).filter((id) => id !== "freelivesports")) {
    const meta = call(id, "metadata", {});
    assert.equal(meta.provider, id);
    checks++;
}
const stirr = drive("stirr", "catalogue", null, () => [
    { videoid: "1", title: "Fixture", category: "Sports Live" },
]);
assert.equal(stirr[0].channelId, "1");
checks++;
const stream = drive("stirr", "resolve", stirr[0], (req) => {
    assert.equal(req.method, "POST");
    return { data: [{ media: ["https://media.example/live.m3u8?nonce=[vx_nonce]"] }] };
});
assert.ok(!stream.url.includes("["));
checks++;
const xumo = drive("xumo", "resolve", { channelId: "1" }, (req, n) =>
    n === 0
        ? { assets: [{ id: "asset", live: true }] }
        : {
              providers: [
                  {
                      sources: [
                          {
                              uri: "https://media.example/a.m3u8?session=[SESSION_ID]&unused=[OTHER]",
                              produces: "application/x-mpegurl",
                          },
                      ],
                  },
              ],
          },
);
assert.ok(!xumo.url.includes("["));
checks++;
const lgPayload = {
    categories: [
        {
            categoryName: "News",
            channels: [
                {
                    channelId: "one",
                    channelName: "One",
                    mediaStaticUrl: "https://media.example/live.m3u8",
                    programs: [
                        {
                            programTitle: "Live",
                            startDateTime: "2026-10-19T00:00:00Z",
                            endDateTime: "2026-10-19T01:00:00Z",
                        },
                    ],
                },
            ],
        },
    ],
};
const lg = drive("lg", "catalogue", null, () => deflateSync(JSON.stringify(lgPayload)).toString("base64"));
assert.equal(lg[0].programs[0].title, "Live");
checks++;
// LG Channels is served for the US: the web client asks with that country.
function firstRequests(id) {
    const input = { now: 1792440000000, seed: "fixture-seed-123", responses: [], row: {}, history: [] };
    const r = call(id, "catalogue", input);
    assert.ok(!r.done && r.requests.length > 0, id + " makes a request");
    return r.requests;
}
function header(request, name) {
    const headers = request.headers || {};
    const key = Object.keys(headers).find((k) => k.toLowerCase() === name);
    return key ? headers[key] : undefined;
}
assert.equal(header(firstRequests("lg")[0], "x-device-country"), "US");
checks++;
const vizio = drive("vizio", "catalogue", null, () => ({
    channels: [
        { channelId: "one", channelName: "One", channelUrls: ["https://media.example/a.m3u8"] },
        {
            channelId: "drm",
            channelName: "DRM",
            channelUrls: ["https://media.example/b.m3u8"],
            licenseUrl: "https://license.example",
        },
    ],
}));
assert.equal(vizio.length, 1);
checks++;
const tcl = drive("tcl", "catalogue", null, (req, n) =>
    n === 0
        ? { lines: Array.from({ length: 36 }, (_, i) => ({ id: String(i), name: "Genre " + i })) }
        : {
              channels: [
                  { bundle_id: "one", name: "One", media: "https://media.example/a.m3u8", source: "fixture" },
              ],
          },
);
assert.equal(tcl.length, 1);
checks++;
const tclStream = drive("tcl", "resolve", tcl[0], (req) => {
    assert.equal(req.method, "POST");
    assert.equal(JSON.parse(req.body).bundle_id, "one");
    return { stream_url: "https://media.example/fresh.m3u8" };
});
assert.equal(tclStream.url, "https://media.example/fresh.m3u8");
checks++;
const dlhd = drive(
    "dlhd",
    "catalogue",
    null,
    () => '<a class="card" href="watch.php?id=42"><div class="card__title">Test &amp; Sport</div></a>',
);
assert.equal(dlhd[0].name, "Test & Sport");
checks++;
const daddy = drive("dlhd", "resolve", dlhd[0], (req) => {
    if (req.url.includes("watch.php"))
        return '<button data-url="https://dlive.sx/stream/stream-42.php">Player</button>';
    if (req.url.includes("stream-42.php")) return '<iframe src="https://player.example/embed/42"></iframe>';
    if (req.url.includes("player.example"))
        return 'source: atob("' + Buffer.from("https://media.example/live.m3u8").toString("base64") + '")';
    return "#EXTM3U\n#EXTINF:4,\nhttps://media.example/a.ts\n";
});
assert.equal(daddy.url, "https://media.example/live.m3u8");
checks++;
const alternate = drive(
    "dlhd",
    "resolve",
    { channelId: "42", preferredPlayer: 2, burntPlayers: [1] },
    (req) => {
        if (req.url.includes("watch.php"))
            return '<button data-url="https://dlive.sx/stream/stream-42.php"></button><button data-url="https://dlive.sx/cast/stream-42.php"></button>';
        if (req.url.includes("stream-42.php")) {
            assert.ok(req.url.includes("/cast/"));
            return '<iframe src="https://player.example/embed/42"></iframe>';
        }
        if (req.url.includes("player.example")) return 'source:"https://media.example/alternate.m3u8"';
        return "#EXTM3U\n#EXTINF:4,\nhttps://media.example/a.ts\n";
    },
);
assert.equal(alternate.playerIndex, 2);
checks++;
// The current player requires an anonymous token before the first manifest request.
const tokenCalls = [];
const tokenStream = drive("dlhd", "resolve", { channelId: "42" }, (req) => {
    tokenCalls.push(req.url);
    if (req.url.includes("stream-42.php")) return '<iframe src="https://player.example/embed/42"></iframe>';
    if (req.url.includes("player.example"))
        return 'const EDGE = "https://media.example"; const CHANNEL = "premium42"; fetch(EDGE + "/api/playlist-token?slug=" + CHANNEL); source:"https://media.example/premium42/index.m3u8"';
    if (req.url.includes("/api/playlist-token")) return { token: "fixture-token", ttl: 120 };
    assert.equal(new URL(req.url).searchParams.get("t"), "fixture-token");
    return "#EXTM3U\n#EXTINF:4,\nsegment.png\n";
});
assert.equal(tokenStream.url, "https://media.example/premium42/index.m3u8");
assert.equal(tokenStream.playlistSession.token, "fixture-token");
assert.equal(tokenStream.playlistSession.slug, "premium42");
assert.equal(tokenCalls.filter((u) => u.includes("/api/playlist-token")).length, 1);
checks++;
const tubi = drive("tubi", "resolve", { channelId: "42" }, (req, n) => {
    if (req.url.includes("signing_key"))
        return { id: "sign-id", key: Buffer.from("server-signing-key").toString("base64") };
    if (req.url.includes("/anonymous/token")) {
        const params = new URL(req.url).searchParams,
            date = params.get("X-Tubi-Date");
        const hash = (s) => createHash("sha256").update(s).digest("hex");
        const canonical =
            "POST\n/device/anonymous/token\n\ncontent-type:application/json\n\ncontent-type\n" +
            hash(req.body);
        const hmac = (m, k) => createHmac("sha256", k).update(m).digest();
        const kDate = hmac(
            date.split("T")[0],
            Buffer.concat([Buffer.from("TUBI"), Buffer.from("server-signing-key")]),
        );
        const signature = hmac(
            "TUBI-HMAC-SHA256\n" + date + "\n" + hash(canonical),
            hmac("tubi_request", kDate),
        ).toString("hex");
        assert.equal(params.get("X-Tubi-Signature"), signature);
        return { access_token: "anonymous-fixture", expires_in: 3600 };
    }
    if (req.url.includes("/programming"))
        return {
            rows: [
                {
                    video_resources: [
                        { manifest: { url: "https://live-manifest.production-public.tubi.io/live.m3u8" } },
                    ],
                },
            ],
        };
    return "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\na.m3u8\n";
});
assert.ok(tubi.url.endsWith("live.m3u8"));
checks++;
const samsung = drive("samsung", "catalogue", null, () => ({
    transport: {
        text: "",
        bytes: gzipSync(
            JSON.stringify({
                regions: {
                    gb: {
                        channels: {
                            one: { name: "One", group: "News" },
                            drm: { name: "DRM", license_url: "https://license.example" },
                        },
                    },
                },
            }),
        ).toString("base64"),
    },
}));
assert.equal(samsung.length, 1);
checks++;
const samsungIdOnly = drive("samsung", "resolve", { channelId: "one" }, (req) => {
    assert.equal(req.url, "https://jmp2.uk/stvp-one");
    return { transport: { text: "#EXTM3U\n#EXTINF:4,\na.ts\n", url: "https://media.example/fresh.m3u8" } };
});
assert.equal(samsungIdOnly.url, "https://media.example/fresh.m3u8");
checks++;
const localBootstrap =
    '<script id="__NEXT_DATA__" type="application/json">' +
    JSON.stringify({
        runtimeConfig: {
            DSP_TOKEN: JSON.stringify({
                token: "h." + Buffer.from(JSON.stringify({ exp: 1999999999 })).toString("base64url") + ".s",
            }),
            DSP_API_URL: "data-store-trans-cdn.api.cms.amdvids.com",
        },
        props: { pageProps: { serverCookies: { _ln_myMarket: { dma: "501", market: "nyNewYorkCity" } } } },
    }) +
    "</script>";
const localRows = drive("localnow", "catalogue", null, (req, n) =>
    n === 0
        ? localBootstrap
        : {
              channels: [
                  {
                      video_id: "one",
                      name: "Local One",
                      genres: ["My City"],
                      program: [{ program_title: "News", starts_at: 1792440000, ends_at: 1792443600 }],
                  },
                  { video_id: "locked", name: "Locked", subscription_access: { unlocked: false } },
                  { video_id: "outside", name: "Outside", genres: ["More Cities"] },
              ],
          },
);
assert.equal(localRows.length, 1);
assert.equal(localRows[0].channelId, "501:one");
assert.equal(localRows[0].programs[0].title, "News");
checks++;
const localStream = drive("localnow", "resolve", { channelId: "501:one" }, (req, n) => {
    if (n === 0) return localBootstrap;
    assert.ok(req.url.includes("/video/play/one/"));
    assert.ok(req.headers["x-access-token"]);
    return { video_m3u8: "https://media.example/local.m3u8" };
});
assert.equal(localStream.url, "https://media.example/local.m3u8");
assert.equal(localStream.headers.Origin, "https://localnow.com");
checks++;
const zliveRows = drive("zlive", "catalogue", null, (req) => {
    assert.equal(req.url, "https://iptv.zlive.st/channels.json");
    return [
        {
            id: "auto-evt-one",
            name: "One",
            sport: "Sport",
            sources: [{ key: "auto-evt-one", label: "Primary" }],
        },
        { id: "one", name: "Other" },
        { id: "constructor", name: "Constructor" },
        { id: "one", name: "Duplicate" },
    ];
});
assert.equal(zliveRows.length, 3);
assert.equal(zliveRows[0].channelId, "auto-evt-one");
assert.equal(zliveRows[0].sources[0].label, "Primary");
checks++;
const sessionLocation = "https://cdn.example/m3u8-proxy.m3u8?data=fixture";
function zTransport(slug, status = 200, location = sessionLocation) {
    return (req) => {
        assert.equal(req.headers.Origin, "https://zlive.st");
        assert.equal(req.headers.Referer, "https://zlive.st/");
        // Without browser fetch metadata the API answers with a looping decoy stream.
        assert.equal(req.headers["sec-fetch-mode"], "cors");
        assert.equal(req.headers["sec-fetch-site"], "same-site");
        assert.equal(req.headers["sec-fetch-dest"], "empty");
        if (req.url.endsWith("/session/ticket")) {
            assert.equal(req.method, "GET");
            return { w: "fixture-ticket", transport: { status } };
        }
        assert.equal(req.url, "https://iptv.zlive.st/resolve");
        assert.equal(req.method, "POST");
        const payload = JSON.parse(req.body);
        const date = new Date(1792440000000).toISOString().slice(0, 10);
        assert.equal(payload.d, date);
        assert.equal(payload.e, "fixture-ticket");
        const initial = createHash("sha256")
            .update("Asq3YkZdzBICwscY4OO1PFAes1Mv1vJ8vxZbzYQeHZE:" + date)
            .digest();
        const one = createHmac("sha256", initial).update("k6").digest();
        const key = createHmac("sha256", one)
            .update(payload.e + "|v6")
            .digest();
        const cipher = createDecipheriv("aes-256-gcm", key, Buffer.from(payload.b, "base64"));
        cipher.setAAD(Buffer.from(payload.e + "|" + date));
        cipher.setAuthTag(Buffer.from(payload.c, "base64"));
        const plain = Buffer.concat([cipher.update(Buffer.from(payload.a, "base64")), cipher.final()]);
        assert.deepEqual(JSON.parse(plain), { s: slug, u: 1792440000 });
        return { location };
    };
}
function event(slug, input, fresh = false) {
    return { slug, at: input.now, fresh, responses: input.responses };
}
const zlive = drive("zlive", "resolve", { channelId: "auto-one" }, zTransport("auto-one"));
assert.equal(zlive.url, sessionLocation);
assert.equal(zlive.expiresAtMs, 1792440000000 + 600000);
checks++;
const firstEvent = event("auto-one", lastInput);
const reused = drive(
    "zlive",
    "resolve",
    { channelId: "auto-one" },
    () => {
        throw new Error("Cached session contacted upstream");
    },
    [firstEvent],
);
assert.equal(reused.url, zlive.url);
checks++;
const floor = drive(
    "zlive",
    "resolve",
    { channelId: "auto-one", fresh: true },
    () => {
        throw new Error("Fresh-session rate limit contacted upstream");
    },
    [firstEvent],
);
assert.equal(floor.url, zlive.url);
checks++;
const refused = drive("zlive", "resolve", { channelId: "refused" }, zTransport("refused", 403));
assert.equal(refused.failure, "refusal-403");
assert.equal(lastInput.responses.length, 1);
checks++;
const refusalEvent = event("refused", lastInput);
const cooldown = drive(
    "zlive",
    "resolve",
    { channelId: "other" },
    () => {
        throw new Error("Cooldown contacted upstream");
    },
    [refusalEvent],
);
assert.equal(cooldown.failure, "refusal-403");
checks++;
const invalid = drive(
    "zlive",
    "resolve",
    { channelId: "bad" },
    zTransport("bad", 200, "http://media.example/file"),
);
assert.equal(invalid.failure, "shape");
checks++;
const cancelled = drive("zlive", "resolve", { channelId: "other" }, zTransport("other"), [
    { slug: "aborted", at: 1792440000000, responses: [], cancelled: true },
]);
assert.ok(cancelled.url);
checks++;
const freeRows = drive("freeshot", "catalogue", null, (req) => {
    assert.equal(req.url, "https://www.freeshot.live/live-tv/sky-sports-action-uk/21");
    return '<a href="/live-tv/sky-sports-main-event-live-uk/20">Main Event</a><a href="/live-tv/sky-sports-main-event-live-uk/20">Duplicate</a><a href="/live-tv/adult-uk/90">Excluded</a>';
});
assert.equal(freeRows.length, 1);
assert.equal(freeRows[0].name, "Sky Sports Main Event UK");
checks++;
const freeStream = drive("freeshot", "resolve", freeRows[0], (req, n) => {
    assert.equal(req.method, "GET");
    if (n === 0) return '<iframe src="  /embed/MainEvent.php  "></iframe>';
    if (n === 1)
        return '<iframe src="https://freeshot.live/ads/advert.php"></iframe><iframe src="//wideiptv.top/player/skysportsmainevent"></iframe>';
    assert.equal(req.url, "https://wideiptv.top/player/skysportsmainevent");
    return 'const config = {streamUrl: "https://ds1.bluetier.top/skysportsmainevent/index.m3u8?token=YWJjZA==.signature", channelSlug:"skysportsmainevent", tokenExpiresIn:300};';
});
assert.equal(freeStream.headers.Origin, "https://wideiptv.top");
assert.equal(freeStream.playlistSession.type, "freeshot");
assert.equal(freeStream.playlistSession.token, "YWJjZA==.signature");
assert.equal(freeStream.playlistSession.expiresAtMs, 1792440300000);
assert.equal(lastInput.responses.length, 3);
checks++;
assert.throws(
    () =>
        drive("freeshot", "resolve", { channelId: "../../outside" }, () => {
            throw new Error("Unexpected request");
        }),
    /Invalid FreeShot/,
);
checks++;
console.log(checks + " multi-provider checks passed in actual JVM runtime");
