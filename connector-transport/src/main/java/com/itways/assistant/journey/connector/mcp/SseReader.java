package com.itways.assistant.journey.connector.mcp;

import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.assistant.journey.connector.http.Deadline;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/**
 * Reads a {@code text/event-stream} answer event by event, within caps, until
 * the event the caller is waiting for arrives.
 *
 * <p>
 * The subset of the SSE grammar MCP uses: {@code data:} lines (joined with a
 * newline when an event has several), a blank line that ends the event,
 * comment lines ({@code :} keep-alives) and the {@code event:}, {@code id:}
 * and {@code retry:} fields, which are read and ignored — the current
 * revision (2026-07-28) has no event ids or resumption, and an older server's
 * ids are of no use to a client that never resumes. Bytes are read in a
 * bounded loop, never buffered whole first: a stream that never ends costs this
 * process at most {@code maxBytes}, {@code maxEvents} events or the call's
 * deadline, whichever comes first.
 */
final class SseReader {

    private final long maxBytes;
    private final int maxEvents;
    private final Deadline deadline;
    private final String label;

    SseReader(long maxBytes, int maxEvents, Deadline deadline, String label) {
        this.maxBytes = maxBytes;
        this.maxEvents = maxEvents;
        this.deadline = deadline;
        this.label = label;
    }

    /**
     * Reads events until {@code wanted} accepts one's data, and returns that
     * data; null when the stream ended first.
     *
     * @throws ConnectorException {@code RESPONSE_INVALID} past a cap, {@code TIMEOUT} past the deadline
     */
    String readUntil(InputStream in, Predicate<String> wanted) throws IOException {
        long bytes = 0;
        int events = 0;
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b == -1) {
                // An unterminated last event still counts.
                if (hasData && wanted.test(data.toString())) {
                    return data.toString();
                }
                return null;
            }
            if (++bytes > maxBytes) {
                throw new ConnectorException(ConnectorErrorCodes.RESPONSE_INVALID, false, null,
                        label + " answered with an event stream larger than " + maxBytes + " bytes");
            }
            if (b != '\n') {
                if (b != '\r') {
                    line.write(b);
                }
                continue;
            }
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            if (text.isEmpty()) {
                // End of one event.
                if (hasData) {
                    events++;
                    String event = data.toString();
                    data.setLength(0);
                    hasData = false;
                    if (wanted.test(event)) {
                        return event;
                    }
                    if (events >= maxEvents) {
                        throw new ConnectorException(ConnectorErrorCodes.RESPONSE_INVALID, false, null,
                                label + " streamed more than " + maxEvents + " events without answering");
                    }
                    if (deadline.remaining().isZero()) {
                        throw new ConnectorException(ConnectorErrorCodes.TIMEOUT, true, null,
                                label + ": no answer within the call's budget of " + deadline.budget().toMillis()
                                        + " ms");
                    }
                }
                continue;
            }
            if (text.charAt(0) == ':') {
                continue; // a comment / keep-alive
            }
            int colon = text.indexOf(':');
            String field = colon < 0 ? text : text.substring(0, colon);
            String value = colon < 0 ? "" : text.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if ("data".equals(field)) {
                if (hasData) {
                    data.append('\n');
                }
                data.append(value);
                hasData = true;
            }
            // event, id, retry and unknown fields: read and ignored.
        }
    }
}
