# Техническое руководство по проекту координатного обратного индекса

## 1. Назначение документа

Этот документ описывает фактическую реализацию проекта `lab-5-inverted-index` на уровне архитектуры, алгоритмов, форматов данных, классов и файлов.

Документ отвечает на вопросы:

- какую задачу решает проект;
- как документ проходит путь от исходного файла до результата поиска;
- как устроен координатный обратный индекс;
- как выполняются булевы и позиционные запросы;
- как индекс сохраняется на диск и читается без полной загрузки posting lists;
- какие способы сжатия реализованы;
- как считается BM25;
- как устроены CLI, HTTP API и браузерный интерфейс;
- почему в индексе хранится snippet, а полный документ открывается из исходного XML;
- какие тесты, бенчмарки и профили существуют;
- за что отвечает каждый production-файл проекта;
- какие ограничения и осознанные компромиссы есть у текущей реализации.

Состояние исходников, описанное здесь, актуально на **14 июня 2026 года**.

## 2. Краткое описание проекта

Проект реализует небольшую поисковую систему на Java на основе **координатного обратного индекса**.

Основные возможности:

- загрузка документов из TXT-каталога;
- загрузка корпуса BEIR в JSONL;
- потоковая загрузка MediaWiki XML dump;
- очистка wiki-разметки до читаемого текста;
- Unicode-токенизация с сохранением позиций;
- построение posting lists с `docId`, term frequency и позициями;
- булевы операции `AND`, `OR`, `NOT`;
- позиционные операции `ADJ`, `EDGE`, `NEAR/k`;
- скобки и полноценное AST запроса;
- skip pointers в памяти и persistent skip table на диске;
- восемь конфигураций сжатия целых чисел;
- дисковый reader через `FileChannel`;
- reader через страничный memory mapping;
- потоковое выполнение запросов через курсоры;
- ранжирование BM25;
- точный подсчёт общего числа результатов;
- постраничная выдача;
- CLI;
- HTTP API и браузерный UI;
- открытие исходной страницы Wikipedia по `pageId`;
- JUnit-тесты;
- JMH-бенчмарки;
- JFR CPU/allocation profiles и HTML flame graphs.

Главный рабочий путь поиска выглядит так:

```text
источник документов
    -> DocumentLoader
    -> WikipediaMarkupCleaner, если это Wikipedia
    -> SimpleTokenizer
    -> InMemoryInvertedIndex
    -> DiskIndexWriter
    -> файлы индекса
    -> DiskIndexReader или MMapIndexReader
    -> AntlrQueryParser
    -> QueryNode AST
    -> StreamingQueryExecutor
    -> PostingCursor кандидатов
    -> DiskBM25Scorer
    -> SearchPage / SearchResult
    -> CLI или HTTP JSON
```

## 3. Технологический стек и сборка

### 3.1. Версия Java

В [`pom.xml`](pom.xml) задано:

```xml
<maven.compiler.release>11</maven.compiler.release>
```

Исходники совместимы с Java 11. Для полученных профилей и последних бенчмарков использовался JDK 21.0.10, но язык и API production-кода ограничены release 11.

### 3.2. Зависимости

Проект намеренно имеет небольшой набор внешних зависимостей:

| Зависимость | Версия | Назначение |
| --- | ---: | --- |
| ANTLR runtime | 4.13.1 | лексический и синтаксический анализ поисковых запросов |
| JMH core | 1.37 | микробенчмарки |
| JMH annotation processor | 1.37 | генерация инфраструктуры JMH во время компиляции |
| JUnit Jupiter | 5.10.2 | модульные и интеграционные тесты |

HTTP-сервер, XML parser, NIO, memory mapping, Deflate и JSON-ответы реализованы средствами JDK. Отдельный web framework не используется.

### 3.3. Maven plugins

[`pom.xml`](pom.xml) настраивает:

- `antlr4-maven-plugin`: генерирует lexer, parser и visitor из грамматики;
- `maven-surefire-plugin`: запускает JUnit 5, `useModulePath=false`;
- `maven-shade-plugin`: собирает fat JAR `target/benchmarks.jar`.

Main class shaded JAR:

```text
org.openjdk.jmh.Main
```

Поэтому JMH запускается через `java -jar`, а прикладные main-классы через `java -cp`.

### 3.4. Основные команды

Сборка и тесты:

```powershell
mvn clean test
```

Сборка fat JAR:

```powershell
mvn -DskipTests package
```

Запуск JMH:

```powershell
java -jar target\benchmarks.jar
```

Запуск CLI:

```powershell
java -cp target\benchmarks.jar searchengine.SearchCli --help
```

Запуск web UI:

```powershell
.\profiling\run-web.ps1 -SkipBuild
```

## 4. Структура репозитория

В `src/main/java` находится 73 вручную написанных Java-файла. В `src/test/java` находится 14 тестовых классов.

Во время Maven build компилируется 77 production source files: к 73 ручным файлам добавляются четыре Java-файла, сгенерированные ANTLR из `SearchQuery.g4`.

```text
.
├── pom.xml
├── report.md
├── PROJECT_GUIDE.md
├── data/
├── profiling/
│   ├── flamegraphs/
│   ├── report-assets/
│   ├── jfr-*/
│   ├── jmh-*.json
│   ├── results-*.md
│   └── *.ps1
├── src/
│   ├── main/
│   │   ├── antlr4/SearchQuery.g4
│   │   ├── java/searchengine/
│   │   │   ├── SearchCli.java
│   │   │   ├── benchmark/
│   │   │   ├── compression/
│   │   │   ├── document/
│   │   │   ├── index/
│   │   │   ├── query/
│   │   │   ├── ranking/
│   │   │   ├── storage/
│   │   │   ├── tokenizer/
│   │   │   └── web/
│   │   └── resources/web/
│   └── test/java/searchengine/
└── target/
    ├── benchmarks.jar
    └── wiki-index-100k-readable-2026-06-13/
```

`target` содержит генерируемые артефакты и не является частью ручной реализации. ANTLR-классы также генерируются Maven plugin в `target/generated-sources/antlr4`.

## 5. Основные понятия модели

### 5.1. Внутренний и внешний идентификаторы

У документа два идентификатора:

- `docId` — положительное целое число, используемое внутри индекса;
- `externalId` — строковый идентификатор источника.

Для Wikipedia:

- `docId` — последовательный номер документа в построенном индексе;
- `externalId` — MediaWiki page id.

Разделение важно: внутренний integer `docId` компактно хранится и быстро сравнивается, а внешний ID позволяет открыть исходный документ.

### 5.2. Терм

Терм — нормализованная последовательность Unicode-букв и цифр. Токенизатор:

- переводит текст в lowercase через `Locale.ROOT`;
- не выполняет stemming;
- не выполняет лемматизацию;
- не удаляет stop words;
- не хранит пунктуацию как токены.

Слова `Россия`, `РОССИЯ` и `россия` превращаются в один терм `россия`, но формы `россия`, `россии`, `россией` остаются разными термами.

### 5.3. Позиция

Позиция — порядковый номер токена в документе, начиная с нуля.

Для текста:

```text
Новая история России
```

получатся токены:

```text
новая:0
история:1
россии:2
```

### 5.4. Posting

Логическая структура posting:

```text
(docId, termFrequency, [positions...])
```

Пример:

```text
(42, 3, [5, 18, 91])
```

Это означает, что терм встречается в документе `42` три раза на позициях `5`, `18`, `91`.

### 5.5. Posting list

Posting list — отсортированный по `docId` список posting одного терма.

```text
история -> [
    (1, 2, [4, 15]),
    (8, 1, [2]),
    (19, 3, [7, 20, 41])
]
```

Document frequency терма равна длине posting list.

### 5.6. Координатный обратный индекс

Индекс является **координатным**, потому что хранит не только факт присутствия терма, но и позиции. Благодаря этому можно вычислять:

- точное соседство;
- порядок терминов;
- расстояние между термами;
- цепочки позиционных операторов.

## 6. Полный путь построения индекса

### 6.1. Загрузка документов

Все loaders реализуют [`DocumentLoader`](src/main/java/searchengine/document/DocumentLoader.java):

```java
Iterator<Document> load();
```

Контракт позволяет источнику отдавать документы по одному. Реальная степень потоковости зависит от loader:

- TXT loader сначала собирает и читает все подходящие файлы;
- BEIR loader читает JSONL построчно;
- Wikipedia loader читает XML через StAX по одной странице.

### 6.2. Подготовка Wikipedia

[`WikipediaDumpDocumentLoader`](src/main/java/searchengine/document/WikipediaDumpDocumentLoader.java):

1. открывает XML dump;
2. отключает DTD и внешние сущности, если StAX implementation поддерживает свойства;
3. проходит события `START_ELEMENT`, `CHARACTERS`, `CDATA`, `END_ELEMENT`;
4. берёт page-level `title`, первый page-level `id` и revision `text`;
5. очищает wikitext через `WikipediaMarkupCleaner`;
6. объединяет title и читаемый body;
7. возвращает `Document`.

Индексируется именно очищенный текст. Web UI показывает текст, полученный тем же cleaner, поэтому пользователь проверяет содержание, близкое к тому, которое было токенизировано.

### 6.3. Токенизация

[`SimpleTokenizer`](src/main/java/searchengine/tokenizer/SimpleTokenizer.java) выполняет один проход по lowercase-строке.

Он итерируется по Unicode code points, а не по отдельным UTF-16 `char`. Это корректно обрабатывает supplementary characters и surrogate pairs.

Максимальная последовательность `Character.isLetterOrDigit(codePoint)` становится токеном. Каждому токену назначается следующая позиция.

Временная сложность:

```text
O(число UTF-16 code units текста)
```

Дополнительная память пропорциональна числу токенов, потому что метод возвращает `List<Token>`.

### 6.4. Накопление позиций

[`InMemoryInvertedIndex`](src/main/java/searchengine/index/InMemoryInvertedIndex.java) строит промежуточную структуру:

```text
Map<String, Map<Integer, IntPositions>>
```

Где:

- внешний ключ — терм;
- внутренний ключ — `docId`;
- `IntPositions` — расширяемый primitive `int[]`.

Использование primitive array вместо `List<Integer>` снижает boxing и давление на GC.

Для каждого токена выполняется:

```text
term
    -> map документов
    -> accumulator позиций документа
    -> add(position)
```

### 6.5. Метаданные документа

Одновременно создаётся [`DocumentMeta`](src/main/java/searchengine/document/DocumentMeta.java):

- `docId`;
- `externalId`;
- длина в токенах;
- snippet.

Snippet:

- строится одним проходом;
- объединяет последовательности whitespace в один пробел;
- ограничен 500 символами;
- при обрезании получает `...`.

Полный текст в `DocumentMeta` не сохраняется.

### 6.6. Финализация posting lists

После чтения документов:

1. для каждого `(term, docId)` accumulator превращается в `int[]`;
2. `termFrequency = positions.length`;
3. создаётся `Posting`;
4. `PostingList` сортирует postings по `docId`;
5. строятся in-memory skip pointers;
6. вычисляется средняя длина документа.

Средняя длина:

```text
avgdl = сумма числа токенов / число документов
```

Она используется BM25.

### 6.7. Запись на диск

[`DiskIndexWriter`](src/main/java/searchengine/storage/DiskIndexWriter.java):

