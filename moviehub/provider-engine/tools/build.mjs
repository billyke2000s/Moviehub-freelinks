import ts from "typescript";
import { adapters } from "./adapters.mjs";
import { readFileSync, writeFileSync, mkdirSync, readdirSync } from "node:fs";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const source = readFileSync(root + "upstream/freelivesports.ts", "utf8");
// Only this audited, self-contained leaf is compiled. Server adapters and their DB/browser hooks
// are deliberately not stubbed into pretending they succeeded.
const ast = ts.createSourceFile("provider.ts", source, ts.ScriptTarget.Latest, true);
for (const statement of ast.statements) {
    if (ts.isImportDeclaration(statement) && statement.moduleSpecifier.text !== "node:crypto") {
        throw new Error("New dependency requires a compatibility review");
    }
}
const compiled = ts.transpileModule(source, {
    compilerOptions: {
        target: ts.ScriptTarget.ES5,
        module: ts.ModuleKind.CommonJS,
        downlevelIteration: true,
        removeComments: true,
    },
    reportDiagnostics: true,
});
if (compiled.diagnostics?.some((d) => d.category === ts.DiagnosticCategory.Error)) {
    throw new Error("Provider compilation failed");
}
const prelude = `var exports = {};
var process = {env: {}};
function require(id) {
  if (id !== 'node:crypto') throw new Error('Unsupported module');
  return {randomUUID: function() {return uuid();}};
}
`;
const wrapper = `
function invoke(operation, payload) {
  if (operation === 'catalogue') return exports.flattenFlsRows(JSON.parse(payload));
  if (operation === 'metadata') return {url: exports.CATALOG_URL, headers: exports.FLS_HEADERS};
  if (operation === 'resolve') return {
    url: exports.fillStreamMacros(payload),
    userAgent: exports.FLS_HEADERS['User-Agent'], referer: exports.FLS_HEADERS.Referer,
    headers: exports.FLS_HEADERS
  };
  throw new Error('Unsupported operation');
}
`;
const pkg = {
    runtime: 2,
    capabilities: { zliveSources: 1 },
    version: Number(process.env.PROVIDER_VERSION || "1"),
    upstream: process.env.UPSTREAM_SHA || "3b860294ecb4a9ebca8819adfc061162fbbb7f99",
    modules: { freelivesports: prelude + compiled.outputText + wrapper },
    sourceSha256: createHash("sha256").update(source).digest("hex"),
};
const compatibility = readFileSync(root + "tools/compat.js", "utf8");
const auditedImports = new Set([
    "node:crypto",
    "node:zlib",
    "node:net",
    "../_fast/dynamicAllow.js",
    "../../core/domain.js",
    "./ssrf.js",
    "../../core/logger.js",
    "./categorize.js",
    "./config.js",
    "./embedExtractors.js",
    "./playerMemory.js",
    "./resolveCache.js",
    "./transport.js",
    "../../core/ssrf.js",
    "../../../logs/tier.js",
    "../../../core/logger.js",
    "./client.js",
    "./deviceToken.js",
    "./endpoints.js",
    "./sign.js",
]);
function compileLeaf(path) {
    const text = readFileSync(root + path, "utf8");
    const tree = ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true);
    for (const node of tree.statements) {
        if (
            ts.isImportDeclaration(node) &&
            !node.importClause?.isTypeOnly &&
            !auditedImports.has(node.moduleSpecifier.text)
        ) {
            throw new Error("Unaudited dependency in " + path + ": " + node.moduleSpecifier.text);
        }
    }
    return ts.transpileModule(text, {
        compilerOptions: {
            target: ts.ScriptTarget.ES5,
            module: ts.ModuleKind.CommonJS,
            downlevelIteration: true,
            removeComments: true,
        },
    }).outputText;
}
for (const [id, adapter] of Object.entries(adapters)) {
    const compiled = compileLeaf("upstream/modules/" + id + "/config.ts");
    const categories = id === "roku" ? compileLeaf("upstream/modules/roku/categorize.ts") : "";
    pkg.modules[id] =
        compatibility +
        `
var __provider=${JSON.stringify(id)}, exports, __categories;
function __load(){
  __categories=(function(exports){${categories}\n;return exports;})({});
  exports=(function(exports){${compiled}\n;return exports;})({});
}
function __run(operation,row){
  if(operation==='catalogue')return ${adapter.catalogue};
  if(operation==='resolve')return Promise.resolve(${adapter.resolve}).then(function(url){
    var headers=${adapter.headers};
    return {url:url,headers:headers,userAgent:headers['User-Agent']||'',referer:headers.Referer||''};
  });
  throw new Error('Unsupported provider operation');
}
`;
}
const dlhdFiles = [
    "config",
    "parseDirectory",
    "embedExtractors",
    "playerMemory",
    "resolveCache",
    "transport",
    "resolveStream",
    "ssrf",
];
const dlhdFactories = dlhdFiles
    .map(
        (name) =>
            `${JSON.stringify("./" + name + ".js")}:function(exports,require){${compileLeaf("upstream/modules/dlhd/" + name + ".ts")}\n;return exports;}`,
    )
    .join(",");
