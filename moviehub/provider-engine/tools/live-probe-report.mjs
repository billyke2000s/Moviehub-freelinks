// Prints live-probe results as GitHub annotations (readable through the API, unlike job logs),
// one per provider. GitHub keeps at most 10 notices per step, so the workflow calls this twice
// with a range: node live-probe-report.mjs 0 8, then 8 16.
import { readFileSync } from "node:fs";
const results = JSON.parse(readFileSync("build/provider-tests/live-probe.json"));
const [from, to] = process.argv.slice(2).map(Number);
const escape = (s) => String(s).replace(/%/g, "%25").replace(/\r/g, "%0D").replace(/\n/g, "%0A");
for (const r of results.slice(from, to)) {
    const s = r.catalogueStats;
    const lines = [
        `catalogue: ${r.catalogue} · ${r.channels} channels · ${r.sky} Sky Sports`,
        `${s.requests} requests, steps ${s.steps}, slowest step ${s.slowestStepMs} ms, max response ${s.maxResponseKB} KB, total ${s.totalKB} KB, hosts ${s.hosts.join(" ")}`,
        ...r.samples.map((x) => `- ${x.name}: resolve ${x.resolve}; play ${x.play || "-"}`),
    ];
    console.log(`::notice title=probe ${r.id}::${escape(lines.join("\n"))}`);
}