1. создаёт каталог;
2. сортирует термы лексикографически;
3. кодирует postings каждого терма;
4. кодирует positions каждого терма;
5. последовательно дописывает блоки в `postings.bin` и `positions.bin`;
6. запоминает offset и length каждого блока;
7. записывает `dictionary.bin`;
8. записывает `documents.bin`;
9. формирует `meta.json`.

Файлы открываются с `CREATE`, `TRUNCATE_EXISTING`, `WRITE`, поэтому повторная запись полностью заменяет прежнее содержимое файлов индекса.

## 7. Загрузчики документов

### 7.1. `Document.java`

Файл: [`Document.java`](src/main/java/searchengine/document/Document.java)

Immutable входная модель:

- `int docId`;
- `String externalId`;
- `String text`.

Строковые поля проверяются через `Objects.requireNonNull`. Класс не проверяет знак `docId`: если loader передаст неположительный ID, `InMemoryInvertedIndex` назначит generated ID.

### 7.2. `DocumentMeta.java`

Файл: [`DocumentMeta.java`](src/main/java/searchengine/document/DocumentMeta.java)

Компактная метаинформация, сохраняемая в индексе:

- ID;
- внешний ID;
- длина документа в токенах;
- snippet.

Есть конструктор без snippet для чтения старых форматов.

### 7.3. `DocumentLoader.java`

Файл: [`DocumentLoader.java`](src/main/java/searchengine/document/DocumentLoader.java)

Минимальный интерфейс источника документов. Он не наследует `AutoCloseable`; конкретные потоковые iterator закрывают свои ресурсы при достижении EOF или лимита.

### 7.4. `TxtFolderDocumentLoader.java`

Файл: [`TxtFolderDocumentLoader.java`](src/main/java/searchengine/document/TxtFolderDocumentLoader.java)

Алгоритм:

1. рекурсивный `Files.walk(folder)`;
2. фильтр regular files;
3. расширение `.txt`, регистр расширения игнорируется;
4. сортировка по строковому пути;
5. UTF-8 чтение каждого файла;
6. `docId` от 1;
7. `externalId` — относительный путь.

Loader детерминирован из-за сортировки, но не потоковый по памяти: сначала создаёт список путей, затем список всех `Document`.

### 7.5. `BeirDocumentLoader.java`

Файл: [`BeirDocumentLoader.java`](src/main/java/searchengine/document/BeirDocumentLoader.java)

Читает BEIR `corpus.jsonl` лениво по одной строке.

Поддерживаемые поля:

- `_id`;
- `title`;
- `text`.

Индексируемый текст:

```text
title + " " + text
```

Если title пуст, используется только text. Если `_id` отсутствует, внешний ID равен generated `docId`.

JSON извлекается небольшим ручным parser, а не полноценной JSON-библиотекой. Реализованы escapes:

- `\n`;
- `\r`;
- `\t`;
- `\"`;
- `\\`;
- `\uXXXX`.

Это решение достаточно для ожидаемого формата BEIR, но не является универсальным JSON parser.

### 7.6. `WikipediaDumpDocumentLoader.java`

Файл: [`WikipediaDumpDocumentLoader.java`](src/main/java/searchengine/document/WikipediaDumpDocumentLoader.java)

Потоковый StAX loader MediaWiki XML.

Внутренний `WikipediaIterator` содержит:

- `InputStream`;
- `XMLStreamReader`;
- счётчик выданных документов;
- следующий внутренний ID;
- состояние текущей `<page>`;
- builders для title, page id и text.

`maxDocuments == 0` означает отсутствие лимита.

Page ID выбирается в порядке:

1. непустой page-level `<id>`;
2. title;
3. generated internal ID.

После достижения лимита reader и stream закрываются.

### 7.7. `WikipediaDumpPageReader.java`

Файл: [`WikipediaDumpPageReader.java`](src/main/java/searchengine/document/WikipediaDumpPageReader.java)

Используется не для индексации, а для открытия исходных страниц по `pageId`.

Методы:

- `findById(Path, String)`;
- `findByIds(Path, Collection<String>)`.

Reader делает последовательный проход по XML dump. `PageState.depth` позволяет отличить page-level `<id>` от revision ID.

Для набора ID:

- пустые и null ID отбрасываются;
- поиск прекращается, когда найдены все ID;
- возвращаемый `LinkedHashMap` упорядочивается как входная коллекция.

Стоимость первого открытия страницы в худшем случае:

```text
O(размер XML dump)
```

Это главный компромисс текущего решения открытия документов.

### 7.8. `WikipediaSourcePage.java`

Файл: [`WikipediaSourcePage.java`](src/main/java/searchengine/document/WikipediaSourcePage.java)

Immutable модель исходной страницы:

- `pageId`;
- `title`;
- raw revision text.

Raw text может содержать wiki-разметку. В HTTP API перед отправкой он очищается.

### 7.9. `WikipediaMarkupCleaner.java`

Файл: [`WikipediaMarkupCleaner.java`](src/main/java/searchengine/document/WikipediaMarkupCleaner.java)

Best-effort преобразователь wikitext в читаемый plain text.

Последовательность обработки:

1. нормализация line endings;
2. удаление XML comments;
3. удаление парных `<ref>` и `<math>`;
4. удаление block tags `references`, `gallery`, `source`, `syntaxhighlight`, `timeline`;
5. удаление сбалансированных шаблонов `{{...}}`;
6. удаление сбалансированных таблиц `{|...|}`;
7. замена внутренних ссылок;
8. замена внешних ссылок;
9. удаление остальных HTML tags;
10. удаление magic words;
11. удаление wiki bold/italic apostrophes;
12. декодирование entities;
13. нормализация строк.

Для внутренних ссылок:

- `[[Target|Label]]` превращается в `Label`;
- `[[Target]]` превращается в `Target`;
- section suffix после `#` удаляется;
- underscores заменяются пробелами;
- file/image/category links удаляются;
- вложенные internal links обрабатываются рекурсивно.

Для внешних ссылок:

- `[https://host Label]` превращается в `Label`;
- URL без label удаляется.

Сбалансированные конструкции удаляются собственным depth counter. Если closing marker не найден, cleaner сохраняет остаток текста, чтобы повреждённая разметка не уничтожила всё последующее содержимое.

Это не полный MediaWiki renderer. Шаблоны не раскрываются, Lua modules не исполняются, HTML layout не воспроизводится.

## 8. Токенизация

### 8.1. `Tokenizer.java`

Файл: [`Tokenizer.java`](src/main/java/searchengine/tokenizer/Tokenizer.java)

Стратегический интерфейс:

```java
List<Token> tokenize(String text);
```

Позволяет заменить правила токенизации без изменения index builder.

### 8.2. `Token.java`

Файл: [`Token.java`](src/main/java/searchengine/tokenizer/Token.java)

Immutable пара:

- нормализованный term;
- позиция.

### 8.3. `SimpleTokenizer.java`

Файл: [`SimpleTokenizer.java`](src/main/java/searchengine/tokenizer/SimpleTokenizer.java)

Реализация на основе `Character.isLetterOrDigit`.

Особенности:

- locale-independent lowercase;
- корректное движение по code points;
- отсутствие пустых токенов;
- позиция увеличивается только при завершении реального токена;
- дефис, punctuation и whitespace являются разделителями.

Например:

```text
Санкт-Петербург 2026
```

даёт:

```text
санкт:0
петербург:1
2026:2
```

## 9. In-memory индекс и posting lists

### 9.1. `InMemoryInvertedIndex.java`

Файл: [`InMemoryInvertedIndex.java`](src/main/java/searchengine/index/InMemoryInvertedIndex.java)

Главный builder и in-memory facade.

Поля:

- `Tokenizer tokenizer`;
- `Map<String, PostingList> index`;
- `Map<Integer, DocumentMeta> documents`;
- `double avgDocumentLength`.

`HashMap` используется для термов. `LinkedHashMap` документов сохраняет порядок загрузки.

Основные методы:

- `build(Iterable<Document>)`;
- `build(Iterator<Document>)`;
- `getPostingList(term)`;
- `getIndex()`;
- `getDocuments()`;
- `getDocumentMeta(docId)`;
- `getDocumentFrequency(term)`;
- `getUniverse()`;
- `search(query, topK, rankResults)`.

`build` очищает старое состояние, поэтому объект представляет только последний построенный индекс.

Duplicate internal `docId` вызывает `IllegalArgumentException`.

`getPostingList` lowercase-нормализует term и возвращает пустой список для неизвестного терма.

Внутренний `IntPositions` — автоматически расширяемый массив с начальной ёмкостью 4 и удвоением.

### 9.2. `Posting.java`

Файл: [`Posting.java`](src/main/java/searchengine/index/Posting.java)

Хранит:

- `docId`;
- `termFrequency`;
- `int[] positions`.

Публичный constructor делает defensive copy массива. Публичный getter также возвращает copy.

Оптимизированные package-private пути:

- `positionsView()` возвращает внутренний массив;
- `trusted(...)` принимает уже принадлежащий вызывающему массив;
- `docOnly(...)` создаёт posting без positions.

Эти пути уменьшают копирование внутри package, но не открыты внешнему API.

Класс сам не проверяет соответствие `termFrequency` длине positions. Корректность обеспечивается местами создания и тестами.

### 9.3. `PostingList.java`

Файл: [`PostingList.java`](src/main/java/searchengine/index/PostingList.java)

Содержит:

- term;
- immutable view postings;
- список skip pointers;
- массив `skipBySourceIndex` для O(1) получения skip из текущего индекса.

Публичный constructor копирует и сортирует postings по `docId`.

Внутренний `trustedSorted` пропускает сортировку и используется алгоритмами, которые уже формируют результат в правильном порядке.

Skip pointers строятся, если `size >= 4`.

Шаг:

```text
floor(sqrt(size))
```

Для индексов:

```text
0, step, 2*step, ...
```

создаётся переход `from -> from + step`, пока target остаётся внутри списка.

### 9.4. `SkipPointer.java`

Файл: [`SkipPointer.java`](src/main/java/searchengine/index/SkipPointer.java)

Хранит:

- source index;
- target index;
- target `docId`.

Это in-memory skip pointer. Дисковый skip содержит больше информации: byte offsets и decoder state.

### 9.5. `DocIdSet.java`

Файл: [`DocIdSet.java`](src/main/java/searchengine/index/DocIdSet.java)

Представляет universe документов для `NOT`.

Constructor превращает `List<Integer>` в отсортированный `int[]`.

Важно: класс сортирует, но сам не удаляет дубликаты. В нормальном потоке universe строится из уникальных keys `documents`, поэтому дубликатов нет.

### 9.6. `PostingListOperations.java`

Файл: [`PostingListOperations.java`](src/main/java/searchengine/index/PostingListOperations.java)

Содержит статические алгоритмы над отсортированными posting lists.

Внутренний `IntArrayBuilder` накапливает результаты merge и positional matching в primitive `int[]`, расширяя capacity вдвое и не создавая boxed `Integer`.

#### `AND` без skips

Классический two-pointer intersection:

```text
если docA == docB: добавить и сдвинуть оба
если docA < docB: сдвинуть A
иначе: сдвинуть B
```

Сложность:

```text
O(|A| + |B|)
```

#### Adaptive `AND` со skips

Skips используются только если:

```text
min(|A|, |B|) >= 4
и
max(|A|, |B|) >= 4 * min(|A|, |B|)
```

