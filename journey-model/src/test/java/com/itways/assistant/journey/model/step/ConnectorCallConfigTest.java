package com.itways.assistant.journey.model.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itways.assistant.journey.model.step.ConnectorCallConfig.InvalidConfigException;
import com.itways.assistant.journey.model.step.ConnectorCallConfig.OnError;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The strict reader of an CONNECTOR_CALL step's configuration: what it
 * accepts, the defaults it fills in, and that everything it refuses is refused
 * with a sentence that names the problem.
 */
class ConnectorCallConfigTest {

    private static final String ID = "6f1c2a52-8d0e-4b61-9a43-2f0d1c7e5b10";

    private static String config(String extra) {
        return "{\"connectorId\":\"" + ID + "\",\"operationKey\":\"getAccountBalance\"" + extra + "}";
    }

    @Test
    void minimalConfigParsesWithDefaults() {
        ConnectorCallConfig config = ConnectorCallConfig.parse(config(""));

        assertThat(config.connectorId()).isEqualTo(UUID.fromString(ID));
        assertThat(config.operationKey()).isEqualTo("getAccountBalance");
        assertThat(config.onError()).isEqualTo(OnError.FAIL);
        assertThat(config.onErrorOrFail()).isEqualTo(OnError.FAIL);
        assertThat(config.inputs()).isEmpty();
        assertThat(config.inputsOrEmpty()).isEmpty();
        assertThat(config.storeRawResponse()).isFalse();
        assertThat(config.storesRawResponse()).isFalse();
        assertThat(config.timeoutMs()).isNull();
        assertThat(config.maxAttempts()).isNull();
        assertThat(config.idempotencyKey()).isNull();
    }

    @Test
    void fullConfigKeepsEveryField() {
        ConnectorCallConfig config = ConnectorCallConfig.parse(config(",\"inputs\":{\"accountNo\":\"{{inputs.entities.account}}\","
                + "\"limit\":5,\"filter\":{\"from\":\"{{state.from}}\"}},\"idempotencyKey\":\"{{inputs.ref}}\","
                + "\"onError\":\"BRANCH\",\"timeoutMs\":5000,\"maxAttempts\":2,\"storeRawResponse\":true"));

        assertThat(config.inputs()).containsEntry("accountNo", "{{inputs.entities.account}}")
                .containsEntry("limit", 5)
                .containsEntry("filter", Map.of("from", "{{state.from}}"));
        assertThat(config.idempotencyKey()).isEqualTo("{{inputs.ref}}");
        assertThat(config.onError()).isEqualTo(OnError.BRANCH);
        assertThat(config.timeoutMs()).isEqualTo(5000);
        assertThat(config.maxAttempts()).isEqualTo(2);
        assertThat(config.storesRawResponse()).isTrue();
    }

    @Test
    void operationKeyIsTrimmed() {
        ConnectorCallConfig config = ConnectorCallConfig
                .parse("{\"connectorId\":\"" + ID + "\",\"operationKey\":\"  ping \"}");

        assertThat(config.operationKey()).isEqualTo("ping");
    }

    @Test
    void unknownFieldIsRefusedByName() {
        assertThatThrownBy(() -> ConnectorCallConfig.parse(config(",\"url\":\"https://example.com\"")))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("url");
    }

    @Test
    void malformedJsonIsRefusedClearly() {
        assertThatThrownBy(() -> ConnectorCallConfig.parse("{\"connectorId\": "))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageStartingWith("the step configuration is not valid");
    }

    @Test
    void nullOrBlankJsonIsRefused() {
        assertThatThrownBy(() -> ConnectorCallConfig.parse(null))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessage("the step has no configuration");
        assertThatThrownBy(() -> ConnectorCallConfig.parse("   "))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessage("the step has no configuration");
        assertThatThrownBy(() -> ConnectorCallConfig.parse("null"))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessage("the step has no configuration");
    }

    @Test
    void missingConnectorIdIsRefused() {
        assertThatThrownBy(() -> ConnectorCallConfig.parse("{\"operationKey\":\"ping\"}"))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessage("connectorId is required");
    }

