package com.axlero.logstream.model;

/** One log line, as returned by search and as pushed over the live-tail WebSocket. */
public class LogHit {
    public final long timestamp;
    public final String level;
    public final String service;
    public final String message;
    public final int responseTimeMs;

    public LogHit(long timestamp, String level, String service, String message, int responseTimeMs) {
        this.timestamp = timestamp;
        this.level = level;
        this.service = service;
        this.message = message;
        this.responseTimeMs = responseTimeMs;
    }
}
