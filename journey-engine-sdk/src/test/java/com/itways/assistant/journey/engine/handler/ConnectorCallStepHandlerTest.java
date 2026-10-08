package com.itways.assistant.journey.engine.handler;

import static com.itways.assistant.journey.engine.handler.HandlerFixtures.MESSAGES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.SCHEMAS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.UTILS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.VARIABLES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.json;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.output;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.run;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.stepField;
import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.journey.connector.CallOptions;
import com.itways.assistant.journey.connector.CallResult;
import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.assistant.journey.connector.ConnectorTransport;
import com.itways.assistant.journey.connector.ConnectorTransports;
import com.itways.assistant.journey.engine.context.Simulation;
import com.itways.assistant.journey.engine.language.ConversationLanguage;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.ConnectorNotResolvableException;
import com.itways.assistant.journey.engine.service.ConnectorPort;
import com.itways.assistant.journey.engine.util.ConnectorCircuitBreakers;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;
import com.itways.assistant.journey.model.catalog.OutputField;
import com.itways.assistant.journey.model.catalog.StepDefinition;
import com.itways.assistant.journey.model.catalog.StepOutputSchema;
import com.itways.assistant.journey.model.connector.AuthScheme;
import com.itways.assistant.journey.model.connector.ConnectorDefaults;
import com.itways.assistant.journey.model.connector.ConnectorDescriptor;
import com.itways.assistant.journey.model.connector.ConnectorOperation;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import com.itways.assistant.journey.model.connector.RiskLevel;
import com.itways.assistant.journey.model.connector.SchemaNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CONNECTOR_CALL against a fake transport and a fake port: the Mock Bank
 * descriptor of the design, built as records. Nothing goes on the wire; what
 * is checked is what the step hands the transport, what it stores and what it
 * reports.
 */
class ConnectorCallStepHandlerTest {

    static final UUID BANK = UUID.fromString("6f1c2a52-8d0e-4b61-9a43-2f0d1c7e5b10");
    static final String SECRET = "sk-live-SECRET-9f8e7d";
    static final String API_KEY_HEADER = "X-API-Key";

