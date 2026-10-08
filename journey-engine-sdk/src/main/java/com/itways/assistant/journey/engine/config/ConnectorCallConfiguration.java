package com.itways.assistant.journey.engine.config;

import com.itways.assistant.journey.connector.EgressPolicy;
import com.itways.assistant.journey.connector.ConnectorTransport;
import com.itways.assistant.journey.connector.ConnectorTransports;
import com.itways.assistant.journey.connector.mcp.McpTransport;
import com.itways.assistant.journey.connector.rest.RestTransport;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The CONNECTOR_CALL step's transports (1.1.0; MCP since 1.2.0):
 * connector-transport's {@link RestTransport} and {@link McpTransport} under
 * the operator's {@link EgressPolicy}, gathered in an
 * {@link ConnectorTransports} registry the step picks from by the
 * descriptor's {@code transport}.
 *
 * <p>
 * The per-connector rules (which hosts one connector may dial, its base
 * URL) come with each resolved connector; these settings are the
 * deployment-wide bounds around them, one set per deployment, never per
 * tenant, and the same for both transports:
 *
 * <ul>
 * <li>{@code itways.connectors.egress.private-hosts} (default empty): hosts
 * that may resolve to a private, loopback or link-local address and still be
 * dialled — exact names or IP literals, comma-separated, {@code .test} for a
 * domain and its subdomains. Development and tests only (the local mock
 * systems); empty in production.</li>
 * <li>{@code itways.connectors.egress.allowed-hosts} (default empty): the
 * platform-wide bound. Empty means any public host; when set, a connector's
 * host must also be on it — an operator's kill switch that needs no tenant to
 * edit anything.</li>
 * <li>{@code itways.connectors.egress.allow-http} (default false): whether
 * plain {@code http://} base URLs may be dialled. True only where the mock
 * systems have no certificate.</li>
 * <li>{@code itways.connectors.max-response-bytes} (default 1048576): the
 * most a response body (or an MCP event stream) may hold; a larger answer
 * fails the step with {@code CONNECTOR_RESPONSE_INVALID}.</li>
 * </ul>
 *
 * <p>
 * The REST bean keeps its 1.1.0 name and stays {@code @Primary}, so a host
 * that injects a single {@code ConnectorTransport} (or wraps every one of
 * them, as conversation-service does for metrics) is not affected by the
 * second bean. These are separate from API_CALL's {@code journey.api-call.*}
 * settings (see {@code EgressGuard}): the frozen step keeps its own rules.
 */
@Configuration
public class ConnectorCallConfiguration {

    @Bean
    public EgressPolicy connectorEgressPolicy(
            @Value("${itways.connectors.egress.private-hosts:}") String privateHosts,
            @Value("${itways.connectors.egress.allowed-hosts:}") String allowedHosts,
            @Value("${itways.connectors.egress.allow-http:false}") boolean allowHttp,
            @Value("${itways.connectors.max-response-bytes:1048576}") long maxResponseBytes) {
        return EgressPolicy.of(privateHosts, allowedHosts, allowHttp).withMaxResponseBytes(maxResponseBytes);
    }

    /** REST. Closed with the context: it holds a pooled HTTP client. */
    @Bean(name = "connectorTransport", destroyMethod = "close")
    @Primary
    public ConnectorTransport connectorTransport(EgressPolicy connectorEgressPolicy) {
        return new RestTransport(connectorEgressPolicy);
    }

    /** MCP (1.2.0). Closed with the context: it holds a pooled HTTP client of its own. */
    @Bean(name = "mcpConnectorTransport", destroyMethod = "close")
    public ConnectorTransport mcpConnectorTransport(EgressPolicy connectorEgressPolicy) {
        return new McpTransport(connectorEgressPolicy);
    }

    /**
     * Every transport bean, by kind. Not closed here: each transport bean
     * closes itself. A host that adds a transport of a new kind declares a
     * bean for it and nothing else.
     */
    @Bean(destroyMethod = "")
    public ConnectorTransports connectorTransports(List<ConnectorTransport> transports) {
        return ConnectorTransports.of(transports);
    }
}
