package com.axlero.logstream.alert;

import com.axlero.logstream.index.LuceneIndexer;
import com.axlero.logstream.model.LogQuery;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Week 4: the rule-evaluation engine.
 *
 * A background ScheduledExecutorService wakes up every N seconds (default 60 = "every minute"),
 * runs every enabled rule's saved query against the Lucene index for the rule's time window,
 * and triggers a (simulated) webhook when the number of matches is above the threshold.
 *
 * A rule fires once when it goes from OK to FIRING, and is re-armed when the count drops back to
 * or below the threshold - so a long outage produces one alert, not one alert per minute.
 *
 * Rules are saved to a JSON file so they survive server restarts.
 */
public class AlertEngine {

    private static final int MAX_HISTORY = 100;

    private final LuceneIndexer indexer;
    private final Path rulesFile;
    private final Gson compactGson = new GsonBuilder().disableHtmlEscaping().create();
    private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final List<AlertRule> rules = new ArrayList<>();
    private final Deque<AlertEvent> history = new ArrayDeque<>();
    private final ScheduledExecutorService scheduler;

    public AlertEngine(LuceneIndexer indexer, String rulesFilePath, int intervalSeconds) {
        this.indexer = indexer;
        this.rulesFile = Paths.get(rulesFilePath);
        loadRules();

        int interval = Math.max(1, intervalSeconds);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "alert-evaluator");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleWithFixedDelay(this::evaluateQuietly, interval, interval, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ rule management

    public synchronized List<AlertRule> getRules() {
        return new ArrayList<>(rules);
    }

    public synchronized List<AlertEvent> getHistory() {
        return new ArrayList<>(history);
    }

    /** Validates and stores a new rule. Throws IllegalArgumentException with a readable message. */
    public synchronized AlertRule addRule(AlertRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("Rule is missing");
        }
        if (rule.name == null || rule.name.isBlank()) {
            throw new IllegalArgumentException("Rule name is required");
        }
        if (rule.windowMinutes < 1 || rule.windowMinutes > 1440) {
            throw new IllegalArgumentException("windowMinutes must be between 1 and 1440");
        }
        if (rule.threshold < 0) {
            throw new IllegalArgumentException("threshold cannot be negative");
        }
        if (rule.query == null) {
            rule.query = new LogQuery();
        }
        if (rule.webhookUrl == null || rule.webhookUrl.isBlank()) {
            rule.webhookUrl = "http://localhost:9000/webhook";
        }
        rule.name = rule.name.trim();
        rule.id = UUID.randomUUID().toString();
        rule.firing = false;
        rule.lastCount = -1;
        rule.lastEvaluated = 0;
        rule.lastFired = 0;
        rules.add(rule);
        saveRules();
        return rule;
    }

    public synchronized boolean deleteRule(String id) {
        boolean removed = rules.removeIf(r -> r.id != null && r.id.equals(id));
        if (removed) {
            saveRules();
        }
        return removed;
    }

    // ------------------------------------------------------------------ evaluation

    /** Runs every enabled rule once, right now. Also called by the scheduler. */
    public synchronized List<AlertRule> evaluateAll() throws Exception {
        long now = System.currentTimeMillis();
        for (AlertRule rule : rules) {
            if (!rule.enabled) {
                continue;
            }
            long from = now - rule.windowMinutes * 60_000L;
            long count = indexer.count(rule.query, from, now);
            rule.lastCount = count;
            rule.lastEvaluated = now;

            boolean breached = count > rule.threshold;
            if (breached && !rule.firing) {
                rule.firing = true;
                rule.lastFired = now;
                fire(rule, count, now);
            } else if (!breached) {
                rule.firing = false;
            }
        }
        return new ArrayList<>(rules);
    }

    private void fire(AlertRule rule, long count, long now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alert", rule.name);
        payload.put("status", "FIRING");
        payload.put("condition", "count > " + rule.threshold + " in the last " + rule.windowMinutes + " minute(s)");
        payload.put("count", count);
        payload.put("threshold", rule.threshold);
        payload.put("windowMinutes", rule.windowMinutes);
        payload.put("query", rule.query);
        payload.put("firedAt", Instant.ofEpochMilli(now).toString());
        String json = compactGson.toJson(payload);

        history.addFirst(new AlertEvent(UUID.randomUUID().toString(), rule.id, rule.name, now, count,
                rule.threshold, rule.windowMinutes, rule.webhookUrl, json));
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }
        WebhookNotifier.send(rule.webhookUrl, json);
    }

    private void evaluateQuietly() {
        try {
            evaluateAll();
        } catch (Throwable t) {
            // never let an exception kill the scheduler thread
            System.err.println("Alert evaluation failed: " + t);
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------ persistence

    private void loadRules() {
        try {
            if (Files.exists(rulesFile)) {
                String json = Files.readString(rulesFile, StandardCharsets.UTF_8);
                AlertRule[] loaded = prettyGson.fromJson(json, AlertRule[].class);
                if (loaded != null) {
                    for (AlertRule r : Arrays.asList(loaded)) {
                        if (r == null) {
                            continue;
                        }
                        if (r.query == null) {
                            r.query = new LogQuery();
                        }
                        if (r.id == null || r.id.isBlank()) {
                            r.id = UUID.randomUUID().toString();
                        }
                        rules.add(r);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Could not read " + rulesFile + " (" + e.getMessage() + "), starting with default rules");
        }

        if (rules.isEmpty() && !Files.exists(rulesFile)) {
            AlertRule demo = new AlertRule();
            demo.id = UUID.randomUUID().toString();
            demo.name = "High ERROR rate";
            demo.query = new LogQuery("", "", "ERROR", 0);
            demo.windowMinutes = 5;
            demo.threshold = 100;
            rules.add(demo);
            saveRules();
        }
    }

    private void saveRules() {
        try {
            Files.writeString(rulesFile, prettyGson.toJson(rules), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Could not save alert rules: " + e.getMessage());
        }
    }
}
