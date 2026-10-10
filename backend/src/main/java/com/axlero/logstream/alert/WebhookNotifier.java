package com.axlero.logstream.alert;

/**
 * Simulated webhook delivery (the project requirement says "a simulated webhook").
 * It prints what would be POSTed. To make it real, replace the body of send() with an
 * HTTP POST, e.g. using java.net.http.HttpClient.
 */
public final class WebhookNotifier {

    private WebhookNotifier() {
    }

    public static void send(String url, String jsonPayload) {
        System.out.println("[WEBHOOK SIMULATED] POST " + url + "  body=" + jsonPayload);
    }
}