То есть оптимизация включается для достаточно больших и заметно несбалансированных списков. На близких по размеру списках дополнительные проверки skips могут не окупаться.

`advance` переходит по skip, если `targetDocId <= искомого docId`, иначе двигается на один posting.

#### `OR`

Merge двух отсортированных списков. Одинаковые `docId` выдаются один раз.

#### `AND NOT`

Для каждого posting included-списка excluded cursor продвигается до его `docId`. Posting добавляется, если такого ID в excluded нет.

#### Полный `NOT`

Вычитается posting list из отсортированного universe. Результирующие postings имеют TF 0 и пустые positions.

#### Сохранение позиций в boolean-ветках

Параметр `preserveAllPositions` нужен, когда boolean expression является operand позиционного оператора.

Для совпавшего документа позиции обеих веток объединяются:

- merge sorted arrays;
- duplicate positions удаляются;
- TF становится числом объединённых позиций.

#### `ADJ`

Для совпавших документов two-pointer алгоритм ищет:

```text
rightPosition == leftPosition + 1
```

В результирующий posting записываются **позиции правого терма**. Это позволяет продолжать цепочку:

```text
A ADJ B ADJ C
```

После `A ADJ B` anchors указывают на B, затем проверяется C сразу после B.

#### `NEAR/k`

Ищется:

```text
abs(leftPosition - rightPosition) <= k
```

Порядок не важен. Алгоритм не строит Cartesian product всех позиций, а линейно двигает два указателя.

В результат записываются distinct позиции левой стороны, участвовавшие в совпадении.

`k` может быть нулём на уровне модели, хотя два разных терма обычно не занимают одну позицию.

## 10. Язык запросов

### 10.1. Грамматика

Файл: [`SearchQuery.g4`](src/main/antlr4/SearchQuery.g4)

Поддерживаются:

```text
A AND B
A OR B
NOT A
A ADJ B
A EDGE B
A NEAR/5 B
(A OR B) AND C
```

Операторы регистронезависимы.

### 10.2. Приоритеты

От более высокого к более низкому:

1. parentheses;
2. позиционные `ADJ`, `EDGE`, `NEAR/k`;
3. unary `NOT`;
4. `AND`;
5. `OR`.

Операторы одного уровня собираются слева направо.

### 10.3. Нет неявного `AND`

Запрос:

```text
история россии
```

не поддерживается. После первого `TERM` parser ожидает конец запроса или явный оператор, поэтому возникает ошибка вида:

```text
Invalid query at 1:8 - extraneous input 'россии' expecting <EOF>
```

Нужно писать:

```text
история AND россии
```

### 10.4. TERM token

`TERM` — любая непустая последовательность символов, кроме whitespace и parentheses.

Зарезервированные слова `AND`, `OR`, `NOT`, `ADJ`, `EDGE` распознаются как операторы, а не как обычные terms.

### 10.5. `EDGE`

`EDGE` является синтаксическим синонимом `ADJ`. Отдельного AST node или отдельного алгоритма для него нет.

### 10.6. Сгенерированные ANTLR-классы

Из [`SearchQuery.g4`](src/main/antlr4/SearchQuery.g4) Maven генерирует четыре production source files:

| Класс | Назначение |
| --- | --- |
| `SearchQueryLexer` | превращает строку в tokens операторов, terms и parentheses |
| `SearchQueryParser` | строит parse tree по grammar rules |
| `SearchQueryVisitor<T>` | generic visitor interface для parse tree |
| `SearchQueryBaseVisitor<T>` | default visitor implementation, от которой наследуется `AstBuilder` |

Generated package задаётся секцией `@header`:

```text
searchengine.query.antlr
```

Эти файлы не редактируются вручную. Изменять нужно grammar, после чего Maven пересоздаст Java-код.

## 11. AST запроса

### 11.1. `QueryParser.java`

Файл: [`QueryParser.java`](src/main/java/searchengine/query/QueryParser.java)

Интерфейс parser:

```java
QueryNode parse(String query);
```

### 11.2. `QueryNode.java`

Файл: [`QueryNode.java`](src/main/java/searchengine/query/QueryNode.java)

Базовый интерфейс AST.

Методы:

- `terms()` — все terms, включая отрицательные;
- `positiveTerms()` — terms, влияющие на BM25.

По умолчанию `positiveTerms()` равен `terms()`.

### 11.3. `TermNode.java`

Файл: [`TermNode.java`](src/main/java/searchengine/query/TermNode.java)

Лист AST. Term lowercase-нормализуется в constructor.

### 11.4. `BinaryNode.java`

Файл: [`BinaryNode.java`](src/main/java/searchengine/query/BinaryNode.java)

Package-private базовый класс бинарных nodes.

Хранит `left` и `right`. `terms()` и `positiveTerms()` объединяют ordered sets дочерних nodes через `LinkedHashSet`.

### 11.5. `AndNode.java`

Файл: [`AndNode.java`](src/main/java/searchengine/query/AndNode.java)

Маркер AST для conjunction.

### 11.6. `OrNode.java`

Файл: [`OrNode.java`](src/main/java/searchengine/query/OrNode.java)

Маркер AST для disjunction.

### 11.7. `AdjNode.java`

Файл: [`AdjNode.java`](src/main/java/searchengine/query/AdjNode.java)

Маркер ordered exact adjacency.

### 11.8. `NearNode.java`

Файл: [`NearNode.java`](src/main/java/searchengine/query/NearNode.java)

Дополнительно хранит non-negative distance.

### 11.9. `NotNode.java`

Файл: [`NotNode.java`](src/main/java/searchengine/query/NotNode.java)

Unary node.

`terms()` возвращает terms child, но `positiveTerms()` возвращает пустой set. Поэтому отрицательные terms используются для фильтрации, но не добавляют BM25 score.

### 11.10. `AntlrQueryParser.java`

Файл: [`AntlrQueryParser.java`](src/main/java/searchengine/query/AntlrQueryParser.java)

Создаёт:

- `SearchQueryLexer`;
- `CommonTokenStream`;
- generated `SearchQueryParser`;
- visitor `AstBuilder`.

Стандартные error listeners удаляются. `ThrowingErrorListener` превращает syntax error в `IllegalArgumentException` с line, column и сообщением ANTLR.

`AstBuilder`:

- строит left-associated `OR`;
- строит left-associated `AND`;
- строит recursive `NOT`;
- превращает `EDGE` в `AdjNode`;
- извлекает integer distance из `NEAR/k`.

Метод `adjacent` отдельно reassociate выражение, если правая часть уже `AdjNode`. Это делает:

```text
A ADJ (B ADJ C)
```

эквивалентным последовательной проверке:

```text
(A ADJ B) ADJ C
```

Такой rewrite согласован с тем, что результат `ADJ` хранит right-side anchor positions.

## 12. Выполнение запросов

В проекте есть три связанных executor:

| Класс | Модель |
| --- | --- |
| `QueryExecutor` | materialized in-memory posting lists |
| `DiskQueryExecutor` | materialized posting lists, прочитанные с диска |
| `StreamingQueryExecutor` | cursor pipeline без полной materialization результата |

Основной production-путь дискового поиска использует `StreamingQueryExecutor`.

### 12.1. `QueryExecutor.java`

Файл: [`QueryExecutor.java`](src/main/java/searchengine/query/QueryExecutor.java)

Рекурсивно обходит AST и вызывает `PostingListOperations`.

Параметр `positionsRequired` распространяется вниз:

- обычной boolean-ветке позиции не обязательны;
- operands `ADJ` и `NEAR` исполняются с `true`;
- boolean expression внутри positional operand объединяет позиции обеих веток.

Оптимизация:

```text
A AND NOT B
```

не строит полный complement `NOT B`, а сразу вызывает `andNot(A, B)`.

То же работает для `NOT B AND A`.

### 12.2. `DiskQueryExecutor.java`

Файл: [`DiskQueryExecutor.java`](src/main/java/searchengine/query/DiskQueryExecutor.java)

Повторяет логику `QueryExecutor`, но получает lists через `PostingListReader`.

Если positions не требуются, вызывает `readDocIdPostingList`, чтобы не читать `positions.bin`.

Класс остаётся полезен для тестов, сравнения реализаций и materialized сценариев, но `DiskSearchEngine` выполняет обычные запросы streaming-способом.

### 12.3. `StreamingQueryExecutor.java`

Файл: [`StreamingQueryExecutor.java`](src/main/java/searchengine/query/StreamingQueryExecutor.java)

Строит дерево `PostingCursor`, отражающее AST.

#### `BaseCursor`

Общий mutable state текущего результата:

- `docId`;
- `termFrequency`;
- `positions`;
- `positioned`.

Чтение значений до успешного `next()` вызывает `IllegalStateException`.

#### `AndCursor`

Выравнивает два дочерних cursor через `advanceTo`.

При совпадении:

- объединяет positions;
- TF равен длине merged positions;
- если positions отсутствуют, TF равен максимуму TF веток.

После выдачи совпадения оба дочерних cursor продвигаются при следующем вызове.

#### `AndNotCursor`

Итерирует только included cursor. Excluded cursor продвигается до candidate ID.

Это экономичнее построения полного `NOT` universe.

#### `OrCursor`

Merge двух sorted streams:

- выдаёт меньший ID;
- одинаковый ID объединяет;
- после исчерпания одной стороны продолжает вторую.

#### `NotCursor`

Итерирует `DocIdSet universe` и исключает ID, найденные в child cursor.

Выдаёт TF 0 и пустые positions.

#### `PositionalCursor`

Сначала выравнивает документы, затем применяет two-pointer position matching.

Для документа без позиционного совпадения оба child cursor продвигаются и поиск продолжается.

#### Закрытие

Composite cursors закрывают children. `closeBoth` сохраняет первое `IOException`, а второе добавляет как suppressed exception.

## 13. Дисковый формат индекса

Текущая версия:

```text
FORMAT_VERSION = 4
```

Файлы определены в [`DiskIndexFiles.java`](src/main/java/searchengine/storage/DiskIndexFiles.java).

### 13.1. Набор файлов

| Файл | Содержимое |
| --- | --- |
| `dictionary.bin` | словарь термов, DF, offsets и lengths |
| `postings.bin` | doc ID stream, TF stream, persistent skips |
| `positions.bin` | positions каждого posting |
| `documents.bin` | метаданные документов и snippets |
| `meta.json` | человекочитаемая сводка формата и размеров |

### 13.2. `dictionary.bin`

Внешний layout:

```text
int formatVersion
int compressionModeOrdinal
int blockCompression        // 1 = deflate
int uncompressedLength
int compressedLength
byte[compressedLength] payload
```

Распакованный payload:

```text
int entryCount
repeat entryCount:
    int termUtf8Length
    byte[termUtf8Length] term
    int documentFrequency
    long postingsOffset
    int postingsLength
    long positionsOffset
    int positionsLength
```

Словарь полностью загружается при открытии reader.

### 13.3. Блок терма в `postings.bin`

```text
int encodedDocIdsLength
byte[encodedDocIdsLength] encodedDocIds
int encodedTfLength
byte[encodedTfLength] encodedTermFrequencies

// только для delta-varbyte v4:
int skipCount
repeat skipCount:
    int index
    int targetDocId
    int previousDocId
    int docIdBytePosition
    int tfBytePosition
    int positionBytePosition
    int positionGapRead
```

Outer lengths позволяют reader выделить slices doc ID и TF payload.

### 13.4. Блок терма в `positions.bin`

