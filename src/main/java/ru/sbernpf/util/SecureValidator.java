package ru.sbernpf.util;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Проверяет объект (и всё, что в нём вложено) по правилам {@link SecureDto}.
 * <p>
 * Используется Jackson-модулем автоматически, но может вызываться и напрямую:
 * <pre>{@code
 * new SecureValidator().validateOrThrow(dto);
 * }</pre>
 * Потокобезопасен; метаданные классов кэшируются.
 */
public final class SecureValidator {

    private static final int MAX_BIG_NUMBER_DIGITS = 100;
    private static final int MAX_BIG_INTEGER_BITS = 400;
    private static final long TIMEZONE_TOLERANCE_DAYS = 1;

    private static final Settings DEFAULT_SETTINGS = Settings.of(Defaults.class.getAnnotation(SecureDto.class));

    private final Clock clock;
    private final List<Pattern> forbiddenPatterns;
    private final ConcurrentMap<Class<?>, List<FieldMeta>> fieldCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, Settings> settingsCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<List<Object>, FieldMeta> parameterCache = new ConcurrentHashMap<>();

    public SecureValidator() {
        this(Clock.systemUTC());
    }

    public SecureValidator(Clock clock) {
        this(clock, List.of());
    }

    /**
     * @param forbiddenPatterns регулярные выражения недопустимых значений для конкретной АС:
     *                          см. {@link #SecureValidator(Clock, Collection)}
     */
    public SecureValidator(Collection<String> forbiddenPatterns) {
        this(Clock.systemUTC(), forbiddenPatterns);
    }

    /**
     * @param clock             часы для проверки дат
     * @param forbiddenPatterns регулярные выражения недопустимых значений для конкретной АС. Ищутся без учёта
     *                          регистра в любой части строки, в том числе после декодирования URL-кодировки
     *                          и HTML-сущностей. Нарушение — {@link SecurityCheck#FORBIDDEN_PATTERN}
     * @throws IllegalArgumentException если выражение пустое или некорректное
     */
    public SecureValidator(Clock clock, Collection<String> forbiddenPatterns) {
        this.clock = clock;
        this.forbiddenPatterns = ThreatPatterns.compileForbidden(forbiddenPatterns);
    }

    /**
     * Возвращает все нарушения; пустой список — объект корректен.
     */
    public List<Violation> validate(Object dto) {
        if (dto == null) {
            return List.of();
        }
        Run run = new Run(Instant.now(clock));
        Settings settings = settingsFor(dto.getClass());
        run.validateObject(dto, "", settings, 0);
        return run.violations;
    }

    /**
     * Бросает {@link SecurityValidationException}, если есть нарушения.
     */
    public void validateOrThrow(Object dto) throws SecurityValidationException {
        List<Violation> violations = validate(dto);
        if (!violations.isEmpty()) {
            throw new SecurityValidationException(dto.getClass(), violations);
        }
    }

    /**
     * Проверяет отдельное значение вне DTO: параметр контроллера, payload сообщения.
     * Используются настройки {@link SecureDto} по умолчанию.
     *
     * @param value       значение; {@code null} считается корректным
     * @param name        имя параметра для путей в {@link Violation}
     * @param secureField настройки проверок или {@code null}
     * @param range       диапазон дат или {@code null}
     * @return все нарушения; пустой список — значение корректно
     */
    public List<Violation> validateParameter(Object value, String name, SecureField secureField, DateTimeRange range) {
        if (value == null || (secureField != null && secureField.ignore())) {
            return List.of();
        }
        Run run = new Run(Instant.now(clock));
        FieldMeta meta = parameterCache.computeIfAbsent(Arrays.asList(name, secureField, range),
                key -> FieldMeta.of(name, secureField, range));
        run.validateValue(value, name, new Rules(DEFAULT_SETTINGS, meta), 0);
        return run.violations;
    }

    private Settings settingsFor(Class<?> type) {
        return settingsCache.computeIfAbsent(type, t -> {
            SecureDto annotation = t.getAnnotation(SecureDto.class);
            return annotation == null ? DEFAULT_SETTINGS : Settings.of(annotation);
        });
    }

    private List<FieldMeta> fieldsOf(Class<?> type) {
        return fieldCache.computeIfAbsent(type, SecureValidator::collectFields);
    }

