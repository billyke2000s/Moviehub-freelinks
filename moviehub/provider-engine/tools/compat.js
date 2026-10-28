// Runtime 2: deterministic replay of async HTTP. No network or Java capability in the script.
var __input,
    __pending,
    __ordinal,
    __random,
    __jobs,
    __clock,
    __restoring,
    __historyInvalid,
    __restoreCancelled;
var process = { env: {} };
if (!Object.hasOwn)
    Object.hasOwn = function (object, key) {
        return Object.prototype.hasOwnProperty.call(object, key);
    };
function Promise(executor) {
    var self = this;
    self.state = 0;
    self.handlers = [];
    function settle(state, value) {
        if (self.state) return;
        if (state === 1 && value && typeof value.then === "function") {
            value.then(
                function (v) {
                    settle(1, v);
                },
                function (e) {
                    settle(2, e);
                },
            );
            return;
        }
        self.state = state;
        self.value = value;
        self.handlers.splice(0).forEach(function (h) {
            __jobs.push(h);
        });
    }
    self.then = function (ok, bad) {
        return new Promise(function (resolve, reject) {
            function run() {
                try {
                    var f = self.state === 1 ? ok : bad;
                    if (!f) {
                        (self.state === 1 ? resolve : reject)(self.value);
                        return;
                    }
                    resolve(f(self.value));
                } catch (e) {
                    reject(e);
                }
            }
            if (self.state) __jobs.push(run);
            else self.handlers.push(run);
        });
    };
    self.catch = function (f) {
        return self.then(null, f);
    };
    self.finally = function (f) {
        return self.then(
            function (v) {
                return Promise.resolve(f()).then(function () {
                    return v;
                });
            },
            function (e) {
                return Promise.resolve(f()).then(function () {
                    throw e;
                });
            },
        );
    };
    try {
        executor(
            function (v) {
                settle(1, v);
            },
            function (e) {
                settle(2, e);
            },
        );
    } catch (e) {
        settle(2, e);
    }
}
Promise.resolve = function (v) {
    return new Promise(function (r) {
        r(v);
    });
};
Promise.reject = function (v) {
    return new Promise(function (r, e) {
        e(v);
    });
};
Promise.all = function (items) {
    return new Promise(function (resolve, reject) {
        var left = items.length,
            out = [];
        if (!left) resolve(out);
        items.forEach(function (item, i) {
            Promise.resolve(item).then(function (v) {
                out[i] = v;
                if (!--left) resolve(out);
            }, reject);
        });
    });
};
var __NativeDate = Date;
Date = function (value) {
    if (!(this instanceof Date)) return new __NativeDate(__clock).toString();
    return arguments.length ? new __NativeDate(value) : new __NativeDate(__clock);
};
Date.now = function () {
    return __clock;
};
Date.parse = __NativeDate.parse;
Date.UTC = __NativeDate.UTC;
Date.prototype = __NativeDate.prototype;
function __hex() {
    return codec("sha256", __input.seed + ":" + __random++);
}
function uuid() {
    var s = __hex();
    return (
        s.slice(0, 8) +
        "-" +
        s.slice(8, 12) +
        "-4" +
        s.slice(13, 16) +
        "-a" +
        s.slice(17, 20) +
        "-" +
        s.slice(20, 32)
    );
}
function __bytes(s) {
    return {
        raw: s,
        length: s.length,
        0: s.charCodeAt(0),
        1: s.charCodeAt(1),
        2: s.charCodeAt(2),
        toString: function (enc) {
            if (enc === "latin1" || enc === "binary") return s;
            if (enc === "hex") return codec("hexencode", s);
            if (enc === "base64") return codec("base64encode", s);
            if (enc === "base64url")
                return codec("base64encode", s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
            return codec("utf8decode", s);
        },
    };
}
var Buffer = {
    concat: function (list) {
        return __bytes(list.map(__binary).join(""));
    },
    from: function (value, enc) {
        if (value && typeof value.raw === "string") return __bytes(value.raw);
        if (typeof value !== "string")
            return __bytes(
                Array.prototype.map
                    .call(value, function (c) {
                        return String.fromCharCode(c);
                    })
                    .join(""),
            );
        return __bytes(
            enc === "base64" || enc === "base64url"
                ? codec("base64decode", value.replace(/-/g, "+").replace(/_/g, "/"))
                : codec("utf8encode", value),
        );
    },
};
function __binary(value) {
    return typeof value.raw === "string"
        ? value.raw
        : value
              .map(function (c) {
                  return String.fromCharCode(c);
              })
              .join("");
}
function __BlockList() {
    this.rules = [];
}
__BlockList.prototype.addSubnet = function (address, prefix) {
    if (this.rules.length >= 1000) throw new Error("Address rule limit");
    this.rules.push([String(address), Number(prefix)]);
};
__BlockList.prototype.check = function (address) {
    return this.rules.some(function (rule) {
        return codec("ipMaskMatch", String(address) + "\n" + rule[0] + "\n" + rule[1]) === "true";
    });
};
function require(id) {
    if (id === "node:net")
        return {
            BlockList: __BlockList,
            isIP: function (address) {
                return Number(codec("ipFamily", String(address)));
            },
        };
    if (id === "node:crypto")
        return {
            createHash: function (algorithm) {
                if (algorithm !== "sha256") throw new Error("Unsupported hash");
                var value = "";
                return {
                    update: function (v) {
                        value += String(v);
                        return this;
                    },
                    digest: function (enc) {
                        var hex = codec("sha256", value),
                            raw = "";
                        for (var i = 0; i < hex.length; i += 2)
                            raw += String.fromCharCode(parseInt(hex.slice(i, i + 2), 16));
                        var b = __bytes(raw);
                        return enc ? b.toString(enc) : b;
                    },
                };
            },
            createHmac: function (algorithm, key) {
                if (algorithm !== "sha256") throw new Error("Unsupported HMAC");
                var value = "";
                return {
                    update: function (v) {
                        value += String(v);
                        return this;
                    },
                    digest: function (enc) {
                        var raw = codec("hmac", JSON.stringify({ key: __binary(key), message: value }));
                        var b = __bytes(raw);
                        return enc ? b.toString(enc) : b;
                    },
                };
            },
            randomUUID: uuid,
            randomBytes: function (n) {
                if (n > 128) throw new Error("Random byte limit");
                var h = "";
                while (h.length < n * 2) h += __hex();
                var raw = "";
                for (var i = 0; i < n; i++)
                    raw += String.fromCharCode(parseInt(h.slice(i * 2, i * 2 + 2), 16));
                return __bytes(raw);
            },
        };
    if (id === "node:zlib")
        return {
            gunzipSync: function (b) {
                return __bytes(codec("gunzip", __binary(b)));
            },
            inflateSync: function (b) {
                return __bytes(codec("inflate", __binary(b)));
            },
        };
    if (/logger\.js$/.test(id))
        return { logger: { warn: function () {}, info: function () {}, debug: function () {} } };
    if (id === "./categorize.js") return __categories;
    throw new Error("Unsupported dependency: " + id);
}
function URLSearchParams(value) {
    this.pairs = [];
    if (typeof value === "string") {
        var self = this;
        value
            .replace(/^\?/, "")
            .split("&")
            .forEach(function (p) {
                if (!p) return;
                var at = p.indexOf("=");
                self.append(
                    decodeURIComponent((at < 0 ? p : p.slice(0, at)).replace(/\+/g, " ")),
                    decodeURIComponent((at < 0 ? "" : p.slice(at + 1)).replace(/\+/g, " ")),
                );
            });
    } else if (value) {
        var that = this;
        Object.keys(value).forEach(function (k) {
            that.append(k, value[k]);
        });
    }
}
URLSearchParams.prototype.append = function (k, v) {
    this.pairs.push([String(k), String(v)]);
};
URLSearchParams.prototype.get = function (k) {
    for (var i = 0; i < this.pairs.length; i++) if (this.pairs[i][0] === k) return this.pairs[i][1];
    return null;
};
URLSearchParams.prototype.set = function (k, v) {
    this.delete(k);
    this.append(k, v);
};
URLSearchParams.prototype.delete = function (k) {
    this.pairs = this.pairs.filter(function (p) {
        return p[0] !== k;
    });
};
URLSearchParams.prototype.entries = function () {
    return this.pairs[Symbol.iterator]();
};
URLSearchParams.prototype[Symbol.iterator] = URLSearchParams.prototype.entries;
URLSearchParams.prototype.toString = function () {
    return this.pairs
        .map(function (p) {
            return encodeURIComponent(p[0]) + "=" + encodeURIComponent(p[1]);
        })
        .join("&");
};
function URL(value, base) {
    var u = JSON.parse(
        codec(
            "url",
            JSON.stringify({
                value: String(value).replace(/\[/g, "%5B").replace(/\]/g, "%5D"),
                base: base ? String(base) : "",
            }),
        ),
    );
    this.protocol = u.protocol;
    this.hostname = u.hostname;
    this.host = u.host;
    this.origin = u.origin;
    this.pathname = u.pathname;
    this.hash = u.hash;
    this.searchParams = new URLSearchParams(u.search);
    Object.defineProperty(this, "search", {
        get: function () {
            var s = this.searchParams.toString();
            return s ? "?" + s : "";
        },
        set: function (s) {
            this.searchParams = new URLSearchParams(s);
        },
    });
    Object.defineProperty(this, "href", {
        get: function () {
            return this.toString();
        },
    });
}
URL.prototype.toString = function () {
    return this.origin + this.pathname + this.search + this.hash;
};
function setTimeout(callback, ms) {
    return fetch("https://provider-timer.invalid/" + Math.min(3000, Math.max(0, Number(ms) || 0))).then(
        callback,
    );
}
var AbortSignal = {
    timeout: function () {
        return {};
    },
};
function fetch(url, init) {
    init = init || {};
    var index = __ordinal++,
        req = {
            url: String(url),
            method: init.method || "GET",
            headers: init.headers || {},
            body: init.body || "",
        };
    if (init.redirect === "manual") req.redirect = "manual";
    var response = __input.responses[index];
    if (!response) {
        if (__restoring) {
            if (__restoreCancelled) return Promise.reject(new Error("Cancelled"));
            __historyInvalid = true;
            throw new Error("Incomplete provider history");
        }
        __pending.push(req);
        return new Promise(function () {});
    }
    if (__restoring && JSON.stringify(req) !== JSON.stringify(response.request)) __historyInvalid = true;
    if (JSON.stringify(req) !== JSON.stringify(response.request))
        throw new Error("Non deterministic request");
    // Fast lookups count failed network time too; legacy replays retain their old clock contract.
    if (__input.fastResolve) __clock = response.completedAt || __clock;
    if (response.error) return Promise.reject(new Error("Provider transport failed"));
    __clock = response.completedAt || __clock;
    var r = response.response;
    return Promise.resolve({
        ok: r.status >= 200 && r.status < 300,
        status: r.status,
        url: r.url,
        headers: {
            get: function (k) {
                return r.headers[String(k).toLowerCase()] || null;
            },
            getSetCookie: function () {
                return r.cookies || [];
            },
        },
        text: function () {
            return Promise.resolve(r.text);
        },
        arrayBuffer: function () {
            return Promise.resolve(
                Buffer.from(r.bytes || codec("base64encode", codec("utf8encode", r.text)), "base64"),
            );
        },
        json: function () {
            try {
                return Promise.resolve(JSON.parse(r.text));
            } catch (e) {
                return Promise.reject(e);
            }
        },
        body: {
            cancel: function () {
                return Promise.resolve();
            },
        },
    });
}
function invoke(operation, payload) {
    if (operation === "metadata") return { runtime: 2, provider: __provider };
    __input = JSON.parse(payload);
    __pending = [];
    __ordinal = 0;
    __random = 0;
    __jobs = [];
    __clock = __input.now;
    var outcome,
        failure,
        complete = false;
    try {
        __load();
        Promise.resolve(__run(operation, __input.row)).then(
            function (v) {
                outcome = v;
                complete = true;
            },
            function (e) {
                failure = e;
                complete = true;
            },
        );
    } catch (e) {
        failure = e;
        complete = true;
    }
    var turns = 0;
    while (__jobs.length) {
        if (++turns > 200000) throw new Error("Microtask limit");
        __jobs.shift()();
    }
    if (complete) {
        if (failure) throw failure;
        return { done: true, value: outcome };
    }
    if (!__pending.length) throw new Error("Unsupported async operation");
    return { done: false, requests: __pending };
}