    private FakeTransport transport;
    private FakePort port;
    private ConnectorCircuitBreakers breakers;
    private ConnectorCallStepHandler handler;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        port = new FakePort(bank(null));
        breakers = new ConnectorCircuitBreakers();
        handler = handler(port);
    }

    private ConnectorCallStepHandler handler(ConnectorPort connectorPort) {
        return new ConnectorCallStepHandler(UTILS, VARIABLES, SCHEMAS, MESSAGES, transport, breakers,
                connectorPort);
    }

    // ---------------------------------------------------------------- success

    @Test
    void successStoresMaskedAndLimitedOutput() {
        transport.answer(200, Map.of("balance", 1520.75, "currency", "JOD", "iban", "JO94CBJO0010000000000131000302",
                "internalRef", "x-77"), 2, 41);
        ExecutionContext context = run(Map.of("account", "1234567890"));

        StepResult result = handler.execute(step(balanceConfig("")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getMessage()).isEqualTo("Balance fetched");
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = (Map<String, Object>) output(context, 3);
        assertThat(stored).containsEntry("balance", 1520.75).containsEntry("currency", "JOD")
                .containsEntry("iban", "********").doesNotContainKey("internalRef");
        assertThat(result.getData()).isEqualTo(stored);
        assertThat(stepField(context, 3, "status")).isEqualTo(200);
        assertThat(stepField(context, 3, "latencyMs")).isEqualTo(41L);
        assertThat(stepField(context, 3, "attempts")).isEqualTo(2);
        assertThat(result.getMetadata()).containsEntry(StepResult.META_HTTP_STATUS, 200)
                .containsEntry("latencyMs", 41L)
                .containsEntry("attempts", 2)
                .containsEntry("connectorId", BANK.toString())
                .containsEntry("operationKey", "getAccountBalance");
        assertThat(transport.connector.id()).isEqualTo(BANK);
        assertThat(transport.operation.key()).isEqualTo("getAccountBalance");
    }

    @Test
    void rawResponseKeepsUndeclaredPropertiesButStillMasks() {
        transport.answer(200, Map.of("balance", 10, "iban", "JO94CBJO0010000000000131000302", "internalRef", "x-77"),
                1, 5);
        ExecutionContext context = run(Map.of("account", "1234567890"));

        handler.execute(step(balanceConfig(",\"storeRawResponse\":true")), context);

        @SuppressWarnings("unchecked")
        Map<String, Object> stored = (Map<String, Object>) output(context, 3);
        assertThat(stored).containsEntry("internalRef", "x-77").containsEntry("iban", "********")
                .containsEntry("balance", 10);
    }

    @Test
    void inputsAreRenderedFromVariablesWithTheirTypes() {
        transport.answer(200, Map.of(), 1, 1);
        ExecutionContext context = run(Map.of("account", "1234567890", "amount", 250));
        String config = "{\"connectorId\":\"" + BANK + "\",\"operationKey\":\"getAccountBalance\",\"inputs\":{"
                + "\"accountNo\":\"{{inputs.entities.account}}\",\"amount\":\"{{inputs.entities.amount}}\","
                + "\"note\":\"Ref {{inputs.entities.account}}\",\"nested\":{\"a\":\"{{inputs.entities.amount}}\","
                + "\"list\":[\"{{inputs.entities.amount}}\",\"x\"]},\"fixed\":7,"
                + "\"missing\":\"{{inputs.entities.nope}}\"}}";

        handler.execute(step(config), context);

        assertThat(transport.inputs).containsEntry("accountNo", "1234567890")
                .containsEntry("amount", 250)
                .containsEntry("note", "Ref 1234567890")
                .containsEntry("fixed", 7)
                .containsEntry("nested", Map.of("a", 250, "list", List.of(250, "x")))
                .containsKey("missing");
        assertThat(transport.inputs.get("missing")).isNull();
    }

    // ---------------------------------------------------------------- failures

    @Test
    void invalidInputsFromTheTransportAreSurfaced() {
        transport.fail(ConnectorException.inputInvalid(List.of("accountNo: required")));
        ExecutionContext context = run();

        StepResult result = handler.execute(step(balanceConfig("")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMetadata()).containsEntry(StepResult.META_ERROR_CODE, ConnectorErrorCodes.INPUT_INVALID)
                .containsEntry(StepResult.META_RETRYABLE, false)
                .doesNotContainKey(StepResult.META_HTTP_STATUS);
        assertThat(result.getMessage()).startsWith("CONNECTOR_CALL getAccountBalance failed [CONNECTOR_INPUT_INVALID]")
                .contains("accountNo: required");
        assertThat(result.getUserMessage()).isEqualTo(message("step.connector.inputInvalid"))
                .doesNotContain("accountNo");
        assertThat(stepField(context, 3, "latencyMs")).isNotNull();
        assertThat(stepField(context, 3, "status")).isNull();
    }

    @Test
    void unreadableConfigFailsWithoutResolvingOrCalling() {
        ExecutionContext context = run();

        StepResult result = handler.execute(step("{\"connectorId\":\"" + BANK
                + "\",\"operationKey\":\"ping\",\"url\":\"https://example.com\"}"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).startsWith("CONNECTOR_CALL: ").contains("url");
        assertThat(result.getUserMessage()).isEqualTo(message("step.connector.configInvalid"));
        assertThat(result.getMetadata()).containsEntry(StepResult.META_ERROR_CODE, ConnectorErrorCodes.CONFIG_INVALID)
                .containsEntry(StepResult.META_RETRYABLE, false);
        assertThat(port.resolves).isZero();
        assertThat(transport.calls).isZero();
    }

    @Test
    void anOperationTheDefinitionNoLongerHasIsOperationUnknownAndNothingIsSent() {
        ExecutionContext context = run();

        StepResult result = handler.execute(step("{\"connectorId\":\"" + BANK + "\",\"operationKey\":\"closeAccount\"}"),
                context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage())
                .isEqualTo("CONNECTOR_CALL: operation 'closeAccount' is not defined by connector 'Mock Bank'");
        assertThat(result.getMetadata())
                .containsEntry(StepResult.META_ERROR_CODE, ConnectorErrorCodes.OPERATION_UNKNOWN)
                .containsEntry(StepResult.META_RETRYABLE, false)
                .containsEntry("operationKey", "closeAccount");
        assertThat(result.getUserMessage()).isEqualTo(message("step.connector.operationUnknown"));
        assertThat(port.resolves).isEqualTo(1);
        assertThat(transport.calls).isZero();
        assertThat(breakers.state(BANK)).isEmpty();
    }

    @Test
    void operationUnknownHasItsOwnUserMessageInBothLanguages() {
        assertThat(ConnectorCallStepHandler.userMessageKey(ConnectorErrorCodes.OPERATION_UNKNOWN))
                .isEqualTo("step.connector.operationUnknown");
        assertThat(MESSAGES.get(ConversationLanguage.ARABIC, "step.connector.operationUnknown"))
                .isNotBlank()
                .isNotEqualTo(message("step.connector.operationUnknown"))
                .isNotEqualTo("step.connector.operationUnknown");
    }

    @Test
    void noPortWiredIsNotResolvable() {
        ExecutionContext context = run();

        StepResult result = handler((ConnectorPort) null).execute(step(balanceConfig("")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).contains("no ConnectorPort is wired into this host");
        assertThat(result.getMetadata()).containsEntry(StepResult.META_ERROR_CODE, ConnectorErrorCodes.NOT_RESOLVABLE)
                .containsEntry(StepResult.META_RETRYABLE, false);
        assertThat(result.getUserMessage()).isEqualTo(message("step.connector.notResolvable"));
        assertThat(transport.calls).isZero();
    }

    @Test
    void aConnectorTheRunMayNotUseIsNotResolvable() {
        port.refusal = new ConnectorNotResolvableException("FORBIDDEN", "the connector belongs to another workspace");
        ExecutionContext context = run();

        StepResult result = handler.execute(step(balanceConfig("")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).contains("FORBIDDEN").contains(BANK.toString());
        assertThat(result.getMetadata()).containsEntry(StepResult.META_ERROR_CODE, ConnectorErrorCodes.NOT_RESOLVABLE)
                .containsEntry(StepResult.META_RETRYABLE, false);
        assertThat(transport.calls).isZero();
    }

    @Test
    void aMappedErrorCodeIsReportedAsTheSystemRefusing() {
        transport.fail(new ConnectorException("ACCOUNT_NOT_FOUND", false, 404, "the system answered 404"));
        ExecutionContext context = run(Map.of("account", "1234567890"));

        StepResult result = handler.execute(step(balanceConfig("")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMetadata()).containsEntry(StepResult.META_ERROR_CODE, "ACCOUNT_NOT_FOUND")
                .containsEntry(StepResult.META_RETRYABLE, false)
                .containsEntry(StepResult.META_HTTP_STATUS, 404)
                .containsEntry("connectorId", BANK.toString())
                .containsEntry("operationKey", "getAccountBalance");
        assertThat(result.getUserMessage()).isEqualTo(message("step.connector.rejected"));
        assertThat(stepField(context, 3, "status")).isEqualTo(404);
    }

    @Test
    void aTimeoutIsRetryableAndSaysSo() {
        transport.fail(new ConnectorException(ConnectorErrorCodes.TIMEOUT, true, null, "no answer within 2000 ms"));
        ExecutionContext context = run(Map.of("account", "1234567890"));

        StepResult result = handler.execute(step(balanceConfig("")), context);

        assertThat(result.getMetadata()).containsEntry(StepResult.META_ERROR_CODE, ConnectorErrorCodes.TIMEOUT)
                .containsEntry(StepResult.META_RETRYABLE, true);
        assertThat(result.getUserMessage()).isEqualTo(message("step.connector.timeout"));
    }

    @Test
    void userMessagesFollowTheRunLanguage() {
        transport.fail(new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, true, 503, "the system answered 503"));
        ExecutionContext context = run(Map.of("account", "1234567890"));
        context.setLanguage(ConversationLanguage.ARABIC);

        StepResult result = handler.execute(step(balanceConfig("")), context);

        assertThat(result.getUserMessage())
                .isEqualTo(MESSAGES.get(ConversationLanguage.ARABIC, "step.connector.unavailable"))
                .isNotEqualTo(message("step.connector.unavailable"));
    }

    @Test
    void onErrorContinueMarksTheResult() {
        transport.fail(new ConnectorException(ConnectorErrorCodes.REJECTED, false, 400, "the system answered 400"));

        StepResult cont = handler.execute(step(balanceConfig(",\"onError\":\"CONTINUE\"")), run());
        StepResult fail = handler.execute(step(balanceConfig(",\"onError\":\"FAIL\"")), run());
        StepResult branch = handler.execute(step(balanceConfig(",\"onError\":\"BRANCH\"")), run());

        assertThat(cont.getMetadata()).containsEntry(StepResult.META_CONTINUE_ON_ERROR, true);
        assertThat(fail.getMetadata()).doesNotContainKey(StepResult.META_CONTINUE_ON_ERROR);
        assertThat(branch.getMetadata()).doesNotContainKey(StepResult.META_CONTINUE_ON_ERROR);
    }

    @Test
    void anUnexpectedTransportFailureIsUnavailableAndScrubbed() {
        transport.crash(new IllegalStateException("could not send with key " + SECRET + " to the bank"));
        ExecutionContext context = run(Map.of("account", "1234567890"));

        StepResult result = handler.execute(step(balanceConfig("")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMetadata()).containsEntry(StepResult.META_ERROR_CODE, ConnectorErrorCodes.UNAVAILABLE)
                .containsEntry(StepResult.META_RETRYABLE, true);
        assertThat(result.getMessage()).contains("IllegalStateException").doesNotContain(SECRET);
        assertThat(breakers.of(BANK).getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- idempotency and options

    @Test
    void aWriteGetsTheExecutionAndStepKey() {
        transport.answer(200, Map.of(), 1, 1);

        handler.execute(step(stopCardConfig("")), run());

        assertThat(transport.options.idempotencyKey()).isEqualTo("exec-1:balance");
    }

    @Test
    void aStepWithoutAKeyUsesItsOrder() {
        transport.answer(200, Map.of(), 1, 1);
        JourneyStep step = step(stopCardConfig(""));
        step.setStepKey(null);

        handler.execute(step, run());

        assertThat(transport.options.idempotencyKey()).isEqualTo("exec-1:3");
    }

    @Test
    void aCustomKeyTemplateIsRendered() {
        transport.answer(200, Map.of(), 1, 1);

        handler.execute(step(stopCardConfig(",\"idempotencyKey\":\"stop-{{inputs.entities.card}}\"")),
                run(Map.of("card", "4111")));

        assertThat(transport.options.idempotencyKey()).isEqualTo("stop-4111");
    }

    @Test
    void aReadGetsNoKeyUnlessTheTypeDeclaresAHeader() {
        transport.answer(200, Map.of(), 1, 1);

        handler.execute(step(balanceConfig("")), run(Map.of("account", "1234567890")));
        assertThat(transport.options.idempotencyKey()).isNull();

        port.connector = bank(new ConnectorDescriptor.Idempotency("Idempotency-Key"));
        handler.execute(step(balanceConfig("")), run(Map.of("account", "1234567890")));
        assertThat(transport.options.idempotencyKey()).isEqualTo("exec-1:balance");
    }

    @Test
    void optionsComeFromTheConfigWithinTheirBounds() {
        transport.answer(200, Map.of(), 1, 1);

        handler.execute(step(balanceConfig(",\"timeoutMs\":2000,\"maxAttempts\":2")), run());

        assertThat(transport.options.readTimeout()).isEqualTo(Duration.ofMillis(2000));
        assertThat(transport.options.budget()).isEqualTo(Duration.ofMillis(3000));
        assertThat(transport.options.maxAttempts()).isEqualTo(2);
    }

    @Test
    void optionsFallBackToTheOperationAndTheTypeClamped() {
        transport.answer(200, Map.of(), 1, 1);

        // ping declares 45 s (clamped to 30 s); the type asks for 5 attempts (clamped to 3).
        handler.execute(step("{\"connectorId\":\"" + BANK + "\",\"operationKey\":\"ping\"}"), run());
        assertThat(transport.options.readTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(transport.options.budget()).isEqualTo(Duration.ofSeconds(45));
        assertThat(transport.options.maxAttempts()).isEqualTo(3);

        // getAccountBalance declares nothing: the type's 8 s.
        handler.execute(step(balanceConfig("")), run());
        assertThat(transport.options.readTimeout()).isEqualTo(Duration.ofSeconds(8));
        assertThat(transport.options.budget()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void withNoDefaultsAnywhereTheReadTimeoutIsTenSeconds() {
        transport.answer(200, Map.of(), 1, 1);
        ConnectorDescriptor plain = new ConnectorDescriptor("plain", "Plain", null, "REST", 1, AuthScheme.NONE, null,
                null, null, null, null, List.of(operation("ping", "GET", null, null, null, null, null)));
        port.connector = new ResolvedConnector(BANK, "Plain", "REST", plain, "https://plain.example", null,
                Map.of(), Map.of(), 1L);

        handler.execute(step("{\"connectorId\":\"" + BANK + "\",\"operationKey\":\"ping\"}"), run());

        assertThat(transport.options.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(transport.options.budget()).isEqualTo(Duration.ofSeconds(15));
        assertThat(transport.options.maxAttempts()).isEqualTo(3);
        assertThat(transport.options.idempotencyKey()).isNull();
    }

    // ---------------------------------------------------------------- circuit breaker

    @Test
    void unavailableFailuresOpenTheBreakerAndTheNextCallSendsNothing() throws InterruptedException {
        breakers = new ConnectorCircuitBreakers(testBreaker(Duration.ofMillis(500)));
        handler = handler(port);
        transport.fail(new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, true, 503, "the system answered 503"));

        for (int i = 0; i < 4; i++) {
            handler.execute(step(balanceConfig("")), run());
        }
        assertThat(breakers.state(BANK)).contains(CircuitBreaker.State.OPEN);
        assertThat(port.invalidated).containsExactly(BANK);

        StepResult open = handler.execute(step(balanceConfig("")), run());
        assertThat(transport.calls).isEqualTo(4);
        assertThat(open.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(open.getMetadata()).containsEntry(StepResult.META_ERROR_CODE, ConnectorErrorCodes.CIRCUIT_OPEN)
                .containsEntry(StepResult.META_RETRYABLE, true);
        assertThat(open.getUserMessage()).isEqualTo(message("step.connector.unavailable"));

        Thread.sleep(700);
        transport.answer(200, Map.of("balance", 1), 1, 1);
        StepResult trial = handler.execute(step(balanceConfig("")), run());
        assertThat(trial.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(transport.calls).isEqualTo(5);
        assertThat(breakers.state(BANK)).contains(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void rejectionsDoNotOpenTheBreaker() {
        breakers = new ConnectorCircuitBreakers(testBreaker(Duration.ofSeconds(1)));
        handler = handler(port);
        transport.fail(new ConnectorException(ConnectorErrorCodes.REJECTED, false, 400, "the system answered 400"));

        for (int i = 0; i < 8; i++) {
            handler.execute(step(balanceConfig("")), run());
        }

        assertThat(transport.calls).isEqualTo(8);
        assertThat(breakers.state(BANK)).contains(CircuitBreaker.State.CLOSED);
        assertThat(port.invalidated).isEmpty();
    }

    // ---------------------------------------------------------------- transports by kind (1.2.0)

    @Test
    void theTransportIsPickedByTheDescriptorsKind() {
        FakeTransport mcp = new FakeTransport("MCP");
        mcp.answer(200, Map.of("id", "c-1", "phone", "+962790000000"), 1, 9);
        port.connector = crm(null);
        handler = new ConnectorCallStepHandler(UTILS, VARIABLES, SCHEMAS, MESSAGES,
                ConnectorTransports.of(transport, mcp), breakers, port);
        ExecutionContext context = run(Map.of("email", "lina@example.com"));

        StepResult result = handler.execute(step(findContactConfig("")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(mcp.calls).isEqualTo(1);
        assertThat(transport.calls).isZero();
        assertThat(mcp.operation.toolNameOrKey()).isEqualTo("crm_find_contact");
        assertThat(mcp.inputs).containsEntry("email", "lina@example.com");
        assertThat(mcp.options.idempotencyKey()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = (Map<String, Object>) output(context, 3);
        assertThat(stored).containsEntry("id", "c-1").containsEntry("phone", "********");
    }

    @Test
    void aTransportTheHostDoesNotServeIsAConfigErrorBeforeAnythingIsSent() {
        port.connector = crm(null);
        ExecutionContext context = run(Map.of("email", "lina@example.com"));

        // The handler under test serves REST only.
        StepResult result = handler.execute(step(findContactConfig("")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMetadata()).containsEntry(StepResult.META_ERROR_CODE, ConnectorErrorCodes.CONFIG_INVALID)
                .containsEntry(StepResult.META_RETRYABLE, false);
        assertThat(result.getMessage()).contains("'MCP'").contains("[REST]");
        assertThat(result.getUserMessage()).isEqualTo(message("step.connector.configInvalid"));
        assertThat(transport.calls).isZero();
        assertThat(breakers.state(BANK)).isEmpty();
    }

    @Test
    void anMcpWriteGetsAKeyOnlyWhenTheToolHasAnArgumentForIt() {
        FakeTransport mcp = new FakeTransport("MCP");
        mcp.answer(200, Map.of(), 1, 1);
        port.connector = crm(null);
        handler = new ConnectorCallStepHandler(UTILS, VARIABLES, SCHEMAS, MESSAGES,
                ConnectorTransports.of(mcp), breakers, port);

        handler.execute(step("{\"connectorId\":\"" + BANK + "\",\"operationKey\":\"createTicket\","
                + "\"inputs\":{\"subject\":\"x\"}}"), run());
        assertThat(mcp.options.idempotencyKey()).isEqualTo("exec-1:balance");

        handler.execute(step("{\"connectorId\":\"" + BANK + "\",\"operationKey\":\"sendNote\","
                + "\"inputs\":{\"text\":\"x\"}}"), run());
        assertThat(mcp.options.idempotencyKey()).isNull();
    }

    @Test
    void aToolErrorIsRejectedAndNeverCountsAgainstTheBreaker() {
        FakeTransport mcp = new FakeTransport("MCP");
        mcp.fail(new ConnectorException(ConnectorErrorCodes.REJECTED, false, null,
                "MCP tools/call crm_find_contact reported an error: no such contact"));
        port.connector = crm(null);
        breakers = new ConnectorCircuitBreakers(testBreaker(Duration.ofSeconds(1)));
        handler = new ConnectorCallStepHandler(UTILS, VARIABLES, SCHEMAS, MESSAGES,
                ConnectorTransports.of(mcp), breakers, port);

        for (int i = 0; i < 8; i++) {
            StepResult result = handler.execute(step(findContactConfig("")), run(Map.of("email", "a@b.c")));
            assertThat(result.getMetadata()).containsEntry(StepResult.META_ERROR_CODE, ConnectorErrorCodes.REJECTED)
                    .containsEntry(StepResult.META_RETRYABLE, false)
                    .doesNotContainKey(StepResult.META_HTTP_STATUS);
            assertThat(result.getUserMessage()).isEqualTo(message("step.connector.rejected"));
        }

        assertThat(mcp.calls).isEqualTo(8);
        assertThat(breakers.state(BANK)).contains(CircuitBreaker.State.CLOSED);
        assertThat(breakers.of(BANK).getMetrics().getNumberOfFailedCalls()).isZero();
    }

    // ---------------------------------------------------------------- secrets

    @Test
    void secretsNeverLeakOnSuccessOrFailure() {
        transport.answer(200, Map.of("balance", 3, "iban", "JO94CBJO0010000000000131000302"), 1, 1);
        ExecutionContext ok = run(Map.of("account", "1234567890"));
        StepResult success = handler.execute(step(balanceConfig("")), ok);

        transport.fail(new ConnectorException(ConnectorErrorCodes.AUTH_FAILED, false, 401, "the system answered 401"));
        ExecutionContext failed = run(Map.of("account", "1234567890"));
        StepResult failure = handler.execute(step(balanceConfig("")), failed);

        for (String text : List.of(json(ok.getVariables()), json(failed.getVariables()), json(success),
                json(failure), String.valueOf(success.getMessage()), String.valueOf(failure.getMessage()),
                String.valueOf(failure.getUserMessage()))) {
            assertThat(text).doesNotContain(SECRET).doesNotContain(API_KEY_HEADER);
        }
        assertThat(failure.getUserMessage()).isEqualTo(message("step.connector.authFailed"));
    }

    // ---------------------------------------------------------------- simulation

    @Test
    void aRehearsalResolvesAndCallsNothing() {
        ExecutionContext context = run(Map.of("account", "1234567890"));
        context.setInternal(Simulation.INTERNAL_SIMULATE, true);

        StepResult result = handler.execute(step(balanceConfig("")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(port.resolves).isZero();
        assertThat(transport.calls).isZero();
        @SuppressWarnings("unchecked")
        Map<String, Object> stub = (Map<String, Object>) output(context, 3);
        assertThat(stub).containsKeys("balance", "currency", "iban").containsEntry("simulated", true);
        assertThat(stub.get("balance")).isNull();
        assertThat(stub.get("iban")).isNull();
        assertThat(stepField(context, 3, "status")).isEqualTo(200);
        assertThat(stepField(context, 3, "attempts")).isEqualTo(0);
        assertThat(result.getMetadata()).containsEntry(Simulation.META_SIMULATED, true)
                .containsEntry("connectorId", BANK.toString())
                .containsEntry("operationKey", "getAccountBalance");
    }

    @Test
    void aRehearsalWithoutAPortStoresOnlyTheMarker() {
        ExecutionContext context = run();
        context.setInternal(Simulation.INTERNAL_SIMULATE, true);

        StepResult result = handler((ConnectorPort) null).execute(step(balanceConfig("")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(output(context, 3)).isEqualTo(Map.of("simulated", true));
    }

    // ---------------------------------------------------------------- catalogue

    @Test
    void describesItselfWithAnErrorBranch() {
        StepDefinition definition = handler.describe();

        assertThat(definition.getType()).isEqualTo("CONNECTOR_CALL");
        assertThat(definition.getCategory()).isEqualTo("connector");
        assertThat(definition.getUiComponent()).isEqualTo("step-connector");
        assertThat(definition.isSupportsErrorBranch()).isTrue();
        assertThat(definition.isDeprecated()).isFalse();
    }

    @Test
    void apiCallIsDeprecatedInFavourOfConnectorCall() {
        StepDefinition apiCall = SCHEMAS.apiCallDefinition();

        assertThat(apiCall.isDeprecated()).isTrue();
        assertThat(apiCall.getReplacedBy()).isEqualTo("CONNECTOR_CALL");
        assertThat(apiCall.isSupportsErrorBranch()).isFalse();
    }

    @Test
    void outputsListTheOperationsPropertiesWithSensitiveOnesFlagged() {
        StepOutputSchema schema = handler.describeOutputs(step(balanceConfig("")));

        Map<String, OutputField> byPath = new LinkedHashMap<>();
        schema.getFields().forEach(field -> byPath.put(field.getPath(), field));
        assertThat(schema.getStepType()).isEqualTo("CONNECTOR_CALL");
        assertThat(byPath).containsKeys("output", "status", "latencyMs", "attempts", "error.code", "error.message",
                "error.retryable");
        assertThat(byPath.get("output.balance").getType()).isEqualTo("number");
        assertThat(byPath.get("output.balance").isDynamic()).isTrue();
        assertThat(byPath.get("output.balance").getLabel()).isEqualTo("Available balance");
        assertThat(byPath.get("output.currency").getType()).isEqualTo("string");
        assertThat(byPath.get("output.currency").isSensitive()).isFalse();
        assertThat(byPath.get("output.iban").getType()).isEqualTo("string");
        assertThat(byPath.get("output.iban").isSensitive()).isTrue();
        assertThat(port.resolves).isZero();
    }

    @Test
    void outputsWithoutAStepAreTheBaseFields() {
        StepOutputSchema schema = handler.describeOutputs(null);

        assertThat(schema.getFields()).extracting(OutputField::getPath).containsExactly("output", "status",
                "latencyMs", "attempts", "error.code", "error.message", "error.retryable");
    }

    @Test
    void outputsOfAnUnreadableConfigOrAFailingPortAreTheBaseFields() {
        assertThat(handler.describeOutputs(step("{not json")).getFields()).hasSize(7);

        port.describeFails = true;
        assertThat(handler.describeOutputs(step(balanceConfig(""))).getFields()).hasSize(7);
    }

    // ---------------------------------------------------------------- fixtures

    private String message(String key) {
        return MESSAGES.get(ConversationLanguage.ENGLISH, key);
    }

    private static CircuitBreakerConfig testBreaker(Duration open) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(open)
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
    }

    static JourneyStep step(String apiConfig) {
        return JourneyStep.builder()
                .stepOrder(3)
                .stepKey("balance")
                .stepName("Balance")
                .actionType("CONNECTOR_CALL")
                .apiConfig(apiConfig)
                .message("Balance fetched")
                .build();
    }

    static String balanceConfig(String extra) {
        return "{\"connectorId\":\"" + BANK + "\",\"operationKey\":\"getAccountBalance\","
                + "\"inputs\":{\"accountNo\":\"{{inputs.entities.account}}\"}" + extra + "}";
    }

    static String findContactConfig(String extra) {
        return "{\"connectorId\":\"" + BANK + "\",\"operationKey\":\"findContact\","
                + "\"inputs\":{\"email\":\"{{inputs.entities.email}}\"}" + extra + "}";
    }

    static String stopCardConfig(String extra) {
        return "{\"connectorId\":\"" + BANK + "\",\"operationKey\":\"stopCard\","
                + "\"inputs\":{\"cardId\":\"{{inputs.entities.card}}\"}" + extra + "}";
    }

    static SchemaNode node(String type, String description, String in, String pattern, Boolean sensitive,
            Map<String, SchemaNode> properties, List<String> required) {
        return new SchemaNode(type, null, description, required, properties, null, null, pattern, null, null, null,
                null, in, null, null, null, sensitive);
    }

    static ConnectorOperation operation(String key, String method, RiskLevel risk, Boolean idempotent,
            String idempotencyHeader, SchemaNode input, SchemaNode output) {
        return new ConnectorOperation(key, key, null, method, "/" + key, risk, idempotent, idempotencyHeader, input,
                output, null, null, null);
    }

    /** The Mock Bank of the design; {@code idempotency} is the type-level header, null for none. */
    static ResolvedConnector bank(ConnectorDescriptor.Idempotency idempotency) {
        AuthScheme auth = new AuthScheme(AuthScheme.SCHEME_API_KEY, AuthScheme.IN_HEADER, API_KEY_HEADER, "apiKey",
                null, null, null, null, null, null);
        ConnectorOperation ping = new ConnectorOperation("ping", "Ping", null, "GET", "/health", RiskLevel.LOW, null,
                null, null, null, null, null, 45_000);
        Map<String, SchemaNode> balanceInputs = new LinkedHashMap<>();
        balanceInputs.put("accountNo", node("string", null, SchemaNode.IN_PATH, "^[0-9]{10,16}$", null, null, null));
        balanceInputs.put("asOf", node("string", null, SchemaNode.IN_QUERY, null, null, null, null));
        Map<String, SchemaNode> balanceOutputs = new LinkedHashMap<>();
        balanceOutputs.put("balance", node("number", "Available balance", null, null, null, null, null));
        balanceOutputs.put("currency", node("string", null, null, null, null, null, null));
        balanceOutputs.put("iban", node("string", null, null, null, true, null, null));
        ConnectorOperation balance = new ConnectorOperation("getAccountBalance", "Account balance", null, "GET",
                "/accounts/{accountNo}/balance", RiskLevel.LOW, null, null,
                node("object", null, null, null, null, balanceInputs, List.of("accountNo")),
                node("object", null, null, null, null, balanceOutputs, null),
                Map.of("404", "ACCOUNT_NOT_FOUND"), null, null);
        Map<String, SchemaNode> stopInputs = new LinkedHashMap<>();
        stopInputs.put("cardId", node("string", null, SchemaNode.IN_PATH, null, null, null, null));
        ConnectorOperation stopCard = new ConnectorOperation("stopCard", "Stop card", null, "POST",
                "/cards/{cardId}/stop", RiskLevel.HIGH, false, "Idempotency-Key",
                node("object", null, null, null, null, stopInputs, List.of("cardId")), null, null, null, null);
        ConnectorDescriptor descriptor = new ConnectorDescriptor("mock-bank-rest", "Mock Bank", null, "REST", 2, auth,
                List.of(Map.of("name", "apiKey", "type", "secret")), null,
                new ConnectorDefaults(null, 8_000, new ConnectorDefaults.Retry(5, 200), null), null, idempotency,
                List.of(ping, balance, stopCard));
        return new ResolvedConnector(BANK, "Mock Bank", "REST", descriptor, "https://bank.test/api",
                List.of(), Map.of(), Map.of("apiKey", SECRET), 7L);
    }

    /**
     * The Mock CRM as an MCP type (1.2.0): a read with a sensitive output, a
     * write whose key travels in {@code requestId}, a write with nowhere to put
     * one. {@code protocolVersion} pins the revision, null negotiates.
     */
    static ResolvedConnector crm(String protocolVersion) {
        AuthScheme auth = new AuthScheme(AuthScheme.SCHEME_BEARER, null, null, "token", null, null, null, null, null,
                null);
        Map<String, SchemaNode> findInputs = new LinkedHashMap<>();
        findInputs.put("email", node("string", null, null, null, null, null, null));
        Map<String, SchemaNode> findOutputs = new LinkedHashMap<>();
        findOutputs.put("id", node("string", null, null, null, null, null, null));
        findOutputs.put("phone", node("string", null, null, null, true, null, null));
        ConnectorOperation find = new ConnectorOperation("findContact", "Find contact", null, null, null,
                RiskLevel.LOW, true, null, node("object", null, null, null, null, findInputs, List.of("email")),
                node("object", null, null, null, null, findOutputs, null), null, null, null, "crm_find_contact", null);
        Map<String, SchemaNode> ticketInputs = new LinkedHashMap<>();
        ticketInputs.put("subject", node("string", null, null, null, null, null, null));
        ConnectorOperation createTicket = new ConnectorOperation("createTicket", "Create ticket", null, null, null,
                RiskLevel.HIGH, false, null, node("object", null, null, null, null, ticketInputs, List.of("subject")),
                node("object", null, null, null, null, Map.of(), null), null, null, null, null, "requestId");
        Map<String, SchemaNode> noteInputs = new LinkedHashMap<>();
        noteInputs.put("text", node("string", null, null, null, null, null, null));
        ConnectorOperation sendNote = new ConnectorOperation("sendNote", "Send note", null, null, null, RiskLevel.HIGH,
                false, null, node("object", null, null, null, null, noteInputs, List.of("text")),
                node("object", null, null, null, null, Map.of(), null), null, null, null, null, null);
        ConnectorDescriptor descriptor = new ConnectorDescriptor("mock-crm-mcp", "Mock CRM", null,
                ConnectorDescriptor.TRANSPORT_MCP, 1, auth, List.of(Map.of("name", "token", "type", "secret")), null,
                null, null, null, List.of(find, createTicket, sendNote),
                protocolVersion != null ? new ConnectorDescriptor.McpSettings(protocolVersion) : null);
        return new ResolvedConnector(BANK, "Mock CRM", "MCP", descriptor, "https://crm.test/mcp",
                List.of(), Map.of(), Map.of("token", SECRET), 2L);
    }

    /** Records what it was handed; answers or fails as scripted. */
    static final class FakeTransport implements ConnectorTransport {

        private final String kind;
        int calls;
        ResolvedConnector connector;
        ConnectorOperation operation;
        Map<String, Object> inputs;
        CallOptions options;
        private CallResult answer;
        private RuntimeException failure;

        FakeTransport() {
            this("REST");
        }

        FakeTransport(String kind) {
            this.kind = kind;
        }

        void answer(int status, Object body, int attempts, long latencyMs) {
            this.answer = new CallResult(status, body, Map.of(), attempts, latencyMs);
            this.failure = null;
        }

        void fail(ConnectorException e) {
            this.failure = e;
        }

        void crash(RuntimeException e) {
            this.failure = e;
        }

        @Override
        public String kind() {
            return kind;
        }

        @Override
        public Set<String> supportedAuthSchemes() {
            return Set.of(AuthScheme.SCHEME_API_KEY);
        }

        @Override
        public CallResult call(ResolvedConnector connector, ConnectorOperation operation,
                Map<String, Object> inputs, CallOptions options) {
            calls++;
            this.connector = connector;
            this.operation = operation;
            this.inputs = inputs;
            this.options = options;
            if (failure != null) {
                throw failure;
            }
            return answer;
        }

        @Override
        public CallResult test(ResolvedConnector connector, Duration budget) {
            throw new UnsupportedOperationException();
        }
    }

    /** Resolves the scripted connector, or refuses; describes its operations. */
    static final class FakePort implements ConnectorPort {

        ResolvedConnector connector;
        ConnectorNotResolvableException refusal;
        boolean describeFails;
        int resolves;
        final List<UUID> invalidated = new ArrayList<>();

        FakePort(ResolvedConnector connector) {
            this.connector = connector;
        }

        @Override
        public ResolvedConnector resolve(UUID connectorId, String accountId, UUID assistantId) {
            resolves++;
            if (refusal != null) {
                throw refusal;
            }
            return connector;
        }

        @Override
        public Optional<ConnectorOperation> describe(UUID connectorId, String operationKey) {
            if (describeFails) {
                throw new IllegalStateException("journey-service unavailable");
            }
            return connector.operation(operationKey);
        }

        @Override
        public void invalidate(UUID connectorId) {
            invalidated.add(connectorId);
        }
    }
}
