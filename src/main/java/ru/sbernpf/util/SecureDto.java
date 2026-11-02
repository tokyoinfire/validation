package ru.sbernpf.util;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает DTO, который проверяется на соответствие правилам безопасности
 * при каждой десериализации и сериализации через Jackson.
 * <p>
 * При нарушении бросается {@link SecurityValidationException}
 * (при десериализации в Spring MVC это превращается в HTTP 400).
 * <p>
 * Вложенные объекты, коллекции и map проверяются каскадно.
 * Отдельные поля можно настроить через {@link SecureField} и {@link DateTimeRange}.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SecureDto {

    /** Проверки, отключённые для всего DTO. */
    SecurityCheck[] skip() default {};

    /** Максимальная длина любой строки DTO. */
    int maxStringLength() default 4096;

    /** Максимальный размер коллекций, массивов и map. */
    int maxCollectionSize() default 1000;

    /** Минимально допустимая дата по умолчанию (ISO-8601, дата или дата-время). */
    String minDate() default "1900-01-01";

    /** Насколько лет вперёд от текущего момента допустима дата по умолчанию. */
    int maxFutureYears() default 100;

    /** Максимальная глубина вложенности объектов при каскадной проверке. */
    int maxDepth() default 32;

    /**
     * Включить {@link SecurityCheck#SQL_STRICT} для всех полей DTO.
     * Для значений, которые попадают в SQL без параметризации.
     */
    boolean sqlStrict() default false;
}
