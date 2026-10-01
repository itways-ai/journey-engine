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
| `KnowledgeRetrievalStepHandler` | `KNOWLEDGE_RETRIEVAL` | Embeds the query and searches the assistant's knowledge index through `KnowledgeBasePort`, with the step's `threshold`, `limit` and `diversity`. Answers with the single best entry, or with a composed answer, and cites the passages in `sources`. A miss is reported as a knowledge gap, unless the step's `recordGaps` is false. See "Knowledge retrieval" below. |
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

### Knowledge retrieval (1.0.20)

The builder's catalog labels the step "Knowledge answer", as the portal does;
its type stays `KNOWLEDGE_RETRIEVAL`, which journeys store.

Step config (`apiConfig`):

| Key | Range | Default | Meaning |
|---|---|---|---|
| `indexName` | | required unless `indexNames` | The index, resolved in the run's assistant scope. |
| `indexNames` | list of names | none | Several indexes searched by one step and merged into one answer (see Several indexes). When it names any, it wins over `indexName`; one name is exactly `indexName`. |
| `query` | | `{{inputs.text}}` | The question, with placeholders. |
| `threshold` | 0.30..0.95 (clamped) | 0.38 | The cosine similarity a passage needs (a "sure match"). Ignored before 1.0.20. |
| `limit` | 1..20 (clamped) | 5 | At most this many passages are served and cited. A composing step asks for `min(20, max(limit, 2 × max-chunks))` instead (see Composing). |
| `diversity` | 0..1 (clamped) | journey-service's (0.3) | MMR spread over different passages, applied by journey-service. |
| `answerMode` | `SINGLE`, `COMPOSE`, `AUTO` | `AUTO` | Whether several passages may be composed into one answer. |
| `recallThreshold` | 0.30..0.95 (clamped), never above `threshold` | 0.34 | A composing step's search floor: the similarity a passage needs to be shown to the model. `SINGLE` ignores it. |
| `recordGaps` | `true`, `false` | `true` | Whether a miss is reported as a knowledge gap. Only an explicit `false` turns it off. |

The defaults follow the knowledge base's own embedding model
(`snowflake-arctic-embed2` at 768 dimensions, the embedding switch; see
conversation-service), whose cosine scores run much lower than
`granite-embedding:278m`'s: `threshold` 0.38 (was 0.70), `recallThreshold` 0.34
(was 0.55) and `journey.knowledge.recall.arabic-offset` 0 (was 0.05), as
journey-service's `journey.knowledge.search.arabic-vector-offset`. A step that
saved its own `threshold` or `recallThreshold` keeps it: tuned for granite, it
is now too strict, so re-check such steps after the switch.

The step calls `KnowledgeBasePort.search(KnowledgeQuery)` with the query text,
the vector, the clamped threshold, limit and diversity. journey-service gates
the hits and says why it served each one (`EngineSearchResult.reason`:
`VECTOR` or `LEXICAL_CORROBORATED`, and to a recall search also
`LEXICAL_RECALL`). Only those two reasons are served; `LEXICAL_RECALL` is a
candidate for a composing step's model and never served as stored; any other
reason is a drop, whatever the score. A hit without a reason (an adapter
or a journey-service from before 1.0.20) must reach the threshold in the step. The
port's order is kept (it is the MMR order).

- **Hit.** `steps.<order>.output` is the answer, `found` is true, `sources` is
  one citation per served passage and `composed` says whether they were merged.
  The step view's metadata carries `found`, `composed`, `sourceCount`,
  `sources`, `indexName` and `threshold`. A citation has `id`, `indexName`,
  `question`, `sourceId`, `sourceName`, `sourceKind`, `rowNumber`, `url`,
  `vectorScore`, `lexicalScore`, `fusedScore` and `reason`; fields
  journey-service did not send are left out.
- **Composing** (`COMPOSE`, or `AUTO` while `journey.knowledge.synthesis.enabled`).
  The step searches on its `recallThreshold` as a recall search
  (`KnowledgeQuery.recall`: journey-service applies the floor as sent, with no
  Arabic offset), asking for `min(20, max(limit, 2 × max-chunks))` hits so that
  journey-service's limit and MMR cannot cut a strong vector match, and the
  model is the precision filter. It is shown at most
  `journey.knowledge.synthesis.max-chunks` (8) candidates: the
  `journey.knowledge.synthesis.vector-first` (4) best by vector similarity, then
  the rest in the port's (fused, diversified) order, each passage once. Those
  (only those, in that order) are cited. `LEXICAL_RECALL` hits (below the floor,
  on strong full-text evidence) are among the candidates, for the model only.
  The model is told to answer in the question's language (an Arabic system
  instruction for an Arabic question) and to reply
  exactly `NO_ANSWER` when the passages do not answer; that reply (in any case,
  wrapped in quotes or punctuation, or leading or closing the reply), and while
  `journey.knowledge.synthesis.abstention-phrases` is on a short "the sources do
  not contain…" / "لا تحتوي المصادر…", is a miss with `missReason`
  `MODEL_ABSTAINED`. A single candidate that reaches `threshold` (plus
  `journey.knowledge.recall.arabic-offset` for an Arabic run) is served as
  stored without the model, unless it is a `LEXICAL_RECALL` hit. With no model
  to ask (none configured, including an account with no AI provider set up:
  `AI_PROVIDER_NOT_CONFIGURED` from the config provider) the candidates that
  reach `threshold` are served as stored; none is a `BELOW_THRESHOLD` miss. Metadata adds `recallThreshold`.
