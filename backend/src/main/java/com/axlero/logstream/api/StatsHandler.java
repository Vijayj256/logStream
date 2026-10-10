package com.axlero.logstream.api;

import com.axlero.logstream.index.LuceneIndexer;
import com.axlero.logstream.live.LiveTailServer;
import com.sun.net.httpserver.HttpExchange;

import java.util.LinkedHashMap;
import java.util.Map;

/** GET /stats  ->  { status, totalDocs, liveTailClients }  (also works as a health check) */
public class StatsHandler extends ApiHandler {

    private final LuceneIndexer indexer;
    private final LiveTailServer liveTail;

    public StatsHandler(LuceneIndexer indexer, LiveTailServer liveTail) {
        this.indexer = indexer;
        this.liveTail = liveTail;
    }

    @Override
    protected void process(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("status", "ok");
        stats.put("totalDocs", indexer.totalDocs());
        stats.put("liveTailClients", liveTail.clientCount());
        sendJson(ex, 200, stats);
    }
}
