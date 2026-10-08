package com.itways.assistant.journey.model.connector;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules a connector type descriptor must meet before it is stored or run.
 * Pure: the same list of problems at save (journey-service, which adds its JSON
 * Schema check in front of this) and in tests of the transport.
 *
 * <p>
 * Each problem is one sentence with a JSON-pointer-like location
 * ({@code operations[stopCard].path}), so the portal can show it next to the
 * field. An empty list means the descriptor is acceptable.
 */
public final class ConnectorDescriptorValidator {

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");
    /**
     * Letters, digits, underscores and dashes, starting with a letter; since
     * 1.3.0 also dot-separated parts of that shape ({@code t24.getUser}), so an
     * importer can namespace keys by system. At most 128 characters.
     */
    private static final Pattern OPERATION_KEY = Pattern
            .compile("^(?=.{1,128}$)[A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z][A-Za-z0-9_-]*)*$");
    private static final String OPERATION_KEY_RULE = ".key: must be letters, digits, underscores and dashes, starting with a letter (dot-separated parts allowed, at most 128 characters)";
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^{}/]+)}");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> SCHEMES = Set.of(AuthScheme.SCHEME_NONE, AuthScheme.SCHEME_API_KEY,
            AuthScheme.SCHEME_BASIC, AuthScheme.SCHEME_BEARER, AuthScheme.SCHEME_OAUTH2_CLIENT_CREDENTIALS);
    private static final Set<String> PLACEMENTS = Set.of(SchemaNode.IN_PATH, SchemaNode.IN_QUERY,
            SchemaNode.IN_HEADER, SchemaNode.IN_BODY);
    /** Header names that carry a credential; a default header with one of these is a secret in the descriptor. */
    private static final Pattern CREDENTIAL_HEADER = Pattern.compile("(?i)^(authorization|proxy-authorization|cookie)$|(?i)(-key|-token|-secret|apikey|api_key)$");

    private ConnectorDescriptorValidator() {
    }

    /** The problems with {@code descriptor}; empty when it is acceptable. */
    public static List<String> problems(ConnectorDescriptor descriptor) {
        List<String> problems = new ArrayList<>();
        if (descriptor == null) {
            problems.add("descriptor: missing");
            return problems;
        }
        if (descriptor.key() == null || !KEY.matcher(descriptor.key()).matches()) {
            problems.add("key: must be lower-case letters, digits and dashes, starting with a letter");
        }
        if (isBlank(descriptor.name())) {
            problems.add("name: required");
        }
        boolean mcp = descriptor.speaksMcp();
        if (!ConnectorDescriptor.TRANSPORT_REST.equals(descriptor.transport()) && !mcp) {
            problems.add("transport: must be REST or MCP");
        }
        if (descriptor.descriptorVersion() == null || descriptor.descriptorVersion() < 1) {
            problems.add("descriptorVersion: must be 1 or more");
        }
        if (mcp) {
            mcpProblems(descriptor, problems);
        } else if (descriptor.mcp() != null && descriptor.mcp().protocolVersion() != null) {
            problems.add("mcp.protocolVersion: not used by the REST transport");
        }

        Set<String> fieldNames = new HashSet<>();
        Set<String> secretFields = new HashSet<>();
        int index = 0;
        for (Map<String, Object> field : descriptor.fieldsOrEmpty()) {
            Object name = field.get("name");
            if (name == null || isBlank(String.valueOf(name))) {
                problems.add("fields[" + index + "].name: required");
            } else if (!fieldNames.add(String.valueOf(name))) {
                problems.add("fields[" + index + "].name: duplicate field '" + name + "'");
            } else if (ConnectorDescriptor.FIELD_TYPE_SECRET.equals(field.get("type"))) {
                secretFields.add(String.valueOf(name));
            }
            index++;
        }

        authProblems(descriptor.authOrNone(), fieldNames, secretFields, problems);
        if (mcp) {
            mcpAuthProblems(descriptor.authOrNone(), problems);
        }

        for (Map.Entry<String, String> header : descriptor.defaultsOrNone().headersOrEmpty().entrySet()) {
            if (header.getKey() != null && CREDENTIAL_HEADER.matcher(header.getKey().trim()).find()) {
                problems.add("defaults.headers[" + header.getKey()
                        + "]: looks like a credential; use a secret field and the auth scheme instead");
            }
        }

        Set<String> systemKeys = systemProblems(descriptor, mcp, problems);

        if (descriptor.operationsOrEmpty().isEmpty()) {
            problems.add("operations: at least one operation is required");
        }
        Set<String> operationKeys = new HashSet<>();
        for (ConnectorOperation operation : descriptor.operationsOrEmpty()) {
            if (mcp) {
                mcpOperationProblems(operation, operationKeys, problems);
            } else {
                operationProblems(descriptor, operation, operationKeys, secretFields, problems);
            }
            operationSystemProblems(operation, systemKeys, problems);
        }

        if (descriptor.test() != null && descriptor.test().operation() != null) {
            String testKey = descriptor.test().operation();
            descriptor.operation(testKey).ifPresentOrElse(op -> {
                if (!op.isIdempotent()) {
                    problems.add("test.operation: '" + testKey + "' must be idempotent");
                }
            }, () -> problems.add("test.operation: no operation '" + testKey + "'"));
        }
        return problems;
    }

    /** Whether {@code descriptor} is acceptable. */
    public static boolean isValid(ConnectorDescriptor descriptor) {
        return problems(descriptor).isEmpty();
    }

    private static void authProblems(AuthScheme auth, Set<String> fields, Set<String> secretFields,
            List<String> problems) {
        String scheme = auth.schemeOrNone();
        if (!SCHEMES.contains(scheme)) {
            problems.add("auth.scheme: unknown scheme '" + scheme + "'");
            return;
        }
        switch (scheme) {
            case AuthScheme.SCHEME_API_KEY -> {
                if (isBlank(auth.name())) {
                    problems.add("auth.name: required for apiKey");
                }
                if (auth.in() != null && !AuthScheme.IN_HEADER.equalsIgnoreCase(auth.in())
                        && !AuthScheme.IN_QUERY.equalsIgnoreCase(auth.in())) {
                    problems.add("auth.in: must be header or query");
                }
                requireSecretField("auth.secretField", auth.secretField(), secretFields, problems);
            }
            case AuthScheme.SCHEME_BEARER -> requireSecretField("auth.secretField", auth.secretField(),
                    secretFields, problems);
            case AuthScheme.SCHEME_BASIC -> {
                requireField("auth.usernameField", auth.usernameField(), fields, problems);
                requireSecretField("auth.passwordField", auth.passwordField(), secretFields, problems);
            }
            case AuthScheme.SCHEME_OAUTH2_CLIENT_CREDENTIALS -> {
                if (isBlank(auth.tokenUrl())) {
                    problems.add("auth.tokenUrl: required for oauth2-client-credentials");
                } else if (!auth.tokenUrl().toLowerCase(Locale.ROOT).startsWith("https://")) {
                    problems.add("auth.tokenUrl: must use https");
                }
                requireField("auth.clientIdField", auth.clientIdField(), fields, problems);
                requireSecretField("auth.clientSecretField", auth.clientSecretField(), secretFields, problems);
                if (auth.scopeField() != null && !fields.contains(auth.scopeField())) {
                    problems.add("auth.scopeField: no field '" + auth.scopeField() + "'");
                }
            }
            default -> {
                // none: nothing to check
            }
        }
    }

    private static void operationProblems(ConnectorDescriptor descriptor, ConnectorOperation op,
            Set<String> keys, Set<String> secretFields, List<String> problems) {
        String where = "operations[" + (op.key() != null ? op.key() : "?") + "]";
        if (op.key() == null || !OPERATION_KEY.matcher(op.key()).matches()) {
            problems.add(where + OPERATION_KEY_RULE);
        } else if (!keys.add(op.key())) {
            problems.add(where + ".key: duplicate operation key");
        }
        if (!METHODS.contains(op.methodOrGet())) {
            problems.add(where + ".method: must be one of " + METHODS);
        }
        if (op.riskLevel() == null) {
            problems.add(where + ".riskLevel: required (LOW, MEDIUM or HIGH)");
        }
        if (op.output() == null) {
            problems.add(where + ".output: required (the output schema; use an empty object when nothing is read)");
        }
        if (op.input() != null && !"object".equals(op.input().typeOrObject())) {
            problems.add(where + ".input.type: must be object");
        }
        if (op.output() != null && !"object".equals(op.output().typeOrObject())) {
            problems.add(where + ".output.type: must be object");
        }

        Map<String, SchemaNode> inputs = op.inputOrEmpty().propertiesOrEmpty();
        String path = op.path();
        if (isBlank(path) || !path.startsWith("/")) {
            problems.add(where + ".path: required and must start with /");
        } else {
            if (path.contains("://") || path.startsWith("//") || path.contains("..") || path.contains("@")
                    || path.contains("?") || path.contains("#")) {
                problems.add(where + ".path: must be a relative path under the base URL (no scheme, host, '..', '@', query or fragment)");
            }
            Matcher params = PATH_PARAM.matcher(path);
            while (params.find()) {
                String param = params.group(1);
                SchemaNode node = inputs.get(param);
                if (node == null) {
                    problems.add(where + ".path: parameter {" + param + "} has no input property");
                } else if (!SchemaNode.IN_PATH.equals(node.placement())) {
                    problems.add(where + ".input.properties[" + param + "].in: must be path (it is a path parameter)");
                } else if (!op.inputOrEmpty().requiredOrEmpty().contains(param)) {
                    problems.add(where + ".input.required: must list path parameter '" + param + "'");
                }
                if (secretFields.contains(param)) {
                    problems.add(where + ".path: must not reference secret field '" + param + "'");
                }
            }
        }

        for (Map.Entry<String, SchemaNode> input : inputs.entrySet()) {
            String name = input.getKey();
            SchemaNode node = input.getValue();
            String at = where + ".input.properties[" + name + "]";
            if (node == null) {
                problems.add(at + ": missing schema");
                continue;
            }
            if (!PLACEMENTS.contains(node.placement())) {
                problems.add(at + ".in: must be path, query, header or body");
            }
            if (SchemaNode.IN_PATH.equals(node.placement()) && (path == null || !path.contains("{" + name + "}"))) {
                problems.add(at + ".in: path, but the path has no {" + name + "}");
            }
            if (secretFields.contains(name)
                    && (SchemaNode.IN_QUERY.equals(node.placement()) || SchemaNode.IN_PATH.equals(node.placement()))) {
                problems.add(at + ": a secret field may not be sent in the path or query");
            }
            if (node.template() != null && !node.template().contains("{value}")) {
                problems.add(at + ".template: must contain {value}");
            }
            if (node.pattern() != null) {
                try {
                    Pattern.compile(node.pattern());
                } catch (Exception e) {
                    problems.add(at + ".pattern: not a valid regular expression");
                }
            }
        }
        for (String required : op.inputOrEmpty().requiredOrEmpty()) {
            if (!inputs.containsKey(required)) {
                problems.add(where + ".input.required: '" + required + "' is not an input property");
            }
        }

        if (!op.isIdempotent() && descriptor.idempotencyHeaderFor(op) == null) {
            problems.add(where + ".idempotencyHeader: a non-idempotent operation must declare how replays are de-duplicated (its own idempotencyHeader or the type's idempotency.header)");
        }
        for (String status : op.errorsOrEmpty().keySet()) {
            if (status == null || !status.matches("^[1-5][0-9]{2}$")) {
                problems.add(where + ".errors[" + status + "]: must be an HTTP status code");
            }
        }
        timeoutProblems(op, where, problems);
        if (!isBlank(op.toolName())) {
            problems.add(where + ".toolName: not used by the REST transport");
        }
        if (!isBlank(op.idempotencyArgument())) {
            problems.add(where + ".idempotencyArgument: not used by the REST transport (declare idempotencyHeader)");
        }
    }

    // ---- systems (1.3.0) -----------------------------------------------------------------------------

    /** Checks {@code descriptor.systems} and returns the declared keys, for the operations to reference. */
    private static Set<String> systemProblems(ConnectorDescriptor descriptor, boolean mcp, List<String> problems) {
        Set<String> keys = new HashSet<>();
        int index = 0;
        for (ConnectorSystem system : descriptor.systemsOrEmpty()) {
            String where = "systems[" + (system != null && system.key() != null ? system.key() : index) + "]";
            index++;
            if (system == null) {
                problems.add(where + ": missing");
                continue;
            }
            if (system.key() == null || !ConnectorSystem.KEY.matcher(system.key()).matches()) {
                problems.add(where + ".key: must be letters, digits, underscores and dashes, starting with a letter (at most 64 characters)");
            } else if (!keys.add(system.key())) {
                problems.add(where + ".key: duplicate system key");
            }
            if (isBlank(system.name())) {
                problems.add(where + ".name: required");
            }
            if (mcp) {
                if (system.pathPrefix() != null && !system.pathPrefix().isEmpty()) {
                    problems.add(where + ".pathPrefix: not used by the MCP transport (the base URL is the server's endpoint)");
                }
                if (!system.headersOrEmpty().isEmpty()) {
                    problems.add(where + ".headers: not used by the MCP transport");
                }
                continue;
            }
            if (!ConnectorSystem.isSafePathPrefix(system.pathPrefix())) {
                problems.add(where + ".pathPrefix: must be /segment[/segment...] of letters, digits and - . _ ~ (no scheme, host, '..', '@', '%', query or fragment)");
            }
            for (Map.Entry<String, String> header : system.headersOrEmpty().entrySet()) {
                if (isBlank(header.getKey())) {
                    problems.add(where + ".headers: a header needs a name");
                } else if (CREDENTIAL_HEADER.matcher(header.getKey().trim()).find()) {
                    problems.add(where + ".headers[" + header.getKey()
                            + "]: looks like a credential; use a secret field and the auth scheme instead");
                }
            }
        }
        return keys;
    }

    private static void operationSystemProblems(ConnectorOperation op, Set<String> systemKeys, List<String> problems) {
        if (op.system() == null) {
            return;
        }
        String where = "operations[" + (op.key() != null ? op.key() : "?") + "].system";
        if (isBlank(op.system())) {
            problems.add(where + ": must name a system (or be absent)");
        } else if (!systemKeys.contains(op.system())) {
            problems.add(where + ": no system '" + op.system() + "'");
        }
    }

    private static void timeoutProblems(ConnectorOperation op, String where, List<String> problems) {
        if (op.timeoutMs() != null && (op.timeoutMs() < 100 || op.timeoutMs() > 30_000)) {
            problems.add(where + ".timeoutMs: must be between 100 and 30000");
        }
    }

    // ---- MCP (1.2.0) ---------------------------------------------------------------------------------

    /** Tool names as servers publish them: no whitespace, at most 128 characters. */
    private static final Pattern TOOL_NAME = Pattern.compile("^\\S{1,128}$");

    private static void mcpProblems(ConnectorDescriptor descriptor, List<String> problems) {
        String version = descriptor.mcpOrNone().protocolVersion();
        if (version != null && !ConnectorDescriptor.McpSettings.isSupported(version)) {
            problems.add("mcp.protocolVersion: must be one of "
                    + ConnectorDescriptor.McpSettings.SUPPORTED_PROTOCOL_VERSIONS + " (or absent, to negotiate)");
        }
        if (descriptor.idempotency() != null && !isBlank(descriptor.idempotency().header())) {
            problems.add("idempotency.header: not used by the MCP transport (declare idempotencyArgument on the operation)");
        }
    }

    /**
     * The MCP transport puts a credential in a header only ({@code apiKey} in
     * a header, {@code bearer}, {@code oauth2-client-credentials}): the
     * specification's authorization is a Bearer token, never a query
     * parameter, and Basic is not a scheme MCP servers speak.
     */
    private static void mcpAuthProblems(AuthScheme auth, List<String> problems) {
        switch (auth.schemeOrNone()) {
            case AuthScheme.SCHEME_BASIC -> problems.add("auth.scheme: basic is not supported by the MCP transport");
            case AuthScheme.SCHEME_API_KEY -> {
                if (AuthScheme.IN_QUERY.equalsIgnoreCase(auth.in())) {
                    problems.add("auth.in: must be header for MCP (a credential never travels in the URL)");
                }
            }
            default -> {
                // none, bearer, oauth2-client-credentials: fine
            }
        }
    }

    /**
     * An MCP operation is one tool: no method, no path, every input a body
     * (argument) property, idempotency declared rather than inferred, and the
     * key — when the tool takes one — carried in a named argument.
     */
    private static void mcpOperationProblems(ConnectorOperation op, Set<String> keys, List<String> problems) {
        String where = "operations[" + (op.key() != null ? op.key() : "?") + "]";
        if (op.key() == null || !OPERATION_KEY.matcher(op.key()).matches()) {
            problems.add(where + OPERATION_KEY_RULE);
        } else if (!keys.add(op.key())) {
            problems.add(where + ".key: duplicate operation key");
        }
        if (!isBlank(op.method())) {
            problems.add(where + ".method: not used by the MCP transport (every tool call is a POST)");
        }
        if (!isBlank(op.path())) {
            problems.add(where + ".path: not used by the MCP transport (the base URL is the server's endpoint)");
        }
        if (op.toolName() != null && !TOOL_NAME.matcher(op.toolName().trim()).matches()) {
            problems.add(where + ".toolName: must be 1 to 128 characters without whitespace");
        }
        if (op.riskLevel() == null) {
            problems.add(where + ".riskLevel: required (LOW, MEDIUM or HIGH)");
        }
        if (op.idempotent() == null) {
            problems.add(where + ".idempotent: required for an MCP tool (true or false; a tool call is never assumed safe to repeat)");
        }
        if (op.output() == null) {
            problems.add(where + ".output: required (the output schema; use an empty object when nothing is read)");
        }
        if (op.input() != null && !"object".equals(op.input().typeOrObject())) {
            problems.add(where + ".input.type: must be object");
        }
        if (op.output() != null && !"object".equals(op.output().typeOrObject())) {
            problems.add(where + ".output.type: must be object");
        }
        if (!isBlank(op.idempotencyHeader())) {
            problems.add(where + ".idempotencyHeader: not used by the MCP transport (declare idempotencyArgument)");
        }
        if (op.idempotencyArgument() != null && isBlank(op.idempotencyArgument())) {
            problems.add(where + ".idempotencyArgument: must name a tool argument");
        }

        Map<String, SchemaNode> inputs = op.inputOrEmpty().propertiesOrEmpty();
        for (Map.Entry<String, SchemaNode> input : inputs.entrySet()) {
            String name = input.getKey();
            SchemaNode node = input.getValue();
            String at = where + ".input.properties[" + name + "]";
            if (node == null) {
                problems.add(at + ": missing schema");
                continue;
            }
            if (!SchemaNode.IN_BODY.equals(node.placement())) {
                problems.add(at + ".in: must be body or absent (an MCP input is a tool argument)");
            }
            if (node.template() != null && !node.template().contains("{value}")) {
                problems.add(at + ".template: must contain {value}");
            }
            if (node.pattern() != null) {
                try {
                    Pattern.compile(node.pattern());
                } catch (Exception e) {
                    problems.add(at + ".pattern: not a valid regular expression");
                }
            }
        }
        for (String required : op.inputOrEmpty().requiredOrEmpty()) {
            if (!inputs.containsKey(required)) {
                problems.add(where + ".input.required: '" + required + "' is not an input property");
            }
        }
        for (String status : op.errorsOrEmpty().keySet()) {
            if (status == null || !status.matches("^[1-5][0-9]{2}$")) {
                problems.add(where + ".errors[" + status + "]: must be an HTTP status code");
            }
        }
        timeoutProblems(op, where, problems);
    }

    private static void requireField(String where, String field, Set<String> fields, List<String> problems) {
        if (isBlank(field)) {
            problems.add(where + ": required");
        } else if (!fields.contains(field)) {
            problems.add(where + ": no field '" + field + "'");
        }
    }

    private static void requireSecretField(String where, String field, Set<String> secretFields,
            List<String> problems) {
        if (isBlank(field)) {
            problems.add(where + ": required");
        } else if (!secretFields.contains(field)) {
            problems.add(where + ": '" + field + "' must be a field of type secret");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Whether {@code url} is a usable base URL: absolute, https, a host, no user info, no query or fragment. */
    public static List<String> baseUrlProblems(String url) {
        List<String> problems = new ArrayList<>();
        URI uri;
        try {
            uri = new URI(url == null ? "" : url.trim());
        } catch (Exception e) {
            problems.add("baseUrl: not a valid URL");
            return problems;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            problems.add("baseUrl: must use https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            problems.add("baseUrl: must name a host");
        }
        if (uri.getRawUserInfo() != null) {
            problems.add("baseUrl: must not carry a user name or password");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            problems.add("baseUrl: must not carry a query or fragment");
        }
        return problems;
    }
}
