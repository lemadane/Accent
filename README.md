# Jatot

[![CI](https://github.com/lemadane/jatot-lang/actions/workflows/ci.yml/badge.svg)](https://github.com/lemadane/jatot-lang/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java 21](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)

**Jatot** is a Java-compatible language that adds practical features Java developers often wish Java already had—without making the language feel unfamiliar.

**Pronunciation:** *Jato* — the final `t` is silent.

The name comes from **TOTAL** seen in a mirror: the mirrored `L` resembles a `J`, turning **TOTAL** into **JATOT**. It also represents the **total set of practical features Java should have**.

> **Jatot is Java reflected, refined, and completed.**

## A first look

```java
@Service
public class DashboardService {
    private final CustomerService! customerService;
    private final OrderService! orderService;

    public DashboardService(
            CustomerService! customerService,
            OrderService! orderService) {
        this.customerService = customerService;
        this.orderService = orderService;
    }

    public Dashboard! load(UUID! customerId) {
        final customerFuture =
            async this.customerService.findRequired(customerId);

        final ordersFuture =
            async this.orderService.findForCustomer(customerId);

        final customer = await customerFuture;
        final orders = await ordersFuture;

        final status = if (customer.isActive()) {
            yield "ACTIVE";
        } else {
            yield "INACTIVE";
        };

        final city =
            customer.address()?.city() ?? "Unknown";

        final names = for (Order! order : orders) {
            yield order.name().normalized();
        };

        return new Dashboard(customer, city, status, names);
    }
}
```

## Confirmed language features (that Java lacks)


### 1. Nullable and non-null reference types

Reference types are nullable by default:

```java
String text;
Customer customer;
```

The `!` suffix means non-null:

```java
String! text;
Customer! customer;
```

`!` does **not** mean non-empty. Empty strings, lists, and arrays remain valid non-null values.

```java
String! message = "";
List<Integer>! numbers = new ArrayList<>();
int[]! values = {};
```

### 2. Optional chaining

```java
final city = customer.address()?.city();
```

If a nullable receiver is `null`, the rest of the optional chain is skipped and the expression evaluates to `null`.

### 3. Null coalescing

```java
final city = customer.address()?.city() ?? "Unknown";
```

The right-hand expression is evaluated only when the left-hand value is `null`.

### 4. Immutable and mutable locals

Use `final` for an immutable local binding:

```java
final customer = this.repository.findRequired(id);
```

Use `var` for a mutable local binding:

```java
var attempts = 0;
attempts++;
```

Jatot does not use `final var`.

### 5. Immutable parameters

Method and constructor parameters are implicitly immutable bindings:

```java
public void rename(String! name) {
    final normalizedName = name.trim();
    this.name = normalizedName;
}
```

Reassigning `name` is a compile-time error. The referenced object may still be mutable.

### 6. Explicit `this.`

Instance field access and instance method calls inside a class must use `this.`:

```java
this.repository.save(order);
this.status = "SAVED";
this.logStatus();
```

Calls on other objects remain normal Java-style calls:

```java
customer.name();
order.calculateTotal();
```

### 7. Textual boolean operators

Jatot supports seven boolean operators in both textual and symbolic form.

#### Operator table

| Textual   | Symbolic equivalent | Meaning                      |
|-----------|---------------------|------------------------------|
| `not`     | `!`                 | Logical negation             |
| `and`     | `&&`                | Short-circuit logical AND    |
| `or`      | `\|\|`              | Short-circuit logical OR     |
| `nand`    | —                   | Negated logical AND          |
| `nor`     | —                   | Negated logical OR           |
| `xor`     | —                   | Exclusive OR                 |
| `xnor`    | —                   | Logical equivalence          |

The existing symbolic operators `!`, `&&`, and `||` remain fully supported and may be freely mixed with textual operators:

```java
boolean allowed = active && not suspended;
boolean visible = enabled and !hidden;
boolean accepted = valid || trusted or administrator;
```

#### Operator semantics

| Expression     | Java equivalent        |
|----------------|------------------------|
| `not a`        | `!a`                   |
| `a and b`      | `a && b`               |
| `a or b`       | `a \|\| b`             |
| `a nand b`     | `!(a && b)`            |
| `a nor b`      | `!(a \|\| b)`          |
| `a xor b`      | `a != b`               |
| `a xnor b`     | `a == b`               |

#### Truth table

| A     | B     | AND   | OR    | NAND  | NOR   | XOR   | XNOR  |
|-------|-------|-------|-------|-------|-------|-------|-------|
| false | false | false | false | true  | true  | false | true  |
| false | true  | false | true  | true  | false | true  | false |
| true  | false | false | true  | true  | false | true  | false |
| true  | true  | true  | true  | false | false | false | true  |

#### Operator precedence (highest to lowest)

1. Parentheses
2. Unary `!` and `not`
3. `&&`, `and`, `nand`
4. `xor`, `xnor`
5. `||`, `or`, `nor`

Binary operators at the same level are left-associative:

```java
not a and b          // parsed as: (not a) and b
a or b and c         // parsed as: a or (b and c)
a xor b and c        // parsed as: a xor (b and c)
a nand b or c        // parsed as: (a nand b) or c
```

#### Short-circuit behavior

`and` and `or` have the same short-circuit semantics as `&&` and `||`.
`nand` and `nor` also short-circuit where possible:

```java
false nand expensiveCheck()   // expensiveCheck() is NOT called — result is always true
true  nor  expensiveCheck()   // expensiveCheck() is NOT called — result is always false
```

`xor` and `xnor` must evaluate both operands because their result depends on both values.

#### Type checking

All seven textual boolean operators are **boolean-only**. Using them on non-boolean values is a compile-time error:

```java
// Valid
boolean result = ready and available;

// Invalid — produces a compile error:
// Operator 'and' requires boolean operands, but found int and int.
int result = 10 and 20;
```

#### Reserved keywords

The following identifiers are reserved as boolean operator keywords and may not be used as variable, method, or type names:

```text
not  and  or  nand  nor  xor  xnor
```

Note: `notification`, `android`, `ordinary`, `xorValue`, etc. remain valid identifiers because keywords are only matched on exact word boundaries.

#### Realistic examples

```java
public boolean canAccess(
        boolean authenticated,
        boolean suspended,
        boolean administrator) {

    return authenticated and not suspended or administrator;
}
```

```java
public boolean exactlyOneSelected(
        boolean emailSelected,
        boolean smsSelected) {

    return emailSelected xor smsSelected;
}
```

```java
public boolean haveSameStatus(
        boolean firstActive,
        boolean secondActive) {

    return firstActive xnor secondActive;
}
```


```java
final val = condition ? "Zack" : "Guest";
```

### 8. `if` expression

```java
final status = if (order.isPaid()) {
    yield "PAID";
} else {
    yield "PENDING";
};
```

Every reachable branch must yield a compatible result.

### 9. `try` expression

```java
final port = try {
    yield Integer.parseInt(portText);
} catch (NumberFormatException exception) {
    yield 8080;
};
```

### 10. `for` expression

Jatot uses Java's existing `for` keyword. It does not introduce `foreach`.

Enhanced `for` expression:

```java
final names = for (Customer! customer : customers) {
    yield customer.name();
};
```

Traditional `for` expression:

```java
final numbers = for (var i = 0; i < 10; i++) {
    yield i;
};
```

Each executed `yield` contributes an element to the resulting collection.

### 11. `while` and `do-while` expressions

```java
final values = while (iterator.hasNext()) {
    yield iterator.next();
};
```

```java
final values = do {
    yield this.readValue();
} while (this.hasMore());
```

### 12. Class extensions

Jatot can extend an existing Java or Jatot class without changing the original class or creating a subclass:

```java
extension String! {
    String! normalized() {
        return this.trim().toUpperCase();
    }
}
```

Usage:

```java
final normalizedName = name.normalized();
```

Class extensions are expected to lower to ordinary static Java helper methods. Real instance members take precedence over extension members.

### 13. Virtual Thread concurrency

Ordinary Java and Jatot methods remain synchronous. Concurrency is requested at the call site:

```java
final customerFuture =
    async this.customerService.findRequired(id);

final ordersFuture =
    async this.orderService.findForCustomer(id);

final customer = await customerFuture;
final orders = await ordersFuture;
```

The intended semantics are:

```text
async expression  -> start the expression on a Virtual Thread and return a future
await future      -> wait for completion and produce the result
```

Jatot hides Virtual Thread creation, future management, exception unwrapping, cancellation, structured lifetime, and cleanup.

Immediately awaiting newly started work should be discouraged when no concurrency is gained:

```java
final customer =
    await async this.customerService.findRequired(id);
```

Prefer the ordinary synchronous call in that case:

```java
final customer =
    this.customerService.findRequired(id);
```

### 14. Generator functions

Jatot supports generator functions to produce lazy, streamable sequences of values.
* Declare the method with the `generator` modifier.
* The method return type must be `Iterable<Type>` (or primitive/boxed types which lower to `Iterable`).
* Inside the method, use `emit <value>` to yield items to the sequence.

```java
public generator int numbers(int limit) {
    for (var i = 0; i < limit; i++) {
        emit i;
    }
}
```

Usage:
```java
for (int val : numbers(5)) {
    System.out.println(val); // prints 0, 1, 2, 3, 4
}
```


### 15. Direct SQL Query Templating

Jatot includes native support for SQL template query expressions (using backticks) that automatically parameterize dynamic variables to prevent SQL injection and map results directly to Java record types:

* **Typed Select Query** (returns a `List<User>` mapped by column name):
  ```java
  List<User> list = sql<User>`SELECT name, email FROM users WHERE name = {name}`;
  ```
* **Untyped Select Query** (returns a list of row maps: `List<Map<String, Object>>`):
  ```java
  List<Map<String, Object>> rows = sql`SELECT * FROM users WHERE active = {isActive}`;
  ```
* **Insert/Update/Delete Query** (returns query update count `int`):
  ```java
  int updated = sql`UPDATE users SET email = {newEmail} WHERE name = {name}`;
  ```

Dynamic interpolation expressions (like `{name}`) are parsed and validated by the compiler, translating the template query directly into standard prepared statement execution at runtime.


### 16. Compile-Time SQL Query Syntax Checking

The compiler automatically parses and validates the SQL syntax of all query literals at compile-time:
* **Mismatched delimiters**: Detects unclosed quotes or mismatched parentheses in the SQL text.
* **Keyword validation**: Enforces correct keyword structure (e.g. `SELECT` requiring `FROM`, `INSERT` requiring `INTO`, `UPDATE` requiring `SET`, and `DELETE` requiring `FROM`).

Any syntax mistakes will immediately halt compilation and raise detailed compiler errors before code is deployed.


### 17. Zero-Boilerplate S  SQL to Record Mapping (ORM)

The runtime automatically maps database result set columns to Java records using constructor reflection:
* **Name-Based Matching**: Maps columns directly to record constructor parameters with matching names (case-insensitive).
* **Positional Fallback**: If compile parameter reflection names are unavailable (e.g., compiled without the `-parameters` flag) or a name match fails, it maps columns to parameters sequentially by select position.
* **Recursive Nested Mapping**: If a record constructor contains a nested record type (e.g., `User(String name, Contact contact)` where `Contact` is `record Contact(String email)`), the mapper recursively instantiates the nested record matching the query columns.


### 18. Synchronous SQL Query Execution for Virtual Threads

Because Jatot has native compiler-level support for **Virtual Threads (`async`/`await`)**, database query literals can be executed synchronously in lightweight thread contexts without blocking carrier system threads:

```java
final usersFuture = async sql<User>`SELECT * FROM users`;
// ... concurrent operations ...
final users = await usersFuture;
```

When a query blocks on database I/O, the JVM automatically unmounts the virtual thread, enabling high-performance concurrent database operations with standard, simple synchronous code.


### 19. Interpolated Strings

Jatot includes native support for type-safe, evaluated string interpolation expressions using the `$"` syntax. The same delimiter seamlessly supports both single-line and multiline string expressions.

#### Four Important Cases

1. **Normal Java String** (no interpolation):
   ```java
   String ordinary = "Hello, {name}";
   // Evaluates literally to: Hello, {name}
   ```
2. **Normal Java Text Block** (no interpolation):
   ```java
   String ordinaryBlock = """
           Hello, {name}
           """;
   // Evaluates literally to: Hello, {name}
   ```
3. **Jatot Interpolated String**:
   ```jatot
   String interpolated = $"Hello, {name}";
   // Evaluates at runtime to: Hello, Lemuel
   ```
4. **Jatot Multiline Interpolated String**:
   ```jatot
   String interpolatedBlock = $"Hello, {name}
   Hope you are fine.";
   // Evaluates to a single multi-line string containing a newline
   ```

#### Syntax Rules and Behavior
* **Activation**: Prefixing a string with `$` (using `$"`) enables runtime interpolation.
* **Single & Multiline Support**: The exact same `$"..."` syntax is used for both single-line and multiline strings. Triple quotes are not required for multiline interpolated strings.
* **Compatibility**: Normal Java strings (`"..."`) and Java text blocks (`"""..."""`) do not interpolate. Braces inside them are treated as literal characters.
* **Brace Interpolation**: An interpolation expression is marked with `{` and ends with the matching `}`.
* **Literal Braces**: Use doubled braces `{{` to produce a literal `{`, and `}}` to produce a literal `}` inside an interpolated string.
* **Evaluation Order**: Expressions inside an interpolated string are evaluated strictly from left to right exactly once.
* **Null Handling**: If an evaluated expression resolves to `null`, it renders as the string `"null"` (equivalent to `String.valueOf(value)`). No `NullPointerException` is thrown from the string mapping itself.
* **Type Behavior**: The entire interpolated string expression resolves to type `java.lang.String`. Void-returning expressions are rejected at compile-time.


### 20. Native Server-Side HTML Components

Jatot includes native support for server-side HTML templating via JSX-like markup tags directly integrated into the language.

#### Syntax & Rules
* **HTML Elements**: Lowercase tags (e.g. `<div class="card">`) represent standard HTML elements. Real HTML attribute names (`class`, `for`) are used instead of React-specific names (`className`, `htmlFor`).
* **Jatot Components**: Capitalized tags (e.g. `<UserCard user={user} />`) represent custom component classes or records that implement the `io.lemadane.jatot.html.Component` interface.
* **Property Injection**: Component properties are matched to constructor parameters at compile-time. Property types, names, and presence of required attributes are checked at compile-time.
* **Control Flow**: You can write conditionals (`{if (cond) { ... } else { ... }}`) and loops (`{for (var item : list) { ... }}`) directly inside the markup block to control structure dynamically.
* **Fragments**: Use empty tags (`<> ... </>`) to group multiple elements without adding wrapping nodes to the DOM.
* **Compile-Time Optimization**: Adjacent static elements, attributes, and text nodes are merged at compile-time into single static string writes (e.g. `<article class="user-card"><h2>` is optimized to one literal write). There is zero Virtual DOM or runtime overhead.
* **Security & Escaping**: All dynamic text interpolations and standard attribute values are automatically HTML-escaped to prevent XSS. Dynamic URL attributes (like `href` or `src`) are automatically validated at runtime to filter out unsafe protocols (such as `javascript:`). Raw unescaped HTML can be injected using the `TrustedHtml` wrapper class.

#### Example Component:
```java
package io.lemadane.jatot.html.demo;

import io.lemadane.jatot.html.Component;
import io.lemadane.jatot.html.Html;

public record UserCard(User user) implements Component {
    @Override
    public Html render() {
        return (
            <article class="user-card">
                <h2>{this.user.name()}</h2>
                <p>{this.user.email()}</p>
            </article>
        );
    }
}
```

#### Ternary conditional operator for ReactJS-like HTML template control flow

Jatot supports standard Java ternary expressions (`{condition ? thenExpr : elseExpr}`). It is fully compatible with Java's standard ternary operator:



### 21. Native Annotations and 3rd Party Annotation Support

Jatot fully supports standard Java and framework annotations (such as Spring Boot's `@RestController`, `@Autowired`, etc.) on classes, fields, methods, constructors, and method parameters:

```java
@RestController
@RequestMapping("/api")
public class ProductController {

    @Autowired
    private ProductService! productService;

    @GetMapping("/{id}")
    public Product! getProduct(@PathVariable String! id) {
        return this.productService.findById(id);
    }
}
```

### 22. Named Arguments & Parameter Default Values

Method and constructor parameters can define default values, which can then be invoked optionally or using named arguments.

#### Parameter Default Values:
```java
public int calculate(int base, int multiplier = 2, int offset = 1) {
    return base * multiplier + offset;
}
```

#### Named Arguments:
```java
// 1. Relying on default parameters
final res1 = calculate(10); // 10 * 2 + 1 = 21

// 2. Named arguments out-of-order
final res2 = calculate(offset: 5, base: 10); // 10 * 2 + 5 = 25

// 3. Mixed positional and named arguments
final res3 = calculate(10, multiplier: 3); // 10 * 3 + 1 = 31
```

### 23. JS-like Symbols

Jatot provides a JavaScript-like `Symbol` type in the standard library (`jatot.lang.Symbol<T>`) to serve as unique, identity-based keys for metadata, component contexts, and registries without the risk of collisions.

#### Core Semantics
1. **Identity-Based Equality**: Two unique symbols are never equal, even if they share the same description.
2. **Optional Descriptions**: Descriptions exist only for debugging and `toString()` representation.
3. **Generic Type Safety**: Symbols define the type of value they are associated with (e.g., `Symbol<UUID>`).
4. **SymbolMap**: A typed container mapping `Symbol<T>` to `T` with full compile-time type safety.
5. **Thread-Safe Global Registry**: Symbols can be registered globally via `Symbol.forKey("key")`.

#### Unique Symbols
A unique symbol is created with `create(...)`. Identical descriptions still produce completely separate symbol objects.

```jatot
static final Symbol<User> CURRENT_USER = Symbol.create("currentUser");
static final Symbol<UUID> TENANT_ID = Symbol.create("tenantId");

Symbol<String> first = Symbol.create("name");
Symbol<String> second = Symbol.create("name");

assert first != second;
```

#### Global Registered Symbols
If you need a shared symbol across components by a string key, use the global registry:

```jatot
Symbol<Object> first = Symbol.forKey("application.user");
Symbol<Object> second = Symbol.forKey("application.user");

assert first == second;
```
The registry is thread-safe and scoped to the class loader. `Symbol.keyFor(...)` retrieves a registered symbol's registry key.

#### Typed Metadata using SymbolMap
To avoid String-based key collisions in metadata contexts, use `SymbolMap`:

```jatot
SymbolMap context = new SymbolMap();

context.put(CURRENT_USER, user);
context.put(TENANT_ID, tenantId);

User currentUser = context.get(CURRENT_USER);
UUID currentTenantId = context.get(TENANT_ID);
```

#### Best Practices
* Symbols **are** excellent for cross-module metadata, plugin extensions, framework context, and request attributes.
* Symbols **are not** UUIDs or persistent database primary keys. They should not automatically survive serialization.
* The `symbol` word is not a reserved keyword and remains valid as an ordinary variable name.


### 24. @Logging Annotation

Jatot provides a native logging system integrated directly into the language via the `@Logging` annotation, without requiring Lombok, SLF4J, or any external bytecode manipulation.

When you annotate a class, record, or enum with `@Logging`, the compiler automatically injects a static, immutable `log` field bound to that specific type.

```jatot
import jatot.logging.Logging;

@Logging
public class BookingService {
    public Booking create(BookingRequest request) {
        log.debug($"Creating booking for {request.customerName()}");

        try {
            Booking booking = save(request);
            log.info($"Booking {booking.id()} was created");
            return booking;
        } catch (Exception exception) {
            log.error($"Failed to create booking", exception);
            throw exception;
        }
    }
}
```

The underlying injected field is equivalent to:
```java
private static final jatot.logging.Logger log = jatot.logging.LogManager.getLogger(BookingService.class);
```

#### Logging Spring Boot Integration

A Spring Boot starter module is available to seamlessly integrate Jatot logging with your Spring environment:

```groovy
implementation 'io.lemadane.jatot:jatot-logging-spring-boot-starter:1.0.0'
```

It maps Spring Boot profiles and active environments so that `jatot.logging` levels automatically align with your `application.yml` properties. In environments without Spring Boot, it seamlessly falls back to reading `jatot-logging.properties`.


### 25. Slugs (`jatot.web`)

Java does not provide a built-in slug type or standard slugification utility. Developers often have to use third-party libraries or build custom logic combining `Normalizer`, regular expressions, and locales. Jatot fills this gap by providing a standardized, immutable value type: `jatot.web.Slug`.

#### Basic Example

```jatot
import jatot.web.Slug;

Slug slug = Slug.from("Aircon Cleaning Services");
System.out.println(slug);
```

Output:
```text
aircon-cleaning-services
```

#### Generation vs. Parsing

Jatot makes an important distinction between generating a slug from arbitrary input text versus parsing/validating a value expected to already be a canonical slug.

1. **Generation (`Slug.from`)**:
   - Converts arbitrary user input into a canonical slug format (e.g. lowercasing, resolving diacritics, replacing invalid character runs with separators, collapsing and trimming separators, and enforcing length bounds).
   ```jatot
   Slug slug = Slug.from("Café Déjà Vu"); // cafe-deja-vu
   ```
2. **Parsing (`Slug.parse`)**:
   - Validates that the input is *already* in a canonical, normalized slug format. It does not perform any corrections, lowercasing, or transformations, and will throw an `IllegalArgumentException` for non-canonical input.
   ```jatot
   Slug slug = Slug.parse("cafe-deja-vu"); // Ok
   Slug.parse("Café Déjà Vu"); // Throws IllegalArgumentException: Invalid slug
   ```
3. **Checking (`Slug.isValid`)**:
   - Validates that a string is a valid canonical slug without throwing an exception, returning a `boolean`.
   ```jatot
   boolean valid = Slug.isValid("cafe-deja-vu"); // true
   boolean invalid = Slug.isValid("Café Déjà Vu"); // false
   ```

#### Core Characteristics & Rules

- **Immutability**: `Slug` is a `final` class with an immutable internal state. Modifying operations like `append(...)` return a new `Slug` instance, leaving the original unchanged.
- **Locale Neutrality**: Converting characters to lowercase strictly uses `Locale.ROOT` to ensure deterministic slugs across servers with different machine locales.
- **Value Semantics**: Unlike `Symbol`, slugs are compared by value. Two slugs containing the same canonical value are equal and share the same hash code.
- **No Database Uniqueness**: Slugs represent a value format and do not guarantee uniqueness on a database. Handlers, repositories, or services must enforce tenant or globally unique suffixes if required.
- **No URL Encoding**: Slugs are path-safe segment identifiers, not URL encoders. Special URL escaping/building should be handled separately.

#### Character Policies

The standard library supports two policies via `SlugCharacterPolicy`:

1. **ASCII (Default)**:
   - Restricts characters strictly to `a-z`, `0-9`, and the configured separator.
   - Diacritics and combining marks are stripped where possible after Unicode `NFD` decomposition.
   ```jatot
   Slug slug = Slug.from("Café Déjà Vu"); // cafe-deja-vu
   ```

2. **UNICODE**:
   - Preserves Unicode letter and digit symbols while transforming spaces and punctuation to separators.
   ```jatot
   Slug slug = Slug.from(
       "東京 レストラン",
       SlugOptions.builder()
           .characterPolicy(SlugCharacterPolicy.UNICODE)
           .build()
   ); // 東京-レストラン
   ```

#### Domain Model & API Integrations

Using `Slug` inside records or domain entities provides strict type safety over raw strings:

```jatot
record Service(
    UUID id,
    String name,
    Slug slug
) {}
```

#### Append Example

You can combine slugs cleanly:

```jatot
Slug serviceSlug = Slug.from("Home Services")
        .append("Aircon Cleaning");
System.out.println(serviceSlug); // home-services-aircon-cleaning
```

You can test this implementation via the included demo:
```bash
# Compile and run the Slug Demo
./gradlew :jatot-compiler:runSlugDemo
```


## Java interoperability

Java interoperability is a first-class requirement.

The target guarantees are:

1. Java APIs are directly callable from Jatot.
2. Public Jatot classes compile to ordinary JVM classes.
3. Java code can instantiate, call, extend, annotate, and reflect on Jatot classes.
4. Jatot annotations and generic signatures are preserved.
5. Jatot nullability is retained as metadata while remaining compatible with JVM descriptors.
6. Class extensions lower to normal static Java methods.
7. `async` can wrap ordinary Java or Jatot expressions.
8. Jatot requires no modified JVM.
9. Mixed Java and Jatot projects produce normal JAR files.
10. Frameworks should not need to know whether a class originated from Java or Jatot.


## Requirements

- JDK 21
- Gradle 9.6.1 or a compatible Gradle installation

The build uses a Java 21 toolchain and compiles with `--release 21`.


## Using Jatot in Your Project

Jatot can be added as a dependency to any Gradle or Spring Boot project.

### Option 1: Via JitPack (Recommended — No GitHub Token Required)

JitPack allows using Jatot directly without setting up personal access token credentials:

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    // Core Jatot Compiler
    implementation 'com.github.lemadane:jatot-lang:v0.1.0-alpha.1'
}
```

---

### Option 2: Via GitHub Packages

#### 1. Add credentials

You need a GitHub Personal Access Token with at least `read:packages` scope.
Export it as environment variables (or store it in `~/.gradle/gradle.properties`):

```bash
export GITHUB_ACTOR="your_github_username"
export GITHUB_TOKEN="your_personal_access_token"
```

Or in `~/.gradle/gradle.properties`:
```properties
githubActor=your_github_username
githubToken=your_personal_access_token
```

#### 2. Configure `build.gradle`

```groovy
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/lemadane/jatot-lang")
        credentials {
            username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("githubActor")
            password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("githubToken")
        }
    }
}

