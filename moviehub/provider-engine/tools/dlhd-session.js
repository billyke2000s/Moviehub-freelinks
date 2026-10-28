// Current DaddyLive player protocol, kept separate from the unchanged historical MIT modules.
// Learn only literal EDGE/CHANNEL declarations from fetched player HTML. No page scripts execute.
var __dlhdNetwork = fetch;
var __dlhdSessions;
function __dlhdSession(url) {
    var parsed = new URL(url);
    return __dlhdSessions[parsed.origin + parsed.pathname.replace(/\/[^/]*$/, "/")] || null;
}
function __installDlhdSessions() {
    __dlhdSessions = Object.create(null);
    fetch = function (url, init) {
        var session = __dlhdSession(String(url));
        if (session && /\.m3u8(?:\?|$)/.test(String(url))) {
            return __dlhdToken(session, init).then(function (token) {
                var target = new URL(url);
                target.searchParams.set("t", token);
                return __dlhdNetwork(target.href, init);
            });
        }
        return __dlhdNetwork(url, init).then(function (response) {
            if (!response.ok) return response;
            return response.text().then(function (html) {
                if (html.indexOf("/api/playlist-token?slug=") < 0) return response;
                var edge = /\bconst\s+EDGE\s*=\s*["'](https:\/\/[^"']+)["']/.exec(html);
                var slug = /\bconst\s+CHANNEL\s*=\s*["']([A-Za-z0-9_-]{1,128})["']/.exec(html);
                if (!edge || !slug) return response;
                var origin = new URL(edge[1]);
                if (origin.href !== origin.origin && origin.href !== origin.origin + "/") return response;
                var prefix = origin.origin + "/" + slug[1] + "/";
                __dlhdSessions[prefix] = {
                    endpoint: origin.origin + "/api/playlist-token?slug=" + encodeURIComponent(slug[1]),
                    slug: slug[1],
                    token: "",
                    expiresAtMs: 0,
                };
                return response;
            });
        });
    };
}
function __dlhdToken(session, init) {
    if (session.token && Date.now() + 5000 < session.expiresAtMs) return Promise.resolve(session.token);
    return __dlhdNetwork(session.endpoint, { headers: (init && init.headers) || {} }).then(function (r) {
        if (!r.ok) throw new Error("Playlist session unavailable");
        return r.json().then(function (value) {
            if (!value || typeof value.token !== "string" || !/^[A-Za-z0-9._~-]{1,4096}$/.test(value.token))
                throw new Error("Invalid playlist session");
            session.token = value.token;
            session.expiresAtMs = Date.now() + Math.max(20, Math.min(600, Number(value.ttl) || 120)) * 1000;
            return session.token;
        });
    });
}
function __dlhdResult(result) {
    var stream = {
        url: result.masterUrl,
        headers: result.upstreamHeaders,
        userAgent: result.upstreamHeaders["User-Agent"] || "",
        referer: result.upstreamHeaders.Referer || "",
        playerIndex: result.playerIndex,
    };
    var session = __dlhdSession(result.masterUrl);
    if (session) stream.playlistSession = session;
    return stream;
}
