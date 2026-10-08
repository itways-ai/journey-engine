package com.itways.assistant.journey.connector;

import com.itways.assistant.journey.model.connector.ConnectorOperation;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Turns an operation plus its inputs into a call on the wire, for one kind of
 * system. v1 ships {@code rest.RestTransport}; IBM MQ, SOAP, JDBC and others
 * arrive as further implementations behind the same descriptor and step.
 *
 * <p>
 * A transport receives the connector's secrets inside the
 * {@link ResolvedConnector} for the duration of the call and applies the
 * auth scheme itself. It never stores them, never puts them in a URL or a log,
 * and scrubs them out of every message it raises.
 */
public interface ConnectorTransport {

    /** The descriptor's {@code transport} value this implementation serves: {@code REST}. */
    String kind();

    /** The {@code auth.scheme} values this transport can apply. */
    Set<String> supportedAuthSchemes();

    /**
     * Calls {@code operation} of {@code connector} with the given inputs.
     *
     * @param connector the resolved connector, secrets opened
     * @param operation   an operation of its descriptor
     * @param inputs      input name → value, already rendered (no placeholders left); validated
     *                    against the operation's input schema here
     * @param options     budget, read timeout, attempts and idempotency key
     * @return the 2xx answer
     * @throws ConnectorException for everything else: refused egress, invalid
     *                              inputs, no answer, a non-2xx status
     */
    CallResult call(ResolvedConnector connector, ConnectorOperation operation, Map<String, Object> inputs,
            CallOptions options);

    /**
     * The connection test the portal's Test button runs: the descriptor's test
     * operation (or its first idempotent GET) once, with no inputs beyond the
     * schema's defaults, within {@code budget}.
     *
     * @throws ConnectorException when the system does not answer 2xx, or the
     *                              type declares no testable operation
     */
    CallResult test(ResolvedConnector connector, Duration budget);

    /**
     * The values a caller must scrub from anything it stores or shows about
     * {@code connector}: its secrets, plus whatever credential the transport
     * acquired on its behalf (an OAuth2 access token). The transport scrubs
     * its own messages with these; journey-service's Try uses them on an
     * answer it shows whole.
     */
    default Collection<String> scrubValues(ResolvedConnector connector) {
        return connector.secretsOrEmpty().values();
    }
}