pkg.modules.dlhd =
    compatibility +
    readFileSync(root + "tools/dlhd-session.js", "utf8") +
    `
var __provider='dlhd',exports,__baseRequire=require,__dlhd;
function __load(){
  __installDlhdSessions();
  var factories={${dlhdFactories}},cache={};
  require=function(id){
    if(id==='../../core/ssrf.js')id='./ssrf.js';
    if(id==='../../../logs/tier.js')return {logMilestone:function(){},logTrace:function(){}};
    if(!factories[id])return __baseRequire(id);
    if(!cache[id]){cache[id]={};cache[id]=factories[id](cache[id],require);}
    return cache[id];
  };
  exports=require('./config.js'); __dlhd=require('./resolveStream.js');
}
function __run(operation,row){
  if(operation==='catalogue')return fetch(exports.getBase()+'/24-7-channels.php',{headers:{Referer:exports.getReferer(),'User-Agent':exports.UA}}).then(function(r){if(!r.ok)throw new Error('Directory unavailable');return r.text();}).then(function(html){return require('./parseDirectory.js').parseChannels(html).map(function(r){return {channelId:String(r.id),name:r.name,category:r.group};});});
  if(operation==='resolve'){var memo=require('./playerMemory.js');if(row.preferredPlayer)memo.noteGood(row.channelId,row.preferredPlayer);(row.burntPlayers||[]).forEach(function(p){memo.noteBad(row.channelId,p);});return __dlhd.resolveStreamUrl(row.channelId,{deep:true}).then(__dlhdResult);}
  throw new Error('Unsupported operation');
}
`;
const tubiFiles = ["catalog", "client", "deviceToken", "endpoints", "resolveStream", "sign"];
const tubiFactories = tubiFiles
    .map(
        (name) =>
            `${JSON.stringify("./" + name + ".js")}:function(exports,require){${compileLeaf("upstream/modules/tubi/" + name + ".ts")}\n;return exports;}`,
    )
    .join(",");
pkg.modules.tubi =
    compatibility +
    `
var __provider='tubi',exports,__baseRequire=require;
function __load(){
 var factories={${tubiFactories}},cache={};
 require=function(id){if(!factories[id])return __baseRequire(id);if(!cache[id]){cache[id]={};cache[id]=factories[id](cache[id],require);}return cache[id];};
 exports=require('./catalog.js');
}
function __run(operation,row){
 if(operation==='catalogue')return exports.fetchApiCatalog().then(function(list){return list.raw.filter(function(r){return r.content_id!=null&&r.video_resources&&r.video_resources[0]&&r.video_resources[0].manifest;}).map(function(r){return {channelId:String(r.content_id),name:r.title||String(r.content_id),category:r.group||'Live TV',logo:r.images&&r.images[0]?r.images[0].url:null,programs:(r.programs||[]).map(function(p){return {title:p.title,start:p.start_time,end:p.end_time,desc:p.description};})};});});
 if(operation==='resolve')return require('./resolveStream.js').resolveApiStream(row.channelId).then(function(r){var ua=require('./endpoints.js').BROWSER_UA;return {url:r.masterUrl,headers:{'User-Agent':ua},userAgent:ua,referer:''};});
 throw new Error('Unsupported operation');
}
`;
const samsungSource = readFileSync(root + "upstream/modules/samsung/adapter.ts", "utf8");
const samsungTree = ts.createSourceFile("samsung.ts", samsungSource, ts.ScriptTarget.Latest, true);
const samsungFunctions = samsungTree.statements
    .filter(
        (node) =>
            ts.isFunctionDeclaration(node) &&
            ["fetchGzJson", "listChannels", "normalize", "resolveStream"].includes(node.name?.text),
    )
    .map((node) => node.getText(samsungTree))
    .join("\n");
if (
    samsungTree.statements.filter(
        (node) =>
            ts.isFunctionDeclaration(node) &&
            ["fetchGzJson", "listChannels", "normalize", "resolveStream"].includes(node.name?.text),
    ).length !== 4
)
    throw new Error("Samsung adapter interface changed");
