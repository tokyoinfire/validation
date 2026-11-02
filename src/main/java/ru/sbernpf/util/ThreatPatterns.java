package ru.sbernpf.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Сигнатуры атак для строковых значений.
 * <p>
 * Перед проверкой строка нормализуется (NFKC — полноширинные символы и т.п.) и
 * дополнительно проверяется после декодирования URL-кодировки и HTML-сущностей,
 * а SQL-комментарии {@code /**}{@code /} заменяются пробелами ({@code UNION/**}{@code /SELECT}).
 * <p>
 * Шаблоны подобраны так, чтобы не срабатывать на обычный текст: апострофы в фамилиях
 * ({@code O'Brien}), слова «select», «update», «or» в предложениях и т.п.
 */
final class ThreatPatterns {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL;

    private static final int MAX_DECODE_ROUNDS = 3;

    /** Единственные разрешённые теги, только без атрибутов (требование 10.4). */
    private static final String SAFE_TAGS = "pre|br|p|i|b";

    /**
     * Сущности, которые встречаются в обходах фильтров. Как и в браузере, {@code &lt} и подобные
     * работают без точки с запятой; {@code &lte=5} из строки запроса сущностью не считается.
     */
    private static final Pattern HTML_ENTITY = Pattern.compile("&(?:#[xX]([0-9a-fA-F]+);?|#([0-9]+);?"
            + "|(lt|gt|amp|quot|LT|GT|AMP|QUOT)(?![a-zA-Z0-9]*=);?"
            + "|(apos|colon|Tab|NewLine|lpar|rpar|sol|bsol|num|equals|grave);)");

    private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
            Map.entry("lt", "<"), Map.entry("LT", "<"), Map.entry("gt", ">"), Map.entry("GT", ">"),
            Map.entry("amp", "&"), Map.entry("AMP", "&"), Map.entry("quot", "\""), Map.entry("QUOT", "\""),
            Map.entry("apos", "'"), Map.entry("colon", ":"), Map.entry("Tab", "\t"), Map.entry("NewLine", "\n"),
            Map.entry("lpar", "("), Map.entry("rpar", ")"), Map.entry("sol", "/"), Map.entry("bsol", "\\"),
            Map.entry("num", "#"), Map.entry("equals", "="), Map.entry("grave", "`"));

    private static final Map<SecurityCheck, List<Pattern>> PATTERNS = new EnumMap<>(SecurityCheck.class);

    static {
        PATTERNS.put(SecurityCheck.SQL_INJECTION, compile(
                // ' OR 1=1 / ' or 'a'='a / ") and x>0
                "['\"`]\\s*\\)*\\s*(or|and|xor|\\|\\||&&)\\s+\\(*\\s*['\"`]?[\\w@]+['\"`]?\\s*(=|<>|!=|<|>|\\blike\\s*['\"])",
                // 1 OR 1=1
                "\\b(or|and)\\s+\\(*\\s*(\\d+)\\s*=\\s*\\2\\b",
                // ' or true--
                "['\"`]\\s*(or|and)\\s+(true|false)\\s*($|--|#|/\\*|;)",
                // UNION [ALL|DISTINCT] SELECT
                "\\bunion\\b(\\s+(all|distinct))?\\s+\\(*\\s*select\\b",
                // stacked queries: '; DROP TABLE ...
                ";\\s*(select|insert|update|delete|merge|drop|alter|truncate|exec|execute|declare|shutdown|grant|revoke)\\b",
                // DDL/DML
                "\\b(drop|truncate|alter)\\s+(table|database|schema|view|index|user|procedure|function|trigger|sequence)\\b",
                "\\bcreate\\s+(table|database|schema|procedure|function|trigger)\\b",
                "\\binsert\\s+into\\s+[\\w.\\[\\]\"`]+\\s*(\\(|values\\b|select\\b)",
                "\\bdelete\\s+from\\s+[\\w.\\[\\]\"`]+",
                "\\bupdate\\s+[\\w.\\[\\]\"`]+\\s+set\\s+[\\w.\\[\\]\"`]+\\s*=",
                // кавычка + комментарий: admin'-- , admin'#, ')/*
                "['\"`]\\s*\\)*\\s*(--|/\\*)",
                "['`]\\s*\\)*\\s*#",
                "\\*/",
                // time-based и служебные функции/объекты СУБД
                "\\b(sleep|pg_sleep|benchmark|dbms_pipe\\.receive_message|dbms_lock\\.sleep)\\s*\\(",
                "\\bwaitfor\\s+(delay|time)\\b",
                "\\b(xp_cmdshell|xp_regread|sp_executesql|sp_oacreate|sp_configure|openrowset|opendatasource)\\b",
                "\\b(information_schema|pg_catalog|sysobjects|syscolumns|all_tab_columns|mysql\\.user)\\b",
                "\\b(load_file|utl_http\\.request|utl_inaddr|extractvalue|updatexml)\\s*\\(",
                "\\binto\\s+(out|dump)file\\b",
                "@@(version|datadir|hostname|servername)",
                "\\b(char|chr|nchar)\\s*\\(\\s*\\d+\\s*\\)\\s*(\\+|\\|\\||,)",
                "\\bexec(ute)?\\s*\\(\\s*['\"@]"
        ));
        // требование 10.3: значения, которые попадают в SQL без параметризации. Слева допускаем цифру: 1union select
        PATTERNS.put(SecurityCheck.SQL_STRICT, compile(
                "(?<![a-z_])(select|union|alter|update|exec|execute|drop|delete|insert)(?![a-z0-9_])",
                "`",
                "--",
                "/\\*"
        ));
        PATTERNS.put(SecurityCheck.XSS, compile(
                // любой тег вне белого списка, а также теги белого списка с атрибутами и без закрывающей >
                "<(?!/?(" + SAFE_TAGS + ")>)/?[a-z!?]",
                "<\\s*/?\\s*(script|iframe|frame|frameset|object|embed|applet|svg|math|meta|link|style|base|form|img|video|audio|source|body|html|input|textarea|button|details|marquee|isindex|template)\\b",
                "<[^>]{0,256}\\bon[a-z]{3,}\\s*=",
                "\\bon(error|load|click|dblclick|mouse\\w+|focus|blur|key\\w+|submit|change|input|toggle|begin|animation\\w+|pointer\\w+)\\s*=",
                // javascript:alert(1), java\\tscript: (но не «JavaScript: The Good Parts»)
                "\\b(java|vb|live)[\\t\\n\\r]*script[\\t\\n\\r]*:(?!\\s)",
                "data\\s*:\\s*(text/html|application/(x?html|javascript)|image/svg)",
                "\\bexpression\\s*\\(",
                "\\b(document\\.(cookie|domain|write)|window\\.location|(alert|prompt|confirm|eval)\\()",
                "<!--|<!\\[cdata\\["
        ));
        PATTERNS.put(SecurityCheck.PATH_TRAVERSAL, compile(
                "(^|[/\\\\])\\.\\.([/\\\\]|$)",
                "^(/etc/(passwd|shadow|hosts)|/proc/self|c:\\\\windows|\\\\\\\\[\\w.]+\\\\)",
                "\\bfile:/"
        ));
        PATTERNS.put(SecurityCheck.COMMAND_INJECTION, compile(
                "([;|`]|&&?|\\$\\()\\s*(rm|cat|curl|wget|bash|sh|zsh|nc|ncat|netcat|python\\d?|perl|ruby|php|powershell|pwsh|cmd(\\.exe)?|chmod|chown|whoami|uname|ping|nslookup|telnet|ssh|scp|certutil|bitsadmin)\\b",
                delimited("\\$\\(", "\\)"),
                "`[^`]*`",
                "&&|\\|\\|"
        ));
        PATTERNS.put(SecurityCheck.TEMPLATE_INJECTION, compile(
                delimited("\\$\\{", "}"),
                delimited("#\\{", "}"),
                delimited("\\{\\{", "}}"),
                delimited("<%", "%>"),
                "\\b(ldap|ldaps|rmi|dns|iiop|corba|nds|nis)://"
        ));
    }

    private ThreatPatterns() {
    }

    /**
     * Возвращает проверки, по которым значение признано опасным.
     */
    static Set<SecurityCheck> detect(String value, Set<SecurityCheck> enabled, List<Pattern> forbidden) {
        Set<SecurityCheck> found = EnumSet.noneOf(SecurityCheck.class);
        List<String> variants = variants(value);
        for (Map.Entry<SecurityCheck, List<Pattern>> entry : PATTERNS.entrySet()) {
            if (enabled.contains(entry.getKey()) && matchesAny(variants, entry.getValue())) {
                found.add(entry.getKey());
            }
        }
        if (enabled.contains(SecurityCheck.FORBIDDEN_PATTERN) && matchesAny(variants, forbidden)) {
            found.add(SecurityCheck.FORBIDDEN_PATTERN);
        }
        return found;
    }

    private static boolean matchesAny(List<String> variants, List<Pattern> patterns) {
        for (String variant : variants) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(variant).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Компилирует запрещённые выражения конкретной АС с теми же флагами, что и встроенные шаблоны.
     * Дополнительно включает Unicode-классы: без них {@code \\b} и {@code \\w} не видят кириллицу.
     *
     * @throws IllegalArgumentException если выражение пустое или некорректное
     */
    static List<Pattern> compileForbidden(Collection<String> regexes) {
        List<Pattern> list = new ArrayList<>(regexes.size());
        for (String regex : regexes) {
            if (regex.isBlank()) {
                throw new IllegalArgumentException("Forbidden pattern must not be blank");
            }
            try {
                list.add(Pattern.compile(regex, FLAGS | Pattern.UNICODE_CHARACTER_CLASS));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid forbidden pattern '" + regex + "'", e);
            }
        }
        return List.copyOf(list);
    }

    /**
     * Индекс первого запрещённого управляющего символа или {@code -1}.
     * Разрешены \t, \n, \r. Запрещены NUL и прочие C0, DEL, C1, bidi-override/isolate,
     * zero-width символы, которые используют для обхода фильтров и подмены текста.
     */
    static int indexOfForbiddenChar(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            if (c < 0x20 || (c >= 0x7F && c <= 0x9F)
                    || (c >= '\u202A' && c <= '\u202E')
                    || (c >= '\u2066' && c <= '\u2069')
                    || c == '\u200B' || c == '\u200E' || c == '\u200F' || c == '\u2028' || c == '\u2029'
                    || c == '\uFEFF') {
                return i;
            }
            if (Character.isSurrogate(c)) {
                boolean validPair = Character.isHighSurrogate(c) && i + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(i + 1));
                if (!validPair) {
                    return i;
                }
                i++;
            }
        }
        return -1;
    }

    private static List<String> variants(String value) {
        List<String> result = new ArrayList<>(4);
        String current = Normalizer.normalize(value, Normalizer.Form.NFKC);
        result.add(current);
        // несколько раундов: защита от двойного кодирования (%2527, &amp;lt; и т.п.)
        for (int round = 0; round < MAX_DECODE_ROUNDS; round++) {
            String decoded = current.indexOf('%') >= 0 ? urlDecode(current) : current;
            if (decoded.indexOf('&') >= 0) {
                decoded = decodeHtmlEntities(decoded);
            }
            if (decoded.equals(current)) {
                break;
            }
            current = Normalizer.normalize(decoded, Normalizer.Form.NFKC);
            result.add(current);
        }
        int size = result.size();
        for (int i = 0; i < size; i++) {
            String s = result.get(i);
            if (s.contains("/*")) {
                result.add(s.replaceAll("/\\*.*?\\*/", " "));
            }
        }
        return result;
    }

    private static String urlDecode(String value) {
        // '+' оставляем как есть: в JSON-полях это обычно не пробел
        String prepared = value.replace("+", "%2B");
        try {
            return URLDecoder.decode(prepared, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // некорректная %-последовательность: декодируем то, что можно
            StringBuilder sb = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '%' && i + 2 < value.length()
                        && Character.digit(value.charAt(i + 1), 16) >= 0
                        && Character.digit(value.charAt(i + 2), 16) >= 0) {
                    sb.append((char) Integer.parseInt(value.substring(i + 1, i + 3), 16));
                    i += 2;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }

    private static String decodeHtmlEntities(String value) {
        Matcher matcher = HTML_ENTITY.matcher(value);
        StringBuilder sb = new StringBuilder(value.length());
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(entityValue(matcher)));
        }
        return matcher.appendTail(sb).toString();
    }

    private static String entityValue(Matcher entity) {
        if (entity.group(3) != null) {
            return NAMED_ENTITIES.get(entity.group(3));
        }
        if (entity.group(4) != null) {
            return NAMED_ENTITIES.get(entity.group(4));
        }
        try {
            int codePoint = entity.group(1) != null
                    ? Integer.parseInt(entity.group(1), 16) : Integer.parseInt(entity.group(2));
            return Character.isValidCodePoint(codePoint) ? Character.toString(codePoint) : entity.group();
        } catch (NumberFormatException e) {
            return entity.group();
        }
    }

    /**
     * Шаблон «открывающий, затем закрывающий разделитель» с линейным временем: обычный {@code \\$\\{[^}]*}}
     * на строке из повторов {@code ${} квадратичен. Пара есть тогда и только тогда, когда закрывающий
     * разделитель стоит после первого открывающего, поэтому проверяется только он (атомарная группа).
     */
    private static String delimited(String open, String close) {
        return "\\A(?>.*?" + open + ").*" + close;
    }

    private static List<Pattern> compile(String... regexes) {
        List<Pattern> list = new ArrayList<>(regexes.length);
        for (String regex : regexes) {
            list.add(Pattern.compile(regex, FLAGS));
        }
        return List.copyOf(list);
    }
}
