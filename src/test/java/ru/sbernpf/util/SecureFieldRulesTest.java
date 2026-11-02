package ru.sbernpf.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.sbernpf.util.jackson.SecureValidationModule;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureFieldRulesTest {

    public static class PatternDto {
        @SecureField(pattern = "\\d{3}")
        public String code;
        @SecureField(pattern = "[a-z]+")
        public List<String> items;
        @SecureField(pattern = "[a-z]+")
        public Map<String, String> byKey;
        @SecureField(pattern = "[a-z]+")
        public Optional<String> optional;
        @SecureField(pattern = "[a-z]+")
        public JsonNode json;
        @SecureField(pattern = "\\d{3}", skip = SecurityCheck.PATTERN)
        public String skipped;
    }

    public static class RangeDto {
        @SecureField(min = "1", max = "120")
        public int primitive;
        @SecureField(min = "1", max = "120")
        public Long boxed;
        @SecureField(min = "0.01", max = "100.50")
        public BigDecimal decimal;
        @SecureField(max = "0.1")
        public Float floating;
        @SecureField(min = "0")
        public Double rate;
        @SecureField(max = "1000000000000000000000000")
        public BigInteger big;
        @SecureField(min = "0")
        public List<Integer> list;
        @SecureField(min = "0")
        public Map<String, Integer> map;
        @SecureField(min = "0")
        public int[] array;

        RangeDto() {
            primitive = 1;
        }
    }

    public static class NoRulesDto {
        public int number = -5;
        public byte[] bytes = {1};
    }

    @SecureDto(skip = SecurityCheck.NUMBER)
    public static class NumberSkippedDto {
        @SecureField(max = "10")
        public int value = 11;
    }

    public record RulesRecord(@SecureField(pattern = "[A-Z]+") String code, @SecureField(max = "5") int count) {
    }

    public static class BadRegexDto {
        @SecureField(pattern = "[")
        public String value = "x";
    }

    public static class BadNumberDto {
        @SecureField(min = "abc")
        public int value = 1;
    }

    public static class MinAboveMaxDto {
        @SecureField(min = "10", max = "1")
        public int value = 1;
    }

    public static class RangeOnStringDto {
        @SecureField(min = "0")
        public String value = "5";
    }

    public static class PatternOnNumberDto {
        @SecureField(pattern = "\\d+")
        public int value = 5;
    }

    private final SecureValidator validator = new SecureValidator();

    private Set<SecurityCheck> checks(Object dto) {
        return validator.validate(dto).stream().map(Violation::check).collect(Collectors.toSet());
    }

    private static PatternDto patternDto() {
        return new PatternDto();
    }

    private static RangeDto rangeDto() {
        return new RangeDto();
    }

    @Test
    void patternMustMatchTheWholeString() {
        PatternDto dto = patternDto();
        dto.code = "123";
        assertEquals(List.of(), validator.validate(dto));

        for (String bad : List.of("12", "1234", "12a", "x123", "123\n")) {
            dto.code = bad;
            assertTrue(checks(dto).contains(SecurityCheck.PATTERN), bad);
        }
    }

    @Test
    void nullAndAbsentRulesAreNotViolations() {
        assertEquals(List.of(), validator.validate(patternDto()));
        assertEquals(List.of(), validator.validate(new NoRulesDto()));
    }

    @Test
    void patternAppliesToElementsAndValuesButNotKeys() throws Exception {
        PatternDto dto = patternDto();
        dto.items = List.of("ok", "BAD");
        assertEquals("items[1]", validator.validate(dto).get(0).path());

        dto = patternDto();
        dto.byKey = Map.of("KEY1", "ok");
        assertEquals(List.of(), validator.validate(dto));
        dto.byKey = Map.of("key", "BAD");
        assertEquals(Set.of(SecurityCheck.PATTERN), checks(dto));

        dto = patternDto();
        dto.optional = Optional.of("BAD");
        assertEquals(Set.of(SecurityCheck.PATTERN), checks(dto));

        dto = patternDto();
        dto.json = new ObjectMapper().readTree("{\"KEY\":\"ok\",\"other\":[\"BAD\"]}");
        assertEquals(Set.of(SecurityCheck.PATTERN), checks(dto));
    }

    @Test
    void signaturesStillRunAlongsidePattern() {
        PatternDto dto = patternDto();
        dto.code = "' OR 1=1 --";
        assertEquals(Set.of(SecurityCheck.PATTERN, SecurityCheck.SQL_INJECTION), checks(dto));
    }

    @Test
    void patternCanBeSkipped() {
        PatternDto dto = patternDto();
        dto.skipped = "abc";
        assertEquals(List.of(), validator.validate(dto));
    }

    @Test
    void messageDoesNotRevealValueOrPattern() {
        PatternDto dto = patternDto();
        dto.code = "secret-value";
        assertEquals("value does not match the allowed pattern", validator.validate(dto).get(0).message());
    }

    @Test
    void numberBoundsAreInclusive() {
        RangeDto dto = rangeDto();
        for (int ok : new int[]{1, 120}) {
            dto.primitive = ok;
            assertEquals(List.of(), validator.validate(dto));
        }
        for (int bad : new int[]{0, 121, -1, Integer.MIN_VALUE}) {
            dto.primitive = bad;
            assertEquals(Set.of(SecurityCheck.NUMBER_RANGE), checks(dto));
        }
    }

    @Test
    void comparesEveryNumberTypeExactly() {
        RangeDto dto = rangeDto();
        dto.boxed = 121L;
        assertEquals(Set.of(SecurityCheck.NUMBER_RANGE), checks(dto));

        dto = rangeDto();
        dto.decimal = new BigDecimal("100.500");
        assertEquals(List.of(), validator.validate(dto));
        dto.decimal = new BigDecimal("100.51");
        assertEquals(Set.of(SecurityCheck.NUMBER_RANGE), checks(dto));
        dto.decimal = new BigDecimal("0.009");
        assertEquals(Set.of(SecurityCheck.NUMBER_RANGE), checks(dto));

        dto = rangeDto();
        dto.floating = 0.1f;
        assertEquals(List.of(), validator.validate(dto));

        dto = rangeDto();
        dto.big = BigInteger.TEN.pow(24);
        assertEquals(List.of(), validator.validate(dto));
        dto.big = BigInteger.TEN.pow(24).add(BigInteger.ONE);
        assertEquals(Set.of(SecurityCheck.NUMBER_RANGE), checks(dto));
    }

    @Test
    void nonFiniteNumbersFailTheRange() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            RangeDto dto = rangeDto();
            dto.rate = value;
            assertTrue(checks(dto).contains(SecurityCheck.NUMBER_RANGE), String.valueOf(value));
        }
    }

    @Test
    void rangeAppliesToCollectionsMapsAndPrimitiveArrays() {
        RangeDto dto = rangeDto();
        dto.list = List.of(1, -1);
        assertEquals("list[1]", validator.validate(dto).get(0).path());

        dto = rangeDto();
        dto.map = Map.of("key", -1);
        assertEquals(Set.of(SecurityCheck.NUMBER_RANGE), checks(dto));

        dto = rangeDto();
        dto.array = new int[]{1, -1};
        assertEquals("array[1]", validator.validate(dto).get(0).path());
    }

    @Test
    void skippingNumberDoesNotDisableExplicitRange() {
        assertEquals(Set.of(SecurityCheck.NUMBER_RANGE), checks(new NumberSkippedDto()));
    }

    @Test
    void worksForRecordsAndThroughJackson() throws Exception {
        assertEquals(List.of(), validator.validate(new RulesRecord("ABC", 5)));
        assertEquals(Set.of(SecurityCheck.PATTERN, SecurityCheck.NUMBER_RANGE), checks(new RulesRecord("abc", 6)));

        ObjectMapper mapper = new ObjectMapper().registerModule(new SecureValidationModule());
        assertFalse(validator.validate(mapper.readValue("{\"code\":\"abc\",\"count\":1}", RulesRecord.class)).isEmpty());
    }

    @Test
    void misconfigurationFailsInsteadOfDisablingTheCheck() {
        for (Object dto : List.of(new BadRegexDto(), new BadNumberDto(), new MinAboveMaxDto(),
                new RangeOnStringDto(), new PatternOnNumberDto())) {
            assertThrows(IllegalArgumentException.class, () -> validator.validate(dto), dto.getClass().getSimpleName());
        }
    }

    @Test
    void parameterRulesAreCachedAndApplied() throws Exception {
        SecureField rules = RangeDto.class.getField("primitive").getAnnotation(SecureField.class);
        for (int i = 0; i < 3; i++) {
            assertEquals(List.of(), validator.validateParameter(5, "size", rules, null));
            assertEquals(SecurityCheck.NUMBER_RANGE,
                    validator.validateParameter(500, "size", rules, null).get(0).check());
        }
    }
}
