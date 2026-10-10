package com.axlero.logstream.api;

import com.axlero.logstream.alert.AlertEngine;
import com.sun.net.httpserver.HttpExchange;

/** POST /alerts/evaluate -> evaluates all rules immediately (handy for demos) and returns their status. */
public class AlertEvaluateHandler extends ApiHandler {

    private final AlertEngine engine;

    public AlertEvaluateHandler(AlertEngine engine) {
        this.engine = engine;
    }

    @Override
    protected void process(HttpExchange ex) throws Exception {
        requireMethod(ex, "POST");
        sendJson(ex, 200, engine.evaluateAll());
    }
}
