package com.itways.assistant.journey.engine.handler;

import com.itways.assistant.journey.model.StepStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.itways.assistant.journey.engine.context.EndUserAuth;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ApiCallStepHandler implements StepHandler {

	private final EngineUtils engineUtils;
	private final VariableContext variableContext;
	private final StepOutputSchemaHelper schemaHelper;
	private final RestTemplate restTemplate;
	private final com.itways.assistant.journey.engine.util.EgressGuard egressGuard;

	public ApiCallStepHandler(EngineUtils engineUtils, VariableContext variableContext,
			StepOutputSchemaHelper schemaHelper, com.itways.assistant.journey.engine.util.EgressGuard egressGuard,
			@org.springframework.beans.factory.annotation.Value("${nibras.journey.api-call.connect-timeout-ms:5000}") int connectTimeoutMs,
			@org.springframework.beans.factory.annotation.Value("${nibras.journey.api-call.read-timeout-ms:30000}") int readTimeoutMs) {
		this.engineUtils = engineUtils;
		this.variableContext = variableContext;
		this.schemaHelper = schemaHelper;
		this.egressGuard = egressGuard;
		// Timeouts are non-negotiable: this runs on the request thread, so a
		// host that never answers used to hang the whole turn indefinitely.
		// Deliberately a local instance, not an injected bean — an injected
		// RestTemplate risks silently picking up ai-engine-sdk's
		// trust-all-certificates template through auto-configuration.
		// Redirects are never followed: EgressGuard vets the URL the author
		// wrote, and a public host answering 302 to http://169.254.169.254/
		// would otherwise be followed straight past it. A 3xx comes back to the
		// journey as the response it is.
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
			@Override
			protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod)
					throws java.io.IOException {
				super.prepareConnection(connection, httpMethod);
				connection.setInstanceFollowRedirects(false);
			}
		};
		factory.setConnectTimeout(connectTimeoutMs);
		factory.setReadTimeout(readTimeoutMs);
		this.restTemplate = new RestTemplate(new BufferingClientHttpRequestFactory(factory));
	}

	@Override
	public String getType() {
		return "API_CALL";
	}

	@Override
	public StepDefinition describe() {
		return schemaHelper.apiCallDefinition();
	}

	@Override
	public StepOutputSchema describeOutputs(JourneyStep step) {
		return schemaHelper.apiCallSchema(step);
	}

	@Override
	public StepResult execute(JourneyStep step, ExecutionContext context) {
		try {
			String url = engineUtils.replacePlaceholders(step.getActionTarget(), context.getVariables());
			ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());

			if (com.itways.assistant.journey.engine.context.Simulation.isActive(context)) {
				return simulate(step, context, config, url);
			}

			if (needsUserToken(config) && EndUserAuth.token(context) == null) {
				// Acting as the user with nobody signed in. Said plainly, in the
				// run's language, and flagged so a channel can offer to verify
				// the person rather than read out a 401 from the host.
				log.warn("API_CALL step '{}' acts as the end user but no user token was supplied", step.getStepName());
				return StepResult.builder()
						.status(StepStatus.ERROR)
						.message("API call acts as the end user but no user token was supplied")
						.userMessage(messages.get(context.resolvedLanguage(), "step.apiCall.userRequired"))
						.metadata(Map.of(META_USER_REQUIRED, true))
						.build();
			}

			// The URL is final only now — placeholders may have filled in the host
			// from what the user typed — so this is where it is vetted.
			String refusal = egressGuard.refusal(url);
			if (refusal != null) {
				log.warn("API_CALL step '{}' refused: {}", step.getStepName(), refusal);
				return StepResult.error("API_CALL refused: " + refusal);
			}

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			Map<String, Object> headerScope = headerScope(context);
			config.getHeaders()
					.forEach((k, v) -> headers.set(k, engineUtils.replacePlaceholders(v, headerScope)));

			Object processedBody = processBody(config.getBody(), context.getVariables());

			log.info("---> API_CALL Step: '{}'", step.getStepName());
			log.info("Request URL    : {} {}", config.getMethod(), url);

			HttpEntity<Object> entity = new HttpEntity<>(processedBody, headers);
			ResponseEntity<Object> response = restTemplate.exchange(url, HttpMethod.valueOf(config.getMethod()), entity,
					Object.class);

			Object apiResult = response.getBody();
			log.info("<--- API_CALL Step: '{}' status={}", step.getStepName(), response.getStatusCode());

		variableContext.storeOutput(context, step, apiResult);
		variableContext.writeStepField(context, step, "status", response.getStatusCode().value());
		variableContext.writeStepField(context, step, "headers", response.getHeaders().toSingleValueMap());

			return StepResult.success(apiResult, step.getMessage());
		} catch (HttpClientErrorException | HttpServerErrorException e) {
			log.error("API_CALL step '{}' status={} error={}", step.getStepName(), e.getStatusCode(),
					e.getResponseBodyAsString());
			String bodyMsg = e.getResponseBodyAsString();
			return StepResult.error("API Call Failed [" + e.getStatusCode() + "]: " +
					(bodyMsg != null && !bodyMsg.isBlank() ? bodyMsg : e.getMessage()));
		} catch (org.springframework.web.client.ResourceAccessException e) {
			// Connect/read timeout or unreachable host. Named separately so run
			// history says "timed out" instead of a raw I/O stack message.
			log.error("API_CALL step '{}' did not answer in time: {}", step.getStepName(), e.getMessage());
			return StepResult.error("API Call Failed: host did not answer in time (" + e.getMessage() + ")");
		} catch (Exception e) {
		 log.error("API_CALL step '{}' unexpected error", step.getStepName(), e);
			return StepResult.error("API Call Failed: " + e.getMessage());
		}
	}

	/**
	 * What this step would have called, without calling it.
	 *
	 * <p>
	 * The URL and method are still interpolated first, so a rehearsal exercises
	 * the placeholders — a URL that resolves to
	 * {@code /tasks/} because the id was missing is exactly the bug a simulator
	 * exists to surface, and stubbing before interpolation would hide it.
	 *
	 * <p>
	 * The stub is a plain map rather than an invented payload shaped like the
	 * customer's API. Guessing a response would let a downstream step read
	 * {@code {{steps.3.output.items[0].name}}} and appear to work against data
	 * no real call would return — a rehearsal that passes on fiction is worse
	 * than one that plainly says nothing came back.
	 */
	private StepResult simulate(JourneyStep step, ExecutionContext context, ApiConfig config, String url) {
		Map<String, Object> stub = new HashMap<>();
		stub.put("simulated", true);
		stub.put("method", config.getMethod());
		stub.put("url", url);

		variableContext.storeOutput(context, step, stub);
		variableContext.writeStepField(context, step, "status", 200);
		variableContext.writeStepField(context, step, "headers", Map.of());

		log.info("---> API_CALL step '{}' SIMULATED: {} {}", step.getStepName(), config.getMethod(), url);

		Map<String, Object> metadata = new HashMap<>();
		metadata.put(com.itways.assistant.journey.engine.context.Simulation.META_SIMULATED, true);
		return StepResult.builder()
				.status(StepStatus.SUCCESS)
				.data(stub)
				.message(step.getMessage())
				.metadata(metadata)
				.build();
	}

	/**
	 * Variable scope used for request-header interpolation only.
	 *
	 * <p>
	 * Returns the journey's own variables plus the end user's token under
	 * {@code auth.userToken}, so a step can declare
	 * {@code "Authorization": "Bearer {{auth.userToken}}"} and call a customer API
	 * as the signed-in user.
	 *
	 * <p>
	 * The augmented map is built per call and thrown away: it is never written
	 * back to the context, so the credential stays out of run history, the
	 * variable picker, the {@code CODE_SCRIPT} sandbox and {@code DATA_MAP}'s LLM
	 * prompt. Headers are the only scope that gets it — URLs end up in logs and
	 * bodies are persisted with the run.
	 */
	/** Set on the step view when the call could not run because nobody was signed in. */
	public static final String META_USER_REQUIRED = "userRequired";

	private static final String USER_TOKEN_REFERENCE = EndUserAuth.SCOPE + "." + EndUserAuth.FIELD_USER_TOKEN;

	private final com.itways.assistant.journey.engine.language.EngineMessages messages = new com.itways.assistant.journey.engine.language.EngineMessages();

	/** True when any header of the call is written to carry the end-user token. */
	static boolean needsUserToken(ApiConfig config) {
		if (config == null || config.getHeaders() == null) {
			return false;
		}
		return config.getHeaders().values().stream()
				.anyMatch(value -> value != null && value.contains(USER_TOKEN_REFERENCE));
	}

	private Map<String, Object> headerScope(ExecutionContext context) {
		String token = EndUserAuth.token(context);
		if (token == null) {
			return context.getVariables();
		}
		Map<String, Object> scope = new HashMap<>(context.getVariables());
		scope.put(EndUserAuth.SCOPE, Map.of(EndUserAuth.FIELD_USER_TOKEN, token));
		return scope;
	}

	/**
	 * Interpolates the request body, preserving the resolved type wherever a
	 * value is a single placeholder.
	 *
	 * <p>
	 * {@code "{{steps.3.output.priority}}"} yields the number 5, not the string
	 * "5". Every other place the engine resolves an authored value already works
	 * this way — {@code STATE_STORE}'s source and {@code TEMPLATE_RENDER}'s
	 * bindings both go through {@code resolveSourceValue} — and only request
	 * bodies stringified everything. That made typed fields unreachable: any host
	 * with an integer or boolean column rejected the body outright (Vikunja
	 * answers a bare "Invalid model provided"), so journeys could only ever send
	 * hard-coded literals for them, and a full-replace API like Vikunja's task
	 * update silently wiped the fields the journey could not restate.
	 *
	 * <p>
	 * Mixed templates such as {@code "Order {{id}} confirmed"} still resolve to a
	 * String, which is the only sensible reading of them.
	 */
	private Object processBody(Object body, Map<String, Object> variables) {
		if (body instanceof String) {
			return engineUtils.resolveSourceValue((String) body, variables);
		} else if (body instanceof Map) {
			Map<String, Object> bodyMap = (Map<String, Object>) body;
			Map<String, Object> processedMap = new HashMap<>();
			for (Map.Entry<String, Object> entry : bodyMap.entrySet()) {
				processedMap.put(entry.getKey(), processBody(entry.getValue(), variables));
			}
			return processedMap;
		} else if (body instanceof List) {
			List<Object> bodyList = (List<Object>) body;
			List<Object> processedList = new ArrayList<>();
			for (Object item : bodyList) {
				processedList.add(processBody(item, variables));
			}
			return processedList;
		}
		return body;
	}

}
