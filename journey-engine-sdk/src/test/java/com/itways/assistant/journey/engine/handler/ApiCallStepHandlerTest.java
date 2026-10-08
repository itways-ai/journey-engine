package com.itways.assistant.journey.engine.handler;

import static com.itways.assistant.journey.engine.handler.HandlerFixtures.JSON;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.SCHEMAS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.UTILS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.VARIABLES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.json;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.output;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.run;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.stepField;
import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.journey.engine.context.EndUserAuth;
import com.itways.assistant.journey.engine.context.Simulation;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.util.EgressGuard;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * API_CALL beyond DNS rebinding (ApiCallDnsRebindingTest): a rehearsal calls
 * nothing, a call written to act as the user needs a signed-in user, the URL is
 * vetted before anything is sent, bodies keep their types, and an HTTP error
 * fails the step with the host's answer.
 *
 * <p>
 * One local server on 127.0.0.1, reachable only because the guard allow-lists
 * it; every other address counts as internal and is refused.
 */
class ApiCallStepHandlerTest {

    private HttpServer server;
    private int port;
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " auth="
                    + exchange.getRequestHeaders().getFirst("Authorization") + " body=" + body);
            boolean missing = exchange.getRequestURI().getPath().startsWith("/missing");
            byte[] reply = (missing ? "{\"error\":\"no such order\"}" : "{\"id\":\"A-17\",\"state\":\"shipped\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(missing ? 404 : 200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static ApiCallStepHandler handler() {
        EgressGuard guard = new EgressGuard(true, "127.0.0.1", InetAddress::getAllByName, address -> true);
        return new ApiCallStepHandler(UTILS, VARIABLES, SCHEMAS, guard, 2000, 5000);
    }

    private static JourneyStep step(String url, Map<String, Object> apiConfig) {
        return JourneyStep.builder().stepOrder(2).stepName("call").actionType("API_CALL").actionTarget(url)
                .apiConfig(json(apiConfig)).message("called").build();
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    @Test
    void aTypedBodyAndTheUsersTokenAreSentAndTheReplyIsStored() throws Exception {
        ExecutionContext context = run(Map.of("id", "A-17", "priority", 5));
        context.setInternal(EndUserAuth.INTERNAL_USER_TOKEN, "user-jwt");
        Map<String, Object> config = Map.of("method", "POST",
                "headers", Map.of("Authorization", "Bearer {{auth.userToken}}"),
                "body", Map.of("priority", "{{inputs.entities.priority}}", "note", "Order {{inputs.entities.id}}"));

        StepResult result = handler().execute(step(base() + "/orders/{{inputs.entities.id}}", config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getMessage()).isEqualTo("called");
        assertThat(result.getData()).isEqualTo(Map.of("id", "A-17", "state", "shipped"));
        assertThat(output(context, 2)).isEqualTo(result.getData());
        assertThat(stepField(context, 2, "status")).isEqualTo(200);
        assertThat(requests).singleElement().satisfies(line -> {
            assertThat(line).startsWith("POST /orders/A-17 auth=Bearer user-jwt body=");
            try {
                assertThat(JSON.readValue(line.substring(line.indexOf("body=") + 5), Map.class))
                        .isEqualTo(Map.of("priority", 5, "note", "Order A-17"));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        // The token is used for the header only; it never becomes a variable.
        assertThat(json(context.getVariables())).doesNotContain("user-jwt");
    }

    @Test
    void anHttpErrorFailsTheStepWithTheHostsAnswer() {
        StepResult result = handler().execute(step(base() + "/missing", Map.of("method", "GET")), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).startsWith("API Call Failed [404").endsWith("{\"error\":\"no such order\"}");
    }

    /** A rehearsal interpolates the URL but calls nothing and says so. */
    @Test
    void aSimulatedCallSendsNothing() {
        ExecutionContext context = run(Map.of("id", "A-17"));
        context.setInternal(Simulation.INTERNAL_SIMULATE, true);

        StepResult result = handler().execute(step(base() + "/orders/{{inputs.entities.id}}", Map.of("method", "DELETE")),
                context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getMetadata()).containsEntry(Simulation.META_SIMULATED, true);
        assertThat(result.getData()).isEqualTo(Map.of("simulated", true, "method", "DELETE",
                "url", base() + "/orders/A-17"));
        assertThat(stepField(context, 2, "status")).isEqualTo(200);
        assertThat(requests).isEmpty();
    }

    @Test
    void aCallActingAsTheUserNeedsASignedInUser() {
        Map<String, Object> config = Map.of("headers", Map.of("Authorization", "Bearer {{auth.userToken}}"));

        StepResult result = handler().execute(step(base() + "/orders", config), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getUserMessage()).isEqualTo("This needs you to be signed in and verified first.");
        assertThat(result.getMetadata()).containsEntry(ApiCallStepHandler.META_USER_REQUIRED, true);
        assertThat(requests).isEmpty();
    }

    /** The guard sees the URL after placeholders filled it in, and refuses before sending. */
    @Test
    void aUrlTheGuardRefusesIsNeverCalled() {
        ExecutionContext context = run(Map.of("host", "10.0.0.7"));

        StepResult internal = handler().execute(step("http://{{inputs.entities.host}}/admin", Map.of()), context);
        assertThat(internal.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(internal.getMessage()).startsWith("API_CALL refused: host 10.0.0.7 is not allowed")
                .contains(EgressGuard.ALLOW_LIST_SETTING);

        assertThat(handler().execute(step("file:///etc/passwd", Map.of()), context).getMessage())
                .isEqualTo("API_CALL refused: only http and https URLs may be called");
        assertThat(requests).isEmpty();
    }
}
