# journey-engine

The library that runs journeys: an ordered graph of steps (ask the user, call an
API, branch, render a template, send mail, hand off to a person) executed one
conversation turn at a time. It has no database and no HTTP API of its own. The
service that embeds it supplies storage, search, rendering and mail through
ports.

## Modules

| Module | Artifact | What it is |
|---|---|---|
| `journey-model` | `com.itways.assistant:journey-model` | The journey document and its statuses (`JourneyDefinition`, `JourneyStep`, `RunStatus`, `StepStatus`, `RunStepLog`, `RunHistoryEvent`, …), the step graph (`JourneyStepGraph`: parents, order, cycle check) and, in `model.catalog`, the step catalogue the builder reads (`StepDefinition`, `StepOutputSchema`, `OutputField`, `ChannelVariableSchema`, `ChannelVariableGroup`). Plain data, no Spring. The service that stores journeys depends on this only. |
| `journey-engine-sdk` | `com.itways.assistant:journey-engine-sdk` | The engine, the step handlers, the ports and the Spring configuration. Pulls in Spring Boot, FreeMarker, GraalVM JS, `ai-engine-sdk` and `common-core`. |

Both are released together at the version in the parent `pom.xml`.

## What the engine does

`JourneyEngine` has two calls: `start(journey, accountId, assistantId, params)` and
`resume(journey, context, params)`. Each has a variant that takes a `StepObserver`,
which receives each step's view as soon as that step finishes (for streaming).
A call runs steps until the journey ends or a step has to wait for someone.

- **Order.** Steps are sorted by their parent links (`parentOrder` / `parentOrders`).
  Among the steps that are ready, the lowest `stepOrder` runs first. A cycle in
  the graph fails the run before any step runs.
- **Branching.** A step with `branchName` runs only when its parent's result
  matches: `true` / `false` under a CONDITION, a case name or `DEFAULT` under a
  SWITCH. Case names are matched without regard to case. A step with
  `parentOrders` is a rejoin: it runs when any of those parents ran. JUMP moves the run
  to another step. A backward jump clears the outputs of the steps it replays,
  and the `state` entries they wrote. A turn is limited to 50 jumps and 2000
  executed steps. Past either limit the run ends in ERROR, so a loop cannot hang
  the request thread.
- **Waiting.** A handler that returns `WAITING` (USER_INPUT, HUMAN_APPROVAL,
  DELAY, or a child journey that is waiting) parks the run. The host stores the
  returned `ExecutionContext` and passes it back to `resume` with the next
  message. The waiting step then runs again with `inputs.answer` set.
- **Failure.** A step that returns `ERROR` or throws stops the run, unless the
  step has `continueOnError`. With `continueOnError`, `FAILED` becomes the
  step's output and the run goes on. The user sees the step's `userMessage` if
  it set one. Otherwise the user sees a generic sentence, and the diagnostic
  stays in the step log.
- **Variables.** Authors read variables with `{{path}}` placeholders and
  condition expressions. The buckets are `inputs` (`text`, `answer`, `files`,
  `entities`), `steps.<order or stepKey>.output`, `state`, `runtime` (for example
  `executionId`, `language`, `stepOrder`) and `channel`. Some values never reach
  these buckets: the end-user token, the rehearsal flag, channel capabilities and
  nested-run bookkeeping live in `ExecutionContext.internal`. So they stay out of
  run history, CODE_SCRIPT and DATA_MAP prompts.
- **Lifecycle.** Each run emits `RunHistoryEvent`s (journey-model, the shape
  run history stores) to every `JourneyRunLifecyclePort`: `RUNNING` at the
  start, then `WAITING`, `COMPLETED` or `ERROR` at the end of each turn. Only
  `COMPLETED` and `ERROR` are final and carry `completedAt` / `durationMs`.
  `rootExecutionId` is always set, and `contextData` holds the run's
  `variables` and `stepResults` as JSON, written with the host's
  `ObjectMapper`. If the `RUNNING` call throws, the run does not start.
- **Rehearsal.** Start a run with the param `simulate: true` to rehearse it.
  API_CALL and SEND_MAIL then return stubs instead of calling out, and no
  lifecycle events are emitted. Every other step runs for real.
- **Language.** Step text is served in the conversation's language. The engine
  uses the translations published with the journey version, and falls back to
  `TextTranslator` when a version has none.
- **Nested journeys.** TRIGGER_JOURNEY starts another journey as its own
  execution, linked by `parentExecutionId` / `rootExecutionId`. The child gets a
  copy of the parent's inputs, but not the parent's step outputs or state. If
  the child waits, the parent waits with it. On resume, the child is resumed
  against the version it was pinned to. Nesting stops at 5 levels, and a journey
  that is already on the call stack cannot be triggered again.

## Step handlers

