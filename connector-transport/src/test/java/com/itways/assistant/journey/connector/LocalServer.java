package com.itways.assistant.journey.connector;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * The external system of the tests: a plain HTTP server on the loopback
 * address, port chosen by the OS, that records every request exactly as it
 * arrived (raw path, raw query, headers, body) and answers whatever the test's
 * responder says. Reachable only because the policy under test allow-lists
 * {@code 127.0.0.1}.
 */
public final class LocalServer implements AutoCloseable {

    /** One request as the server saw it; header names lower-cased, first value each. */
    public record Received(String method, String rawPath, String rawQuery, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * What to answer; {@code delayMs} holds the answer back (for read-timeout
     * tests); {@code holdOpenMs} sends the body chunked and keeps the connection
     * open that long before closing it (a stream the server does not end).
     */
    public record Reply(int status, Map<String, String> headers, String body, long delayMs, long holdOpenMs) {

        public Reply(int status, Map<String, String> headers, String body, long delayMs) {
            this(status, headers, body, delayMs, 0);
        }

        public static Reply json(int status, String body) {
            return new Reply(status, Map.of("Content-Type", "application/json"), body, 0);
        }

        public static Reply status(int status) {
            return new Reply(status, Map.of(), "", 0);
        }

        public static Reply text(int status, String contentType, String body) {
            return new Reply(status, Map.of("Content-Type", contentType), body, 0);
        }

        public Reply withHeader(String name, String value) {
            Map<String, String> all = new LinkedHashMap<>(headers);
            all.put(name, value);
            return new Reply(status, all, body, delayMs, holdOpenMs);
        }

        public Reply delayed(long millis) {
            return new Reply(status, headers, body, millis, holdOpenMs);
        }

        /** The body sent at once, then the connection left open for {@code millis} before it is closed. */
        public Reply heldOpen(long millis) {
            return new Reply(status, headers, body, delayMs, millis);
        }
    }

    private final HttpServer server;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<Received, Reply> responder = request -> Reply.json(200, "{\"status\":\"ok\"}");

    public LocalServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.putIfAbsent(name.toLowerCase(Locale.ROOT),
                    values.isEmpty() ? "" : values.get(0)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), headers, body);
            received.add(request);
            Reply reply = responder.apply(request);
            if (reply.delayMs() > 0) {
                try {
                    Thread.sleep(reply.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            if (reply.holdOpenMs() > 0) {
                // Chunked, flushed, and the end never announced until the hold is over.
                exchange.sendResponseHeaders(reply.status(), 0);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().flush();
                try {
                    Thread.sleep(reply.holdOpenMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        // Concurrent handlers: a retried request must be answered while the slow first one still sleeps.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** {@code http://127.0.0.1:<port>}, no trailing slash. */
    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    public void respond(Function<Received, Reply> responder) {
        this.responder = responder;
    }

    public List<Received> received() {
        return new ArrayList<>(received);
    }

    public Received only() {
        List<Received> all = received();
        if (all.size() != 1) {
            throw new AssertionError("expected exactly one request, got " + all.size() + ": " + all);
        }
        return all.get(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
