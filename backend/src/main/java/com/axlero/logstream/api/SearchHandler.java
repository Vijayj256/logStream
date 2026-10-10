package com.axlero.logstream.api;

import com.axlero.logstream.index.LuceneIndexer;
import com.sun.net.httpserver.HttpExchange;

import java.util.Map;

/** GET /search?q=&service=&level=&minRt=&minutes=&bucket=&max=   ->  { total, tookMs, hits: [...] } */
public class SearchHandler extends ApiHandler {

    private final LuceneIndexer indexer;

    public SearchHandler(LuceneIndexer indexer) {
        this.indexer = indexer;
    }

    @Override
    protected void process(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");
        Map<String, String> p = queryParams(ex);
        int minutes = intParam(p, "minutes", 0);       // 0 = any time
        int max = intParam(p, "max", 50);
        int bucket = intParam(p, "bucket", 1);         // same bucket size the chart uses, keeps both windows identical
        long fromMs = minutes > 0 ? LuceneIndexer.windowStart(System.currentTimeMillis(), minutes, bucket) : 0;
        sendJson(ex, 200, indexer.search(logQueryFrom(p), fromMs, max));
    }
}
