package ru.sbernpf.util;

/**
 * Виды проверок безопасности, выполняемых для полей DTO.
 */
public enum SecurityCheck {

    /** SQL-инъекции: тавтологии ({@code ' OR 1=1}), UNION SELECT, stacked queries, комментарии, time-based функции. */
    SQL_INJECTION,

    /**
     * Строгий SQL-фильтр: операторы SELECT, UNION, ALTER, UPDATE, EXEC, DROP, DELETE, INSERT и метасимволы
     * {@code `}, {@code --}, {@code /*} в любом контексте. Срабатывает на обычный текст, поэтому включается
     * только явно: {@link SecureField#sqlStrict()} или {@link SecureDto#sqlStrict()}.
     */
    SQL_STRICT,

    /** XSS: теги вне белого списка (pre, br, p, i, b без атрибутов), обработчики событий {@code on*=}, {@code javascript:}/{@code data:text/html} URI. */
    XSS,

    /** Обход каталогов: {@code ../}, {@code ..\}, в том числе URL-кодированные. */
    PATH_TRAVERSAL,

    /** Инъекции команд ОС: {@code ; rm}, {@code | curl}, {@code $(...)}, обратные кавычки. */
    COMMAND_INJECTION,

    /** Инъекции выражений/шаблонов и JNDI (Log4Shell): {@code ${...}}, {@code #{...}}, {@code {{...}}}. */
    TEMPLATE_INJECTION,

    /** Запрещённые значения, заданные для конкретной АС: см. {@link SecureValidator#SecureValidator(java.util.Collection)}. */
    FORBIDDEN_PATTERN,

    /** Управляющие символы: NUL, прочие C0/C1 (кроме \t \n \r), bidi-override (Trojan Source). */
    CONTROL_CHARS,

    /** Максимальная длина строки (защита от DoS). */
    STRING_LENGTH,

    /** Дата/время в допустимом диапазоне. */
    DATE_RANGE,

    /** Числа: NaN/Infinity, чрезмерно большие BigDecimal/BigInteger. */
    NUMBER,

    /** Строка соответствует {@link SecureField#pattern()} целиком (разрешённый набор значений). */
    PATTERN,

    /** Число в границах {@link SecureField#min()} и {@link SecureField#max()}. */
    NUMBER_RANGE,

    /** Максимальный размер коллекций, массивов и map. */
    COLLECTION_SIZE,

    /** Максимальная глубина вложенности объектов. */
    NESTING_DEPTH
}