    @Test
    void missingOrBlankOperationKeyIsRefused() {
        assertThatThrownBy(() -> ConnectorCallConfig.parse("{\"connectorId\":\"" + ID + "\"}"))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessage("operationKey is required");
        assertThatThrownBy(
                () -> ConnectorCallConfig.parse("{\"connectorId\":\"" + ID + "\",\"operationKey\":\" \"}"))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessage("operationKey is required");
    }

    @Test
    void connectorIdMustBeAUuid() {
        assertThatThrownBy(
                () -> ConnectorCallConfig.parse("{\"connectorId\":\"bank\",\"operationKey\":\"ping\"}"))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageStartingWith("the step configuration is not valid");
    }

    @Test
    void timeoutOutsideItsRangeIsRefused() {
        assertThatThrownBy(() -> ConnectorCallConfig.parse(config(",\"timeoutMs\":50")))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("timeoutMs")
                .hasMessageContaining("50");
        assertThatThrownBy(() -> ConnectorCallConfig.parse(config(",\"timeoutMs\":40000")))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("timeoutMs")
                .hasMessageContaining("40000");
    }

    @Test
    void timeoutAtItsBoundsIsAccepted() {
        assertThat(ConnectorCallConfig.parse(config(",\"timeoutMs\":100")).timeoutMs())
                .isEqualTo(ConnectorCallConfig.MIN_TIMEOUT_MS);
        assertThat(ConnectorCallConfig.parse(config(",\"timeoutMs\":30000")).timeoutMs())
                .isEqualTo(ConnectorCallConfig.MAX_TIMEOUT_MS);
    }

    @Test
    void attemptsOutsideOneToThreeAreRefused() {
        assertThatThrownBy(() -> ConnectorCallConfig.parse(config(",\"maxAttempts\":0")))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("maxAttempts");
        assertThatThrownBy(() -> ConnectorCallConfig.parse(config(",\"maxAttempts\":4")))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("maxAttempts");
        assertThat(ConnectorCallConfig.parse(config(",\"maxAttempts\":1")).maxAttempts()).isEqualTo(1);
        assertThat(ConnectorCallConfig.parse(config(",\"maxAttempts\":3")).maxAttempts())
                .isEqualTo(ConnectorCallConfig.MAX_ATTEMPTS);
    }

    @Test
    void blankIdempotencyKeyIsRefused() {
        assertThatThrownBy(() -> ConnectorCallConfig.parse(config(",\"idempotencyKey\":\"  \"")))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void everyOnErrorValueParses() {
        for (OnError value : OnError.values()) {
            assertThat(ConnectorCallConfig.parse(config(",\"onError\":\"" + value.name() + "\"")).onError())
                    .isEqualTo(value);
        }
        assertThatThrownBy(() -> ConnectorCallConfig.parse(config(",\"onError\":\"RETRY\"")))
                .isInstanceOf(InvalidConfigException.class);
    }

    @Test
    void aFloatWhereAnIntegerIsExpectedIsRefused() {
        assertThatThrownBy(() -> ConnectorCallConfig.parse(config(",\"timeoutMs\":5000.5")))
                .isInstanceOf(InvalidConfigException.class);
        assertThatThrownBy(() -> ConnectorCallConfig.parse(config(",\"maxAttempts\":2.0")))
                .isInstanceOf(InvalidConfigException.class);
    }

    @Test
    void toJsonRoundTrips() {
        ConnectorCallConfig config = ConnectorCallConfig.parse(config(",\"inputs\":{\"accountNo\":\"123\"},"
                + "\"idempotencyKey\":\"k-{{inputs.ref}}\",\"onError\":\"CONTINUE\",\"timeoutMs\":2500,"
                + "\"maxAttempts\":1,\"storeRawResponse\":true"));

        ConnectorCallConfig again = ConnectorCallConfig.parse(config.toJson());

        assertThat(again).isEqualTo(config);
    }

    @Test
    void toJsonOfDefaultsRoundTrips() {
        ConnectorCallConfig config = ConnectorCallConfig.parse(config(""));

        assertThat(ConnectorCallConfig.parse(config.toJson())).isEqualTo(config);
        assertThat(config.toJson()).doesNotContain("timeoutMs").doesNotContain("idempotencyKey");
    }
}