dependencies {
    // Core Jatot Compiler
    implementation 'io.lemadane.jatot:jatot-compiler:0.1.0-alpha.1'
    // HTML Components Runtime
    implementation 'io.lemadane.jatot:jatot-html-runtime:0.1.0-alpha.1'
    // Spring Boot Auto-configuration & Return Value Handler
    implementation 'io.lemadane.jatot:jatot-html-spring:0.1.0-alpha.1'
}

sourceSets {
    main {
        java {
            srcDirs += ['build/generated/sources/jatot/main']
        }
    }
}
```

### 3. Configure the Jatot Compile Task (Gradle or Maven)

#### For Gradle (`build.gradle`):

Add the Jatot transpiler task so `.jatot` files in `src/main/jatot/` are automatically compiled to Java during `./gradlew build`:

```groovy
tasks.register('compileJatot', JavaExec) {
    group = 'build'
    description = 'Compiles main Jatot source files to Java.'
    classpath = configurations.compileClasspath
    mainClass = 'io.lemadane.jatot.cli.JatotCli'
    workingDir = projectDir
    
    doFirst {
        new File(projectDir, 'build/generated/sources/jatot/main').mkdirs()
    }
    
    args 'compile', 'src/main/jatot', '-d', 'build/classes/java/main', '-cp', configurations.compileClasspath.asPath, '--save-java', 'build/generated/sources/jatot/main'
    
    inputs.dir('src/main/jatot').optional()
    outputs.dir('build/generated/sources/jatot/main')
}

