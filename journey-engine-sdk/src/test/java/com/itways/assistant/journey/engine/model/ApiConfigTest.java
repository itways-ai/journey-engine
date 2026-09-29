package com.itways.assistant.journey.engine.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** A step's config has the same defaults however it is made: builder, no-args constructor or JSON. */
class ApiConfigTest {

    @Test
    void builderDefaults() {
        ApiConfig config = ApiConfig.builder().build();

        assertThat(config.isAllowMissingInputs()).isFalse();
        assertThat(config.isAllowResubmit()).isFalse();
        assertThat(config.getMethod()).isEqualTo("GET");
        assertThat(config.getInputMode()).isEqualTo("FREE_TEXT");
        assertThat(config.getHeaders()).isEmpty();
    }

    @Test
    void noArgsAndJsonDefaultsMatchTheBuilder() throws Exception {
        ApiConfig built = ApiConfig.builder().build();

        assertThat(new ApiConfig()).isEqualTo(built);
        assertThat(new ObjectMapper().readValue("{}", ApiConfig.class)).isEqualTo(built);
    }

    @Test
    void flagsCanBeSet() {
        ApiConfig config = ApiConfig.builder().allowMissingInputs(true).allowResubmit(true).build();

        assertThat(config.isAllowMissingInputs()).isTrue();
        assertThat(config.isAllowResubmit()).isTrue();
    }
}
