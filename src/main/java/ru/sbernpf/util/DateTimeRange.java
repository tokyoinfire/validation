package ru.sbernpf.util;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Допустимый диапазон для поля даты/времени. Переопределяет значения по умолчанию из {@link SecureDto}.
 * <p>
 * Поддерживаются {@code LocalDate}, {@code LocalDateTime}, {@code Instant}, {@code OffsetDateTime},
 * {@code ZonedDateTime}, {@code YearMonth}, {@code Year}, {@code java.util.Date}, {@code Calendar}.
 * Даты без зоны трактуются как UTC.
 * <pre>{@code
 * @DateTimeRange(min = "1900-01-01", notInFuture = true)
 * private LocalDate birthDate;
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
public @interface DateTimeRange {

    /** Нижняя граница (ISO-8601, дата или дата-время); пусто — из {@link SecureDto#minDate()}. */
    String min() default "";

    /** Верхняя граница (ISO-8601); пусто — сейчас + {@link #maxFutureYears()}. */
    String max() default "";

    /** Сколько лет вперёд допустимо; {@code -1} — из {@link SecureDto#maxFutureYears()}. */
    int maxFutureYears() default -1;

    /** Сколько лет назад допустимо (относительно текущего момента); {@code -1} — без ограничения. */
    int maxPastYears() default -1;

    /** Дата не может быть в будущем (дата рождения, дата операции). Допуск — 1 сутки на часовые пояса. */
    boolean notInFuture() default false;

    /** Дата не может быть в прошлом (плановая дата). Допуск — 1 сутки на часовые пояса. */
    boolean notInPast() default false;
}
