package ru.sbernpf.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlStrictTest {

    public static class FieldDto {
        @SecureField(sqlStrict = true)
        public String code;
        public String comment;
        @SecureField(sqlStrict = true)
        public List<String> codes;
        @SecureField(sqlStrict = true)
        public NestedDto nested;

        FieldDto(String code, String comment) {
            this.code = code;
            this.comment = comment;
        }
    }

    public static class NestedDto {
        public String value;

        NestedDto(String value) {
            this.value = value;
        }
    }

    @SecureDto(sqlStrict = true)
    public static class StrictDto {
        public String value;
        public NestedDto nested;
        public OwnRulesDto own;
        @SecureField(skip = SecurityCheck.SQL_STRICT)
        public String free;

        StrictDto(String value) {
            this.value = value;
        }
    }

    @SecureDto
    public static class OwnRulesDto {
        public String value;

        OwnRulesDto(String value) {
            this.value = value;
        }
    }

    public record StrictRecord(@SecureField(sqlStrict = true) String code) {
    }

    private final SecureValidator validator = new SecureValidator();

    private Set<SecurityCheck> checks(Object dto) {
        return validator.validate(dto).stream().map(Violation::check).collect(Collectors.toSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT", "select", "SeLeCt", "SELECT * FROM users", "UNION", "ALTER", "UPDATE", "EXEC", "EXECUTE",
            "DROP", "DELETE", "INSERT", "EXEC sp_who", "DROP users", "DELETE users", "INSERT users", "(select 1)",
            "1union", "a`b", "`", "a--b", "1 -- comment", "--", "a/*b", "/*", "SEL/**/ECT",
            "ＳＥＬＥＣＴ", "%53ELECT", "&#83;ELECT", "union+select", "a－－b"
    })
    void blocksOperatorsAndMetacharacters(String value) {
        assertTrue(checks(new FieldDto(value, null)).contains(SecurityCheck.SQL_STRICT), value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "name", "created_at", "selection", "preselect", "dropdown", "updated", "reunion", "deleted", "insertion",
            "select_id", "my_update", "executor", "a-b", "a – b", "a — b", "a/b", "a*b", "O'Brien", "Иванов"
    })
    void allowsOtherValues(String value) {
        assertEquals(List.of(), validator.validate(new FieldDto(value, null)), value);
    }

    @Test
    void isOffByDefault() {
        assertEquals(List.of(), validator.validate(new FieldDto(null, "Delete account -- select later")));
        assertFalse(checks(new NestedDto("DROP")).contains(SecurityCheck.SQL_STRICT));
    }

    @Test
    void appliesToCollectionElementsOfTheField() {
        FieldDto dto = new FieldDto(null, null);
        dto.codes = List.of("ok", "DROP");
        assertEquals(Set.of(SecurityCheck.SQL_STRICT), checks(dto));
    }

    @Test
    void fieldFlagDoesNotReachNestedObjectFields() {
        FieldDto dto = new FieldDto(null, null);
        dto.nested = new NestedDto("DROP");
        assertEquals(List.of(), validator.validate(dto));
    }

    @Test
    void classFlagCoversAllFieldsAndPlainNestedObjects() {
        assertEquals(Set.of(SecurityCheck.SQL_STRICT), checks(new StrictDto("DROP")));

        StrictDto dto = new StrictDto("ok");
        dto.nested = new NestedDto("DROP");
        assertEquals(Set.of(SecurityCheck.SQL_STRICT), checks(dto));
    }

    @Test
    void nestedSecureDtoKeepsItsOwnRules() {
        StrictDto dto = new StrictDto("ok");
        dto.own = new OwnRulesDto("DROP");
        assertEquals(List.of(), validator.validate(dto));
    }

    @Test
    void fieldCanOptOutOfClassFlag() {
        StrictDto dto = new StrictDto("ok");
        dto.free = "Delete account";
        assertEquals(List.of(), validator.validate(dto));
    }

    @Test
    void worksForRecordsAndParameters() throws Exception {
        assertEquals(Set.of(SecurityCheck.SQL_STRICT), checks(new StrictRecord("DROP")));

        SecureField strict = FieldDto.class.getField("code").getAnnotation(SecureField.class);
        List<Violation> violations = validator.validateParameter("DROP", "sort", strict, null);
        assertEquals(SecurityCheck.SQL_STRICT, violations.get(0).check());
        assertEquals("sort", violations.get(0).path());
    }
}