- **Generation failed** (the model was asked and failed: a provider error, an
  exception, an empty reply). The best candidate by similarity is served as
  stored, cited alone, only if it reaches
  `journey.knowledge.synthesis.sure-match-threshold` (0.85, clamped 0.30..0.95;
  never below the step's `threshold`, plus the Arabic offset for an Arabic run),
  is not a `LEXICAL_RECALL` hit, and is a near-identical stored question (reason
  `VECTOR` with `vectorMatch` `QUESTION`, or a `questionScore` at the bar). Otherwise the reply is
  `step.knowledge.unavailable` ("I cannot answer that right now. Please try
  again in a moment."), `found` false, `sources` empty, `missReason`
  `GENERATION_FAILED` with `candidateCount`, and no gap is ever recorded (the
  answer may well be there). The run is marked (an engine internal keyed to the
  question's fingerprint), and a later knowledge step's miss of the same
  question replies with that text too; it is still a miss but never a gap,
  whatever its own miss reason or `recordGaps`.
- **Miss** (nothing came back, nothing reached the threshold, or the model
  abstained). The "no answer" text, `found` false, `sources` empty, and
  `KnowledgeBasePort.recordMiss(KnowledgeMiss)` with the question, its vector,
  the best score and passage, the channel type and the run. Not called in a
  simulated run, nor when the step's `recordGaps` is false. Metadata adds
  `missReason` (`NO_RESULTS`, `BELOW_THRESHOLD` or `MODEL_ABSTAINED`, with
  `candidateCount`; `GENERATION_FAILED` above), `bestScore` and `gapReported`
  (false when no gap was recorded). They look the same to the journey, so a
  chain goes on to its next step whichever it was.
- **Several indexes** (`indexNames`). Each index is searched through the port
  (`KnowledgeBasePort.searchEach`: one `search(KnowledgeQuery)` per index, with
  the same query; the default runs them in turn, conversation-service's adapter
  in parallel). The hits are merged (one embedding model, so the scores compare),
  each tagged with its index, interleaved by rank (each index's first hit, then
  each one's second, ...) and de-duplicated (by id, and by the same text in two
  indexes, keeping the better copy). A composing step then picks its candidates
  as above (the best 4 by similarity, then the rest, up to 8) and makes one model
  call; `SINGLE` serves the best hits across the indexes by similarity.
  Citations keep each passage's `indexName`. A miss records one gap naming every
  index searched (`KnowledgeMiss.indexNames`; `indexName` is the first). An
  index that does not exist is skipped (metadata `missingIndexNames` and
  `warnings`); the step fails with `KNOWLEDGE_INDEX_MISSING` only when all are
  missing. A search that fails otherwise is skipped too (`failedIndexNames`),
  and then a miss records no gap (that index may hold the answer); all failing
  is a search failure. Metadata always carries `indexName` (the first searched)
  and `indexNames` (those searched).
- **Chained steps.** One step with `indexNames` replaces a chain of knowledge
  steps (an FAQ, then guides, then web pages): one search round and one model
  call instead of up to three. A journey that still chains them sets
  `recordGaps: false` on every one but the last. A question a later step
  answers is then not a gap, and a question none answers is recorded once, by
  the last step.
- **Missing index.** The port throws `KnowledgeIndexMissingException` (journey
  404 `INDEX_NOT_FOUND`): the step fails with `KNOWLEDGE_INDEX_MISSING`
  (metadata `errorCode: INDEX_NOT_FOUND`) and records no gap.
- **Search failure.** The step fails with a generic message; the detail stays
  in the log.

The question is personal data: INFO logs carry its length and the first eight
hex digits of its SHA-256, the text only DEBUG.

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
| `KnowledgeBasePort` | yes | KNOWLEDGE_RETRIEVAL | Search in an account's knowledge index, scoped to the assistant. Since 1.0.20 `search(KnowledgeQuery)` (text, threshold, limit, diversity) and `recordMiss(KnowledgeMiss)` are default methods: a 1.0.19 adapter compiles and behaves as before (positional search, no gaps). A 1.0.20 adapter overrides both and throws `KnowledgeIndexMissingException` for a missing index. |
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
`journey.knowledge.synthesis.enabled` (true),
`journey.knowledge.synthesis.max-chunks` (8; was 5, and 3 before),
`journey.knowledge.synthesis.vector-first` (4),
`journey.knowledge.synthesis.sure-match-threshold` (0.85; was 0.60),
`journey.knowledge.synthesis.abstention-phrases` (true) and
`journey.knowledge.recall.arabic-offset` (0; was 0.05, see below).

All of them were under the legacy `nibras.` prefix (`nibras.journey.*`,
`nibras.knowledge.synthesis.*`) up to 1.0.18; since 1.0.19 the old keys are not
read, so a host that set one renames it (the host is conversation-service).
The run parameters the engine lifts out of the variables
(`__nibras_conversation_id`, `__nibras_channel_capabilities`,
`__nibras_user_token`) keep their names: they are stored in run history.

## Build

The parent is `com.itways:platform-parent` 2.2.0 (from `common-lib`). It sets
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