const samsungCompiled = ts.transpileModule(samsungFunctions, {
    compilerOptions: {
        target: ts.ScriptTarget.ES5,
        module: ts.ModuleKind.CommonJS,
        downlevelIteration: true,
        removeComments: true,
    },
}).outputText;
pkg.modules.samsung =
    compatibility +
    `
var __provider='samsung',exports,__samsung;
function __load(){
 exports=(function(exports){${compileLeaf("upstream/modules/samsung/config.ts")}\n;return exports;})({});
 __samsung=(function(){
  var SOURCE_ID='samsung',SNAPSHOT='',CHANNELS_URL=exports.CHANNELS_URL,UA=exports.UA,STREAM_URL=exports.STREAM_URL,samsungRegions=function(){return ['gb'];};
  var gunzipSync=require('node:zlib').gunzipSync,readFileSync=function(){throw new Error('Live catalogue unavailable');};
  var allow={allow:function(){}};
  ${samsungCompiled}
  return {list:listChannels,normalize:normalize,resolve:resolveStream};
 })();
}
function __run(operation,row){
 if(operation==='catalogue')return __samsung.list().then(function(list){return list.raw.map(function(raw){return __samsung.normalize(raw,{ingestedAt:new Date().toISOString()});}).filter(function(r){return !!r;}).map(function(r){return {channelId:r.sourceChannelId,name:r.name,category:r.groupLabel,logo:r.logoUrl,streamUrl:r.streamEntryUrl};});});
 if(operation==='resolve')return __samsung.resolve(row.streamUrl || exports.STREAM_URL.replace('{id}',row.channelId)).then(function(r){return {url:r.masterUrl,headers:{'User-Agent':exports.UA},userAgent:exports.UA,referer:''};});
 throw new Error('Unsupported operation');
}
`;
const localSource = readFileSync(root + "upstream/modules/localnow/import.ts", "utf8");
const localTree = ts.createSourceFile("local.ts", localSource, ts.ScriptTarget.Latest, true);
const localCategory = localTree.statements
    .filter(
        (node) =>
            (ts.isFunctionDeclaration(node) && node.name?.text === "categoryFor") ||
            (ts.isVariableStatement(node) &&
                node.declarationList.declarations.some((d) => d.name.getText(localTree) === "CALL_SIGN")),
    )
    .map((node) => node.getText(localTree))
    .join("\n");
if (!localCategory.includes("function categoryFor")) throw new Error("Local Now normalization changed");
const localCategoryJs = ts.transpileModule(localCategory, {
    compilerOptions: { target: ts.ScriptTarget.ES5, module: ts.ModuleKind.CommonJS, removeComments: true },
}).outputText;
pkg.modules.localnow =
    compatibility +
    `
var __provider='localnow',exports,__category;
function __load(){
 exports=(function(exports){${compileLeaf("upstream/modules/localnow/api.ts")}\n;return exports;})({});
 __category=(function(){${localCategoryJs}\n;return categoryFor;})();
}
function __run(operation,row){
 if(operation==='catalogue')return exports.detectMarket().then(function(market){return exports.fetchMarket(market.dma,market.market).then(function(raw){return exports.selectChannels(raw).map(function(item){var ch=item.ch;return {channelId:market.dma+':'+item.id,name:ch.name||item.id,logo:ch.logo||null,category:__category(ch),programs:(ch.program||[]).filter(function(p){return Number.isFinite(Number(p.starts_at))&&Number.isFinite(Number(p.ends_at));}).map(function(p){return {title:p.program_title||'Unknown',start:new Date(Number(p.starts_at)*1000).toISOString(),end:new Date(Number(p.ends_at)*1000).toISOString(),desc:p.program_description||ch.description||''};})};});});});
 if(operation==='resolve'){var id=row.channelId.indexOf(':')<0?row.channelId:row.channelId.slice(row.channelId.indexOf(':')+1);return exports.resolvePlayback(id,null).then(function(r){var h=exports.LOCAL_STREAM_HEADERS;return {url:r.masterUrl,headers:h,userAgent:h['User-Agent'],referer:h.Referer};});}
 throw new Error('Unsupported operation');
}
`;
pkg.modules.zlive = compatibility + readFileSync(root + "tools/zlive.js", "utf8");
pkg.modules.freeshot = compatibility + readFileSync(root + "tools/freeshot.js", "utf8");
pkg.sourceSha256 = createHash("sha256").update(JSON.stringify(pkg.modules)).digest("hex");
pkg.sources = Object.fromEntries(
    readdirSync(root + "upstream/", { recursive: true })
        .filter((path) => path.endsWith(".ts"))
        .sort()
        .map((path) => [
            path,
            createHash("sha256")
                .update(readFileSync(root + "upstream/" + path))
                .digest("hex"),
        ]),
);
if (!Number.isSafeInteger(pkg.version) || pkg.version < 1) throw new Error("Invalid version");
const bytes = Buffer.from(JSON.stringify(pkg));
mkdirSync(root + "dist", { recursive: true });
writeFileSync(root + "dist/providers.json", bytes);
const assets = root + "../android-tv-launcher/app/src/main/assets/providers/";
mkdirSync(assets, { recursive: true });
writeFileSync(assets + "builtin.json", bytes);
writeFileSync(assets + "LICENSE.masqueradarr.txt", readFileSync(root + "upstream/LICENSE"));
console.log(`Built runtime ${pkg.runtime}, package ${pkg.version}, ${bytes.length} bytes`);
