package ru.sbernpf.util;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Настройка проверок для конкретного поля DTO.
 * <p>
 * Например, для пароля, в котором допустимы любые символы:
 * <pre>{@code
 * @SecureField(skip = {SecurityCheck.SQL_INJECTION, SecurityCheck.XSS, SecurityCheck.COMMAND_INJECTION})
 * private String password;
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
public @interface SecureField {

    /** Проверки, отключённые для поля (и элементов коллекции в нём). */
    SecurityCheck[] skip() default {};

    /** Максимальная длина строки; {@code -1} — использовать значение из {@link SecureDto}. */
    int maxLength() default -1;

    /** Максимальный размер коллекции; {@code -1} — использовать значение из {@link SecureDto}. */
    int maxSize() default -1;

    /**
     * Регулярное выражение, которому строка должна соответствовать целиком; пусто — без ограничения.
     * Действует на элементы коллекции и значения map, но не на ключи map. Ошибка в выражении или
     * указание его для числа обнаруживается при первом использовании ({@code IllegalArgumentException}).
     * Выражение выполняется на каждом значении, поэтому избегайте вложенных квантификаторов.
     */
    String pattern() default "";

    /**
     * Нижняя граница числа включительно, например {@code "0"} или {@code "0.01"}; пусто — без ограничения.
     * Действует на элементы коллекций и массивов, в том числе примитивных, и на значения map.
     * Ошибка в формате или указание границы для строки обнаруживается при первом использовании
     * ({@code IllegalArgumentException}).
     */
    String min() default "";

    /** Верхняя граница числа включительно, см. {@link #min()}. */
    String max() default "";

    /**
     * Включить {@link SecurityCheck#SQL_STRICT} для поля (и элементов коллекции в нём).
     * Для значений, которые попадают в SQL без параметризации.
     */
    boolean sqlStrict() default false;

    /** Полностью исключить поле из проверок (включая каскад во вложенный объект). */
    boolean ignore() default false;
}
