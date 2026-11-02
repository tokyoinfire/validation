package ru.sbernpf.util.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Настройки библиотеки в конфигурации приложения.
 */
@ConfigurationProperties(prefix = "sbernpf.secure-validation")
public class SecureValidationProperties {

    /** Регулярные выражения недопустимых значений для конкретной АС. */
    private List<String> forbiddenPatterns = new ArrayList<>();

    public List<String> getForbiddenPatterns() {
        return forbiddenPatterns;
    }

    public void setForbiddenPatterns(List<String> forbiddenPatterns) {
        this.forbiddenPatterns = forbiddenPatterns;
    }
}
