package ru.sbernpf.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Шаблоны должны оставаться быстрыми на длинных строках: поля с большим maxLength не должны давать DoS. */
class ThreatPatternsPerformanceTest {

    private static final int LENGTH = 200_000;

    public static class Dto {
        @SecureField(maxLength = LENGTH, sqlStrict = true)
        public String value;

        Dto(String value) {
            this.value = value;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"<", "< ", "<a ", "<%", "{{", "${", "#{", "$(", "&lt", "&#", "%25", "/*", "' or "})
    void repeatedOpenersAreHandledQuickly(String unit) {
        Dto dto = new Dto(unit.repeat(LENGTH / unit.length()));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> new SecureValidator().validate(dto), unit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"${", "#{", "$(", "{{", "<%"})
    void longPaddingDoesNotHideDelimitedInjection(String open) {
        String close = switch (open) {
            case "$(" -> ")";
            case "{{" -> "}}";
            case "<%" -> "%>";
            default -> "}";
        };
        Dto dto = new Dto(open + "x".repeat(1000) + close);
        assertFalse(new SecureValidator().validate(dto).isEmpty(), open);
    }

    @ParameterizedTest
    @ValueSource(strings = {"$", "{", "}", "#", "(", ")", "%", "цена $5 (без НДС), скидка 5%"})
    void singleDelimitersAreNotInjections(String value) {
        assertEquals(java.util.List.of(), new SecureValidator().validate(new Dto(value)), value);
    }
}
