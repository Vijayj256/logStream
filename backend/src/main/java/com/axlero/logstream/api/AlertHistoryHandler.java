package com.axlero.logstream.api;

import com.axlero.logstream.alert.AlertEngine;
import com.sun.net.httpserver.HttpExchange;

/** GET /alerts/history -> the most recent alerts that fired (newest first). */
public class AlertHistoryHandler extends ApiHandler {

    private final AlertEngine engine;

    public AlertHistoryHandler(AlertEngine engine) {
        this.engine = engine;
    }

    @Override
    protected void process(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");
        sendJson(ex, 200, engine.getHistory());
    }
}