compileJava.dependsOn compileJatot
```

#### For Maven (`pom.xml`):

Add JitPack to your `<repositories>` and configure the `exec-maven-plugin` to run `JatotCli` during the `generate-sources` build phase:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.lemadane</groupId>
        <artifactId>jatot-lang</artifactId>
        <version>v0.1.0-alpha.1</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>exec-maven-plugin</artifactId>
            <version>3.1.0</version>
            <executions>
                <execution>
                    <id>compile-jatot</id>
                    <phase>generate-sources</phase>
                    <goals>
                        <goal>java</goal>
                    </goals>
                    <configuration>
                        <mainClass>io.lemadane.jatot.cli.JatotCli</mainClass>
                        <arguments>
                            <argument>compile</argument>
                            <argument>src/main/jatot</argument>
                            <argument>-d</argument>
                            <argument>${project.build.directory}/classes</argument>
                            <argument>--save-java</argument>
                            <argument>${project.build.directory}/generated-sources/jatot</argument>
                        </arguments>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### 4. Create and Use HTML Components in Spring Boot

Place your `.jatot` files in `src/main/jatot/`.

**src/main/jatot/UserCard.jatot**:
```jatot
package com.example.demo;

import io.lemadane.jatot.html.Component;
import io.lemadane.jatot.html.Html;

