# journey-engine

The library that runs journeys: an ordered graph of steps (ask the user, call an
API, branch, render a template, send mail, hand off to a person) executed one
conversation turn at a time. It has no database and no HTTP API of its own. The
service that embeds it supplies storage, search, rendering and mail through
ports.

1.4.0 renames the product concept "integration" to "connector" throughout: the
step type `INTEGRATION_CALL` is now `CONNECTOR_CALL`, its config key
`integrationId` is `connectorId`, the error codes `INTEGRATION_*` are
`CONNECTOR_*`, the message keys `step.integration.*` are `step.connector.*`, the
properties `itways.integrations.*` are `itways.connectors.*`, and the
`Integration*` classes are `Connector*`. Behaviour is unchanged.

## Modules

| Module | Artifact | What it is |
|---|---|---|
| `journey-model` | `com.itways.assistant:journey-model` | The journey document and its statuses (`JourneyDefinition`, `JourneyStep`, `RunStatus`, `StepStatus`, `RunStepLog`, `RunHistoryEvent`, …), the step graph (`JourneyStepGraph`: parents, order, cycle check), in `model.catalog` the step catalogue the builder reads (`StepDefinition`, `StepOutputSchema`, `OutputField`, `ChannelVariableSchema`, `ChannelVariableGroup`) and, since 1.1.0, in `model.connector` the connector type descriptor and the resolved connector (`ConnectorDescriptor`, `ConnectorOperation`, `AuthScheme`, `SchemaNode`, `ResolvedConnector`, `ConnectorDescriptorValidator`) and in `model.step` the strictly parsed `ConnectorCallConfig`; since 1.2.0 the MCP fields (`transport: MCP`, `ConnectorDescriptor.McpSettings`, `ConnectorOperation.toolName` / `idempotencyArgument`) and the validator's MCP rules. Plain data, no Spring (Jackson only). The service that stores journeys depends on this and on `connector-transport`. |
| `connector-transport` | `com.itways.assistant:connector-transport` | Since 1.1.0. Calls one operation of a configured connector on the wire: the `ConnectorTransport` SPI, `rest.RestTransport` (HTTPS JSON APIs; auth schemes none / apiKey / basic / bearer / oauth2-client-credentials; pinned DNS, no redirects, per-connector host list, base-URL prefix, response cap, timeouts, retries for idempotent operations), since 1.2.0 `mcp.McpTransport` (a remote MCP server over Streamable HTTP, one tool per operation; see "MCP transport") and `ConnectorTransports` (the host's transports by descriptor `transport` value), `http.*` (what the two transports share), `InputBinder`, `OutputMasker`, `SecretScrubber` and the typed `ConnectorException` with its `ConnectorErrorCodes`. No Spring beans, no Spring MVC: the engine's CONNECTOR_CALL step and journey-service's Test button both wire it themselves, so they behave the same. Pulls in `common-core`, `httpclient5` and, from `common-web`, only its `web.net` classes (the rest is excluded). |
| `journey-engine-sdk` | `com.itways.assistant:journey-engine-sdk` | The engine, the step handlers, the ports and the Spring configuration. Pulls in Spring Boot, FreeMarker, GraalVM JS, `ai-engine-sdk`, `common-core`, `connector-transport` and `resilience4j-circuitbreaker`. |

All three are released together at the version in the parent `pom.xml`.

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
- **Failure.** A step that returns `ERROR` or throws stops the run, unless:
  (1) the step has a child with `branchName` `error`. Then the step's output is
  `FAILED`, `steps.<key>.error` holds `{code, message, retryable, httpStatus?}`
  from the handler's result metadata, and the error child (and its descendants)
  run while the step's other children do not. Or (2) the step has
  `continueOnError`, or the handler's result carries metadata
  `continueOnError: true` (`onError: CONTINUE`). Then `FAILED` becomes the
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

### Error branch (1.1.0)

A step gets an error branch through a child whose `branchName` is `error`. The
builder writes one when CONNECTOR_CALL's config says `onError: BRANCH`, but the
engine keys on the child's branch name, so any step type can gain one.

- The error child runs only when its parent failed (the engine recorded `FAILED`).
- Under a parent that has an error child, children with no branch name or named
  `success` run only when the parent did not fail. Parents without an error
  child behave exactly as before.
- On failure the engine writes `steps.<key>.output = "FAILED"` and
  `steps.<key>.error = {code, message, retryable, httpStatus}` (`httpStatus` only
  when the handler set it), so the fallback can read `{{steps.<key>.error.code}}`.
- A backward JUMP clears the failure marker and the `error` field with the other
  step outputs, so a replayed step that succeeds takes the success path.
- `WAITING` is not a failure: a parked step never opens its error branch.
- The step catalogue flags the types that support it with `supportsErrorBranch`.

## Step handlers

| Handler | Step type | Purpose |
|---|---|---|
| `ApiCallStepHandler` | `API_CALL` | Calls an HTTP API. Placeholders are resolved in the URL, headers and body, and body values keep their types. The URL must pass the egress guard. `{{auth.userToken}}` is available in headers only. Frozen and deprecated since 1.1.0 (`replacedBy: CONNECTOR_CALL` in the catalogue); unchanged. |
| `ConnectorCallStepHandler` | `CONNECTOR_CALL` | Calls one operation of a configured connector, resolved through `ConnectorPort` and called through the connector-transport transport the descriptor names (`ConnectorTransports`: `RestTransport` for `REST`, `McpTransport` for `MCP`; a transport the host does not serve fails the step `CONNECTOR_CONFIG_INVALID`): inputs mapped with placeholders and validated against the operation's input schema, retries only for idempotent operations, one circuit breaker per connector, a time budget (`timeoutMs` × 1.5), an idempotency key on writes (`executionId:stepKey`, re-sent on a JUMP replay), sensitive outputs masked before they are stored, and an error branch (see "Error branch"). |
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
| `util` | only `VariablePath` | Placeholders, variable paths, egress rules, schema helpers, the per-connector circuit breakers. |
| `config`, `impl`, `handler`, `validation` | no | Spring wiring, the run loop, the step handlers (and the engine's own FreeMarker `TemplateRender`), answer validation. |

`connector-transport`'s package `com.itways.assistant.journey.connector` (and
`connector.rest`, `connector.mcp`) is host-facing in full: journey-service
constructs a `RestTransport` and an `McpTransport` (and an
`ConnectorTransports` over them) for its Test and Try endpoints, calls
`McpTransport.discover` for its MCP import, and uses `InputBinder`,
`OutputMasker` and `SecretScrubber` directly. `connector.http` is the plumbing
the two transports share; public for that reason only, it may change between
minor versions.

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
| `ConnectorPort` | yes for CONNECTOR_CALL | CONNECTOR_CALL | Resolves a configured connector (its descriptor, base URL, allow-list, opened secrets, kept in memory for the call only; cached at most 60 s by id and lockVersion, dropped on `invalidate`, which the step calls when the connector's breaker opens) and describes an operation for the variable picker. |
| `MailDeliveryPort` | optional | SEND_MAIL | Delivers mail. Without it, SEND_MAIL fails with "no mail transport". |
| `JourneyRunLifecyclePort` | optional (any number) | engine | Persists run lifecycle events. Must be idempotent on `executionId`. |
| `StepTextPort` | optional | step localization | Caches machine translations of step text. |

## Egress guard (`JOURNEY_API_ALLOWED_HOSTS`)

Tenants write API_CALL URLs, and those URLs can be built from what the end user
typed. `EgressGuard` therefore refuses any URL that is not http(s), or whose
host resolves to a private, loopback, link-local or other internal address. What
counts as internal is the platform's one address rule, `PublicUrlPolicy.isPublic`
in `common-core` (the engine depends on `common-core` and, through
`connector-transport`, on `common-web`'s `web.net` classes only, never on the
rest of `common-web` or on `common-messaging`); it also treats the documentation ranges (192.0.2/24,
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

## Connector egress (`itways.connectors.*`, 1.1.0)

CONNECTOR_CALL does not use `EgressGuard`. Its calls go through
`connector-transport`'s `RestTransport`, whose rules are per connector and
stricter than API_CALL's:

- The base URL comes from the connector (journey-service checked it with
  `PublicUrlPolicy` when it was saved), never from the journey: `https` only
  (`http` only where `itways.connectors.egress.allow-http` is true, for local
  mocks), a host, no user name, no query. An operation's path is relative and
  can never change the scheme, host, port or leave the base path: path
  parameters are percent-encoded, the final URL is normalised and compared
  with the base, and `..`, `@`, `//`, a query or a fragment smuggled through a
  value are refused (`CONNECTOR_EGRESS_REFUSED`).
- The host must be on the connector's own allow-list (`allowedHosts`; by
  default the base URL's host) and, when the operator set one, on the
  platform-wide bound `itways.connectors.egress.allowed-hosts` (empty = any
  public host). Both follow `HostAllowList`: exact names, IP literals,
  `.domain` suffixes.
- Names are resolved once through a pinned resolver that refuses private,
  loopback, link-local and other internal addresses, and the connection dials
  exactly those addresses (no DNS rebinding). Hosts that may resolve privately
  anyway (a mock bank on `.test` in development) go in
  `itways.connectors.egress.private-hosts`; empty in production.
- Redirects are never followed (a 3xx is `CONNECTOR_REJECTED`); TLS is the
  JDK default (system trust store, host name verified); response bodies are
  capped at `itways.connectors.max-response-bytes` (1 MiB).
- Credentials are applied by the transport from the resolved connector's
  secrets, into headers (or, for an `apiKey` type that declares `in: query`,
  the query string); they are never variables, never in the run context, run
  history or a log. Logs carry the host and the path *template* only.

| Property | Default | Meaning |
|---|---|---|
| `itways.connectors.egress.allowed-hosts` | empty | Platform-wide bound: when set, a connector's host must also be on it. |
| `itways.connectors.egress.private-hosts` | empty | Hosts allowed to resolve to private addresses (development and tests only). |
| `itways.connectors.egress.allow-http` | `false` | Whether `http://` base URLs may be dialled (development and tests only). |
| `itways.connectors.max-response-bytes` | `1048576` | Response body cap. |

Circuit breakers are per connector (`ConnectorCircuitBreakers`, the ADR 0010
numbers: half of the last 20 calls failed, at least 10; open 15 s; 3 half-open
trials). Only "the system is not there" counts as a failure: no answer, a
timeout, 429, 502, 503, 504. A 4xx is the system speaking and does not open the
breaker. While open, the step fails at once with `CONNECTOR_CIRCUIT_OPEN`
and nothing is sent.

## MCP transport (1.2.0)

A connector type with `transport: MCP` describes a remote [Model Context
Protocol](https://modelcontextprotocol.io/specification/) server over its
Streamable HTTP transport. Each operation is one tool of that server, called
deterministically by the journey (`tools/call`): no model chooses a tool, and
nothing the server says — a tool's description, its annotations, a result —
is ever shown to one. The connector's base URL is the server's endpoint
(`https://host/mcp`); the engine wires `McpTransport` next to `RestTransport`
in `ConnectorCallConfiguration` under the same `itways.connectors.*`
egress settings.

Descriptor, over the REST shape: `transport: "MCP"`; `method` and `path` are
not used (the validator refuses them); `toolName` names the server's tool
(the operation key when absent); every input property is a tool argument
(`in` must be absent or `body`; `as` renames it on the wire); `idempotent`
must be declared (`true` or `false` — a tool call is never assumed safe to
repeat; discovered tools start as `false` unless the server marks them
read-only); a write that can take
the key names the argument in `idempotencyArgument` (MCP has no idempotency
header; `idempotencyHeader` and the type's `idempotency.header` are refused);
`auth.scheme` is `none`, `apiKey` (`in: header` only), `bearer` or
`oauth2-client-credentials` — a credential travels in a header, never in the
URL; `basic` is refused. Optional `mcp.protocolVersion` pins the specification
revision (below); absent, the transport negotiates.

Specification revisions, verified against modelcontextprotocol.io on
2026-10-04 (`/specification/2026-07-28/basic/transports/streamable-http`,
`/specification/2025-11-25/basic/transports`, `.../basic/lifecycle`,
`.../server/tools`, `/specification/2026-07-28/changelog`):

- **2026-07-28** (current): stateless. One POST per request with
  `Accept: application/json, text/event-stream`, `MCP-Protocol-Version`,
  `Mcp-Method` and (for `tools/call`) `Mcp-Name`; `params._meta` carries
  `io.modelcontextprotocol/protocolVersion`, `clientCapabilities` and
  `clientInfo`. The answer is a JSON object or an SSE stream that carries the
  response. No `initialize`, no `ping`, no session. `-32022` unsupported
  version (with `data.supported`), `-32020` header mismatch, `-32021` missing
  capability, `-32601` unknown method (HTTP 404), `-32602` unknown tool;
  results carry `resultType` (`complete` or `input_required`).
- **2025-11-25, 2025-06-18, 2025-03-26** (legacy): `initialize` first (the
  server answers with the revision it speaks and may assign `Mcp-Session-Id`),
  then `notifications/initialized` (202), then requests with
  `MCP-Protocol-Version` and the session id; a 404 means the session expired
  and the transport initializes again, once.

Negotiation: with no pinned revision the current one is tried first; a server
that refuses it the way a legacy server does (`-32022`, a `-32000..-32019`
"not initialized" / "unsupported version" error, a bare 400) gets the legacy
handshake, and the outcome is remembered per connector (id and lock
version). A pinned revision is never negotiated away; a server that speaks no
revision the transport knows fails `CONNECTOR_UNAVAILABLE`, not retryable.

Error mapping: a result with `isError: true` is `CONNECTOR_REJECTED` (the
tool failed in its own terms: not retried, not a breaker failure); `-32601` /
`-32602` are `CONNECTOR_CONFIG_INVALID`; an unsupported revision is
`CONNECTOR_UNAVAILABLE` not retryable; 401 / 403 `CONNECTOR_AUTH_FAILED`;
3xx `CONNECTOR_REJECTED` (never followed); 429 / 502 / 503 / 504 and no
answer `CONNECTOR_UNAVAILABLE` / `CONNECTOR_TIMEOUT`, retried only when
the operation declares `idempotent: true` or the call carries a key the tool
takes; other 5xx `CONNECTOR_UNAVAILABLE` not retried; an answer beyond the
cap, a stream without the response or a result that is not an object
`CONNECTOR_RESPONSE_INVALID`; `resultType: input_required` (MRTR)
`CONNECTOR_REJECTED`. The breaker counts `UNAVAILABLE` and `TIMEOUT` only, as
for REST.

Output: `structuredContent` when the tool returned it; else the first `text`
content block, parsed as a JSON object when it is one, otherwise
`{"text": "..."}`; image, audio and resource blocks are dropped (DEBUG). The
step masks and limits it by the operation's output schema exactly as for REST.

Limits: the response cap (`itways.connectors.max-response-bytes`) applies to
a JSON body and to a whole SSE stream; a stream is read for at most 256 events
and is aborted once the response arrived (a server that keeps it open costs
nothing more); `McpTransport.discover` (journey-service's import) reads at most
20 pages and 200 tools and leaves out a schema over 64 KiB (the tool is
marked). Logs carry the connector id, operation key, tool name, host,
endpoint path and revision — never an argument, a result, a credential or a
session id. `test()` runs the declared `test.operation` if any, else
`tools/list`, every page within the same caps (`{"toolCount": n, "tools": [...]}`).

Not supported, by design: stdio or any local process; the agent loop in which a
model picks tools (mode B); resources and prompts; elicitation and
`input_required` results; OAuth authorization-code flows (a static header
credential or client credentials only); the server-initiated GET stream;
sessions ended with DELETE; resumption with `Last-Event-ID`; `x-mcp-header`
parameters.

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

CONNECTOR_CALL's own settings are in the Connector egress section above.

All of them were under the legacy `nibras.` prefix (`nibras.journey.*`,
`nibras.knowledge.synthesis.*`) up to 1.0.18; since 1.0.19 the old keys are not
read, so a host that set one renames it (the host is conversation-service).
The run parameters the engine lifts out of the variables
(`__nibras_conversation_id`, `__nibras_channel_capabilities`,
`__nibras_user_token`) keep their names: they are stored in run history.

## Build

The parent is `com.itways:platform-parent` 2.3.0 (from `common-lib`). It sets
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
  parents, cycles); `connector/*Test` the descriptor validator (REST and MCP
  rules) and its JSON shape; `step/ConnectorCallConfigTest` the strict step
  config parser.
- `connector-transport`'s `rest/RestTransportTest` runs `RestTransport` against
  a loopback HTTP server (allowed only through `EgressPolicy.privateHosts` and
  `allowHttp`, exactly as a deployment would allow a local mock): auth schemes,
  retries and back-off, idempotency keys, status mapping, redirects refused,
  base-URL escapes refused, private addresses refused, response cap, and that
  no secret, query string or rendered path reaches a log line or a message.
  `mcp/McpTransportTest` runs `McpTransport` against `FakeMcpServer` on the same
  loopback server, in the current stateless revision and the legacy handshake
  (with and without sessions, expiry, pinned revisions, `data.supported`
  fallback, unknown revisions), as JSON and as SSE (notification first, a
  stream kept open, oversized, without a response), tool results of every
  shape, `isError`, `input_required`, JSON-RPC errors, 401/403, 5xx, timeouts,
  retries and the idempotency argument, redirects, metadata addresses, OAuth2
  refresh, `test()`, `discover()` with pagination and caps, the registry, and
  that no credential, argument, result or session id reaches a log line.
  `ArchitectureTest` holds the module to no Spring and no `common-web` beyond
  `web.net`.
- `engine/impl/JourneyEngineErrorBranchTest` covers the error branch (1.1.0)
  and proves the old failure, `continueOnError`, JUMP, CONDITION and SWITCH
  paths are unchanged; `engine/handler/ConnectorCallStepHandlerTest` runs the
  CONNECTOR_CALL handler with fake transports (REST and MCP, by kind) and a
  fake `ConnectorPort`.

API_CALL tests use a local HTTP server on the loopback interface.
`ApiCallDnsRebindingTest` also needs `127.0.0.2`: it is skipped where that
address is not routable (macOS), and it runs in the Linux container.

To run one class: `mvn test -Dtest=UserInputStepHandlerTest -Dsurefire.failIfNoSpecifiedTests=false`
(the flag stops the other module, which has no matching test, from failing the build).
