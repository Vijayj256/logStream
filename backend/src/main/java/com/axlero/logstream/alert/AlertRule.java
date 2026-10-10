package com.axlero.logstream.alert;

import com.axlero.logstream.model.LogQuery;

/**
 * A saved alert rule: "if the number of logs matching [query] in the last
 * [windowMinutes] minutes is greater than [threshold], fire a webhook".
 *
 * Example: level=ERROR, windowMinutes=5, threshold=100  ->  "more than 100 errors in 5 minutes".
 */
public class AlertRule {

    // ----- configured by the user
    public String id;
    public String name;
    public LogQuery query = new LogQuery();
    public int windowMinutes = 5;
    public int threshold = 100;
    public String webhookUrl = "http://localhost:9000/webhook";
    public boolean enabled = true;

    // ----- runtime status, updated by the engine on every evaluation
    public boolean firing = false;     // true while the condition is breached
    public long lastCount = -1;        // matching logs found in the last evaluation (-1 = never evaluated)
    public long lastEvaluated = 0;     // epoch millis of the last evaluation
    public long lastFired = 0;         // epoch millis of the last time the webhook was triggered
}