Содержит encoded flat array позиций всех postings терма.

При delta-mode каждая позиция заменяется gap относительно предыдущей позиции **в том же документе**. На границе следующего posting previous position снова становится нулём.

Количество positions для каждого документа берётся из соответствующего TF.

### 13.5. `documents.bin`

Внешний layout:

```text
int formatVersion
int blockCompression
int uncompressedLength
int compressedLength
byte[...] payload
```

Payload:

```text
int documentCount
repeat documentCount:
    int docId
    string externalId
    int tokenLength
    string snippet
```

Полного документа здесь нет.

### 13.6. `meta.json`

Содержит:

- format version;
- document count;
- term count;
- average document length;
- выбранные codecs;
- размеры файлов;
- total bytes.

`DiskIndexWriter` перезаписывает meta до 10 раз, чтобы поле total size стабилизировалось с учётом собственного размера `meta.json`.

### 13.7. Совместимость

[`DiskIndexCodec`](src/main/java/searchengine/storage/DiskIndexCodec.java) понимает:

- v1 legacy metadata;
- v2 compressed metadata;
- v3 snippets;
- v4 explicit compression mode и persistent skips.

Для старых versions compression mode считается `delta-varbyte`.

## 14. Сжатие

### 14.1. Общий интерфейс

[`IntCompressor.java`](src/main/java/searchengine/compression/IntCompressor.java):

```java
byte[] compress(int[] values);
int[] decompress(byte[] bytes);
```

### 14.2. Режимы индекса

[`IndexCompressionMode.java`](src/main/java/searchengine/compression/IndexCompressionMode.java) задаёт восемь режимов:

| ID | Delta для doc IDs/positions | Base codec |
| --- | --- | --- |
| `none` | нет | 32-bit integer |
| `delta` | да | 32-bit integer |
| `varbyte` | нет | variable byte |
| `delta-varbyte` | да | variable byte |
| `bitpacking` | нет | fixed-width bit packing |
| `delta-bitpacking` | да | fixed-width bit packing |
| `pfor` | нет | patched bit packing |
| `delta-pfor` | да | patched bit packing |

TF всегда кодируется как обычные values без delta, потому что TF sequence не обязана быть монотонной.

### 14.3. `NoCompression.java`

Файл: [`NoCompression.java`](src/main/java/searchengine/compression/NoCompression.java)

Layout:

```text
int count
int value0
int value1
...
```

Название означает отсутствие integer compression, хотя length header всё равно присутствует.

### 14.4. `VarByteCompression.java`

Файл: [`VarByteCompression.java`](src/main/java/searchengine/compression/VarByteCompression.java)

Каждое non-negative integer разбивается на группы по 7 bits.

- continuation byte имеет старший bit 1;
- последний byte имеет старший bit 0;
- перед values varint-кодируется length массива.

Отрицательные числа отклоняются.

### 14.5. `DeltaVarByteCompression.java`

Файл: [`DeltaVarByteCompression.java`](src/main/java/searchengine/compression/DeltaVarByteCompression.java)

Standalone composition:

```text
sorted values -> gaps -> VarByte
```

Требует non-decreasing input. В `IndexCompressionMode.DELTA_VARBYTE` та же идея реализована разделением delta policy и base compressor.

### 14.6. `BitPackingCompression.java`

Файл: [`BitPackingCompression.java`](src/main/java/searchengine/compression/BitPackingCompression.java)

Находит maximum и вычисляет один `bitWidth` для всего массива:

```text
bitWidth = max(1, bitsRequired(max))
```

Layout:

```text
int count
byte bitWidth
packed bits
```

Bits укладываются little-endian внутри рабочего `long` buffer.

Недостаток: одно большое значение увеличивает width всех остальных values.

### 14.7. `PatchedBitPackingCompression.java`

Файл: [`PatchedBitPackingCompression.java`](src/main/java/searchengine/compression/PatchedBitPackingCompression.java)

Упрощённый PFOR-подобный codec.

Данные делятся на blocks по 128 values. Width выбирается по 90-му percentile bit widths.

Для каждого block записываются:

```text
unsigned short count
byte bitWidth
int packedLength
byte[...] packedValues
unsigned short exceptionCount
repeat exceptionCount:
    unsigned short index
    int originalValue
```

Values, не помещающиеся в width, в packed stream представлены нулём, а реальные значения записываются как exceptions.

### 14.8. Почему `delta-varbyte` может быть быстрее `none`

Это не противоречие.

В текущей архитектуре только `delta-varbyte` получает специализированный [`DiskPostingCursor`](src/main/java/searchengine/storage/DiskPostingCursor.java), который:

- декодирует posting по одному;
- не materialize весь posting list;
- умеет перепрыгивать через persistent byte offsets;
- не читает positions, если они не нужны.

Для остальных compression modes `AbstractDiskIndexReader.openCursor` сначала вызывает полное декодирование в `PostingList`, затем оборачивает его в `ListPostingCursor`.

Поэтому сравнивается не только стоимость codec:

```text
none full decode + allocation
против
delta-varbyte streaming decode + persistent skips
```

Именно этим объясняются результаты, где `delta-varbyte` быстрее `none`.

### 14.9. `CompressionAnalyzer.java`

Файл: [`CompressionAnalyzer.java`](src/main/java/searchengine/compression/CompressionAnalyzer.java)

Для каждого режима:

- собирает doc IDs;
- собирает TF;
- flatten positions;
- считает raw bytes;
- измеряет encode/decode nanos;
- проверяет round trip;
- считает compression ratio.

Внутренний `Result` хранит mode, byte sizes и времена.

Diagnostic output показывает sample doc IDs, deltas, TF, positions и размер каждого encoded sample.

## 15. Readers и курсоры

### 15.1. `PostingListReader.java`

Файл: [`PostingListReader.java`](src/main/java/searchengine/storage/PostingListReader.java)

Интерфейс:

- полное чтение list с positions;
- чтение doc ID + TF;
- открытие cursor;
- получение DF;
- закрытие.

Default `openCursor` materialize list и создаёт `ListPostingCursor`.

### 15.2. `PostingCursor.java`

Файл: [`PostingCursor.java`](src/main/java/searchengine/storage/PostingCursor.java)

Cursor contract:

- `next()`;
- `isPositioned()`;
- `docId()`;
- `termFrequency()`;
- `positions()`;
- `advanceTo(targetDocId)`.

Default `advanceTo` линейно вызывает `next`, пока ID меньше target.

Специализированные cursors переопределяют его.

### 15.3. `AbstractDiskIndexReader.java`

Файл: [`AbstractDiskIndexReader.java`](src/main/java/searchengine/storage/AbstractDiskIndexReader.java)

При открытии полностью загружает:

- dictionary;
- document metadata.

Не загружает все posting lists.

Нормализует terms lowercase и через dictionary находит offsets.

Ключевое ветвление `openCursor`:

- `delta-varbyte` -> `DiskPostingCursor`;
- любой другой mode -> decode whole `PostingList` -> `ListPostingCursor`.

### 15.4. `DiskIndexReader.java`

Файл: [`DiskIndexReader.java`](src/main/java/searchengine/storage/DiskIndexReader.java)

Открывает два `FileChannel`:

- postings;
- positions.

Для каждого term выделяет `ByteBuffer(length)` и выполняет positional reads с заданного offset до заполнения buffer.

Этот reader не зависит от mutable channel position, потому что использует `read(buffer, position)`.

### 15.5. `MMapIndexReader.java`

Файл: [`MMapIndexReader.java`](src/main/java/searchengine/storage/MMapIndexReader.java)

Делегирует slices двум `PagedMMapFile`.

### 15.6. `PagedMMapFile.java`

Файл: [`PagedMMapFile.java`](src/main/java/searchengine/storage/PagedMMapFile.java)

Default настройки:

```text
page size = 64 KiB
max mapped pages = 64
```

LRU реализован через access-order `LinkedHashMap`.

Если slice находится внутри одной mapped page:

- создаётся duplicate;
- задаются position/limit;
- возвращается read-only slice;
- данные не копируются.

Если slice пересекает pages, его bytes копируются в новый contiguous `ByteBuffer`.

Метод mapping page синхронизирован, что защищает LRU cache при параллельных HTTP-запросах.

### 15.7. `VarByteBufferCursor.java`

Файл: [`VarByteBufferCursor.java`](src/main/java/searchengine/storage/VarByteBufferCursor.java)

Лениво читает VarByte payload из `ByteBuffer`.

Constructor сразу читает varint length. Cursor хранит:

- byte position;
- count уже прочитанных values.

`setState(position, read)` позволяет восстановить decoder на persistent skip point.

### 15.8. `DiskPostingCursor.java`

Файл: [`DiskPostingCursor.java`](src/main/java/searchengine/storage/DiskPostingCursor.java)

Специализирован для layout `delta-varbyte`.

Имеет три VarByte cursors:

- doc ID gaps;
- TF;
- position gaps, только если positions required.

`next()`:

1. читает следующий doc gap;
2. прибавляет его к previous doc ID;
3. читает TF;
4. при необходимости читает ровно TF position gaps;
5. восстанавливает absolute positions.

`advanceTo(target)`:

1. если текущий ID уже достаточен, возвращает его;
2. binary search выбирает последний skip с `targetDocId <= target`;
3. восстанавливает byte positions и read counters;
4. продолжает последовательный decode.

Skip entry хранит `previousDocId`, потому что первый gap после восстановления должен прибавляться к ID posting перед skip target.

Внутренний immutable `SkipEntry` также хранит index, target ID и offsets всех decoder streams.

### 15.9. `ListPostingCursor.java`

Файл: [`ListPostingCursor.java`](src/main/java/searchengine/storage/ListPostingCursor.java)

Cursor над materialized `PostingList`.

`advanceTo` использует binary search по postings, а не default linear scan.

### 15.10. `EmptyPostingCursor.java`

Файл: [`EmptyPostingCursor.java`](src/main/java/searchengine/storage/EmptyPostingCursor.java)

Singleton cursor неизвестного или пустого терма.

`next()` всегда `false`; чтение fields вызывает `IllegalStateException`.

## 16. Storage utility classes

### 16.1. `DiskIndexFiles.java`

Файл: [`DiskIndexFiles.java`](src/main/java/searchengine/storage/DiskIndexFiles.java)

Package-private centralized names и path resolvers пяти файлов индекса.

### 16.2. `DictionaryEntry.java`

Файл: [`DictionaryEntry.java`](src/main/java/searchengine/storage/DictionaryEntry.java)

Immutable descriptor term block:

- term;
- DF;
- postings offset/length;
- positions offset/length.

### 16.3. `DictionaryData.java`

Файл: [`DictionaryData.java`](src/main/java/searchengine/storage/DictionaryData.java)

Внутренняя пара:

- map dictionary entries;
- compression mode, прочитанный из header.

### 16.4. `DiskIndexStats.java`

Файл: [`DiskIndexStats.java`](src/main/java/searchengine/storage/DiskIndexStats.java)

Хранит размеры всех пяти файлов и вычисляет total.

### 16.5. `DiskIndexCodec.java`

Файл: [`DiskIndexCodec.java`](src/main/java/searchengine/storage/DiskIndexCodec.java)

Центральный serializer/deserializer:

- encode/decode postings;
- encode/decode positions;
- write/read dictionary;
- write/read documents;
- Deflate blocks;
- version compatibility;
- meta JSON;
- persistent skip table.

