// Wrappers replace server database/hooks with MovieHub's normalized row/stream interface.
export const adapters = {
    stirr: {
        catalogue: "exports.fetchStirrRows()",
        resolve: "exports.resolveStirrMaster(row.channelId)",
        headers: "exports.STIRR_STREAM_HEADERS",
    },
    tcl: {
        catalogue: "exports.fetchTclRows()",
        resolve: "exports.resolveTclMaster(row.channelId,row.source,row.media)",
        headers: "exports.TCL_STREAM_HEADERS",
    },
    xumo: {
        catalogue: "exports.fetchXumoRows()",
        resolve: "exports.resolveXumoMaster(row.channelId)",
        headers: "exports.XUMO_STREAM_HEADERS",
    },
    lg: {
        catalogue: "exports.fetchLgRows()",
        resolve: "exports.expandStreamMacros(row.streamUrl)",
        headers: "exports.LG_HEADERS",
    },
    whale: {
        catalogue: "exports.fetchWhaleRows()",
        resolve: "exports.fillUrlMacros(row.streamUrl)",
        headers: "exports.WHALE_HEADERS",
    },
    vizio: {
        catalogue:
            "exports.fetchVizioCatalog().then(function(rows){return rows.filter(exports.isSurfacedRow);})",
        resolve: "exports.expandMacros(row.streamUrl)",
        headers: "{'User-Agent':exports.UA}",
    },
    roku: {
        catalogue: "exports.fetchRokuRows()",
        resolve: "exports.resolveRokuMaster(row.channelId,false)",
        headers: "exports.ROKU_STREAM_HEADERS",
    },
    plex: {
        catalogue: "exports.fetchPlexRows()",
        // The manifest path needs the COMPOUND id (row.id, "<server prefix>-<gridKey>"), not the
        // stable gridKey in row.channelId: Plex answers 404 for the gridKey alone. The prefix
        // changes when Plex moves servers, so it comes from the (cached) catalogue row each time.
        resolve: "exports.resolvePlexMaster(row.id||row.channelId)",
        headers: "exports.PLEX_STREAM_HEADERS",
    },
};
