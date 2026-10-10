package com.axlero.logstream.model;

import java.util.List;

/** Response of GET /histogram: number of matching logs per time bucket. */
public class HistogramResult {

    public static class Bucket {
        public final long time;   // bucket start, epoch millis
        public final long count;  // matching logs inside the bucket

        public Bucket(long time, long count) {
            this.time = time;
            this.count = count;
        }
    }

    public final int bucketMinutes;
    public final long total;
    public final long tookMs;
    public final List<Bucket> points;

    public HistogramResult(int bucketMinutes, long total, long tookMs, List<Bucket> points) {
        this.bucketMinutes = bucketMinutes;
        this.total = total;
        this.tookMs = tookMs;
        this.points = points;
    }
}