| Handler | Step type | Purpose |
|---|---|---|
| `ApiCallStepHandler` | `API_CALL` | Calls an HTTP API. Placeholders are resolved in the URL, headers and body, and body values keep their types. The URL must pass the egress guard. `{{auth.userToken}}` is available in headers only. |
| `CodeScriptStepHandler` | `CODE_SCRIPT` | Runs JavaScript in a GraalVM sandbox over a JSON copy of the variables, with no host access and with statement and time limits. The value of the last expression is the output. |
| `ConditionStepHandler` | `CONDITION` | Evaluates a SpEL expression (restricted context) to true or false, for branching. |
| `DataMapStepHandler` | `DATA_MAP` | Asks the LLM to fill the author's JSON shape from the user's message and context. Re-asks once for blank fields. |
| `DelayStepHandler` | `DELAY` | A "not before" gate. It parks the run, and clears on the first resume after the deadline (or on any resume, with `resumeOnEvent`). |
| `DocumentInsightStepHandler` | `DOCUMENT_INSIGHT` | Placeholder. Returns a fixed extraction result and does not read the document. |
| `HandoffStepHandler` | `HANDOFF` | Ends the run as COMPLETED and publishes `handoff` metadata (queue, note) for the host to route to a person. |
| `HumanApprovalStepHandler` | `HUMAN_APPROVAL` | Waits for approve or reject. A rejection, or a timeout with no decision, halts the run. |
| `JumpHandler` | `JUMP` | Moves execution to the step order in `actionTarget`. |
| `KnowledgeRetrievalStepHandler` | `KNOWLEDGE_RETRIEVAL` | Embeds the query and searches the assistant's knowledge index through `KnowledgeBasePort`. Answers with the single best entry, or with a composed answer. |
| `MailStepHandler` | `SEND_MAIL` | Sends mail with the step's SMTP settings (the password stays sealed) through `MailDeliveryPort`. |
| `RedirectStepHandler` | `REDIRECT` | Gives the client an http(s) URL to open (`redirectUrl` metadata). |
| `ResponseStepHandler` | `RESPONSE` | Replies with the resolved text. |
| `StateStoreStepHandler` | `STATE_STORE` | Writes `state.<variable>` with SET, APPEND or INCREMENT. |
| `SwitchStepHandler` | `SWITCH` | Evaluates an expression whose value picks a named branch, or `DEFAULT`. |
| `TemplateRenderHandler` | `TEMPLATE_RENDER` | Renders a stored template (at the pinned version) through `TemplateRenderPort`. It passes only the bindings the step names, and retries once when the renderer is busy. |
| `TriggerJourneyStepHandler` | `TRIGGER_JOURNEY` | Runs another journey inline, by trigger intent (see "Nested journeys" above). |
| `UserInputStepHandler` | `USER_INPUT` | Asks a question and validates the answer against the field rules, re-asking up to 3 times. It can offer a known entity for a yes/no (`fillFrom`), confirm an INTERACTIVE answer, and ask a form one field at a time on channels that cannot show forms. |

`StepOutputRegistry.getCatalog()` lists every handler's `describe()` for the
builder UI.

## Packages: what a host may use

Each package has a `package-info.java` that says what it is and whether a host
may use it. The package names are kept as they are (renaming them would be a
breaking release for every host).

