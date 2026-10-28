// ZLive's public player protocol observed in /assets/DOoCRg-K.js, 25 October 2026.
// Ticket -> AES-GCM request -> JSON location. Never fall back around a refusal.
var __provider = "zlive";
var __zCrypto = require("node:crypto");
var __zUa =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
var __zHeaders = { "User-Agent": __zUa, Origin: "https://zlive.st", Referer: "https://zlive.st/" };
// The API serves a looping "refresh the site" decoy stream unless requests carry the fetch metadata
// a browser adds (verified 25 October 2026: browser hints alone still got the decoy). Media
// requests keep the plain headers above.
var __zApi = {
    "User-Agent": __zUa,
    Origin: "https://zlive.st",
    Referer: "https://zlive.st/",
    Accept: "*/*",
    "Accept-Language": "en-GB,en;q=0.9",
    "sec-ch-ua": '"Chromium";v="131", "Not_A Brand";v="24", "Google Chrome";v="131"',
    "sec-ch-ua-mobile": "?0",
    "sec-ch-ua-platform": '"macOS"',
    "sec-fetch-dest": "empty",
    "sec-fetch-mode": "cors",
    "sec-fetch-site": "same-site",
};
function __load() {}
function __zBase(row) {
    var domain = row.domain || "zlive.st";
    if (!/^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z][a-z0-9-]*$/.test(domain))
        throw new Error("Invalid ZLive domain");
    return "https://iptv." + domain;
}
function __zLocation(value) {
    var u = new URL(value);
    if (u.protocol !== "https:" || /@/.test(value.split("/")[2]) || u.hash)
        throw new Error("Invalid ZLive location");
    // ProviderBroker and EngineSources perform public-address validation on every connection.
    return value;
}
function __zCheck(response) {
    if (!response.ok) {
        var error = new Error("ZLive HTTP " + response.status);
        error.httpStatus = response.status;
        throw error;
    }
    return response;
}
function __zPayload(slug, ticket) {
    if (typeof ticket !== "string" || ticket.length < 1 || ticket.length > 4096)
        throw new Error("Invalid ZLive ticket");
    var day = new Date(Date.now()).toISOString().slice(0, 10);
    // This is the public web client's protocol constant, not a user credential.
    var seed = "Asq3YkZdzBICwscY4OO1PFAes1Mv1vJ8vxZbzYQeHZE";
    var key = __zCrypto
        .createHash("sha256")
        .update(seed + ":" + day)
        .digest();
    key = __zCrypto.createHmac("sha256", key).update("k6").digest();
    key = __zCrypto
        .createHmac("sha256", key)
        .update(ticket + "|v6")
        .digest();
    var iv = __zCrypto.randomBytes(12);
    var fields = [
        key.toString("base64"),
        iv.toString("base64"),
        Buffer.from(ticket + "|" + day).toString("base64"),
        Buffer.from(JSON.stringify({ s: slug, u: Math.floor(Date.now() / 1000) })).toString("base64"),
    ];
    var sealed = codec("aesGcmEncrypt", fields.join("\n"));
    return {
        a: codec("base64encode", sealed.slice(0, -16)),
        b: iv.toString("base64"),
        c: codec("base64encode", sealed.slice(-16)),
        d: day,
        e: ticket,
    };
}
function __zHistory(slug, fresh) {
    var now = Date.now(),
        cache = null,
        minted = 0,
        until = 0,
        step = 0,
        negative = null;
    (__input.history || []).forEach(function (event) {
        (event.responses || []).forEach(function (record) {
            var r = record.response;
            var at = record.completedAt || event.at;
            if (
                event.slug === slug &&
                now - at >= 0 &&
                now - at < 30000 &&
                (record.error || (r && r.status >= 500))
            )
                negative = { failure: "dns-connect" };
            if (!r) return;
            if (r.status === 401 || r.status === 403 || r.status === 429) {
                step++;
                var retry = Number((r.headers || {})["retry-after"]);
                if (!retry && (r.headers || {})["retry-after"])
                    retry = (Date.parse(r.headers["retry-after"]) - at) / 1000;
                var wait =
                    retry > 0
                        ? Math.min(retry * 1000, 3600000)
                        : Math.min(60000 * Math.pow(2, step - 1), 900000);
                until = Math.max(until, at + wait);
            } else if (r.status === 200 && record.request.url.slice(-8) === "/resolve") {
                // A successful API response sent after a refusal ends that refusal incident.
                if (event.at >= until) {
                    step = 0;
                    until = 0;
                }
                if (event.slug === slug && !event.cancelled && now - at >= 0 && now - at < 600000) {
                    try {
                        cache = __zLocation(JSON.parse(r.text).location);
                        minted = at;
                    } catch (_) {
                        if (now - at < 30000) negative = { failure: "shape" };
                    }
                }
            }
        });
    });
    if (until > now) return { failure: "refusal-403" };
    if (negative) return negative;
    return cache && (!fresh || now - minted < 60000)
        ? {
              url: cache,
              headers: __zHeaders,
              userAgent: __zUa,
              referer: __zHeaders.Referer,
              expiresAtMs: minted + 600000,
          }
        : null;
}
function __run(operation, row) {
    row = row || {};
    var base = __zBase(row);
    if (operation === "catalogue")
        return fetch(base + "/channels.json", { headers: __zApi })
            .then(__zCheck)
            .then(function (r) {
                return r.json();
            })
            .then(function (body) {
                if (!Array.isArray(body)) throw new Error("Invalid ZLive catalogue");
                var seen = Object.create(null);
                return body
                    .filter(function (r) {
                        return r && /^[A-Za-z0-9_-]{1,160}$/.test(r.id) && !seen[r.id] && (seen[r.id] = true);
                    })
                    .map(function (r) {
                        // Keep the raw ID: the current site passes channel.id unchanged to /resolve.
                        return {
                            channelId: r.id,
                            resolverSlug: r.id,
                            name: r.name || r.id,
                            category: r.sport || "Other",
                            region: r.region || "",
                            sources: r.sources || [],
                        };
                    });
            });
    if (operation !== "resolve") throw new Error("Unsupported operation");
    var slug = row.resolverSlug || row.channelId;
    if (!/^[A-Za-z0-9_-]{1,160}$/.test(slug)) throw new Error("Invalid ZLive channel");
    var saved = __zHistory(slug, !!row.fresh);
    if (saved) return saved;
    return fetch(base + "/session/ticket", { headers: __zApi })
        .then(__zCheck)
        .then(function (r) {
            return r.json();
        })
        .then(function (ticket) {
            var headers = Object.assign({}, __zApi, { "Content-Type": "application/json" });
            return fetch(base + "/resolve", {
                method: "POST",
                headers: headers,
                body: JSON.stringify(__zPayload(slug, ticket.w)),
            });
        })
        .then(__zCheck)
        .then(function (r) {
            return r.json();
        })
        .then(function (value) {
            return {
                url: __zLocation(value.location),
                expiresAtMs: Date.now() + 600000,
                headers: __zHeaders,
                userAgent: __zUa,
                referer: __zHeaders.Referer,
            };
        })
        .catch(function (error) {
            return {
                failure:
                    error.httpStatus === 401 || error.httpStatus === 403 || error.httpStatus === 429
                        ? "refusal-403"
                        : "shape",
            };
        });
}