Класс package-private: binary format инкапсулирован внутри storage package.

### 16.6. `DiskIndexWriter.java`

Файл: [`DiskIndexWriter.java`](src/main/java/searchengine/storage/DiskIndexWriter.java)

Публичный writer. Default mode — `DELTA_VARBYTE`.

Возвращает `DiskIndexStats`.

## 17. Search facade

### 17.1. `DiskSearchEngine.java`

Файл: [`DiskSearchEngine.java`](src/main/java/searchengine/storage/DiskSearchEngine.java)

Высокоуровневый facade над reader, parser, executor и scorer.

Factory methods:

- `disk(Path)`;
- `mmap(Path)`.

При создании:

- получает document map;
- строит sorted universe;
- вычисляет avg document length из metadata;
- создаёт `DiskBM25Scorer`.

Методы:

- `execute(query)` — materialize все cursor results в `PostingList`;
- `openCursor(query)` — вернуть streaming cursor;
- `search(...)` — top K;
- `searchDetailed(...)` — результаты со snippet и positions;
- `searchPage(...)` — exact total и page;
- `termPositions(term, docId)` — позиции терма в одном документе.

### 17.2. Диагностика результатов

`includeDiagnostics=true` добавляет:

- snippet из `documents.bin`;
- positions для каждого `node.terms()`.

Positions диагностируются отдельным cursor lookup на каждый term и каждый показанный result. Это удобно для UI и проверки корректности, но увеличивает число чтений.

### 17.3. Неранжированная pagination

Cursor полностью сканируется, чтобы получить exact total.

Результаты page собираются, когда:

```text
totalMatches >= offset
и
page ещё не заполнена
```

Порядок — возрастающий `docId`, потому что query cursors сохраняют sorted order.

## 18. BM25

### 18.1. Формула

Параметры по умолчанию:

```text
k1 = 1.2
b = 0.75
```

IDF:

```text
idf(t) = ln(1 + (N - df(t) + 0.5) / (df(t) + 0.5))
```

Term contribution:

```text
idf(t) * tf * (k1 + 1)
---------------------------------------------
tf + k1 * (1 - b + b * dl / avgdl)
```

Total score — сумма contributions положительных query terms.

### 18.2. `BM25Scorer.java`

Файл: [`BM25Scorer.java`](src/main/java/searchengine/ranking/BM25Scorer.java)

In-memory scorer:

1. создаёт score 0 для каждого candidate;
2. для каждого query term проходит его posting list;
3. обновляет только candidate doc IDs;
4. создаёт `SearchResult`;
5. сортирует score descending, затем doc ID ascending;
6. обрезает до top K.

### 18.3. `DiskBM25Scorer.java`

Файл: [`DiskBM25Scorer.java`](src/main/java/searchengine/ranking/DiskBM25Scorer.java)

Имеет materialized и streaming overloads.

`rankPage`:

1. открывает doc-only cursor для каждого positive term;
2. вычисляет IDF один раз;
3. сканирует candidates;
4. для candidate продвигает каждый term cursor до candidate doc ID;
5. считает score;
6. поддерживает bounded priority queue размера `offset + limit`;
7. считает exact total;
8. сортирует retained top results;
9. возвращает requested slice.

Внутренний `TermState` объединяет cursor конкретного query term и заранее вычисленный IDF, а также отвечает за закрытие cursor.

Heap ordering хранит худший retained result сверху:

- меньший score хуже;
- при равном score больший doc ID хуже.

### 18.4. Стоимость pagination

Каждый HTTP `load more` выполняет запрос заново:

- candidates снова сканируются;
- exact total снова считается;
- queue растёт до `offset + limit`.

Это обеспечивает стабильную точную выдачу без server-side search session, но глубокие pages становятся дороже.

### 18.5. `SearchResult.java`

Файл: [`SearchResult.java`](src/main/java/searchengine/ranking/SearchResult.java)

Immutable result:

- internal doc ID;
- external/page ID;
- score;
- snippet;
- map term -> positions.

Position arrays defensively копируются при создании и при чтении.

### 18.6. `SearchPage.java`

Файл: [`SearchPage.java`](src/main/java/searchengine/ranking/SearchPage.java)

Содержит:

- exact total matches;
- offset;
- limit;
- immutable results.

`hasMore()` использует `long` в сумме offset и page size, чтобы избежать integer overflow в проверке.

## 19. Почему в индексе хранится только snippet

Это осознанное разделение:

```text
поисковый индекс != хранилище исходных документов
```

В `documents.bin` хранятся только:

- `docId`;
- `externalId`;
- token length;
- snippet до 500 символов.

Плюсы:

- заметно меньший индекс;
- меньше RAM при загрузке metadata;
- быстрый показ search cards;
- source data не дублируется.

Полный документ открывается через:

```text
externalId/pageId -> WikipediaDumpPageReader -> исходный XML
```

Поэтому web server требует `--source-wiki`.

Недостатки:

- XML dump должен оставаться доступен;
- первое открытие страницы требует последовательного scan;
- нельзя открыть полный документ только по каталогу индекса;
- title результата до открытия source не хранится отдельно, поэтому UI первоначально показывает page ID.

Для production-подобного развития можно добавить отдельное document store:

```text
pageId -> compressed title + plain text
```

или построить secondary map:

```text
pageId -> byte offset XML page
```

Текущая реализация достаточна для лабораторной проверки и позволяет показать документ преподавателю, если рядом есть исходный dump.

## 20. CLI

### 20.1. `SearchCli.java`

Файл: [`SearchCli.java`](src/main/java/searchengine/SearchCli.java)

Главный command-line entry point.

Режимы:

- построить индекс из TXT;
- построить индекс из BEIR;
- построить индекс из Wikipedia;
- открыть существующий индекс;
- выполнить один query;
- interactive prompt;
- вывести одну source page;
- показать snippets и positions;
- показать полный source text;
- сравнить compression modes.

### 20.2. Источники

```text
--txt <folder>
--beir <corpus.jsonl>
--wiki <dump.xml>
```

Одновременно разрешён только один dataset source.

Без source CLI считает, что `--index-dir` указывает на существующий индекс.

### 20.3. Reader

```text
--reader mmap
--reader disk
```

Default — mmap.

### 20.4. Поиск

```text
--query <query>
--top-k <n>
--limit <n>
--no-ranking
--show-docs
```

`--limit` — alias `--top-k`.

`--show-docs` включает snippets и позиции terms.

### 20.5. Source pages

```text
--source-wiki <dump.xml>
--show-source
--page-id <id>
```

Interactive command:

```text
:open <page-id>
```

CLI source output печатает raw revision text, а web endpoint показывает cleaned plain text.

### 20.6. Interactive commands

```text
:help
:open <page-id>
:quit
:q
exit
```

Leading UTF-8 BOM в первой введённой строке удаляется.

### 20.7. Конфигурация

Внутренний `Config`:

- разбирает arguments вручную;
- проверяет positive top K;
- проверяет non-negative max docs;
- проверяет конфликт dataset sources;
- выбирает loader;
- выбирает compression mode.

## 21. Web server и HTTP API

### 21.1. `SearchWebServer.java`

Файл: [`SearchWebServer.java`](src/main/java/searchengine/web/SearchWebServer.java)

Main class web-приложения.

Обязательные параметры:

```text
--index-dir <dir>
--source-wiki <dump.xml>
```

Дополнительные:

```text
--host <host>       default 127.0.0.1
--port <port>       default 8080
--reader mmap|disk  default mmap
```

Path values:

- trim;
- проверяются на control characters;
- проверяются через `Files.isDirectory` и `Files.isRegularFile`.

Такая проверка устраняет ошибку `InvalidPathException`, возникавшую при попадании line break в аргумент пути.

Server держит main thread через `CountDownLatch(1).await()` и закрывается shutdown hook.

Внутренний `Config` разбирает и валидирует web-specific arguments. Он отделён от одноимённого private config CLI и не разделяет с ним состояние.

### 21.2. `SearchHttpServer.java`

Файл: [`SearchHttpServer.java`](src/main/java/searchengine/web/SearchHttpServer.java)

Основан на JDK `com.sun.net.httpserver.HttpServer`.

Thread pool:

```text
max(2, min(8, availableProcessors))
```

Contexts:

- `/api/search`;
- `/api/document`;
- `/api/config`;
- `/` для static resources.

Поддерживается только GET. Другие methods получают `405` и `Allow: GET`.

Внутренние классы:

- `SearchHandler` обслуживает `/api/search`;
- `DocumentHandler` обслуживает `/api/document`;
- `StaticHandler` раздаёт classpath resources;
- `SourcePageCache` реализует synchronized LRU source cache;
- `Resource` связывает classpath path и MIME type.

### 21.3. `GET /api/search`

Параметры:

| Параметр | Default | Ограничение |
| --- | ---: | --- |
| `q` | обязательный | непустая строка |
| `offset` | 0 | `>= 0` |
| `limit` | 10 | 1..50 |
| `ranking` | true | `true` или `false` |

Ответ:

```json
{
  "query": "история AND россии",
  "ranking": true,
  "elapsedMs": 12.345,
  "total": 123,
  "offset": 0,
  "limit": 10,
  "hasMore": true,
  "results": [
    {
      "docId": 42,
      "pageId": "12345",
      "score": 8.75,
      "snippet": "...",
      "terms": {
        "история": {
          "total": 4,
          "positions": [2, 17, 90, 111]
        }
      }
    }
  ]
}
```

Для каждого term отправляются максимум первые 32 positions, но `total` содержит полное число.

`elapsedMs` измеряет server-side search, включая diagnostics для показанной страницы.

### 21.4. `GET /api/document`

Параметр:

```text
pageId
```

Алгоритм:

1. проверить LRU source cache;
2. при miss просканировать XML dump;
3. получить `WikipediaSourcePage`;
4. очистить raw wikitext;
5. вернуть title и plain text.

Ответ:

```json
{
  "pageId": "12345",
  "title": "История России",
  "text": "..."
}
```

Cache содержит максимум 32 страницы и синхронизирован.

### 21.5. `GET /api/config`

Возвращает:

```json
{
  "sourceDocuments": true,
  "pageSize": 10,
  "maxPageSize": 50
}
```

Текущий frontend не обязан запрашивать config, потому что page size также задан в JS.

### 21.6. Static resources

Доступны только:

- `/`;
- `/index.html`;
- `/styles.css`;
- `/app.js`.

Они читаются из classpath. Для static responses выставляется `Cache-Control: no-cache`.

### 21.7. Security headers

Все ответы получают:

- `X-Content-Type-Options: nosniff`;
- `Referrer-Policy: no-referrer`;
- Content Security Policy с `default-src 'self'`;
- запрет embedding через `frame-ancestors 'none'`.

JSON строится вручную, но строки проходят escaping quotes, backslashes, control characters и line breaks.

## 22. Браузерный интерфейс

### 22.1. `index.html`

Файл: [`index.html`](src/main/resources/web/index.html)

Содержит:

- поисковую форму;
- ranking toggle;
- кнопки операторов;
- summary результата;
- время поиска;
- список cards;
- кнопку «Показать ещё 10»;
- dialog полного документа;
- dialog справки;
- template result card.

### 22.2. `styles.css`

Файл: [`styles.css`](src/main/resources/web/styles.css)

Реализует:

