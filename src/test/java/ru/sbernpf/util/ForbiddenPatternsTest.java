package ru.sbernpf.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.sbernpf.util.jackson.SecureValidationModule;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForbiddenPatternsTest {

    @SecureDto
    public static class Dto {
        public String value;
        @SecureField(skip = SecurityCheck.FORBIDDEN_PATTERN)
        public String free;
        public List<String> items;
        public Map<String, String> map;

        Dto() {
        }

        Dto(String value) {
            this.value = value;
        }
    }

    private final SecureValidator validator = new SecureValidator(List.of("\\bTRUNCATE\\b", "tbl_[a-z]+_internal"));

    private Set<SecurityCheck> checks(Object dto) {
        return validator.validate(dto).stream().map(Violation::check).collect(Collectors.toSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "TRUNCATE", "truncate", "TrUnCaTe", "x truncate y", "TRUNC%41TE", "TRUNC&#65;TE", "ＴＲＵＮＣＡＴＥ",
            "tbl_users_internal", "select from TBL_USERS_INTERNAL now"
    })
    void blocksConfiguredValuesInCanonicalForm(String value) {
        assertTrue(checks(new Dto(value)).contains(SecurityCheck.FORBIDDEN_PATTERN), value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"truncated", "Иванов", "tbl_users", "ok"})
    void allowsOtherValues(String value) {
        assertEquals(List.of(), validator.validate(new Dto(value)), value);
    }

    @Test
    void wordBoundariesWorkWithCyrillic() {
        SecureValidator russian = new SecureValidator(List.of("\\bПАРОЛЬ\\b"));
        assertEquals(1, russian.validate(new Dto("мой пароль")).size());
        assertEquals(List.of(), russian.validate(new Dto("парольный")));
    }

    @Test
    void validatorWithoutPatternsIgnoresThem() {
        assertEquals(List.of(), new SecureValidator().validate(new Dto("TRUNCATE")));
        assertEquals(List.of(), new SecureValidator(Clock.systemUTC()).validate(new Dto("TRUNCATE")));
    }

    @Test
    void appliesToCollectionsMapKeysAndParameters() {
        Dto dto = new Dto();
        dto.items = List.of("ok", "TRUNCATE");
        assertEquals(Set.of(SecurityCheck.FORBIDDEN_PATTERN), checks(dto));

        dto = new Dto();
        dto.map = Map.of("TRUNCATE", "ok");
        assertEquals(Set.of(SecurityCheck.FORBIDDEN_PATTERN), checks(dto));

        assertEquals(SecurityCheck.FORBIDDEN_PATTERN,
                validator.validateParameter("TRUNCATE", "q", null, null).get(0).check());
    }

    @Test
    void canBeSkippedForField() {
        Dto dto = new Dto();
        dto.free = "TRUNCATE";
        assertEquals(List.of(), validator.validate(dto));
    }

    @Test
    void builtInSignaturesStillWork() {
        assertTrue(checks(new Dto("' OR 1=1 --")).contains(SecurityCheck.SQL_INJECTION));
    }

    @Test
    void messageDoesNotRevealValueOrPattern() {
        assertEquals("potential forbidden pattern detected", validator.validate(new Dto("TRUNCATE")).get(0).message());
    }

    @Test
    void rejectsInvalidPatternsImmediately() {
        assertThrows(IllegalArgumentException.class, () -> new SecureValidator(List.of("[")));
        assertThrows(IllegalArgumentException.class, () -> new SecureValidator(List.of("")));
        assertThrows(IllegalArgumentException.class, () -> new SecureValidator(List.of("  ")));
    }

    @Test
    void hardenUsesTheGivenValidator() throws Exception {
        ObjectMapper mapper = SecureValidationModule.harden(new ObjectMapper(), validator);
        assertThrows(SecurityValidationException.class, () -> mapper.readValue("{\"value\":\"TRUNCATE\"}", Dto.class));
    }
}
