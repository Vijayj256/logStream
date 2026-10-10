package com.axlero.logstream.api;

import com.axlero.logstream.index.LuceneIndexer;
import com.sun.net.httpserver.HttpExchange;

import java.util.Map;

/** GET /histogram?q=&service=&level=&minRt=&minutes=60&bucket=1  ->  { bucketMinutes, total, tookMs, points: [{time,count}] } */
public class HistogramHandler extends ApiHandler {

    private final LuceneIndexer indexer;

    public HistogramHandler(LuceneIndexer indexer) {
        this.indexer = indexer;
    }

    @Override
    protected void process(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");
        Map<String, String> p = queryParams(ex);
        int minutes = intParam(p, "minutes", 60);
        int bucket = intParam(p, "bucket", 1);
        sendJson(ex, 200, indexer.histogram(logQueryFrom(p), minutes, bucket));
    }
}