- responsive layout;
- search hero;
- result cards;
- BM25 badges;
- term chips;
- modal dialogs;
- подсветку `<mark>`;
- mobile breakpoint 720px;
- доступный visually-hidden label.

### 22.3. `app.js`

Файл: [`app.js`](src/main/resources/web/app.js)

Состояние:

```text
query
ranking
offset
total
loading
results
```

Поиск:

1. берёт query и ranking;
2. при новом query очищает old results;
3. сохраняет `q` и `ranking` в URL;
4. вызывает `/api/search`;
5. добавляет cards;
6. увеличивает offset до числа уже показанных результатов;
7. показывает exact total;
8. управляет load-more button.

Первые 10 результатов загружаются автоматически. Остальные догружаются batches по 10.

Frontend показывает два времени:

- server search time;
- время до отображения ответа в браузере.

### 22.4. Подсветка

Terms сортируются по длине descending и объединяются в Unicode case-insensitive regexp.

DOM строится через `createTextNode` и `<mark>`, а не через `innerHTML`, поэтому текст документа не интерпретируется как HTML.

Следует учитывать: подсветка ищет substring, а index tokenizer работает по границам Unicode letter/digit tokens. Поэтому визуальный счётчик occurrences является вспомогательным, а не точной заменой posting positions.

### 22.5. Открытие документа

Кнопка result card вызывает `/api/document`.

После загрузки dialog показывает:

- title;
- page ID;
- internal doc ID;
- число визуально найденных occurrences query terms;
- cleaned text с подсветкой.

## 23. Benchmark package

Все JMH benchmark используют:

- average time mode;
- warmup;
- measurement iterations;
- forks;
- thread-scoped state.

Скрипт полного suite переопределяет часть параметров командной строкой для единой методики.

### 23.1. `BenchmarkCorpusFactory.java`

Файл: [`BenchmarkCorpusFactory.java`](src/main/java/searchengine/benchmark/BenchmarkCorpusFactory.java)

Создаёт детерминированный synthetic corpus.

В каждом документе:

- каждая 5-я позиция — `common`;
- каждая 17-я, если не 5-я, — `medium{docId % 32}`;
- остальные — один из 4096 terms.

### 23.2. `BooleanQueryBenchmark.java`

Файл: [`BooleanQueryBenchmark.java`](src/main/java/searchengine/benchmark/BooleanQueryBenchmark.java)

Операции:

- adaptive AND;
- AND without skips;
- OR;
- AND NOT;
- ADJ;
- NEAR/3.

Pair sizes:

| Pair | Left | Right |
| --- | ---: | ---: |
| rare-rare | 100 | 100 |
| rare-medium | 100 | 5 000 |
| rare-common | 100 | 50 000 |
| medium-medium | 5 000 | 5 000 |
| common-common | 50 000 | 50 000 |

Universe — 100 000. Overlap контролируется и равен примерно четверти меньшего списка.

### 23.3. `CompressionBenchmark.java`

Файл: [`CompressionBenchmark.java`](src/main/java/searchengine/benchmark/CompressionBenchmark.java)

Матрица:

```text
8 compression modes
x
3 value kinds
x
compress/decompress
```

Каждый массив содержит 100 000 values.

Kinds:

- sorted doc IDs;
- small TF values;
- mostly small position gaps с редкими exceptions.

### 23.4. `CompressionIndexReport.java`

Файл: [`CompressionIndexReport.java`](src/main/java/searchengine/benchmark/CompressionIndexReport.java)

Не JMH benchmark, а standalone report generator.

Для 10 000 документов:

- запускает `CompressionAnalyzer`;
- пишет индекс каждым mode;
- считает total bytes;
- измеряет write;
- выполняет cold query;
- усредняет 20 warm queries.

### 23.5. `DiskIndexWriteBenchmark.java`

Файл: [`DiskIndexWriteBenchmark.java`](src/main/java/searchengine/benchmark/DiskIndexWriteBenchmark.java)

Измеряет запись индекса 10 000 x 80 в новый temp subdirectory на каждую invocation.

После trial recursively удаляет temp tree.

### 23.6. `DiskSearchBenchmark.java`

Файл: [`DiskSearchBenchmark.java`](src/main/java/searchengine/benchmark/DiskSearchBenchmark.java)

Corpus: 20 000 x 80.

Запрос:

```text
(common AND medium1) OR (term42 NEAR/4 common)
```

Для каждого compression mode сравнивает:

- in-memory;
- `DiskIndexReader`;
- `MMapIndexReader`.

### 23.7. `IndexBuildBenchmark.java`

Файл: [`IndexBuildBenchmark.java`](src/main/java/searchengine/benchmark/IndexBuildBenchmark.java)

Измеряет построение in-memory index из 10 000 документов по 80 terms.

### 23.8. `IndexLoadBenchmark.java`

Файл: [`IndexLoadBenchmark.java`](src/main/java/searchengine/benchmark/IndexLoadBenchmark.java)

Измеряет constructor + metadata load:

- disk reader;
- mmap reader.

Corpus: 20 000 x 80.

### 23.9. `MMapReadBenchmark.java`

Файл: [`MMapReadBenchmark.java`](src/main/java/searchengine/benchmark/MMapReadBenchmark.java)

Для frequent term `common` сравнивает:

- in-memory lookup;
- disk full read;
- disk doc-only read;
- mmap full read;
- mmap doc-only read.

### 23.10. `QueryPipelineBenchmark.java`

Файл: [`QueryPipelineBenchmark.java`](src/main/java/searchengine/benchmark/QueryPipelineBenchmark.java)

Разделяет pipeline на:

- parse;
- execute;
- rank.

Это позволяет увидеть, что на synthetic workload ranking и allocations результатов дороже parsing.

## 24. Профилирование

### 24.1. `profiling/README.md`

Файл: [`profiling/README.md`](profiling/README.md)

Главная инструкция по JMH, GC profiler, JFR, VisualVM и async-profiler.

### 24.2. `run-all-benchmarks.ps1`

Файл: [`run-all-benchmarks.ps1`](profiling/run-all-benchmarks.ps1)

Опционально собирает JAR и запускает весь JMH suite:

- 1 thread;
- configurable warmup;
- configurable measurement;
- configurable forks;
- JSON output.

Текущий полный artifact содержит 114 parameterized results.

### 24.3. `run-jfr.ps1`

Файл: [`run-jfr.ps1`](profiling/run-jfr.ps1)

Запускает выбранный JMH benchmark с:

```text
-prof jfr:configName=profile;stackDepth=128
```

Затем находит свежий `profile.jfr` и вызывает converter.

### 24.4. `jfr-to-flamegraph.ps1`

Файл: [`jfr-to-flamegraph.ps1`](profiling/jfr-to-flamegraph.ps1)

Конвертирует JFR в HTML flame graph.

Profiles:

- CPU runnable samples;
- allocation.

Поддерживает:

- include regexp;
- exclude regexp;
- skip bottom frames;
- minimum frame width;
- custom title.

При отсутствии local converter скачивает pinned `jfr-converter` 4.4 и проверяет SHA-256.

### 24.5. `run-gc-profiler.ps1`

Файл: [`run-gc-profiler.ps1`](profiling/run-gc-profiler.ps1)

Собирает проект и запускает выбранный JMH benchmark с `-prof gc`.

### 24.6. `run-async-profiler.ps1`

Файл: [`run-async-profiler.ps1`](profiling/run-async-profiler.ps1)

Attach к переданному PID через `ASYNC_PROFILER_HOME`.

### 24.7. `run-web.ps1`

Файл: [`run-web.ps1`](profiling/run-web.ps1)

Проверяет paths, при необходимости собирает JAR и запускает `SearchWebServer`.

### 24.8. Текущие flame graphs

Interactive HTML:

- [`query-pipeline-cpu.html`](profiling/flamegraphs/query-pipeline-cpu.html);
- [`query-pipeline-allocation.html`](profiling/flamegraphs/query-pipeline-allocation.html);
- [`index-build-cpu.html`](profiling/flamegraphs/index-build-cpu.html);
- [`index-build-allocation.html`](profiling/flamegraphs/index-build-allocation.html).

PNG copies для отчёта:

- [`query-pipeline-cpu-flamegraph.png`](profiling/report-assets/query-pipeline-cpu-flamegraph.png);
- [`query-pipeline-allocation-flamegraph.png`](profiling/report-assets/query-pipeline-allocation-flamegraph.png);
- [`index-build-cpu-flamegraph.png`](profiling/report-assets/index-build-cpu-flamegraph.png);
- [`index-build-allocation-flamegraph.png`](profiling/report-assets/index-build-allocation-flamegraph.png).

### 24.9. Что показывают профили

Query execute profile позволяет оценить:

- traversal posting lists;
- merge cursors;
- positional matching;
- allocations промежуточных arrays/results.

Index build profile показывает:

- токенизацию;
- построение строк terms;
- hash maps;
- accumulators позиций;
- создание postings;
- сортировку lists;
- snippet generation.

Flame graph следует читать снизу вверх:

- нижние frames — entry path;
- ширина — доля samples или allocation weight;
- верхние frames — конкретная работа.

## 25. Тесты

### 25.1. `TokenizerTest`

Файл: [`TokenizerTest.java`](src/test/java/searchengine/TokenizerTest.java)

Проверяет:

- lowercase;
- позиции;
- Unicode letters/digits;
- отсутствие пустых tokens.

### 25.2. `DocumentLoaderTest`

Файл: [`DocumentLoaderTest.java`](src/test/java/searchengine/DocumentLoaderTest.java)

Проверяет TXT и BEIR loaders, порядок файлов, IDs, title + body.

### 25.3. `WikipediaDumpDocumentLoaderTest`

Файл: [`WikipediaDumpDocumentLoaderTest.java`](src/test/java/searchengine/WikipediaDumpDocumentLoaderTest.java)

Проверяет:

- XML pages;
- page IDs;
- max document limit;
- одинаковый readable text для индекса и web;
- отсутствие служебной wiki-разметки в index;
- source lookup по page ID;
- сохранение requested ID order;
- missing page.

### 25.4. `WikipediaMarkupCleanerTest`

Файл: [`WikipediaMarkupCleanerTest.java`](src/test/java/searchengine/WikipediaMarkupCleanerTest.java)

Проверяет common markup и сохранение текста после malformed unclosed markup.

### 25.5. `IndexBuilderTest`

Файл: [`IndexBuilderTest.java`](src/test/java/searchengine/IndexBuilderTest.java)

Проверяет DF, TF, positions и document token length.

### 25.6. `PostingListOperationsTest`

Файл: [`PostingListOperationsTest.java`](src/test/java/searchengine/PostingListOperationsTest.java)

Проверяет:

- AND two pointers;
- AND skips;
- OR;
- merge positions;
- AND NOT;
- complement;
- ADJ;
- NEAR без Cartesian product.

### 25.7. `QuerySearchTest`

Файл: [`QuerySearchTest.java`](src/test/java/searchengine/QuerySearchTest.java)

Проверяет AST, adjacency, near и boolean query with NOT.

### 25.8. `QueryCombinationTest`

Файл: [`QueryCombinationTest.java`](src/test/java/searchengine/QueryCombinationTest.java)

Покрывает сложные комбинации:

```text
history AND russia
history EDGE russia
history AND NOT russia
NOT (history ADJ russia)
history AND NOT (russia NEAR/1 history)
(history OR blue) ADJ mountain
(history AND blue) ADJ mountain
history ADJ (russia ADJ blue)
```

