package com.axlero.logstream.model;

import java.util.Locale;

/**
 * A reusable "saved query": free text over the message plus optional filters.
 * Used by the search endpoint, the histogram endpoint and the alert rules,
 * so all three features understand queries in exactly the same way.
 */
public class LogQuery {

    public String q = "";              // full-text search over the log message
    public String service = "";        // exact service name filter (empty = any)
    public String level = "";          // INFO / WARN / ERROR (empty = any)
    public int minResponseTime = 0;    // only logs with response_time >= this (0 = no filter)
    public int maxResponseTime = -1;   // only logs with response_time <= this (-1 = no limit)

    public LogQuery() {
    }

    public LogQuery(String q, String service, String level, int minResponseTime) {
        this.q = q == null ? "" : q.trim();
        this.service = service == null ? "" : service.trim();
        this.level = level == null ? "" : level.trim();
        this.minResponseTime = Math.max(0, minResponseTime);
    }

    public boolean hasText() {
        return q != null && !q.isBlank();
    }

    public boolean hasService() {
        return service != null && !service.isBlank();
    }

    public boolean hasLevel() {
        return level != null && !level.isBlank();
    }

    public String serviceValue() {
        return service.trim();
    }

    public String levelValue() {
        return level.trim().toUpperCase(Locale.ROOT);
    }
}
