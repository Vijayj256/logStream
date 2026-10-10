package com.axlero.logstream.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands the query syntax typed into the search box (from the project brief):
 *
 *     level:ERROR AND service:billing-api AND response_time > 1000
 *
 * Supported:  level:X   service:X   service:"quoted value"
 *             response_time > N, >= N, < N, <= N, = N   (also written response_time:>N or response_time:N)
 *             any other words are full-text search over the log message
 *             AND (or &&) between conditions - every condition must match
 *
 * The result is a plain LogQuery, so search, the histogram and alert rules all understand typed queries.
 */
public final class QueryStringParser {

    private static final Pattern RESPONSE = Pattern.compile(
            "(?i)\\bresponse_?time\\s*(?:(>=|<=|>|<|=)|:\\s*(>=|<=|>|<)?)\\s*(\\d+)");
    private static final Pattern FIELD = Pattern.compile(
            "(?i)\\b(level|service)\\s*:\\s*(?:\"([^\"]*)\"|([^\\s()\"]+))");
    private static final Pattern AND = Pattern.compile("(?:^|\\s)(?:AND|&&)(?=\\s|$)");

    private QueryStringParser() {
    }

    /** Returns a new LogQuery where typed conditions have been moved into the structured filters. */
    public static LogQuery expand(LogQuery in) {
        if (in == null) {
            return new LogQuery();
        }
        String text = in.q == null ? "" : in.q;
        String service = in.service == null ? "" : in.service;
        String level = in.level == null ? "" : in.level;
        long lo = Math.max(0, in.minResponseTime);
        long hi = in.maxResponseTime < 0 ? Long.MAX_VALUE : in.maxResponseTime;

        Matcher m = RESPONSE.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String op = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : "=");
            String digits = m.group(3);
            long n = digits.length() > 9 ? Integer.MAX_VALUE : Long.parseLong(digits);
            switch (op) {
                case ">":
                    lo = Math.max(lo, n + 1);
                    break;
                case ">=":
                    lo = Math.max(lo, n);
                    break;
                case "<":
                    hi = Math.min(hi, n - 1);
                    break;
                case "<=":
                    hi = Math.min(hi, n);
                    break;
                default:
                    lo = Math.max(lo, n);
                    hi = Math.min(hi, n);
                    break;
            }
            m.appendReplacement(sb, " ");
        }
        m.appendTail(sb);
        text = sb.toString();

        m = FIELD.matcher(text);
        sb = new StringBuffer();
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2) : m.group(3);
            if (m.group(1).equalsIgnoreCase("level")) {
                level = value;
            } else {
                service = value;
            }
            m.appendReplacement(sb, " ");
        }
        m.appendTail(sb);
        text = sb.toString();

        text = AND.matcher(text).replaceAll(" ");
        text = text.replace('(', ' ').replace(')', ' ').replaceAll("\\s+", " ").trim();

        LogQuery out = new LogQuery(text, service, level, (int) Math.min(lo, Integer.MAX_VALUE));
        if (hi != Long.MAX_VALUE && hi < lo) {
            out.minResponseTime = 1;   // impossible range (e.g. response_time < 0): matches nothing
            out.maxResponseTime = 0;
        } else {
            out.maxResponseTime = hi == Long.MAX_VALUE ? -1 : (int) Math.min(hi, Integer.MAX_VALUE);
        }
        return out;
    }
}