Также сравнивает disk и in-memory, проверяет anchor positions, diagnostics, positive terms и invalid queries.

Именно этот test защищает от ошибки, при которой `history AND russia` мог ошибочно возвращать документ только с `history`.

### 25.9. `CompressionTest`

Файл: [`CompressionTest.java`](src/test/java/searchengine/CompressionTest.java)

Round trip всех codecs и всех `IndexCompressionMode`.

### 25.10. `DiskIndexTest`

Файл: [`DiskIndexTest.java`](src/test/java/searchengine/DiskIndexTest.java)

Проверяет:

- наличие пяти файлов;
- version 4;
- compression metadata;
- snippets;
- все compression modes;
- doc-only reads;
- mmap;
- page boundary;
- disk search;
- streaming query;
- current-posting-only cursor;
- persistent skip offsets.

### 25.11. `BM25Test`

Файл: [`BM25Test.java`](src/test/java/searchengine/BM25Test.java)

Проверяет, что больший TF при прочих условиях даёт больший score.

### 25.12. `SearchPaginationTest`

Файл: [`SearchPaginationTest.java`](src/test/java/searchengine/SearchPaginationTest.java)

Проверяет:

- exact total;
- страницы 10 + 10 + 3;
- `hasMore`;
- стабильность ranked ordering;
- наличие snippet.

### 25.13. `SearchHttpServerTest`

Файл: [`SearchHttpServerTest.java`](src/test/java/searchengine/web/SearchHttpServerTest.java)

Поднимает реальный local HTTP server и проверяет:

- static UI;
- pagination JSON;
- total;
- hasMore;
- page IDs;
- document endpoint;
- cleaned text без wiki markup.

### 25.14. `RealCollectionSmokeTest`

Файл: [`RealCollectionSmokeTest.java`](src/test/java/searchengine/RealCollectionSmokeTest.java)

Проверяет русский control workload на настроенном реальном индексе:

- `история AND россия`;
- adjacency;
- near;
- NOT;
- subset relations;
- реальные positions.

Этот test предназначен для интеграционной проверки коллекции, а не только synthetic fixtures.

По умолчанию test пропускается через JUnit assumption. Для запуска нужно передать:

```powershell
mvn test -Dreal.index.dir=target\wiki-index-100k-readable-2026-06-13
```

## 26. Полный каталог production-файлов

Ниже каждый Java-файл перечислен ровно один раз.

### 26.1. Корневой package

| Файл | Содержимое |
| --- | --- |
| [`SearchCli.java`](src/main/java/searchengine/SearchCli.java) | CLI main, config parsing, index build, interactive prompt, source page output |

### 26.2. `document`

| Файл | Содержимое |
| --- | --- |
| [`Document.java`](src/main/java/searchengine/document/Document.java) | входной документ |
| [`DocumentMeta.java`](src/main/java/searchengine/document/DocumentMeta.java) | persisted metadata и snippet |
| [`DocumentLoader.java`](src/main/java/searchengine/document/DocumentLoader.java) | loader interface |
| [`TxtFolderDocumentLoader.java`](src/main/java/searchengine/document/TxtFolderDocumentLoader.java) | recursive TXT loader |
| [`BeirDocumentLoader.java`](src/main/java/searchengine/document/BeirDocumentLoader.java) | lazy BEIR JSONL loader |
| [`WikipediaDumpDocumentLoader.java`](src/main/java/searchengine/document/WikipediaDumpDocumentLoader.java) | streaming XML-to-Document loader |
| [`WikipediaDumpPageReader.java`](src/main/java/searchengine/document/WikipediaDumpPageReader.java) | source lookup по page ID |
| [`WikipediaSourcePage.java`](src/main/java/searchengine/document/WikipediaSourcePage.java) | source page DTO |
| [`WikipediaMarkupCleaner.java`](src/main/java/searchengine/document/WikipediaMarkupCleaner.java) | wikitext-to-plain-text cleaner |

### 26.3. `tokenizer`

| Файл | Содержимое |
| --- | --- |
| [`Tokenizer.java`](src/main/java/searchengine/tokenizer/Tokenizer.java) | tokenizer strategy |
| [`Token.java`](src/main/java/searchengine/tokenizer/Token.java) | term + position |
| [`SimpleTokenizer.java`](src/main/java/searchengine/tokenizer/SimpleTokenizer.java) | Unicode lowercase tokenizer |

### 26.4. `index`

| Файл | Содержимое |
| --- | --- |
| [`InMemoryInvertedIndex.java`](src/main/java/searchengine/index/InMemoryInvertedIndex.java) | index builder и in-memory search facade |
| [`Posting.java`](src/main/java/searchengine/index/Posting.java) | posting data |
| [`PostingList.java`](src/main/java/searchengine/index/PostingList.java) | sorted postings и in-memory skips |
| [`PostingListOperations.java`](src/main/java/searchengine/index/PostingListOperations.java) | boolean и positional algorithms |
| [`SkipPointer.java`](src/main/java/searchengine/index/SkipPointer.java) | in-memory skip descriptor |
| [`DocIdSet.java`](src/main/java/searchengine/index/DocIdSet.java) | sorted universe |

### 26.5. `query`

| Файл | Содержимое |
| --- | --- |
| [`QueryParser.java`](src/main/java/searchengine/query/QueryParser.java) | parser interface |
| [`QueryNode.java`](src/main/java/searchengine/query/QueryNode.java) | AST interface |
| [`BinaryNode.java`](src/main/java/searchengine/query/BinaryNode.java) | base binary AST node |
| [`TermNode.java`](src/main/java/searchengine/query/TermNode.java) | term node |
| [`AndNode.java`](src/main/java/searchengine/query/AndNode.java) | AND node |
| [`OrNode.java`](src/main/java/searchengine/query/OrNode.java) | OR node |
| [`NotNode.java`](src/main/java/searchengine/query/NotNode.java) | NOT node |
| [`AdjNode.java`](src/main/java/searchengine/query/AdjNode.java) | ADJ/EDGE node |
| [`NearNode.java`](src/main/java/searchengine/query/NearNode.java) | NEAR/k node |
| [`AntlrQueryParser.java`](src/main/java/searchengine/query/AntlrQueryParser.java) | ANTLR parse tree -> AST |
| [`QueryExecutor.java`](src/main/java/searchengine/query/QueryExecutor.java) | in-memory materialized execution |
| [`DiskQueryExecutor.java`](src/main/java/searchengine/query/DiskQueryExecutor.java) | disk-backed materialized execution |
| [`StreamingQueryExecutor.java`](src/main/java/searchengine/query/StreamingQueryExecutor.java) | cursor composition и streaming execution |

### 26.6. `compression`

| Файл | Содержимое |
| --- | --- |
| [`IntCompressor.java`](src/main/java/searchengine/compression/IntCompressor.java) | codec interface |
| [`NoCompression.java`](src/main/java/searchengine/compression/NoCompression.java) | raw 32-bit values |
| [`VarByteCompression.java`](src/main/java/searchengine/compression/VarByteCompression.java) | VarByte |
| [`DeltaVarByteCompression.java`](src/main/java/searchengine/compression/DeltaVarByteCompression.java) | delta + VarByte |
| [`BitPackingCompression.java`](src/main/java/searchengine/compression/BitPackingCompression.java) | one-width bit packing |
| [`PatchedBitPackingCompression.java`](src/main/java/searchengine/compression/PatchedBitPackingCompression.java) | block patched bit packing |
| [`IndexCompressionMode.java`](src/main/java/searchengine/compression/IndexCompressionMode.java) | восемь index codec configurations |
| [`CompressionAnalyzer.java`](src/main/java/searchengine/compression/CompressionAnalyzer.java) | size/speed analysis и validation |

### 26.7. `storage`

| Файл | Содержимое |
| --- | --- |
| [`DiskIndexFiles.java`](src/main/java/searchengine/storage/DiskIndexFiles.java) | file names |
| [`DictionaryEntry.java`](src/main/java/searchengine/storage/DictionaryEntry.java) | term block descriptor |
| [`DictionaryData.java`](src/main/java/searchengine/storage/DictionaryData.java) | dictionary + mode |
| [`DiskIndexStats.java`](src/main/java/searchengine/storage/DiskIndexStats.java) | file size stats |
| [`DiskIndexCodec.java`](src/main/java/searchengine/storage/DiskIndexCodec.java) | binary serialization |
| [`DiskIndexWriter.java`](src/main/java/searchengine/storage/DiskIndexWriter.java) | index persistence |
| [`PostingListReader.java`](src/main/java/searchengine/storage/PostingListReader.java) | reader abstraction |
| [`AbstractDiskIndexReader.java`](src/main/java/searchengine/storage/AbstractDiskIndexReader.java) | common reader logic |
| [`DiskIndexReader.java`](src/main/java/searchengine/storage/DiskIndexReader.java) | FileChannel reader |
| [`MMapIndexReader.java`](src/main/java/searchengine/storage/MMapIndexReader.java) | mmap reader |
| [`PagedMMapFile.java`](src/main/java/searchengine/storage/PagedMMapFile.java) | paged LRU mapping |
| [`PostingCursor.java`](src/main/java/searchengine/storage/PostingCursor.java) | cursor interface |
| [`DiskPostingCursor.java`](src/main/java/searchengine/storage/DiskPostingCursor.java) | streaming delta-varbyte cursor |
| [`VarByteBufferCursor.java`](src/main/java/searchengine/storage/VarByteBufferCursor.java) | stateful VarByte decoder |
| [`ListPostingCursor.java`](src/main/java/searchengine/storage/ListPostingCursor.java) | materialized list cursor |
| [`EmptyPostingCursor.java`](src/main/java/searchengine/storage/EmptyPostingCursor.java) | empty singleton cursor |
| [`DiskSearchEngine.java`](src/main/java/searchengine/storage/DiskSearchEngine.java) | disk search facade |

### 26.8. `ranking`

| Файл | Содержимое |
| --- | --- |
| [`BM25Scorer.java`](src/main/java/searchengine/ranking/BM25Scorer.java) | in-memory BM25 |
| [`DiskBM25Scorer.java`](src/main/java/searchengine/ranking/DiskBM25Scorer.java) | streaming disk BM25 и top-K page |
| [`SearchResult.java`](src/main/java/searchengine/ranking/SearchResult.java) | result DTO |
| [`SearchPage.java`](src/main/java/searchengine/ranking/SearchPage.java) | page DTO |

### 26.9. `web`

| Файл | Содержимое |
| --- | --- |
| [`SearchWebServer.java`](src/main/java/searchengine/web/SearchWebServer.java) | web main и argument validation |
| [`SearchHttpServer.java`](src/main/java/searchengine/web/SearchHttpServer.java) | HTTP API, static server, source cache |

### 26.10. `benchmark`

