package com.itways.assistant.journey.engine.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.itways.assistant.journey.engine.validation.AnswerValidator.FieldError;

class AnswerValidatorTest {

    private static Map<String, Object> field(String name, String type, Map<String, Object> validations) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        if (type != null) {
            field.put("type", type);
        }
        if (validations != null) {
            field.put("validations", validations);
        }
        return field;
    }

    private static Map<String, Object> answer(Object... keysAndValues) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return values;
    }

    /** A rule that can show or hide {@code field}: the only part the validator reads. */
    private static List<Map<String, Object>> ruleActingOn(String field) {
        return List.of(Map.of("when", Map.of("field", "other", "equals", "x"), "then", Map.of("field", field)));
    }

    private static FieldError only(List<FieldError> errors) {
        assertThat(errors).hasSize(1);
        return errors.get(0);
    }

    @Test
    void noDeclaredFieldsAcceptsAnything() {
        assertThat(AnswerValidator.validate("anything", null, null)).isEmpty();
        assertThat(AnswerValidator.validate(answer("a", 1), List.of(), null)).isEmpty();
        assertThat(AnswerValidator.validate(answer("a", 1), "not a list", null)).isEmpty();
    }

    @Test
    void aMissingRequiredFieldIsReportedUnderItsName() {
        List<Map<String, Object>> fields = List.of(field("email", "text", Map.of("required", true)));

        FieldError error = only(AnswerValidator.validate(answer(), fields, null));

        assertThat(error.field()).isEqualTo("email");
        assertThat(error.label()).isEqualTo("email");
        assertThat(error.messageKey()).isEqualTo("step.input.error.required");
        assertThat(error.args()).isEmpty();
    }

    @Test
    void theAuthorsLabelIsUsedWhenThereIsOne() {
        Map<String, Object> email = field("email", "text", Map.of("required", true));
        email.put("label", "Your e-mail");

        assertThat(only(AnswerValidator.validate(answer("email", "  "), List.of(email), null)).label())
                .isEqualTo("Your e-mail");
    }

    @Test
    void requiredAsTheStringTrueIsStillRequired() {
        List<Map<String, Object>> fields = List.of(field("name", null, Map.of("required", "TRUE")));

        assertThat(only(AnswerValidator.validate(answer("name", ""), fields, null)).messageKey())
                .isEqualTo("step.input.error.required");
    }

    @Test
    void anUncheckedCheckboxCountsAsEmpty() {
        List<Map<String, Object>> fields = List.of(field("terms", "checkbox", Map.of("required", true)));

        assertThat(only(AnswerValidator.validate(answer("terms", false), fields, null)).messageKey())
                .isEqualTo("step.input.error.required");
        assertThat(AnswerValidator.validate(answer("terms", true), fields, null)).isEmpty();
    }

    @Test
    void anEmptyOptionalFieldSkipsEveryOtherRule() {
        List<Map<String, Object>> fields = List.of(field("code", "text", Map.of("minLength", 4, "pattern", "[0-9]+")));

        assertThat(AnswerValidator.validate(answer(), fields, null)).isEmpty();
    }

    @Test
    void aFieldARuleCanHideIsNeverRequired() {
        List<Map<String, Object>> fields = List.of(field("company", "text", Map.of("required", true)));

        assertThat(AnswerValidator.validate(answer(), fields, ruleActingOn("company"))).isEmpty();
        assertThat(AnswerValidator.validate(answer(), fields, ruleActingOn("someone_else"))).hasSize(1);
    }

    @Test
    void numbersAreCheckedAgainstMinAndMax() {
        List<Map<String, Object>> fields = List.of(field("age", "number", Map.of("min", "18", "max", 99.5)));

        assertThat(only(AnswerValidator.validate(answer("age", "abc"), fields, null)).messageKey())
                .isEqualTo("step.input.error.number");

        FieldError tooLow = only(AnswerValidator.validate(answer("age", 17), fields, null));
        assertThat(tooLow.messageKey()).isEqualTo("step.input.error.min");
        assertThat(tooLow.args()).containsExactly("18");

        FieldError tooHigh = only(AnswerValidator.validate(answer("age", "100"), fields, null));
        assertThat(tooHigh.messageKey()).isEqualTo("step.input.error.max");
        assertThat(tooHigh.args()).containsExactly(99.5);

        assertThat(AnswerValidator.validate(answer("age", " 42 "), fields, null)).isEmpty();
    }

    @Test
    void emailIsCheckedByFlagOrByType() {
        List<Map<String, Object>> byType = List.of(field("mail", "email", null));
        List<Map<String, Object>> byFlag = List.of(field("mail", "text", Map.of("email", "true")));

        assertThat(only(AnswerValidator.validate(answer("mail", "not-an-address"), byType, null)).messageKey())
                .isEqualTo("step.input.error.email");
        assertThat(only(AnswerValidator.validate(answer("mail", "a@b"), byFlag, null)).messageKey())
                .isEqualTo("step.input.error.email");
        assertThat(AnswerValidator.validate(answer("mail", "sara@example.com"), byType, null)).isEmpty();
        assertThat(AnswerValidator.validate(answer("mail", "sara@example.com"), byFlag, null)).isEmpty();
    }

    @Test
    void textLengthIsCheckedWithTheLimitAsTheArgument() {
        List<Map<String, Object>> fields = List.of(field("code", "text", Map.of("minLength", 4, "maxLength", "6")));

        FieldError tooShort = only(AnswerValidator.validate(answer("code", "abc"), fields, null));
        assertThat(tooShort.messageKey()).isEqualTo("step.input.error.minLength");
        assertThat(tooShort.args()).containsExactly("4");

        FieldError tooLong = only(AnswerValidator.validate(answer("code", "abcdefg"), fields, null));
        assertThat(tooLong.messageKey()).isEqualTo("step.input.error.maxLength");
        assertThat(tooLong.args()).containsExactly("6");

        assertThat(AnswerValidator.validate(answer("code", "abcd"), fields, null)).isEmpty();
    }

    @Test
    void aPatternMustMatchTheWholeAnswer() {
        List<Map<String, Object>> fields = List.of(field("pin", "text", Map.of("pattern", "[0-9]{4}")));

        assertThat(only(AnswerValidator.validate(answer("pin", "a1234b"), fields, null)).messageKey())
                .isEqualTo("step.input.error.pattern");
        assertThat(AnswerValidator.validate(answer("pin", "1234"), fields, null)).isEmpty();
    }

    @Test
    void anAlreadyAnchoredPatternIsUsedAsWritten() {
        List<Map<String, Object>> fields = List.of(field("pin", "text", Map.of("pattern", "^[A-Z]{2}\\d+$")));

        assertThat(AnswerValidator.validate(answer("pin", "AB12"), fields, null)).isEmpty();
        assertThat(AnswerValidator.validate(answer("pin", "ab12"), fields, null)).hasSize(1);
    }

    @Test
    void aPatternThatDoesNotCompileIsTreatedAsSatisfied() {
        List<Map<String, Object>> fields = List.of(field("pin", "text", Map.of("pattern", "[0-9")));

        assertThat(AnswerValidator.validate(answer("pin", "anything"), fields, null)).isEmpty();
    }

    @Test
    void aPlainTextAnswerFillsAOneFieldForm() {
        List<Map<String, Object>> fields = List.of(field("email", "email", Map.of("required", true)));

        assertThat(AnswerValidator.validate("sara@example.com", fields, null)).isEmpty();
        assertThat(only(AnswerValidator.validate("nope", fields, null)).messageKey())
                .isEqualTo("step.input.error.email");
    }

    @Test
    void aPlainTextAnswerToSeveralFieldsAsksForAStructuredAnswer() {
        List<Map<String, Object>> fields = List.of(field("first", null, null), field("last", null, null));

        FieldError error = only(AnswerValidator.validate("Sara Ali", fields, null));

        assertThat(error.field()).isNull();
        assertThat(error.label()).isNull();
        assertThat(error.messageKey()).isEqualTo("step.input.error.structured");
    }

    @Test
    void eachFieldReportsItsFirstFailureOnly() {
        List<Map<String, Object>> fields = List.of(
                field("name", "text", Map.of("required", true)),
                field("code", "text", Map.of("minLength", 5, "pattern", "[0-9]+")),
                field("ok", "text", null));

        List<FieldError> errors = AnswerValidator.validate(answer("code", "ab", "ok", "fine"), fields, null);

        assertThat(errors).extracting(FieldError::field, FieldError::messageKey).containsExactly(
                tuple("name", "step.input.error.required"),
                tuple("code", "step.input.error.minLength"));
    }

    @Test
    void aFieldWithoutANameIsIgnored() {
        Map<String, Object> nameless = field(" ", "text", Map.of("required", true));

        assertThat(AnswerValidator.validate(answer(), List.of(nameless, field("x", null, null)), null)).isEmpty();
    }

    @Test
    void fieldsToAskLeavesOutConditionalAndNamelessFieldsInDeclaredOrder() {
        List<Map<String, Object>> fields = List.of(
                field("first", null, null),
                field("company", null, null),
                field("", null, null),
                field("last", null, null));

        assertThat(AnswerValidator.fieldsToAsk(fields, ruleActingOn("company")))
                .extracting(f -> f.get("name")).containsExactly("first", "last");
        assertThat(AnswerValidator.fieldsToAsk(null, null)).isEmpty();
    }
}
