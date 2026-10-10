package com.axlero.logstream.api;

import com.axlero.logstream.alert.AlertEngine;
import com.axlero.logstream.alert.AlertRule;
import com.sun.net.httpserver.HttpExchange;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /alerts/rules
 *   GET             -> list all rules (with their live status)
 *   POST  {json}    -> create a rule
 *   DELETE ?id=...  -> delete a rule
 */
public class AlertRulesHandler extends ApiHandler {

    private final AlertEngine engine;

    public AlertRulesHandler(AlertEngine engine) {
        this.engine = engine;
    }

    @Override
    protected void process(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod().toUpperCase();
        switch (method) {
            case "GET":
                sendJson(ex, 200, engine.getRules());
                break;
            case "POST": {
                AlertRule rule = GSON.fromJson(readBody(ex), AlertRule.class);
                sendJson(ex, 201, engine.addRule(rule));
                break;
            }
            case "DELETE": {
                String id = queryParams(ex).get("id");
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException("Missing 'id' parameter");
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("deleted", engine.deleteRule(id));
                sendJson(ex, 200, result);
                break;
            }
            default:
                throw new IllegalArgumentException("Use GET, POST or DELETE for this endpoint");
        }
    }
}
