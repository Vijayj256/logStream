package com.axlero.logstream.model;

import java.util.List;

/** Response of GET /search. */
public class SearchResult {
    public final long total;        // exact number of logs matching the query
    public final long tookMs;       // how long the search took inside the server
    public final List<LogHit> hits; // newest first, capped by the "max" parameter

    public SearchResult(long total, long tookMs, List<LogHit> hits) {
        this.total = total;
        this.tookMs = tookMs;
        this.hits = hits;
    }
}
