package com.axlero.logstream.alert;

/** One entry in the alert history: a rule that fired, and the webhook payload that was "sent". */
public class AlertEvent {
    public final String id;
    public final String ruleId;
    public final String ruleName;
    public final long firedAt;
    public final long count;
    public final int threshold;
    public final int windowMinutes;
    public final String webhookUrl;
    public final String payload;

    public AlertEvent(String id, String ruleId, String ruleName, long firedAt, long count,
                      int threshold, int windowMinutes, String webhookUrl, String payload) {
        this.id = id;
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.firedAt = firedAt;
        this.count = count;
        this.threshold = threshold;
        this.windowMinutes = windowMinutes;
        this.webhookUrl = webhookUrl;
        this.payload = payload;
    }
}
