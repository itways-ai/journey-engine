package com.itways.assistant.journey.model.connector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * How a connector type authenticates, as its descriptor declares it. The
 * transport applies the scheme from the connector's opened secrets; the
 * journey never sees a credential as a variable.
 *
 * <p>
 * The {@code *Field} values name configuration fields of the descriptor. A
 * field of type {@code secret} is read from the resolved connector's secrets;
 * any other field (a user name, a tenant id) from its config.
 *
 * @param scheme            one of the {@code SCHEME_*} constants
 * @param in                {@code apiKey}: {@code header} (default) or {@code query}
 * @param name              {@code apiKey}: the header or query parameter name
 * @param secretField       {@code apiKey} / {@code bearer}: the field holding the key or token
 * @param usernameField     {@code basic}: the field holding the user name
 * @param passwordField     {@code basic}: the field holding the password
 * @param tokenUrl          {@code oauth2-client-credentials}: the token endpoint; may carry
 *                          {@code {field}} placeholders filled from config (a tenant id)
 * @param clientIdField     {@code oauth2-client-credentials}: the field holding the client id
 * @param clientSecretField {@code oauth2-client-credentials}: the field holding the client secret
 * @param scopeField        {@code oauth2-client-credentials}: the field holding the scope, optional
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthScheme(
        String scheme,
        String in,
        String name,
        String secretField,
        String usernameField,
        String passwordField,
        String tokenUrl,
        String clientIdField,
        String clientSecretField,
        String scopeField) {

    public static final String SCHEME_NONE = "none";
    public static final String SCHEME_API_KEY = "apiKey";
    public static final String SCHEME_BASIC = "basic";
    public static final String SCHEME_BEARER = "bearer";
    public static final String SCHEME_OAUTH2_CLIENT_CREDENTIALS = "oauth2-client-credentials";

    public static final String IN_HEADER = "header";
    public static final String IN_QUERY = "query";

    /** No authentication. */
    public static final AuthScheme NONE = new AuthScheme(SCHEME_NONE, null, null, null, null, null, null, null, null,
            null);

    /** The scheme, {@code none} when the descriptor declares nothing. */
    public String schemeOrNone() {
        return scheme == null || scheme.isBlank() ? SCHEME_NONE : scheme;
    }

    /** Whether an {@code apiKey} goes in the query string (never the default). */
    public boolean isApiKeyInQuery() {
        return SCHEME_API_KEY.equals(schemeOrNone()) && IN_QUERY.equalsIgnoreCase(in);
    }
}
