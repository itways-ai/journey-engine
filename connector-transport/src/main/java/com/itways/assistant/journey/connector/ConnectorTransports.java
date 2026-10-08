package com.itways.assistant.journey.connector;

import com.itways.assistant.journey.model.connector.ConnectorDescriptor;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The transports a host wired, by the descriptor {@code transport} value each
 * serves (1.2.0): {@code REST} → {@code RestTransport}, {@code MCP} →
 * {@code McpTransport}. The engine's CONNECTOR_CALL step and
 * journey-service's Test and Try endpoints pick the transport for a
 * connector here, from its pinned descriptor, so neither has to know the
 * kinds. A descriptor whose transport no one serves is a configuration problem
 * ({@link ConnectorErrorCodes#CONFIG_INVALID}), never a guess.
 *
 * <p>
 * Plain class, no Spring: the engine's configuration builds one from its
 * transport beans; a host with a single transport wraps it with
 * {@link #of(ConnectorTransport...)}. Kinds are matched without regard to
 * case. {@link #close()} closes every transport that can be closed.
 */
public final class ConnectorTransports implements Closeable {

    private final Map<String, ConnectorTransport> byKind;

    private ConnectorTransports(Map<String, ConnectorTransport> byKind) {
        this.byKind = Map.copyOf(byKind);
    }

    /**
     * @throws IllegalArgumentException for two transports of one kind, or one without a kind
     */
    public static ConnectorTransports of(Collection<? extends ConnectorTransport> transports) {
        Objects.requireNonNull(transports, "transports");
        Map<String, ConnectorTransport> byKind = new LinkedHashMap<>();
        for (ConnectorTransport transport : transports) {
            String kind = normalize(Objects.requireNonNull(transport, "transport").kind());
            if (kind == null) {
                throw new IllegalArgumentException(transport.getClass().getName() + " declares no transport kind");
            }
            ConnectorTransport previous = byKind.putIfAbsent(kind, transport);
            if (previous != null) {
                throw new IllegalArgumentException("two transports serve kind " + kind + ": "
                        + previous.getClass().getName() + " and " + transport.getClass().getName());
            }
        }
        return new ConnectorTransports(byKind);
    }

    public static ConnectorTransports of(ConnectorTransport... transports) {
        return of(java.util.List.of(transports));
    }

    /** The kinds served, as the transports declare them (upper case). */
    public Set<String> kinds() {
        return byKind.keySet();
    }

    /** The transport for a descriptor {@code transport} value; empty when none serves it. */
    public Optional<ConnectorTransport> find(String kind) {
        String key = normalize(kind);
        return key == null ? Optional.empty() : Optional.ofNullable(byKind.get(key));
    }

    /**
     * The transport {@code descriptor} needs.
     *
     * @throws ConnectorException {@code CONFIG_INVALID} when the descriptor is missing or names a
     *                              transport no one serves
     */
    public ConnectorTransport require(ConnectorDescriptor descriptor) {
        if (descriptor == null) {
            throw ConnectorException.configInvalid("connector carries no descriptor");
        }
        String kind = descriptor.transport();
        return find(kind).orElseThrow(() -> ConnectorException.configInvalid("transport '"
                + (kind == null ? "" : kind) + "' is not served by this host (available: " + kinds() + ")"));
    }

    /** The transport for {@code connector}'s pinned descriptor ({@link #require(ConnectorDescriptor)}). */
    public ConnectorTransport require(ResolvedConnector connector) {
        Objects.requireNonNull(connector, "connector");
        return require(connector.descriptor());
    }

    /** Closes every transport that is {@link Closeable}; the first failure is kept and thrown last. */
    @Override
    public void close() throws IOException {
        IOException first = null;
        for (ConnectorTransport transport : byKind.values()) {
            if (transport instanceof Closeable closeable) {
                try {
                    closeable.close();
                } catch (IOException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static String normalize(String kind) {
        return kind == null || kind.isBlank() ? null : kind.trim().toUpperCase(Locale.ROOT);
    }
}
