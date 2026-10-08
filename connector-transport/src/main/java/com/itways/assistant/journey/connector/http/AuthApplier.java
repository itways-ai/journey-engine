package com.itways.assistant.journey.connector.http;

import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.assistant.journey.model.connector.AuthScheme;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Puts the connector's credential on a request as its descriptor's auth
 * scheme says: an API key header or query parameter, Basic, Bearer, or the
 * OAuth2 access token. Applied last, so no default header or header input can
 * override what the scheme sets. Values are read from the resolved
 * connector's opened secrets (or its config for a user name); a field without
 * a value fails the call naming the <em>field</em>, never a value. Shared by
 * the transports, each of which checks its own set of schemes first.
 */
public final class AuthApplier {

    private final OAuth2ClientCredentials oauth2;

    public AuthApplier(OAuth2ClientCredentials oauth2) {
        this.oauth2 = oauth2;
    }

    /**
     * Adds the credential to {@code headers} (or {@code query} for an API key
     * declared {@code in: query}).
     *
     * @throws ConnectorException {@code AUTH_FAILED} for a missing value, {@code CONFIG_INVALID} for
     *                              a scheme this transport does not apply
     */
    public void apply(ResolvedConnector connector, AuthScheme auth, Map<String, String> headers,
            Map<String, String> query, Deadline deadline) {
        switch (auth.schemeOrNone()) {
            case AuthScheme.SCHEME_NONE -> {
                // nothing to add
            }
            case AuthScheme.SCHEME_API_KEY -> {
                String key = required(connector, auth.secretField(), "secretField");
                String name = auth.name() == null || auth.name().isBlank() ? "X-API-Key" : auth.name().trim();
                if (auth.isApiKeyInQuery()) {
                    query.put(name, key);
                } else {
                    headers.put(name, key);
                }
            }
            case AuthScheme.SCHEME_BASIC -> {
                String user = required(connector, auth.usernameField(), "usernameField");
                String password = required(connector, auth.passwordField(), "passwordField");
                String pair = Base64.getEncoder()
                        .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + pair);
            }
            case AuthScheme.SCHEME_BEARER -> headers.put("Authorization",
                    "Bearer " + required(connector, auth.secretField(), "secretField"));
            case AuthScheme.SCHEME_OAUTH2_CLIENT_CREDENTIALS -> headers.put("Authorization",
                    "Bearer " + oauth2.token(connector, auth, deadline));
            default -> throw ConnectorException
                    .configInvalid("auth.scheme: '" + auth.schemeOrNone() + "' is not a scheme this transport applies");
        }
    }

    /** Whether a 401 to this scheme may be answered by fetching a fresh token and sending once more. */
    public static boolean refreshable(AuthScheme auth) {
        return AuthScheme.SCHEME_OAUTH2_CLIENT_CREDENTIALS.equals(auth.schemeOrNone());
    }

    /** Forgets the OAuth2 token cached for {@code connector}; the next request fetches a fresh one. */
    public void invalidate(ResolvedConnector connector) {
        oauth2.invalidate(connector);
    }

    /** The value of {@code field}, or {@code AUTH_FAILED} naming the field (the value is never in a message). */
    public static String required(ResolvedConnector connector, String field, String what) {
        if (field == null || field.isBlank()) {
            throw new ConnectorException(ConnectorErrorCodes.AUTH_FAILED, false, null,
                    "auth." + what + " is not declared by the connector type");
        }
        String value = connector.fieldValue(field);
        if (value == null || value.isBlank()) {
            throw new ConnectorException(ConnectorErrorCodes.AUTH_FAILED, false, null,
                    "auth: field '" + field + "' has no value in this connector");
        }
        return value;
    }
}