| Файл | Содержимое |
| --- | --- |
| [`BenchmarkCorpusFactory.java`](src/main/java/searchengine/benchmark/BenchmarkCorpusFactory.java) | synthetic corpus |
| [`BooleanQueryBenchmark.java`](src/main/java/searchengine/benchmark/BooleanQueryBenchmark.java) | posting operations |
| [`CompressionBenchmark.java`](src/main/java/searchengine/benchmark/CompressionBenchmark.java) | codec throughput |
| [`CompressionIndexReport.java`](src/main/java/searchengine/benchmark/CompressionIndexReport.java) | whole-index codec report |
| [`DiskIndexWriteBenchmark.java`](src/main/java/searchengine/benchmark/DiskIndexWriteBenchmark.java) | writer latency |
| [`DiskSearchBenchmark.java`](src/main/java/searchengine/benchmark/DiskSearchBenchmark.java) | in-memory/disk/mmap search |
| [`IndexBuildBenchmark.java`](src/main/java/searchengine/benchmark/IndexBuildBenchmark.java) | build latency |
| [`IndexLoadBenchmark.java`](src/main/java/searchengine/benchmark/IndexLoadBenchmark.java) | reader startup |
| [`MMapReadBenchmark.java`](src/main/java/searchengine/benchmark/MMapReadBenchmark.java) | posting read variants |
| [`QueryPipelineBenchmark.java`](src/main/java/searchengine/benchmark/QueryPipelineBenchmark.java) | parse/execute/rank |

Итого:

```text
1 root
+ 9 document
+ 3 tokenizer
+ 6 index
+ 13 query
+ 8 compression
+ 17 storage
+ 4 ranking
+ 2 web
+ 10 benchmark
= 73 Java production files
```

## 27. Текущий большой индекс

Каталог:

```text
target/wiki-index-100k-readable-2026-06-13
```

Metadata:

| Параметр | Значение |
| --- | ---: |
| format version | 4 |
| documents | 100 000 |
| unique terms | 1 575 007 |
| average document length | 598.76324 tokens |
| doc ID codec | delta-varbyte |
| TF codec | varbyte |
| positions codec | delta-varbyte |
| dictionary | deflate |
| documents metadata | deflate |
| dictionary bytes | 22 233 495 |
| postings bytes | 159 790 905 |
| positions bytes | 98 332 032 |
| documents bytes | 16 982 202 |
| total bytes | 297 339 080 |

Это индекс первых 100 000 страниц используемого русского Wikipedia dump после очистки wiki-разметки.

## 28. Потоки управления

### 28.1. CLI build

```text
SearchCli.main
    -> Config.parse
    -> Config.loader
    -> loader.load
    -> InMemoryInvertedIndex.build
    -> optional CompressionAnalyzer
    -> DiskIndexWriter.write
    -> open DiskSearchEngine
```

### 28.2. Web search

```text
browser submit
    -> GET /api/search
    -> SearchHandler
    -> DiskSearchEngine.searchPage
    -> AntlrQueryParser.parse
    -> StreamingQueryExecutor.execute
    -> cursor scan
    -> DiskBM25Scorer.rankPage
    -> diagnostics
    -> JSON
    -> render cards
```

### 28.3. Open document

```text
click "Открыть документ"
    -> GET /api/document?pageId=...
    -> SourcePageCache
    -> WikipediaDumpPageReader.findById
    -> WikipediaMarkupCleaner.toPlainText
    -> JSON
    -> highlighted dialog
```

### 28.4. `история AND россии`

```text
lexer: TERM AND TERM
parser: AndNode(TermNode("история"), TermNode("россии"))
executor:
    open cursor "история"
    open cursor "россии"
    AndCursor aligns doc IDs
result:
    only doc IDs present in both posting lists
ranking:
    score from both positive terms
```

Документ, содержащий только `история`, не может пройти `AndCursor`.

## 29. Сложность основных операций

Обозначения:

- `N` — число документов;
- `T` — число токенов корпуса;
- `A`, `B` — длины posting lists;
- `P`, `Q` — числа positions в одном документе;
- `C` — число query candidates;
- `K` — `offset + limit`;
- `M` — число positive query terms.

| Операция | Оценка |
| --- | --- |
| построение индекса | ожидаемо `O(T)` плюс сортировка postings по terms |
| AND без skips | `O(A + B)` |
| OR | `O(A + B)` |
| AND NOT | `O(A + B)` worst case |
| ADJ/NEAR для совпавшего doc | `O(P + Q)` |
| full NOT | `O(N + B)` |
| disk dictionary load | `O(number of terms + documents)` после Deflate |
| term block lookup | ожидаемо `O(1)` map lookup |
| ranked page | `O(C * M * cursor advance + C log K)` |
| source page lookup | `O(XML dump size)` worst case |

Skips не меняют worst-case Big-O, но уменьшают число decoded/compared postings на несбалансированных lists.

## 30. Потокобезопасность и ресурсы

### 30.1. Общие immutable данные

После открытия:

- dictionary map не изменяется;
- document metadata map не изменяется;
- каждый query создаёт собственные cursors.

### 30.2. `DiskIndexReader`

Использует positional `FileChannel.read`, поэтому отдельные запросы не конкурируют за shared mutable file position.

### 30.3. `PagedMMapFile`

Mapping и LRU access синхронизированы. Возвращаемые slices являются independent duplicate/slice buffers.

### 30.4. Source cache

`SourcePageCache.get` синхронизирован. Это защищает map, но одновременно сериализует cache misses и длительные XML scans.

### 30.5. Закрытие

Основные readers, cursors, search engine и HTTP server реализуют `AutoCloseable`.

Composite close сохраняет suppressed exceptions.

## 31. Обработка ошибок

### 31.1. Query errors

ANTLR errors становятся `IllegalArgumentException`.

CLI:

- interactive mode печатает `Invalid query`;
- one-shot mode пропускает exception наружу.

HTTP:

- invalid input -> 400 JSON;
- unexpected failure -> 500 JSON.

### 31.2. Corrupted index

Codec проверяет:

- version;
- compression ordinal;
- lengths;
- DF против decoded array lengths;
- число positions;
- skip table length;
- block bounds;
- truncated streams.

### 31.3. Paths

Web config trim-ит values и отклоняет control characters. Это важно для Windows paths, где newline является illegal character.

### 31.4. XML security

Wikipedia readers пытаются отключить:

- DTD;
- external entities.

Если конкретный StAX provider не поддерживает property, loader продолжает работу.

## 32. Ограничения

### 32.1. Нет морфологии

`россия` и `россии` — разные terms. Для русского поиска это снижает recall.

### 32.2. Нет phrase literal syntax

Кавычки не задают phrase. Фраза выражается через `ADJ`.

### 32.3. Нет implicit AND

Между terms всегда нужен оператор.

### 32.4. Полный документ не хранится в индексе

Для открытия Wikipedia нужен исходный XML dump.

### 32.5. Source lookup последовательный

Нет таблицы page ID -> XML offset.

### 32.6. Streaming только для `delta-varbyte`

Остальные codecs materialize posting list при `openCursor`.

### 32.7. Exact pagination пересчитывает запрос

Load more не продолжает server-side cursor.

### 32.8. Cleaner не является MediaWiki renderer

Он удаляет templates, а не раскрывает их.

### 32.9. BEIR parser специализирован

Ручной field extractor не покрывает произвольный JSON.

### 32.10. In-memory build

Wikipedia XML читается потоком, но итоговый index целиком строится в RAM перед записью. Это не SPIMI/BSBI external-memory indexing.

## 33. Осознанные архитектурные решения

### 33.1. Отдельные postings и positions

Boolean query и BM25 часто не требуют positions. Разделение позволяет читать только doc ID + TF.

### 33.2. Dictionary и document metadata полностью в памяти

Они нужны почти каждому запросу. Полная загрузка упрощает lookup и ranking.

### 33.3. Posting data лениво

Posting lists намного больше dictionary. Они читаются только для query terms.

### 33.4. Специализация `AND NOT`

Complement относительно всех документов может быть огромным. Direct difference дешевле.

### 33.5. Position propagation

Boolean subexpressions объединяют positions только когда являются частью positional query. Это сохраняет корректность выражений вида:

```text
(A OR B) ADJ C
```

без постоянной необходимости переносить positions во всех boolean results.

### 33.6. Bounded heap для ranked pagination

Не требуется хранить и сортировать все `C` результатов, только top `offset + limit`.

### 33.7. Page ID как внешний ключ

Индекс остаётся независим от XML offsets и позволяет найти source document после перестройки внутренней нумерации.

## 34. Возможные направления развития

### 34.1. Хранилище полных документов

Добавить:

```text
documents-full.bin
```

с compressed title/text и offset table.

### 34.2. Быстрый source lookup

Во время первого прохода построить:

```text
pageId -> XML byte offset
```

или отдельную key-value базу.

### 34.3. Морфология

Добавить Russian stemming/lemmatization как новую реализацию `Tokenizer` или отдельный normalization stage.

### 34.4. Streaming cursors для всех codecs

Нужны incremental decoders и persistent state для bitpacking/PFOR blocks.

### 34.5. External-memory index build

Разбить corpus на blocks, писать sorted partial indexes и merge на диске.

### 34.6. Search sessions

Для deep pagination хранить query snapshot/cursor state или использовать search-after token.

### 34.7. Query planner

Для commutative AND сортировать operands по DF и начинать с самого редкого term.

### 34.8. Более точные snippets

Строить snippet вокруг первого query occurrence, а не только первые 500 символов документа.

### 34.9. Полноценная JSON-библиотека

Заменить ручной parsing/serialization на Jackson при снятии ограничения на зависимости.

## 35. Практические примеры

### 35.1. Построить Wikipedia index

```powershell
java -cp target\benchmarks.jar searchengine.SearchCli `
  --wiki data\ruwiki-latest-pages-articles.xml `
  --max-docs 100000 `
  --index-dir target\wiki-index `
  --compression delta-varbyte
```

### 35.2. Выполнить один запрос

```powershell
java -cp target\benchmarks.jar searchengine.SearchCli `
  --index-dir target\wiki-index `
  --query "история AND россии" `
  --top-k 10 `
  --show-docs
```

### 35.3. Показать исходные страницы результатов

```powershell
java -cp target\benchmarks.jar searchengine.SearchCli `
  --index-dir target\wiki-index `
  --source-wiki data\ruwiki-latest-pages-articles.xml `
  --query "история AND россии" `
  --show-source
```

### 35.4. Запустить web UI

```powershell
.\profiling\run-web.ps1 `
  -IndexDirectory target\wiki-index-100k-readable-2026-06-13 `
  -WikipediaDump data\ruwiki-latest-pages-articles.xml `
  -Reader mmap `
  -SkipBuild
```

### 35.5. Примеры запросов

```text
история AND россии
история OR культура
история AND NOT война
санкт ADJ петербург
санкт EDGE петербург
москва NEAR/5 река
(история OR культура) AND россия
история AND NOT (россия ADJ империя)
```

## 36. Итог

Проект реализует полный учебный цикл поисковой системы:

```text
document ingestion
-> text normalization
-> coordinate inverted index
-> binary persistence
-> compressed lazy reads
-> query parsing
-> boolean/positional execution
-> BM25
-> pagination
-> CLI/web presentation
-> source verification
-> tests/benchmarks/profiling
```

Наиболее существенные технические особенности реализации:

- positions хранятся отдельно от doc IDs и TF;
- query строится как AST через ANTLR;
- основной disk path исполняется cursor pipeline;
- `AND NOT` не materialize complement;
- `delta-varbyte` использует настоящий incremental decoder;
- persistent skips сохраняют byte offsets и decoder state;
- exact ranked pagination использует bounded heap;
- индекс хранит snippet и page ID, а полный текст извлекается из source dump;
- один cleaner используется и при индексации Wikipedia, и при показе документа;
- корректность проверяется unit, integration и real-collection tests;
- производительность измеряется JMH, JFR и flame graphs.
