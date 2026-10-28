import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
const api = (path) =>
    JSON.parse(execFileSync("gh", ["api", path], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }));
const root = "TheBinaryNinja/masqueradarr";
try {
    const commit = api(`repos/${root}/commits/HEAD`).sha;
    const tree = api(`repos/${root}/git/trees/${commit}`);
    const server = tree.tree.find((e) => e.path === "server");
    let repo = root,
        ref = commit,
        path = "server/src/sources/adapters/freelivesports/config.ts";
    if (server?.type === "commit") {
        // Resolve the pinned submodule, never guess a different public fork or substitute an old snapshot.
        repo = "iFlip721/masqueradarr-source";
        ref = server.sha;
        path = "src/sources/adapters/freelivesports/config.ts";
    }
    const license = api(`repos/${repo}/contents/LICENSE?ref=${ref}`);
    const licenseText = Buffer.from(license.content, "base64").toString("utf8").trim().replace(/\r\n/g, "\n");
    const auditedLicense = readFileSync("provider-engine/upstream/LICENSE", "utf8")
        .trim()
        .replace(/\r\n/g, "\n");
    if (licenseText !== auditedLicense) throw new Error("Upstream licence requires compatibility review");
    const files = new Map([["freelivesports.ts", "freelivesports/config.ts"]]);
    for (const id of ["stirr", "tcl", "xumo", "lg", "whale", "vizio", "samsung", "roku", "plex"])
        files.set(`modules/${id}/config.ts`, `${id}/config.ts`);
    files.set("modules/roku/categorize.ts", "roku/categorize.ts");
    files.set("modules/samsung/adapter.ts", "../adapters/samsung.ts");
    for (const name of [
        "config",
        "parseDirectory",
        "embedExtractors",
        "resolveStream",
        "playerMemory",
        "resolveCache",
        "transport",
    ])
        files.set(`modules/dlhd/${name}.ts`, `dlhd/${name}.ts`);
    for (const name of ["catalog", "client", "deviceToken", "endpoints", "resolveStream", "sign"])
        files.set(`modules/tubi/${name}.ts`, `tubi/api/${name}.ts`);
    files.set("modules/dlhd/ssrf.ts", "../core/ssrf.ts");
    files.set("modules/localnow/api.ts", "local/api.ts");
    files.set("modules/localnow/import.ts", "local/import.ts");
    files.set("modules/zlive/config.ts", "zlive/config.ts");
    files.set("modules/zlive/resolver.ts", "zlive/resolver.ts");
    files.set("modules/zlive/dynamicAllow.ts", "_fast/dynamicAllow.ts");
    files.set("modules/zlive/domain.ts", "../core/domain.ts");
    files.set("modules/zlive/ssrf.ts", "../core/ssrf.ts");
    const base = server?.type === "commit" ? "src/sources/adapters/" : "server/src/sources/adapters/";
    const retrieved = [];
    for (const [destination, relative] of files) {
        const resolved = new URL(relative, "https://source.invalid/" + base).pathname.slice(1);
        const file = api(`repos/${repo}/contents/${resolved}?ref=${ref}`);
        const source = Buffer.from(file.content, "base64").toString("utf8");
        if (source.length > 128 * 1024) throw new Error("Provider source limit");
        retrieved.push([destination, source]);
    }
    // Retrieve every module before mutating the build inputs. Partial fetches never publish a package.
    for (const [destination, source] of retrieved)
        writeFileSync("provider-engine/upstream/" + destination, source);
    writeFileSync(process.env.GITHUB_ENV, `UPSTREAM_SHA=${commit}\n`, { flag: "a" });
    console.log(`Retrieved provider modules from pinned source ${ref}`);
} catch (error) {
    console.error(
        "Upstream provider update blocked: pinned server submodule/module is unavailable, or its licence/dependencies require review. Existing installed/released packages are retained. Authorised access to iFlip721/masqueradarr-source is required.",
    );
    process.exitCode = 1;
}
