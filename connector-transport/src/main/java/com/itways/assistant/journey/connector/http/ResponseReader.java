package com.itways.assistant.journey.connector.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;

/**
 * Reads an answer off the wire within the response cap, and turns a 2xx body
 * into the value a {@code CallResult} carries. Shared by the transports; not
 * part of the SPI.
 *
 * <p>
 * The body is read through a bounded loop, never buffered whole first: a
 * system that streams gigabytes costs this process at most
 * {@code maxBytes + 1}. Headers come back lower-cased, first value each, minus
 * the ones that carry credentials or would let one answer steer the next
 * request ({@code set-cookie}, {@code authorization}, {@code www-authenticate},
 * {@code proxy-authenticate}).
 */
public final class ResponseReader {

    /** Headers never handed to the caller: credentials, challenges and cookies. */
    private static final Set<String> HIDDEN_HEADERS = Set.of("set-cookie", "set-cookie2", "authorization",
            "proxy-authorization", "www-authenticate", "proxy-authenticate");

    /** What came back, before any interpretation. */
    public record Raw(int status, Map<String, String> headers, byte[] body, boolean truncated, ContentType contentType) {

        /** The body as text in the answer's charset (UTF-8 when unsaid); empty for no body. */
        public String text() {
            Charset charset = contentType != null && contentType.getCharset() != null ? contentType.getCharset()
                    : StandardCharsets.UTF_8;
            return new String(body, charset);
        }

        public boolean isJson() {
            if (contentType == null) {
                return false;
            }
            String mime = contentType.getMimeType() == null ? "" : contentType.getMimeType().toLowerCase(Locale.ROOT);
            return mime.equals("application/json") || mime.endsWith("+json") || mime.equals("text/json");
        }

        public String header(String name) {
            return name == null ? null : headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final ObjectMapper json;
    private final long maxBytes;

    public ResponseReader(ObjectMapper json, long maxBytes) {
        this.json = json;
        this.maxBytes = maxBytes;
    }

    /** Drains {@code response} within the cap; {@code truncated} says the cap was hit. */
    public Raw read(ClassicHttpResponse response) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : response.getHeaders()) {
            String name = header.getName().toLowerCase(Locale.ROOT);
            if (!HIDDEN_HEADERS.contains(name)) {
                headers.putIfAbsent(name, header.getValue());
            }
        }
        HttpEntity entity = response.getEntity();
        byte[] body = new byte[0];
        boolean truncated = false;
        ContentType contentType = null;
        if (entity != null) {
            contentType = ContentType.parseLenient(entity.getContentType());
            try (InputStream in = entity.getContent()) {
                if (in != null) {
                    byte[] buffer = new byte[8192];
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        long room = maxBytes - out.size();
                        if (read > room) {
                            out.write(buffer, 0, (int) Math.max(0, room));
                            truncated = true;
                            break;
                        }
                        out.write(buffer, 0, read);
                    }
                    body = out.toByteArray();
                }
            }
        }
        return new Raw(response.getCode(), Map.copyOf(headers), body, truncated, contentType);
    }

    /**
     * The value of a 2xx answer: null for no body; the parsed JSON otherwise;
     * the text itself when the system did not declare JSON and the body is
     * not JSON either (a plain-text health endpoint). A body declared JSON
     * that does not parse, or one beyond the cap, is
     * {@link ConnectorErrorCodes#RESPONSE_INVALID}.
     */
    public Object body(Raw raw, String operationLabel) {
        if (raw.truncated()) {
            throw new ConnectorException(ConnectorErrorCodes.RESPONSE_INVALID, false, raw.status(),
                    operationLabel + " answered with a body larger than " + maxBytes + " bytes");
        }
        if (raw.body().length == 0) {
            return null;
        }
        String text = raw.text();
        if (text.isBlank()) {
            return null;
        }
        try {
            return json.readValue(text, Object.class);
        } catch (IOException e) {
            if (raw.isJson()) {
                throw new ConnectorException(ConnectorErrorCodes.RESPONSE_INVALID, false, raw.status(),
                        operationLabel + " answered with a body that is not JSON", e);
            }
            return text;
        }
    }
}