public record UserCard(String name, String email) implements Component {
    @Override
    public Html render() {
        return (
            <div class="card">
                <h3>{this.name}</h3>
                <p>{this.email}</p>
            </div>
        );
    }
}
```

Then return the component directly from your Spring Boot controller:
```java
package com.example.demo;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import io.lemadane.jatot.html.Component;

@Controller
public class UserController {

    @GetMapping("/user")
    public Component getUser() {
        return new UserCard("Alice", "alice@example.com");
    }
}
```

The `jatot-html-spring` library will automatically intercept the returned `Component` (or raw `Html`), render it, and stream the HTML back to the browser.

### 5. File-Based Routing

Jatot supports file-based routing dynamically at startup. By mapping your package structure to URL endpoints, you can avoid writing manual `@GetMapping` mappings.

* Place your page components inside the `.routes` subpackage (e.g. `com.example.demo.routes`).
* Declare a component class/record named `Page` that implements `io.lemadane.jatot.html.Component`.
* Directory path parameters use an underscore prefix (e.g. `_name`).

#### File Structure Example:
```text
src/main/jatot/routes/
├── Page.jatot               // Package com.example.demo.routes -> Maps GET "/"
└── users/
    └── _name/
        └── Page.jatot       // Package com.example.demo.routes.users._name -> Maps GET "/users/{name}"
