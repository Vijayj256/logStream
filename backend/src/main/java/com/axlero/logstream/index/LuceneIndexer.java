package com.axlero.logstream.index;

import com.axlero.logstream.model.HistogramResult;
import com.axlero.logstream.model.LogHit;
import com.axlero.logstream.model.LogQuery;
import com.axlero.logstream.model.QueryStringParser;
import com.axlero.logstream.model.SearchResult;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.facet.range.LongRangeFacetCounts;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The heart of the project: wraps one Lucene index.
 *
 *  - indexLog()   : called by the gRPC ingestion service for every incoming log
 *  - search()     : full-text + filter search, newest first (Week 2)
 *  - histogram()  : logs-per-time-bucket counts for the chart (Week 3)
 *  - count()      : number of matches in a time window, used by the alert engine (Week 4)
 *
 * Performance design: documents are added to Lucene's in-memory buffer and become
 * searchable through a near-real-time SearcherManager. The slow fsync "commit" to disk
 * happens on a background timer (every 5 s) instead of per log, which keeps ingestion fast.
 */
public class LuceneIndexer implements AutoCloseable {

    private static final int MAX_BUCKETS = 1000;
    private static final int MAX_RESULTS = 1000;

    private final Directory directory;
    private final StandardAnalyzer analyzer;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;
    private final ScheduledExecutorService committer;