    private static List<FieldMeta> collectFields(Class<?> type) {
        List<FieldMeta> result = new ArrayList<>();
        for (Class<?> c = type; c != null && !isPlatformClass(c); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                int mod = field.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || field.isSynthetic()) {
                    continue;
                }
                JsonIgnore jsonIgnore = field.getAnnotation(JsonIgnore.class);
                if (jsonIgnore != null && jsonIgnore.value()) {
                    continue;
                }
                SecureField secureField = field.getAnnotation(SecureField.class);
                if (secureField != null && secureField.ignore()) {
                    continue;
                }
                if (!field.trySetAccessible()) {
                    // fail closed: лучше явная ошибка, чем непроверенное поле
                    throw new IllegalStateException("Cannot access field " + c.getName() + "." + field.getName()
                            + " for security validation; open the package to ru.sbernpf.util"
                            + " or mark the field with @SecureField(ignore = true)");
                }
                result.add(FieldMeta.of(field, secureField, field.getAnnotation(DateTimeRange.class)));
            }
        }
        return List.copyOf(result);
    }

    private static boolean isPlatformClass(Class<?> c) {
        String name = c.getName();
        return c.isPrimitive() || name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")
                || name.startsWith("jdk.") || name.startsWith("org.apache.kafka.")
                || name.startsWith("sun.") || name.startsWith("com.sun.") || name.startsWith("kotlin.")
                || name.startsWith("scala.") || name.startsWith("com.fasterxml.") || name.startsWith("org.springframework.");
    }

    /** Состояние одного прохода валидации. */
    private final class Run {

        private final Instant now;
        private final List<Violation> violations = new ArrayList<>();
        private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        Run(Instant now) {
            this.now = now;
        }

        void validateObject(Object obj, String path, Settings settings, int depth) {
            if (!visited.add(obj)) {
                return;
            }
            try {
                for (FieldMeta meta : fieldsOf(obj.getClass())) {
                    Object value;
                    try {
                        value = meta.field.get(obj);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException("Cannot read field " + meta.field, e);
                    }
                    Rules rules = new Rules(settings, meta);
                    validateValue(value, join(path, meta.field.getName()), rules, depth);
                }
            } finally {
                visited.remove(obj);
            }
        }

        void validateValue(Object value, String path, Rules rules, int depth) {
            if (value == null) {
                return;
            }
            if (value instanceof CharSequence || value instanceof URI || value instanceof URL) {
                validateString(value.toString(), path, rules);
            } else if (value instanceof Number) {
                validateNumber((Number) value, path, rules);
            } else if (value instanceof Optional) {
                ((Optional<?>) value).ifPresent(v -> validateValue(v, path, rules, depth));
            } else if (value instanceof JsonNode) {
                validateJsonNode((JsonNode) value, path, rules, depth);
            } else if (isTemporal(value)) {
                validateDate(value, path, rules);
            } else if (value instanceof Collection) {
                Collection<?> collection = (Collection<?>) value;
                if (checkSize(collection.size(), path, rules)) {
                    int i = 0;
                    for (Object element : collection) {
                        validateValue(element, path + "[" + i++ + "]", rules, depth + 1);
                    }
                }
            } else if (value instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) value;
                if (checkSize(map.size(), path, rules)) {
                    int i = 0;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        // ключ в путь не пишем: это пользовательские данные
                        String entryPath = path + "[#" + i++ + "]";
                        validateValue(entry.getKey(), entryPath + "<key>", rules.forKeys(), depth + 1);
                        validateValue(entry.getValue(), entryPath, rules, depth + 1);
                    }
                }
            } else if (value.getClass().isArray()) {
                if (value.getClass().getComponentType().isPrimitive() && !rules.hasNumberRange()) {
                    return; // byte[], char[] и т.п. — бинарные данные/секреты
                }
                int length = Array.getLength(value);
                if (checkSize(length, path, rules)) {
                    for (int i = 0; i < length; i++) {
                        validateValue(Array.get(value, i), path + "[" + i + "]", rules, depth + 1);
                    }
                }
            } else if (!value.getClass().isEnum() && !isPlatformClass(value.getClass())) {
                if (depth + 1 > rules.settings.maxDepth) {
                    if (rules.enabled(SecurityCheck.NESTING_DEPTH)) {
                        add(path, SecurityCheck.NESTING_DEPTH, "nesting depth exceeds " + rules.settings.maxDepth);
                    }
                    return;
                }
                Settings nested = value.getClass().isAnnotationPresent(SecureDto.class)
                        ? settingsFor(value.getClass()) : rules.settings;
                validateObject(value, path, nested, depth + 1);
            }
        }

        void validateString(String value, String path, Rules rules) {
            if (rules.hasNumberRange()) {
                throw new IllegalArgumentException("@SecureField min/max apply to numbers, but "
                        + rules.meta.where + " holds a string");
            }
            if (rules.enabled(SecurityCheck.STRING_LENGTH) && value.length() > rules.maxLength) {
                add(path, SecurityCheck.STRING_LENGTH, "string length " + value.length() + " exceeds " + rules.maxLength);
                return; // не гоняем регулярки по слишком длинным строкам
            }
            if (rules.enabled(SecurityCheck.CONTROL_CHARS)) {
                int idx = ThreatPatterns.indexOfForbiddenChar(value);
                if (idx >= 0) {
                    add(path, SecurityCheck.CONTROL_CHARS, String.format("forbidden character U+%04X at position %d",
                            (int) value.charAt(idx), idx));
                }
            }
            if (rules.pattern != null && rules.enabled(SecurityCheck.PATTERN)
                    && !rules.pattern.matcher(value).matches()) {
                add(path, SecurityCheck.PATTERN, "value does not match the allowed pattern");
            }
            for (SecurityCheck check : ThreatPatterns.detect(value, rules.enabled, forbiddenPatterns)) {
                add(path, check, "potential " + check.name().toLowerCase().replace('_', ' ') + " detected");
            }
        }

        void validateNumber(Number number, String path, Rules rules) {
            if (rules.pattern != null) {
                throw new IllegalArgumentException("@SecureField pattern applies to strings, but "
                        + rules.meta.where + " holds a number");
            }
            validateNumberRange(number, path, rules);
            if (!rules.enabled(SecurityCheck.NUMBER)) {
                return;
            }
            if (number instanceof Double || number instanceof Float) {
                double d = number.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    add(path, SecurityCheck.NUMBER, "NaN/Infinity is not allowed");
                }
            } else if (number instanceof BigDecimal) {
                BigDecimal bd = (BigDecimal) number;
                if (bd.precision() > MAX_BIG_NUMBER_DIGITS || Math.abs(bd.scale()) > MAX_BIG_NUMBER_DIGITS) {
                    add(path, SecurityCheck.NUMBER, "decimal number is too large (precision/scale > "
                            + MAX_BIG_NUMBER_DIGITS + ")");
                }
            } else if (number instanceof BigInteger) {
                if (((BigInteger) number).bitLength() > MAX_BIG_INTEGER_BITS) {
                    add(path, SecurityCheck.NUMBER, "integer number is too large");
                }
            }
        }

        void validateNumberRange(Number number, String path, Rules rules) {
            if (!rules.hasNumberRange() || !rules.enabled(SecurityCheck.NUMBER_RANGE)) {
                return;
            }
            BigDecimal value = toBigDecimal(number);
            if (value == null) {
                add(path, SecurityCheck.NUMBER_RANGE, "value is not a finite number");
            } else if (rules.min != null && value.compareTo(rules.min) < 0) {
                add(path, SecurityCheck.NUMBER_RANGE, "value is below minimum " + rules.min);
            } else if (rules.max != null && value.compareTo(rules.max) > 0) {
                add(path, SecurityCheck.NUMBER_RANGE, "value is above maximum " + rules.max);
            }
        }

        void validateDate(Object value, String path, Rules rules) {
            if (!rules.enabled(SecurityCheck.DATE_RANGE)) {
                return;
            }
            Instant instant = toInstant(value);
            if (instant == null) {
                return;
            }
            Instant min = rules.minDate(now);
            Instant max = rules.maxDate(now);
            if (instant.isBefore(min)) {
                add(path, SecurityCheck.DATE_RANGE, "date is before " + min);
            } else if (instant.isAfter(max)) {
                add(path, SecurityCheck.DATE_RANGE, "date is after " + max);
            }
        }

        void validateJsonNode(JsonNode node, String path, Rules rules, int depth) {
            if (node.isTextual()) {
                validateString(node.textValue(), path, rules);
            } else if (node.isNumber()) {
                validateNumber(node.numberValue(), path, rules);
            } else if (node.isContainerNode()) {
                if (depth + 1 > rules.settings.maxDepth) {
                    if (rules.enabled(SecurityCheck.NESTING_DEPTH)) {
                        add(path, SecurityCheck.NESTING_DEPTH, "nesting depth exceeds " + rules.settings.maxDepth);
                    }
                    return;
                }
                if (!checkSize(node.size(), path, rules)) {
                    return;
                }
                if (node.isArray()) {
                    for (int i = 0; i < node.size(); i++) {
                        validateJsonNode(node.get(i), path + "[" + i + "]", rules, depth + 1);
                    }
                } else {
                    int i = 0;
                    for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                        Map.Entry<String, JsonNode> entry = it.next();
                        String entryPath = path + "[#" + i++ + "]";
                        validateString(entry.getKey(), entryPath + "<key>", rules.forKeys());
                        validateJsonNode(entry.getValue(), entryPath, rules, depth + 1);
                    }
                }
            }
        }

        boolean checkSize(int size, String path, Rules rules) {
            if (rules.enabled(SecurityCheck.COLLECTION_SIZE) && size > rules.maxSize) {
                add(path, SecurityCheck.COLLECTION_SIZE, "size " + size + " exceeds " + rules.maxSize);
                return false;
            }
            return true;
        }

        void add(String path, SecurityCheck check, String message) {
            violations.add(new Violation(path.isEmpty() ? "<root>" : path, check, message));
        }
    }

    private static String join(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    /** Точное значение числа; {@code null} для NaN, Infinity и типов без числового представления. */
    private static BigDecimal toBigDecimal(Number number) {
        if (number instanceof BigDecimal) {
            return (BigDecimal) number;
        }
        if (number instanceof BigInteger) {
            return new BigDecimal((BigInteger) number);
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isTemporal(Object value) {
        return value instanceof Instant || value instanceof LocalDate || value instanceof LocalDateTime
                || value instanceof OffsetDateTime || value instanceof ZonedDateTime || value instanceof YearMonth
                || value instanceof Year || value instanceof Date || value instanceof Calendar;
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant) {
            return (Instant) value;
        } else if (value instanceof LocalDate) {
            return ((LocalDate) value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } else if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
        } else if (value instanceof OffsetDateTime) {
            return ((OffsetDateTime) value).toInstant();
        } else if (value instanceof ZonedDateTime) {
            return ((ZonedDateTime) value).toInstant();
        } else if (value instanceof YearMonth) {
            return ((YearMonth) value).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        } else if (value instanceof Year) {
            return ((Year) value).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        } else if (value instanceof Date) {
            // java.sql.Date.toInstant() бросает исключение, поэтому через миллисекунды
            return Instant.ofEpochMilli(((Date) value).getTime());
        } else if (value instanceof Calendar) {
            return Instant.ofEpochMilli(((Calendar) value).getTimeInMillis());
        }
        return null;
    }

    private static Pattern parsePattern(String regex, String where) {
        if (regex.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid @SecureField pattern in " + where, e);
        }
    }

    private static BigDecimal parseNumber(String value, String where) {
        if (value.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number '" + value + "' in @SecureField of " + where, e);
        }
    }

    static Instant parseBound(String value, String where) {
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            // пробуем следующий формат
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // пробуем следующий формат
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid ISO-8601 date '" + value + "' in " + where, e);
        }
    }

    private static Instant plusYears(Instant instant, int years) {
        return instant.atOffset(ZoneOffset.UTC).plusYears(years).toInstant();
    }

    /** Настройки уровня DTO. */
    private static final class Settings {
        final Set<SecurityCheck> skip;
        final int maxStringLength;
        final int maxCollectionSize;
        final Instant minDate;
        final int maxFutureYears;
        final int maxDepth;
        final boolean sqlStrict;

        private Settings(SecureDto a) {
            this.skip = toSet(a.skip());
            this.maxStringLength = a.maxStringLength();
            this.maxCollectionSize = a.maxCollectionSize();
            this.minDate = parseBound(a.minDate(), "@SecureDto.minDate");
            this.maxFutureYears = a.maxFutureYears();
            this.maxDepth = a.maxDepth();
            this.sqlStrict = a.sqlStrict();
        }

        static Settings of(SecureDto annotation) {
            return new Settings(annotation);
        }
    }

    /** Настройки уровня поля (статичны, кэшируются вместе с классом). */
    private static final class FieldMeta {
        final Field field;
        final String where;
        final Set<SecurityCheck> skip;
        final int maxLength;
        final int maxSize;
        final boolean sqlStrict;
        final Pattern pattern;
        final BigDecimal min;
        final BigDecimal max;
        final DateTimeRange range;
        final Instant rangeMin;
        final Instant rangeMax;

        private FieldMeta(Field field, String where, SecureField secureField, DateTimeRange range) {
            this.field = field;
            this.where = where;
            this.skip = secureField == null ? EnumSet.noneOf(SecurityCheck.class) : toSet(secureField.skip());
            this.maxLength = secureField == null ? -1 : secureField.maxLength();
            this.maxSize = secureField == null ? -1 : secureField.maxSize();
            this.sqlStrict = secureField != null && secureField.sqlStrict();
            this.pattern = secureField == null ? null : parsePattern(secureField.pattern(), where);
            this.min = secureField == null ? null : parseNumber(secureField.min(), where);
            this.max = secureField == null ? null : parseNumber(secureField.max(), where);
            if (min != null && max != null && min.compareTo(max) > 0) {
                throw new IllegalArgumentException("@SecureField min is greater than max in " + where);
            }
            this.range = range;
            this.rangeMin = range == null || range.min().isEmpty() ? null : parseBound(range.min(), where);
            this.rangeMax = range == null || range.max().isEmpty() ? null : parseBound(range.max(), where);
        }

        static FieldMeta of(Field field, SecureField secureField, DateTimeRange range) {
            return new FieldMeta(field, field.getDeclaringClass().getName() + "." + field.getName(), secureField, range);
        }

        static FieldMeta of(String parameterName, SecureField secureField, DateTimeRange range) {
            return new FieldMeta(null, parameterName, secureField, range);
        }
    }

    /** Итоговые правила для значения: настройки DTO + переопределения поля. */
    private static final class Rules {
        final Settings settings;
        final FieldMeta meta;
        final Set<SecurityCheck> enabled;
        final int maxLength;
        final int maxSize;
        final Pattern pattern;
        final BigDecimal min;
        final BigDecimal max;

        Rules(Settings settings, FieldMeta meta) {
            this(settings, meta, false);
        }

        private Rules(Settings settings, FieldMeta meta, boolean forKeys) {
            this.settings = settings;
            this.meta = meta;
            Set<SecurityCheck> e = EnumSet.allOf(SecurityCheck.class);
            if (!settings.sqlStrict && !meta.sqlStrict) {
                e.remove(SecurityCheck.SQL_STRICT);
            }
            e.removeAll(settings.skip);
            e.removeAll(meta.skip);
            this.enabled = e;
            this.maxLength = meta.maxLength >= 0 ? meta.maxLength : settings.maxStringLength;
            this.maxSize = meta.maxSize >= 0 ? meta.maxSize : settings.maxCollectionSize;
            this.pattern = forKeys ? null : meta.pattern;
            this.min = forKeys ? null : meta.min;
            this.max = forKeys ? null : meta.max;
        }

        /** Те же правила для ключей map: pattern, min и max описывают только значения. */
        Rules forKeys() {
            return new Rules(settings, meta, true);
        }

        boolean hasNumberRange() {
            return min != null || max != null;
        }

        boolean enabled(SecurityCheck check) {
            return enabled.contains(check);
        }

        Instant minDate(Instant now) {
            DateTimeRange range = meta.range;
            Instant min = meta.rangeMin != null ? meta.rangeMin : settings.minDate;
            if (range != null && range.maxPastYears() >= 0) {
                min = later(min, plusYears(now, -range.maxPastYears()));
            }
            if (range != null && range.notInPast()) {
                min = later(min, now.minus(TIMEZONE_TOLERANCE_DAYS, ChronoUnit.DAYS));
            }
            return min;
        }

        Instant maxDate(Instant now) {
            DateTimeRange range = meta.range;
            Instant max;
            if (meta.rangeMax != null) {
                max = meta.rangeMax;
            } else {
                int years = range != null && range.maxFutureYears() >= 0 ? range.maxFutureYears() : settings.maxFutureYears;
                max = plusYears(now, years);
            }
            if (range != null && range.notInFuture()) {
                Instant limit = now.plus(TIMEZONE_TOLERANCE_DAYS, ChronoUnit.DAYS);
                max = max.isAfter(limit) ? limit : max;
            }
            return max;
        }

        private static Instant later(Instant a, Instant b) {
            return a.isAfter(b) ? a : b;
        }
    }

    private static Set<SecurityCheck> toSet(SecurityCheck[] checks) {
        Set<SecurityCheck> set = EnumSet.noneOf(SecurityCheck.class);
        set.addAll(Arrays.asList(checks));
        return set;
    }

    /** Носитель значений {@link SecureDto} по умолчанию для классов без аннотации. */
    @SecureDto
    private static final class Defaults {
    }
}
