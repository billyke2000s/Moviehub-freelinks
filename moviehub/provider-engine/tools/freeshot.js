// MovieHub adapter: parse literal public markup only; no third-party page code or ad scripts run.
var __provider = "freeshot";
var __freeShotUA =
    "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
function __load() {}
function __freeShotPage(url) {
    return fetch(url, { headers: { "User-Agent": __freeShotUA } }).then(function (response) {
        if (!response.ok) throw new Error("FreeShot page unavailable");
        return response.text();
    });
}
function __freeShotIframe(html, expression, base) {
    var frames = /<iframe\b[^>]*\bsrc=["']([^"']+)["']/gi,
        match;
    while ((match = frames.exec(html))) {
        try {
            var address = new URL(match[1].trim(), base).href;
            if (expression.test(address)) return address;
        } catch (error) {}
    }
    throw new Error("FreeShot player unavailable");
}
function __run(operation, row) {
    if (operation === "catalogue") {
        // This page's related-channel directory lists the UK sports channels in one request.
        // Do not open any channel/player while refreshing the directory.
        return __freeShotPage("https://www.freeshot.live/live-tv/sky-sports-action-uk/21").then(
            function (html) {
                var links =
                    /href=["'](?:https:\/\/(?:www\.)?freeshot\.live)?\/live-tv\/([a-z0-9-]+-uk\/\d+)["']/gi;
                var seen = {},
                    channels = [],
                    match;
                while ((match = links.exec(html))) {
                    var id = match[1],
                        slug = id.split("/")[0];
                    if (
                        !/^(?:sky-sports|tnt-sports|bt-sport|eurosport|premier-sports|racing|sky-racing)/.test(
                            slug,
                        ) ||
                        seen[id]
                    )
                        continue;
                    seen[id] = true;
                    var name = slug
                        .replace(/-live(?=-uk$)/, "")
                        .split("-")
                        .map(function (word) {
                            return word === "uk" ? "UK" : word.charAt(0).toUpperCase() + word.slice(1);
                        })
                        .join(" ");
                    channels.push({ channelId: id, name: name, category: "Sports" });
                    if (channels.length > 128) throw new Error("FreeShot catalogue limit");
                }
                if (!channels.length) throw new Error("FreeShot sports directory unavailable");
                return channels;
            },
        );
    }
    if (operation !== "resolve" || !/^[a-z0-9-]+-uk\/\d+$/.test(row.channelId))
        throw new Error("Invalid FreeShot operation or channel");
    var playerSlug, embed;
    var channelPage = "https://www.freeshot.live/live-tv/" + row.channelId;
    return __freeShotPage(channelPage)
        .then(function (html) {
            embed = __freeShotIframe(
                html,
                /^https:\/\/(?:www\.)?freeshot\.live\/embed\/[A-Za-z0-9_-]+\.php$/,
                channelPage,
            );
            return __freeShotPage(embed);
        })
        .then(function (html) {
            var player = __freeShotIframe(html, /^https:\/\/wideiptv\.top\/player\/[A-Za-z0-9_-]+$/, embed);
            playerSlug = player.substring(player.lastIndexOf("/") + 1);
            return __freeShotPage(player);
        })
        .then(function (html) {
            var literal = /\bstreamUrl:\s*("(?:\\.|[^"\\])*")/.exec(html);
            var slug = /\bchannelSlug:\s*["']([A-Za-z0-9_-]+)["']/.exec(html);
            var ttl = /\btokenExpiresIn:\s*(\d+)/.exec(html);
            if (!literal || !slug || slug[1] !== playerSlug)
                throw new Error("Invalid FreeShot player configuration");
            var url = new URL(JSON.parse(literal[1]));
            if (
                url.protocol !== "https:" ||
                !/^[A-Za-z0-9-]+\.bluetier\.top$/.test(url.hostname) ||
                url.pathname.indexOf("/" + playerSlug + "/") !== 0
            )
                throw new Error("FreeShot stream scope rejected");
            var token = url.searchParams.get("token");
            if (!token || !/^[A-Za-z0-9+/=._~-]{1,4096}$/.test(token))
                throw new Error("Invalid FreeShot token");
            var headers = {
                "User-Agent": __freeShotUA,
                Origin: "https://wideiptv.top",
                Referer: "https://wideiptv.top/",
            };
            return {
                url: url.href,
                headers: headers,
                userAgent: __freeShotUA,
                referer: headers.Referer,
                playlistSession: {
                    type: "freeshot",
                    endpoint: "https://wideiptv.top/api/refresh_token.php",
                    slug: playerSlug,
                    token: token,
                    expiresAtMs: Date.now() + Math.max(20, Math.min(600, ttl ? Number(ttl[1]) : 300)) * 1000,
                },
            };
        });
}
