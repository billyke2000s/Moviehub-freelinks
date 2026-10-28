import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { randomUUID } from "node:crypto";
const pkg = JSON.parse(
    readFileSync(
        new URL("../../android-tv-launcher/app/src/main/assets/providers/builtin.json", import.meta.url),
    ),
);
function runtime() {
    const c = vm.createContext({ uuid: randomUUID });
    vm.runInContext(pkg.modules.freelivesports, c, { timeout: 1000 });
    return (op, payload) =>
        JSON.parse(
            vm.runInContext(`JSON.stringify(invoke(${JSON.stringify(op)},${JSON.stringify(payload)}))`, c, {
                timeout: 1000,
            }),
        );
}
test("upstream catalogue mapping, ordering, deduplication and inline guide", () => {
    const invoke = runtime();
    const rows = invoke(
        "catalogue",
        JSON.stringify([
            { _id: "b", name: "B", url: "https://example.com/b.m3u8", channelNumber: 2 },
            {
                _id: "a",
                name: "A",
                url: "https://example.com/a.m3u8",
                channelNumber: 1,
                epg: {
                    entries: [
                        {
                            title: "Sport",
                            start: "2026-10-19T00:00:00Z",
                            stop: "2026-10-19T01:00:00Z",
                            description: "Live",
                        },
                    ],
                },
            },
            { _id: "a", name: "Duplicate", url: "https://example.com/a.m3u8" },
            { name: "Invalid" },
        ]),
    );
    assert.deepEqual(
        rows.map((r) => r.channelId),
        ["a", "b"],
    );
    assert.equal(rows[0].programs[0].title, "Sport");
    assert.equal(rows[0].programs[0].end, "2026-10-19T01:00:00Z");
});
test("fresh macros and playback headers", () => {
    const invoke = runtime();
    const r = invoke("resolve", "https://example.com/a.m3u8?cb=[CB]&did=[DEVICE_ID]");
    assert.ok(!r.url.includes("["));
    assert.match(r.url, /cb=\d+/);
    assert.equal(r.referer, "https://www.freelivesports.tv/");
    assert.ok(r.headers.Origin);
});
test("unknown operations and Node modules fail closed", () => {
    const invoke = runtime();
    assert.throws(() => invoke("shell", ""));
    assert.equal(pkg.runtime, 2);
    assert.match(pkg.upstream, /^[a-f0-9]{40}$/);
});