    public LuceneIndexer(String indexPath) throws IOException {
        this.directory = openDirectory(indexPath);
        this.analyzer = new StandardAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setRAMBufferSizeMB(64.0);
        this.writer = new IndexWriter(directory, config);
        this.searcherManager = new SearcherManager(writer, null);

        this.committer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lucene-committer");
            t.setDaemon(true);
            return t;
        });
        this.committer.scheduleWithFixedDelay(this::commitQuietly, 5, 5, TimeUnit.SECONDS);
        // make new logs searchable in the background, so no search request has to pay for it
        this.committer.scheduleWithFixedDelay(this::refreshQuietly, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Opens the index folder. Normally Lucene picks the best implementation itself (memory-mapped files).
     * Start the server with -Dindex.directory=nio to use plain file I/O instead, e.g. as a fallback
     * if memory-mapped files cause trouble on your machine.
     */
    private static Directory openDirectory(String indexPath) throws IOException {
        if ("nio".equalsIgnoreCase(System.getProperty("index.directory", "default"))) {
            return new NIOFSDirectory(Paths.get(indexPath));
        }
        return FSDirectory.open(Paths.get(indexPath));
    }

    // ------------------------------------------------------------------ ingestion

    /** Index one log entry. IndexWriter is thread-safe, so many gRPC threads can call this at once. */
    public void indexLog(long timestamp, String level, String service,
                         String message, int responseTimeMs) throws IOException {
        String lvl = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        String svc = service == null ? "" : service.trim();
        String msg = message == null ? "" : message;

        Document doc = new Document();
        doc.add(new StringField("level", lvl, Field.Store.YES));        // exact-match filter
        doc.add(new StringField("service", svc, Field.Store.YES));      // exact-match filter
        doc.add(new TextField("message", msg, Field.Store.YES));        // full-text search
        doc.add(new LongPoint("timestamp", timestamp));                 // range queries (histogram, alerts)
        doc.add(new StoredField("timestamp_stored", timestamp));        // read back when showing results
        doc.add(new NumericDocValuesField("ts_sort", timestamp));       // lets us sort newest-first
        doc.add(new IntPoint("response_time", responseTimeMs));         // range filter (response_time >= N)
        doc.add(new StoredField("response_time_stored", responseTimeMs));
        writer.addDocument(doc);
    }

    /** Makes everything indexed so far visible to searches (cheap, no disk fsync). */
    public void refresh() throws IOException {
        searcherManager.maybeRefreshBlocking();
    }

    // ------------------------------------------------------------------ search (Week 2)

    /**
     * Searches the index. Results are sorted newest-first.
     *
     * @param fromMs only logs with timestamp >= fromMs (0 or negative = no lower bound)
     */
    public SearchResult search(LogQuery lq, long fromMs, int maxResults) throws Exception {
        long t0 = System.nanoTime();
        int limit = Math.max(1, Math.min(maxResults, MAX_RESULTS));
        refresh();

        IndexSearcher searcher = searcherManager.acquire();
        try {
            Query query = buildQuery(lq, fromMs > 0 ? Long.valueOf(fromMs) : null, null);
            // ONE pass over the matches: collects the newest `limit` logs AND counts every match exactly
            Sort newestFirst = new Sort(new SortField("ts_sort", SortField.Type.LONG, true));
            TopFieldDocs top = searcher.search(query,
                    new TopFieldCollectorManager(newestFirst, limit, null, Integer.MAX_VALUE));
            long total = top.totalHits.value;

            List<LogHit> hits = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc sd : top.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                hits.add(new LogHit(
                        doc.getField("timestamp_stored").numericValue().longValue(),
                        doc.get("level"),
                        doc.get("service"),
                        doc.get("message"),
                        doc.getField("response_time_stored").numericValue().intValue()));
            }
            return new SearchResult(total, elapsedMs(t0), hits);
        } finally {
            searcherManager.release(searcher);
        }
    }

    // ------------------------------------------------------------------ aggregations (Week 3)

    /**
     * Counts matching logs per time bucket, e.g. "logs per minute over the last hour".
     * Buckets are aligned to wall-clock boundaries and the last bucket contains "now".
     */
    public HistogramResult histogram(LogQuery lq, int minutes, int bucketMinutes) throws Exception {
        long t0 = System.nanoTime();
        int bucketMin = Math.max(1, bucketMinutes);
        int buckets = bucketCount(minutes, bucketMin);
        long bucketMs = bucketMin * 60_000L;
        long start = windowStart(System.currentTimeMillis(), minutes, bucketMin);
        long endExclusive = start + buckets * bucketMs;

        // One facet range per time bucket; Lucene's Facets module counts all of them in a single pass
        LongRange[] ranges = new LongRange[buckets];
        for (int i = 0; i < buckets; i++) {
            long from = start + i * bucketMs;
            ranges[i] = new LongRange(Long.toString(from), from, true, from + bucketMs - 1, true);
        }

        refresh();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            Query query = buildQuery(lq, start, endExclusive - 1);
            FacetsCollector collector = new FacetsCollector();
            FacetsCollector.search(searcher, query, 1, collector);
            Facets facets = new LongRangeFacetCounts("ts_sort", collector, ranges);
            FacetResult result = facets.getAllChildren("ts_sort");

            Map<Long, Long> counts = new HashMap<>();
            if (result != null) {
                for (LabelAndValue lv : result.labelValues) {
                    counts.put(Long.parseLong(lv.label), lv.value.longValue());
                }
            }
            List<HistogramResult.Bucket> points = new ArrayList<>(buckets);
            long total = 0;
            for (int i = 0; i < buckets; i++) {
                long from = start + i * bucketMs;
                long c = counts.getOrDefault(from, 0L);
                points.add(new HistogramResult.Bucket(from, c));
                total += c;
            }
            return new HistogramResult(bucketMin, total, elapsedMs(t0), points);
        } finally {
            searcherManager.release(searcher);
        }
    }

    private static int bucketCount(int minutes, int bucketMinutes) {
        int bucketMin = Math.max(1, bucketMinutes);
        int window = Math.max(1, Math.min(minutes, 7 * 24 * 60));
        return Math.min(MAX_BUCKETS, Math.max(1, window / bucketMin));
    }

    /**
     * Start of the time window the histogram draws (aligned to bucket boundaries).
     * The search endpoint uses the same start, so the chart total and the "found" count always agree.
     */
    public static long windowStart(long now, int minutes, int bucketMinutes) {
        long bucketMs = Math.max(1, bucketMinutes) * 60_000L;
        long endExclusive = (now / bucketMs + 1) * bucketMs;
        return endExclusive - bucketCount(minutes, bucketMinutes) * bucketMs;
    }

    // ------------------------------------------------------------------ alerting support (Week 4)

    /** Number of logs matching the query with fromMs <= timestamp <= toMs. */
    public long count(LogQuery lq, long fromMs, long toMs) throws Exception {
        refresh();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            return searcher.count(buildQuery(lq, fromMs, toMs));
        } finally {
            searcherManager.release(searcher);
        }
    }

    /** Total number of logs in the index. */
    public long totalDocs() throws IOException {
        refresh();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            return searcher.getIndexReader().numDocs();
        } finally {
            searcherManager.release(searcher);
        }
    }

    // ------------------------------------------------------------------ internals

    /** Turns a LogQuery (+ optional time range) into one Lucene BooleanQuery. */
    private Query buildQuery(LogQuery lq, Long fromMs, Long toMs) throws Exception {
        LogQuery query = QueryStringParser.expand(lq);   // understands level:ERROR AND response_time > 1000
        BooleanQuery.Builder builder = new BooleanQuery.Builder();

        if (query.hasText()) {
            // escape() makes user input safe: it can't break the Lucene query syntax
            QueryParser parser = new QueryParser("message", analyzer);
            builder.add(parser.parse(QueryParser.escape(query.q)), BooleanClause.Occur.MUST);
        } else {
            builder.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
        }
        if (query.hasService()) {
            builder.add(new TermQuery(new Term("service", query.serviceValue())), BooleanClause.Occur.FILTER);
        }
        if (query.hasLevel()) {
            builder.add(new TermQuery(new Term("level", query.levelValue())), BooleanClause.Occur.FILTER);
        }
        if (query.minResponseTime > 0 || query.maxResponseTime >= 0) {
            int lo = Math.max(0, query.minResponseTime);
            int hi = query.maxResponseTime >= 0 ? query.maxResponseTime : Integer.MAX_VALUE;
            if (lo > hi) {
                builder.add(new MatchNoDocsQuery(), BooleanClause.Occur.FILTER);
            } else {
                builder.add(IntPoint.newRangeQuery("response_time", lo, hi), BooleanClause.Occur.FILTER);
            }
        }
        if (fromMs != null || toMs != null) {
            long from = fromMs == null ? Long.MIN_VALUE : fromMs;
            long to = toMs == null ? Long.MAX_VALUE : toMs;
            builder.add(LongPoint.newRangeQuery("timestamp", from, to), BooleanClause.Occur.FILTER);
        }
        return builder.build();
    }

    private void refreshQuietly() {
        try {
            searcherManager.maybeRefresh();
        } catch (Exception e) {
            // index is closing; nothing to do
        }
    }

    private void commitQuietly() {
        try {
            if (writer.hasUncommittedChanges()) {
                writer.commit();
            }
        } catch (Exception e) {
            System.err.println("Background commit failed: " + e.getMessage());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @Override
    public void close() throws IOException {
        committer.shutdownNow();
        searcherManager.close();
        writer.close();   // also commits everything still pending
        directory.close();
    }
}