| Package (`com.itways.assistant.journey.engine.…`) | Host-facing? | What it is |
|---|---|---|
| (root) | yes | `@EnableJourneyEngine`. |
| `service` | yes: the SPI | `JourneyEngine` and `StepOutputRegistry` (the host calls them); the six `*Port` interfaces, `AiConfigProvider` and `TextTranslator` (the host implements them); `StepHandler` (to add a step type); `StepObserver`. `StepHandlerRegistry` is internal. |
| `model` | yes | The data the SPI passes: `ExecutionContext`, `StepResult`, `MailConfig`, `TemplateRenderResult` (and `ApiConfig`, a step's parsed config). |
| `context` | yes, except `VariableContext` | Reserved start-params the engine lifts out of the variables: `EndUserAuth`, `Simulation`, `ChannelCapabilities`, `ConversationParams`. |
| `language` | yes, except `StepLocalizer` | `ConversationLanguage`, `LanguageDetector`, `LanguageParams`, `DecisionWords`, `Messages`, `EngineMessages`. |
| `util` | only `VariablePath` | Placeholders, variable paths, egress rules, schema helpers. |
| `config`, `impl`, `handler`, `validation` | no | Spring wiring, the run loop, the step handlers (and the engine's own FreeMarker `TemplateRender`), answer validation. |

## Ports the host implements

The engine is enabled with `@EnableJourneyEngine` on the host application. That
imports `JourneyConfiguration`, which also enables `ai-engine-sdk` and
component-scans `com.itways.assistant.journey.engine`. The host provides these
beans:

| Port | Required | Used by | What it does |
|---|---|---|---|
| `KnowledgeBasePort` | yes | KNOWLEDGE_RETRIEVAL | Vector search in an account's knowledge index, scoped to the assistant. |
| `TemplateRenderPort` | yes | TEMPLATE_RENDER | Renders a stored template. Throws `TemplateRenderBusyException` when the renderer is busy. |
| `JourneyLookupPort` | yes | TRIGGER_JOURNEY | Finds a journey by trigger intent, or by pinned version id. |
| `AiConfigProvider` | yes | DATA_MAP | The account's AI provider settings. |
| `TextTranslator` | yes for KNOWLEDGE_RETRIEVAL, optional elsewhere | knowledge answers, step text | Machine translation into the run's language. `TextTranslator.NONE` never translates. |
| `MailDeliveryPort` | optional | SEND_MAIL | Delivers mail. Without it, SEND_MAIL fails with "no mail transport". |
| `JourneyRunLifecyclePort` | optional (any number) | engine | Persists run lifecycle events. Must be idempotent on `executionId`. |
| `StepTextPort` | optional | step localization | Caches machine translations of step text. |

## Egress guard (`JOURNEY_API_ALLOWED_HOSTS`)

Tenants write API_CALL URLs, and those URLs can be built from what the end user
typed. `EgressGuard` therefore refuses any URL that is not http(s), or whose
host resolves to a private, loopback, link-local or other internal address. What
counts as internal is the platform's one address rule, `PublicUrlPolicy.isPublic`
in `common-core` (the engine depends on `common-core` only, never on `common-web`
or `common-messaging`); it also treats the documentation ranges (192.0.2/24,
198.51.100/24, 203.0.113/24, 2001:db8::/32) as internal. It
checks every address a name resolves to, not just the first. The connection
then dials only the addresses that were checked, so DNS rebinding does not get
through. Redirects are not followed: a 3xx comes back to the journey as the
response.

Hosts that a deployment wants reachable on its own network go in the operator
allow-list:

| Property | Environment variable | Default |
|---|---|---|
| `journey.api-call.allowed-hosts` | `JOURNEY_API_ALLOWED_HOSTS` (mapped in the host's properties) | empty. Takes exact host names or IP literals, comma-separated. `.corp.example` allows a domain and its subdomains. |
| `journey.api-call.block-private-networks` | | `true` |
| `journey.api-call.connect-timeout-ms` / `read-timeout-ms` | | `5000` / `30000` |

A refusal names the host as the URL wrote it, and never the address it resolved
to.

Other settings: `journey.script.statement-limit` (500000),
`journey.script.timeout-seconds` (10),
`journey.user-input.max-attempts` (3),
`journey.data-map.context-budget-chars` (8000),
`journey.knowledge.synthesis.enabled` (true) and
`journey.knowledge.synthesis.max-chunks` (3).

All of them were under the legacy `nibras.` prefix (`nibras.journey.*`,
`nibras.knowledge.synthesis.*`) up to 1.0.18; since 1.0.19 the old keys are not
read, so a host that set one renames it (the host is conversation-service).
The run parameters the engine lifts out of the variables
(`__nibras_conversation_id`, `__nibras_channel_capabilities`,
`__nibras_user_token`) keep their names: they are stored in run history.

## Build

The parent is `com.itways:platform-parent` 2.1.0 (from `common-lib`). It sets
Java 21, manages the versions (Spring Boot 3.2.2; `ai-engine-sdk` and
`common-core` through `platform-bom`) and runs JaCoCo, surefire and the sources
jar. Only GraalVM JS (`graalvm.js.version`) carries its own version.

Build with JDK 21 (a newer JDK breaks Lombok), after installing `common-lib`
and `ai-engine-sdk` into the same local Maven repository:

```sh
mvn -f ../common-lib/pom.xml install -DskipTests
mvn -f ../ai-engine-sdk/pom.xml install -DskipTests
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn clean install
```

## Tests

`mvn test` (or `mvn clean install`) runs the unit tests. They are plain JUnit 5
and AssertJ tests: no Spring context and no external services.

- `engine/handler/*Test`: one class per step handler. Each builds the handler
  by hand with the fixtures in `HandlerFixtures` and stub ports.
- `engine/impl/JourneyEngineFlowTest` and `JourneyEngineNestedJourneyTest` run
  the real `JourneyEngineImpl` with the real control-flow handlers
  (`EngineFixture`). They cover ordering, branching, jumps, pause and resume,
  failures, lifecycle events and nested journeys.
- `engine/util/*Test` and `engine/validation/*Test` cover the pure utilities
  (egress rules, conditions, step keys, placeholders, variable paths, answer
  validation).
- `journey-model`'s `JourneyStepGraphTest` covers the step graph (order,
  parents, cycles).

API_CALL tests use a local HTTP server on the loopback interface.
`ApiCallDnsRebindingTest` also needs `127.0.0.2`: it is skipped where that
address is not routable (macOS), and it runs in the Linux container.

To run one class: `mvn test -Dtest=UserInputStepHandlerTest -Dsurefire.failIfNoSpecifiedTests=false`
(the flag stops the other module, which has no matching test, from failing the build).
