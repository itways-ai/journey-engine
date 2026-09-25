package com.itways.assistant.journey.engine.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.ApiConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingPropertyAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class EngineUtils {

    private final ObjectMapper objectMapper;
    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * Resolves a source template preserving the original type when it is a single
     * placeholder (e.g. {@code {{steps.2.output.score}}} → Integer 8). Mixed
     * templates still use string substitution via {@link #replacePlaceholders}.
     */
    public Object resolveSourceValue(String source, Map<String, Object> context) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        return Placeholders.resolve(source, context);
    }

    public Object evaluateExpression(String expression, Map<String, Object> context) {
        if (expression == null || expression.isEmpty()) {
            return null;
        }
        String clean = expression.trim();
        if (clean.startsWith("{{") && clean.endsWith("}}")) {
            clean = clean.substring(2, clean.length() - 2).trim();
        }
        try {
            // SimpleEvaluationContext, not Standard: condition expressions are
            // author-supplied text, and the Standard context exposes T(),
            // constructors and bean references — a straight line from "can edit
            // a journey" to running arbitrary Java. Data binding + instance
            // methods covers every legitimate condition (map paths, comparisons,
            // String.contains and friends); static methods stay unreachable.
            SimpleEvaluationContext evalContext = SimpleEvaluationContext
                    .forPropertyAccessors(new MapAccessor(), DataBindingPropertyAccessor.forReadOnlyAccess())
                    .withInstanceMethods()
                    .withRootObject(context)
                    .build();
            return parser.parseExpression(clean).getValue(evalContext);
        } catch (Exception e) {
            // SpEL cannot parse numeric path segments (`steps.1.output`), so fall
            // back to the variable resolver when the expression is just a path.
            VariablePath.Resolution direct = VariablePath.lookup(context, clean);
            if (direct.found()) {
                return direct.value();
            }
            // Neither path worked. Report which identifiers are missing rather
            // than letting the expression collapse to a silent false.
            reportUnresolvedIdentifiers(clean, context);
            return null;
        }
    }

    /** SpEL keywords and literals that are never variable references. */
    private static final Set<String> EXPRESSION_KEYWORDS = Set.of(
            "true", "false", "null", "and", "or", "not", "new", "instanceof", "matches",
            "empty", "T", "gt", "lt", "ge", "le", "eq", "ne", "div", "mod");

    private static final Pattern IDENTIFIER = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z0-9_$]+|\\[[^\\]]*\\])*");

    private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'|\"[^\"]*\"");

    private void reportUnresolvedIdentifiers(String expression, Map<String, Object> context) {
        // Blank out string literals so their contents are not mistaken for paths.
        String stripped = STRING_LITERAL.matcher(expression).replaceAll(" ");
        Matcher matcher = IDENTIFIER.matcher(stripped);
        while (matcher.find()) {
            String token = matcher.group();
            if (EXPRESSION_KEYWORDS.contains(token) || Character.isDigit(token.charAt(0))) {
                continue;
            }
            if (!VariablePath.lookup(context, token).found()) {
                VariableDiagnostics.recordUnresolved(token);
            }
        }
    }

    public boolean evaluateCondition(String expression, Map<String, Object> context) {
        Object res = evaluateExpression(expression, context);
        return Boolean.TRUE.equals(res);
    }

    public String replacePlaceholders(String text, Map<String, Object> context) {
        return Placeholders.replace(text, context);
    }

    public Object resolveValue(String path, Map<String, Object> context) {
        return VariablePath.resolve(context, path);
    }

    /** Paths referenced by {@code text} that do not exist in {@code context}. */
    public List<String> unresolvedPlaceholders(String text, Map<String, Object> context) {
        return Placeholders.unresolvedPaths(text, context);
    }

    public ApiConfig parseApiConfig(String json) {
        try {
            if (json == null || json.isEmpty()) return new ApiConfig();
            return objectMapper.readValue(json, ApiConfig.class);
        } catch (Exception e) {
            return new ApiConfig();
        }
    }
}