```

#### Page.jatot Constructor Parameter Injection:
Dynamic path variables (like `{name}`) and query parameters are automatically mapped and injected into the constructor parameters of the target `Page` component at runtime using reflection (including recursive instantiation of nested record types).

```jatot
package com.example.demo.routes.users._name;

import io.lemadane.jatot.html.Component;
import io.lemadane.jatot.html.Html;
import com.example.demo.User;

public record Page(User user) implements Component {
    @Override
    public Html render() {
        return (
            <h1>Hello, {this.user.name()}!</h1>
        );
    }
```

#### Nested Layouts (`Layout.jatot`)
You can define layout components named `Layout.jatot` (producing a class named `Layout` implementing `Component`) in any routing directory. Layouts automatically wrap child page components:
* Root Layout: `routes.Layout`
* Section Layout: `routes.users.Layout`

Layouts accept an `io.lemadane.jatot.html.HtmlChildren` parameter to render dynamic nested child pages or nested sub-layouts:

```jatot
package com.example.demo.routes;

import io.lemadane.jatot.html.Component;
import io.lemadane.jatot.html.Html;
import io.lemadane.jatot.html.HtmlChildren;

public record Layout(HtmlChildren children) implements Component {
    @Override
    public Html render() {
        return (
            <!DOCTYPE html>
            <html lang="en">
                <head>
                    <title>Jatot Site</title>
                </head>
                <body>
                    <div class="layout-container">
                        {this.children}
                    </div>
                </body>
            </html>
        );
    }
}
```

#### Decoupled Server Data Loading (`Loader.jatot`)

Jatot supports decoupled server-side data loading. If a class/record named `Loader` is found in the same package as the `Page` component, its `load(...)` method is executed first at request time to fetch data. The resolved model is then injected directly into the `Page` component's constructor:

```jatot
package com.example.demo.routes.users._name;

import com.example.demo.User;

public class Loader {
    // Dynamic path/query variables are injected into method arguments automatically
    public User load(String name) {
        return new User(name, name.toLowerCase() + "@example.com");
    }
}
```

The returned object of type `User` is then passed to the constructor of `Page(User user)` dynamically.

#### Static Site Generation & Prerendering (`@Prerender`)

Annotate any `Page` component with `@Prerender` to enable Compile-Time Static Site Generation (SSG). 
```jatot
package com.example.demo.routes;

import io.lemadane.jatot.html.Component;
import io.lemadane.jatot.html.Html;
import io.lemadane.jatot.html.spring.Prerender;

@Prerender
public record Page() implements Component {
    @Override
    public Html render() {
        return (
            <h1>Static Landing Page</h1>
        );
    }
}
```
* **Performance optimization**: Pages marked with `@Prerender` are compiled, rendered, and cached as static HTML templates once at startup. Subsequent visits stream the cached literals immediately, bypassing constructor instantiation and layout wrapping.

### 6. Running the Demo Applications Locally

The project includes two end-to-end demo applications under their respective modules. You can try them by running the following commands:

#### Running the Routing & Layouts Demo
Runs a clean, database-less application showing layouts, file routing, decoupled loaders, and SSG:
```bash
./gradlew :jatot-html-demo-routing:runRoutingDemo
```
* **Root landing page (SSG / File-based routes):** [http://localhost:8080/](http://localhost:8080/)
* **Dynamic parameter page (File-based routes):** [http://localhost:8080/users/Alice](http://localhost:8080/users/Alice)
* **Raw HTML Fragment rendering:** [http://localhost:8080/fragment](http://localhost:8080/fragment)

#### Running the SQL & ORM Demo
Runs a database-integrated application showing SQL prepared statement executions, virtual thread concurrency, and record ORM mapping:
```bash
./gradlew :jatot-html-demo-sql:runSqlDemo
```
* **Dynamic User DB parameter route:** [http://localhost:8080/users/Mel](http://localhost:8080/users/Mel) (reads from seeded database row)
* **Database mapping ORM test endpoint:** [http://localhost:8080/](http://localhost:8080/)

#### Running the Symbols Demo
Runs a simple console application demonstrating unique symbol identity, global registry, and `SymbolMap` usage:
```bash
./gradlew :jatot-compiler:runSymbolDemo
```

#### `@Logging` Demo

Runs a simple console application demonstrating the natively injected `log` field, multiple log levels, and exception handling:
```bash
./gradlew :jatot-compiler:runLoggingDemo
```

#### Named Arguments & Default Parameters Demo

Runs a simple console application demonstrating the use of parameter default values and named arguments in Jatot:
```bash
./gradlew :jatot-compiler:runNamedArgsDemo
```

## Build

Build with the included Gradle wrapper:

```bash
./gradlew clean build
```

On Windows:

```cmd
gradlew.bat clean build
```

## Run the current CLI

Check whether the example source is lexically valid:

```bash
gradle run --args="check examples/HelloJatot.jatot"
```

Print its token stream:

```bash
gradle run --args="tokens examples/HelloJatot.jatot"
```

Run the convenience verification task:

```bash
gradle checkJatot
```

Current CLI usage:

```text
jatot <check|tokens> <source.jatot>
```

At this stage, `check` performs lexical validation only.



## Mutability declaration

The first visible Jatot feature will be local mutability declarations:

```java
public class Main {
    public static void main(String[] args) {
        final message = "Hello from Jatot"; // 'final' and not 'final var' 
                                            // for immutable variable
        var count = 1;  // 'var' for mutable variable
        count++;
        System.out.println(message);
        System.out.println(count);
    }
}
```

Expected Java lowering:

```java
public class Main {
    public static void main(String[] args) {
        final var message = "Hello from Jatot";
        var count = 1;

        count++;

        System.out.println(message);
        System.out.println(count);
    }
}
```


## JSON Standard Library (`jatot.json`)

Jatot provides a native, zero-dependency JSON parser and stringifier through the `jatot-json` standard library module. This API brings JavaScript-like convenience but strictly enforces Java Record type safety, preventing common pitfalls with mutable JavaBeans and silent coercions.

### Key Features
- **Strictly Record-Oriented**: `Json.parse(...)` will *only* deserialize into Java Records, ensuring deterministic, immutable structures. It strictly rejects ordinary Java classes.
- **Constructor Validation**: During deserialization, the record's canonical constructor is always invoked. Any assertions or data validations placed within the constructor run automatically on the parsed JSON!
- **Zero-Dependency**: No Jackson, no Gson. It is built natively into Jatot, perfectly avoiding large shaded JAR issues.
- **Deep Compatibility**: Supports primitives, `java.time.*` (ISO-8601), `java.util.UUID`, Enums, `Optional<T>`, generic lists/maps, and deeply nested generic records.
- **Customizable**: Control serialization with `JsonOptions` and override property names via `@JsonName`.


## Native JSON Literals

Jatot extends standard JSON support with language-level **Native JSON Literals**, allowing you to write JSON structures directly inline with string interpolation!

This leverages Jatot's template string syntax (`json<Target>"""..."""`) and lowers directly into highly-optimized `jatot.json.Json.parse` calls at compile time.

```jatot
// 1. Define your strict target Record
public record User(
    String id,
    String firstName,
    String lastName
) {}

// 2. Write JSON natively!
String inputId = "123e4567-e89b-12d3-a456-426614174000";
String firstName = "Lemuel";

User parsedUser = json<User>"""
{
    "id": "${inputId}",
    "firstName": "${firstName.toUpperCase()}",
    "lastName": "Adane"
}
""";
```

### Quick Start

```java
import jatot.json.Json;
import java.util.UUID;
import java.time.LocalDate;

public record User(
    UUID id, 
    String firstName, 
    String lastName,
    LocalDate birthDate
) {
    public User {
        // Will be executed when parsed from JSON!
        if (firstName == null || firstName.isBlank()) {
            throw new IllegalArgumentException("firstName cannot be blank");
        }
    }
}

public class Main {
    public static void main(String[] args) {
        User user = new User(UUID.randomUUID(), "Lemuel", "Adane", LocalDate.of(2000, 1, 1));
        
        // Serialize
        String json = Json.stringify(user);
        
        // Parse directly into a strongly-typed record!
        User parsedUser = Json.parse(json, User.class);
        
        System.out.println(parsedUser.firstName());
    }
}
```

You can test this implementation via the included demo:
```bash
# Compile and run the JSON Demo
javac -cp jatot-json/build/classes/java/main jatot-compiler/src/e2e/examples/JsonDemo.java
java -cp jatot-json/build/classes/java/main:jatot-compiler/src/e2e/examples JsonDemo
```


You can test this implementation via the included demo:
```bash
# Compile and run the JSON Literal Demo
./gradlew :jatot-compiler:classes
java -cp jatot-compiler/build/classes/java/main io.lemadane.jatot.cli.JatotCli compile jatot-compiler/src/e2e/examples/JsonLiteralDemo.jatot -d jatot-compiler/build/classes/java/demo -cp jatot-json/build/classes/java/main --save-java
java -cp jatot-compiler/build/classes/java/demo:jatot-json/build/classes/java/main JsonLiteralDemo
```

## Project status

Jatot is currently in the **compiler-foundation stage**.

The repository already contains:

- a Java 21 Gradle project
- a **lexer** for Jatot keywords and operators
- a **parser** producing a full abstract syntax tree
- **semantic analysis** with symbol resolution and type checking
- a **lowering** pass that transforms Jatot AST constructs to Java-compatible forms
- a **Java source emitter** for transpilation output
- source-file and diagnostic abstractions
- a CLI with `check`, `tokens`, and `checkJatot` commands
- a Virtual Thread runtime prototype for `async`/`await` lowering
- JUnit 5 tests (including end-to-end compiler tests)
- an example `.jatot` source file

The compiler pipeline is functional from lexing through Java emission. Work continues on expanding language feature coverage and hardening the transpilation output.

## IDE Support & Tooling

Jatot includes complete, production-ready IDE support for standard editor features (syntax highlighting, completions, hover info, definition lookup, diagnostics, and code actions):

### 1. `jatot-language-tools` (Shared Tooling Core)
The backend service layer that leverages the compiler frontend to provide editor services.
* **Error-Tolerant Parsing**: Recovers gracefully from syntax errors (e.g. trailing dots `obj.`, unclosed braces) to keep giving completions and structure.
* **Semantic Analysis**: Stores type mappings on AST expressions to answer hover and type-check queries.
* **Transpiler Bridge**: Emits generated Java source in-memory.

### 2. `jatot-language-server` (Language Server Protocol)
An LSP-compliant server built with LSP4J communicating via `stdin`/`stdout`.
* Writes server logs clean of LSP protocol strictly to `stderr`.
* Built and run via Gradle:
  ```bash
  ./gradlew :tools:jatot-language-server:installDist --no-configuration-cache
  ```
  The packaged script is located at:
  `tools/jatot-language-server/build/install/jatot-language-server/bin/jatot-language-server`

### 3. `jatot-vscode` (VS Code Extension)
Provides out-of-the-box support for VS Code:
* **Syntax Highlighting**: Custom TextMate grammar (`.jatot` language mode) highlighting interpolated strings, JSX HTML tags, and async/await blocks.
* **LSP Client**: Boots the Language Server from local Gradle build automatically.
* **Commands**: Run command `Jatot: Show Generated Java` to transpile on-the-fly and display raw Java side-by-side.

### 4. `jatot-intellij` (IntelliJ Plugin)
A native custom-language plugin built using Gradle IntelliJ Platform SDK Plugin 2.x:
* Custom file type registration (`.jatot` files and icon).
* Code commenter and brace matcher.
* Connects completion providers and diagnostics directly to `jatot-language-tools` for consistent, compiler-accurate Intellisense.
* Compile and test via:
  ```bash
  ./gradlew :tools:jatot-intellij:compileKotlin --no-configuration-cache
  ```

## Design goal

A Java developer should be able to read Jatot immediately.

Jatot keeps Java as the baseline:

- classes, interfaces, records, enums, and annotations
- packages and imports
- constructors and method overloading
- generics and Java collections
- checked and unchecked exceptions
- Java libraries and frameworks
- JVM bytecode and the standard Java runtime

Jatot adds focused syntax while preserving Java-shaped code.

## Guiding principle

> **Jatot should remove Java's accidental complexity without hiding the programmer's intention.**




