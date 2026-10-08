package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.model.connector.ConnectorOperation;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import java.util.Optional;
import java.util.UUID;

/**
 * The host's access to configured connectors, for the CONNECTOR_CALL step
 * (1.1.0). Implemented in conversation-service over journey-service's internal
 * resolve endpoint; the engine never stores what it gets back.
 *
 * <p>
 * {@link #resolve} is the only path on which a connector's secrets reach the
 * engine. The implementation re-checks that the connector belongs to the
 * run's account and is usable from the run's assistant (journey-service answers
 * 403 otherwise), may cache the result briefly in memory keyed by id and
 * {@code lockVersion} (60 s; never serialised), and drops that entry on
 * {@link #invalidate}, which the step calls when the connector's circuit
 * breaker opens.
 */
public interface ConnectorPort {

    /**
     * The connector, its descriptor (definition) and its opened secrets, for one call.
     *
     * @param connectorId the connector the step names
     * @param accountId     the run's account
     * @param assistantId   the run's assistant (workspace); null for a run without one
     * @throws ConnectorNotResolvableException when the connector does not exist,
     *                                           is disabled, belongs to another
     *                                           account or workspace, or
     *                                           journey-service cannot be asked
     */
    ResolvedConnector resolve(UUID connectorId, String accountId, UUID assistantId);

    /**
     * One operation of a connector's definition, for the builder's variable
     * picker ({@code describeOutputs}). No secrets are involved; the
     * implementation calls journey-service's operations endpoint. Empty when the
     * connector or the operation is unknown, or cannot be asked right now.
     */
    Optional<ConnectorOperation> describe(UUID connectorId, String operationKey);

    /** Forgets any cached resolution of this connector. Default: nothing cached. */
    default void invalidate(UUID connectorId) {
    }
}
