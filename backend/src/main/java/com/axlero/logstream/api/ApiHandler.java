package com.axlero.logstream.api;

import com.axlero.logstream.model.LogQuery;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Base class for every REST endpoint. It takes care of the boring parts once:
 * CORS headers (so the React dev server on another port may call us), answering the browser's
 * OPTIONS "preflight" request, turning exceptions into JSON error responses, and always closing
 * the exchange so a failing request can never hang.
 */
public abstract class ApiHandler implements HttpHandler {

    protected static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Implemented by each endpoint. Throw IllegalArgumentException for a 400 Bad Request. */
    protected abstract void process(HttpExchange ex) throws Exception;

    @Override
    public final void handle(HttpExchange ex) throws IOException {
        try {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            process(ex);
        } catch (IllegalArgumentException | JsonParseException e) {
            trySendError(ex, 400, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            trySendError(ex, 500, "Internal error: " + e);
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------ helpers for subclasses

    protected static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    protected static void requireMethod(HttpExchange ex, String method) {
        if (!method.equalsIgnoreCase(ex.getRequestMethod())) {
            throw new IllegalArgumentException("Use " + method + " for this endpoint");
        }
    }

    protected static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    protected static Map<String, String> queryParams(HttpExchange ex) {
        Map<String, String> map = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return map;
        }
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(key, value);
        }
        return map;
    }

    protected static int intParam(Map<String, String> params, String name, int fallback) {
        String v = params.get(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter '" + name + "' must be a whole number");
        }
    }

    /** Reads q / service / level / minRt from the URL into a LogQuery. */
    protected static LogQuery logQueryFrom(Map<String, String> p) {
        return new LogQuery(
                p.getOrDefault("q", ""),
                p.getOrDefault("service", ""),
                p.getOrDefault("level", ""),
                intParam(p, "minRt", 0));
    }

    // ------------------------------------------------------------------ error body

    private static class ErrorBody {
        final String error;

        ErrorBody(String error) {
            this.error = error == null ? "Unknown error" : error;
        }
    }

    private static void trySendError(HttpExchange ex, int status, String message) {
        try {
            sendJson(ex, status, new ErrorBody(message));
        } catch (Exception ignored) {
            // the response may already be partly sent; nothing more we can do
        }
    }
}
