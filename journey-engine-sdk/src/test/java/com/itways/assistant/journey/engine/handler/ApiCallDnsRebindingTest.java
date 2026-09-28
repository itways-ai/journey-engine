package com.itways.assistant.journey.engine.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.util.EgressGuard;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;
import com.sun.net.httpserver.HttpServer;

/**
 * An API_CALL connects to the address the guard checked, never to one the
 * name answers afterwards (DNS rebinding), and never follows a redirect inward.
 *
 * <p>
 * Two local servers on the same port stand in for two DNS answers: 127.0.0.2
 * plays a public address (the guard's stub rule treats everything else as
 * internal) and 127.0.0.1 a private one. The private server must never see a
 * request unless its host is on the operator allow-list.
 */
class ApiCallDnsRebindingTest {

	private static final String HOST = "api.example.test";
	private static final String ALLOW_LISTED = "billing.corp.test";

	private InetAddress publicAddress;
	private InetAddress privateAddress;
	private HttpServer publicServer;
	private HttpServer privateServer;
	private final AtomicInteger publicHits = new AtomicInteger();
	private final AtomicInteger privateHits = new AtomicInteger();
	private final AtomicInteger lookups = new AtomicInteger();
	private int port;

	@BeforeEach
	void servers() throws IOException {
		publicAddress = InetAddress.getByName("127.0.0.2");
		privateAddress = InetAddress.getByName("127.0.0.1");
		try {
			publicServer = HttpServer.create(new InetSocketAddress(publicAddress, 0), 0);
			port = publicServer.getAddress().getPort();
			privateServer = HttpServer.create(new InetSocketAddress(privateAddress, port), 0);
		} catch (IOException e) {
			// 127.0.0.2 is routable on Linux (where the build runs), not on every laptop.
			assumeTrue(false, "needs 127.0.0.2 on the loopback interface: " + e.getMessage());
		}
		publicServer.createContext("/", exchange -> {
			publicHits.incrementAndGet();
			if (exchange.getRequestURI().getPath().equals("/redirect")) {
				exchange.getResponseHeaders().add("Location", "http://inside.example.test:" + port + "/secret");
				exchange.sendResponseHeaders(302, -1);
				exchange.close();
				return;
			}
			answer(exchange, "{\"from\":\"public\"}");
		});
		privateServer.createContext("/", exchange -> {
			privateHits.incrementAndGet();
			answer(exchange, "{\"from\":\"private\"}");
		});
		publicServer.start();
		privateServer.start();
	}

	private static void answer(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	@AfterEach
	void stop() {
		if (publicServer != null) {
			publicServer.stop(0);
		}
		if (privateServer != null) {
			privateServer.stop(0);
		}
	}

	private ApiCallStepHandler handler(EgressGuard.Lookup dns) {
		EgressGuard guard = new EgressGuard(true, ALLOW_LISTED, host -> {
			lookups.incrementAndGet();
			return dns.resolve(host);
		}, address -> !address.equals(publicAddress));
		ObjectMapper json = new ObjectMapper();
		return new ApiCallStepHandler(new EngineUtils(json), new VariableContext(), new StepOutputSchemaHelper(json),
				guard, 2000, 5000);
	}

	private static JourneyStep call(String url) {
		return JourneyStep.builder().stepOrder(1).stepName("call").actionType("API_CALL").actionTarget(url)
				.apiConfig("{\"method\":\"GET\"}").build();
	}

	private static ExecutionContext run() {
		Map<String, Object> variables = new HashMap<>();
		variables.put("inputs", new HashMap<>());
		return ExecutionContext.builder().accountId("acc-1").variables(variables).build();
	}

	@SuppressWarnings("unchecked")
	private static Object stepField(ExecutionContext context, String field) {
		Map<String, Object> steps = (Map<String, Object>) context.getVariables().get("steps");
		return ((Map<String, Object>) steps.get("1")).get(field);
	}

	@Test
	void aNameThatAnswersPublicThenPrivateNeverReachesThePrivateAddress() {
		ApiCallStepHandler handler = handler(host -> lookups.get() == 1
				? new InetAddress[] { publicAddress }
				: new InetAddress[] { privateAddress });

		StepResult result = handler.execute(call("http://" + HOST + ":" + port + "/data"), run());

		assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
		// JRN-17: the same refusal as the check before the call, naming the host and the setting only.
		assertThat(result.getMessage()).startsWith("API_CALL refused: host " + HOST + " is not allowed")
				.contains(EgressGuard.ALLOW_LIST_SETTING)
				.doesNotContain("127.0.0.1")
				.doesNotContain("resolves to");
		assertThat(lookups).hasValue(2);
		assertThat(privateHits).hasValue(0);
		assertThat(publicHits).hasValue(0);
	}

	@Test
	void theConnectionDialsTheAddressItCheckedNotALaterAnswer() {
		// Public to the check and to the connection's own lookup; private to any lookup after that.
		ApiCallStepHandler handler = handler(host -> lookups.get() <= 2
				? new InetAddress[] { publicAddress }
				: new InetAddress[] { privateAddress });

		StepResult result = handler.execute(call("http://" + HOST + ":" + port + "/data"), run());

		assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
		assertThat(result.getData()).isEqualTo(Map.of("from", "public"));
		assertThat(lookups).hasValue(2);
		assertThat(publicHits).hasValue(1);
		assertThat(privateHits).hasValue(0);
	}

	@Test
	void aNameWithAnyPrivateAddressIsNotDialledAtAll() {
		ApiCallStepHandler handler = handler(host -> new InetAddress[] { publicAddress, privateAddress });

		StepResult result = handler.execute(call("http://" + HOST + ":" + port + "/data"), run());

		assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
		assertThat(result.getMessage()).contains("host " + HOST + " is not allowed");
		assertThat(publicHits).hasValue(0);
		assertThat(privateHits).hasValue(0);
	}

	@Test
	void anAllowListedHostStillReachesItsPrivateAddress() {
		ApiCallStepHandler handler = handler(host -> new InetAddress[] { privateAddress });

		StepResult result = handler.execute(call("http://" + ALLOW_LISTED + ":" + port + "/data"), run());

		assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
		assertThat(result.getData()).isEqualTo(Map.of("from", "private"));
		assertThat(privateHits).hasValue(1);
	}

	@Test
	void aRedirectToAPrivateAddressIsNotFollowed() {
		ApiCallStepHandler handler = handler(host -> host.equals(HOST)
				? new InetAddress[] { publicAddress }
				: new InetAddress[] { privateAddress });
		ExecutionContext context = run();

		StepResult result = handler.execute(call("http://" + HOST + ":" + port + "/redirect"), context);

		// The 3xx comes back to the journey as the response it is.
		assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
		assertThat(stepField(context, "status")).isEqualTo(302);
		assertThat(publicHits).hasValue(1);
		assertThat(privateHits).hasValue(0);
	}
}
