# Accent

[![CI](https://github.com/accent-lang/accent/actions/workflows/ci.yml/badge.svg)](https://github.com/accent-lang/accent/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java 21](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)

**Accent** is an accent of Java with the features that Java could have had—without making the language feel unfamiliar. Inspired by the creator's time working as a Java Developer at Accenture.

> **Accent is Java reflected, refined, and completed.**



## Standalone HTTP — Undertow by Default

Accent includes `accent.http` in its core runtime, backed by Undertow. A standalone HTTP application needs no Spring annotations, application context, or separate server dependency.

**Main.accent**:

```accent
import accent.http.App;
import accent.http.Response;
import java.util.Map;

public class Main {
    public static void main(String[] args) {
        final app = new App();
        app.get("/health", req -> Response.text("ok"));
        app.get("/users/:id", (req) -> Response.json(Map.of("id", req.path("id"))));
        app.post("/echo", req -> Response.json(req.json()));
        final port = Integer.parseInt(System.getenv("PORT") ?? "8080");
        app.listen(port);
        System.out.println("Accent HTTP listening on " + app.port());
    }
}
```

`app.listen(port)` starts the server and binds on all network interfaces. Use `app.listen("127.0.0.1", port)` for a local-only listener. Each handler runs on a virtual thread, so ordinary blocking SQL or service calls can be used directly. The server keeps the application running and closes gracefully on JVM shutdown.

From this checkout, install the CLI and run the included example:

```bash
./gradlew :accent-compiler:installDist
accent-compiler/build/install/accent/bin/accent run accent-compiler/src/e2e/standalone-http/Main.accent
```

With the installed `accent` executable on your `PATH`, run your own application with `accent run Main.accent`. The distribution includes Undertow and its runtime dependencies. Set `PORT` to change the example's default port of 8080.

```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/users/42
curl -X POST -H 'Content-Type: application/json' -d '{"message":"hello"}' http://127.0.0.1:8080/echo
```

| API | Behavior |
| --- | --- |
| `app.get/post/put/patch/delete(path, handler)` | Register routes; `:name` captures one path segment. Literal routes take precedence over parameter routes; trailing slashes are normalized. |
| `app.head/options(path, handler)` | Optional explicit handlers. GET routes provide HEAD automatically; OPTIONS and 405 responses include `Allow`. |
| `app.use((req, next) -> next.handle(req))` | Register middleware in nesting order; return a response directly to short-circuit. |
| `req.path("id")`, `req.query("q")`, `req.queryValues("q")` | Read decoded path/query parameters; repeated query values are preserved. |
| `req.header("name")`, `req.body()`, `req.bodyBytes()` | Read case-insensitive headers and the buffered request body. Text bodies use UTF-8. |
| `req.json()`, `req.json(User.class)` | Parse JSON as ordinary values or a typed record/model; malformed JSON returns 400. |
| `Response.json(value)` | Serialize records, models, lists, maps, scalars, or null using Accent's JSON runtime. |
| `Response.html(component)`, `Response.html(html)` | Render Accent components or `Html` values; existing HTML escaping rules apply. |
| `Response.text(text)`, `Response.bytes(bytes, contentType)` | Return text or binary content. |
| `response.status(201).header("X-Name", "value")` | Create a response with a custom final status and headers. Response objects are immutable. |
| `app.maxBodyBytes(bytes)` | Configure the body limit before startup; the default is 1 MiB, and oversized bodies return 413. |
| `app.shutdownTimeout(Duration.ofSeconds(5))`, `app.close()` | Configure graceful shutdown and release listener/executor resources. Closing is idempotent. |

Route handlers may use either `(req) -> ...`, `req -> ...`, or `(Request req) -> ...`. Middleware parameter types are also inferred. Unknown paths return 404, a null handler result becomes 204, and unexpected handler failures return a generic 500 response without exposing exception details. Use `new HttpException(status, message)` for an intentional client-visible error.

Configure routes, middleware, and limits before `listen()`. Configuration is frozen at startup; an `App` cannot be restarted after closing. Request objects belong to individual requests. Shared handler or service state still needs coordination across concurrent requests. The initial API serves HTTP; HTTPS, WebSockets, multipart parsing, and standalone file-based routing are not exposed yet.

Spring Boot remains an optional integration for applications that need it; standalone `App` uses Undertow directly. The optional Spring starter includes the matching Undertow Servlet adapter so Boot can start a complete Servlet server when another container has not been supplied.

## A first look

```accent
@Service
public record DashboardService(
        CustomerService! customerService,
        OrderService! orderService) {

    public Dashboard! load(UUID! customerId) {
        final customerAsync =
            async this.customerService.findRequired(customerId);

        final ordersAsync =
            async this.orderService.findForCustomer(customerId);

        final customer = await customerAsync;
        final orders = await ordersAsync;

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

### 4. Virtual Thread concurrency

Accent provides two compiler shorthands for the `Async<T>` methods:

| Shorthand | Equivalent method call | Result type |
| --- | --- | --- |
| `async expression` | `Async.run(() -> expression)` | `Async<T>` |
| `await valueAsync` | `valueAsync.await()` | `T` |

The compiler rewrites the shorthand to the equivalent method call. Both forms share the same execution, result, failure, and cancellation behavior, and can be mixed freely.

```accent
final customerAsync = async this.customerService.findRequired(id);
final customer = await customerAsync;
```

The equivalent method form is:

```accent
import accent.runtime.Async;

final customerAsync = Async.run(() -> this.customerService.findRequired(id));
final customer = customerAsync.await();
```

The shorthand does not require an `Async` import. Import `accent.runtime.Async` when calling `Async.run()` or declaring `Async<T>` explicitly.

Ordinary Java and Accent methods remain synchronous. If `findRequired(id)` returns `Customer`, then `Async.run(() -> service.findRequired(id))` immediately returns `Async<Customer>` while the service call runs on a new virtual thread. The service method itself still returns `Customer`.

Start independent calls before awaiting their results:

```accent
import accent.runtime.Async;

final customerAsync =
    Async.run(() -> this.customerService.findRequired(id));

final ordersAsync =
    Async.run(() -> this.orderService.findForCustomer(id));

final customer = customerAsync.await();
final orders = ordersAsync.await();
```

`Async.run(() -> expression)` returns `accent.runtime.Async<T>`, where `T` is the expression's result type. You can let Accent infer it or declare it explicitly:

```accent
import accent.runtime.Async;

final Async<Customer> customerAsync = Async.run(() -> this.customerService.findRequired(id));
final Customer customer = customerAsync.await();
```

Use the `Async` suffix for operation variables (`customerAsync`, `ordersAsync`) and the value name for their results (`customer`, `orders`).

The semantics are:

```text
async expression -> Async.run(() -> expression) -> Async<T>
await valueAsync -> valueAsync.await() -> T
```

Each `Async.run()` call starts its operation on a virtual thread and returns `Async<T>`. Call `.await()` to wait for its result. Runtime failures are rethrown; checked failures are wrapped in `AccentExecutionException`.

| Method | Behavior |
| --- | --- |
| `.await()` | Waits on the calling thread and returns `T`; repeated calls reuse the result. |
| `.await(Duration timeout)` | Waits up to the duration; a timeout throws `AccentExecutionException` wrapping `TimeoutException` and leaves the operation running. |
| `.cancel()` | Requests cancellation with interruption; returns whether cancellation succeeded. Code must cooperate with interruption to stop. |
| `.isDone()` | Returns true after success, failure, or cancellation. |
| `.isCancelled()` | Returns true when the operation was cancelled. |

For example, use a bounded wait and explicitly cancel if that is your intended timeout policy:

```accent
import accent.runtime.Async;

import java.time.Duration;
import accent.runtime.AccentExecutionException;

final customerAsync = Async.run(() -> this.customerService.findRequired(id));
try {
    final customer = customerAsync.await(Duration.ofSeconds(2));
} catch (AccentExecutionException exception) {
    customerAsync.cancel();
    throw exception;
}
```

Awaiting a cancelled operation throws `CancellationException`. If the awaiting thread is interrupted, `.await()` preserves its interrupt flag and throws `CancellationException`; it does not cancel the operation. Multiple callers can await the same operation. A void operation produces `Async<Void>` and its successful `.await()` returns `null`.

Tasks are independent: leaving a method or observing one task fail does not automatically await or cancel other tasks. Each `Async.run()` starts a new virtual thread; Accent does not impose a concurrency limit. Shared mutable objects still require coordination.

The operation is a lambda: captured local variables must be final or effectively final. Referenced objects remain shared; their contents are not copied. Put the entire operation inside the lambda, for example `Async.run(() -> first() + second())`, or use `async (first() + second())`. Shorthand binds as a unary expression: `(await valueAsync).method()` calls a method on the completed result. Passing `service.findRequired(id)` directly would evaluate it before `run()`; pass `() -> service.findRequired(id)` instead.

Virtual threads do not keep the JVM running after its non-daemon threads exit. Await important work before application shutdown.

Start independent operations before awaiting them to allow overlap. Immediately awaiting a single newly started operation gains no concurrency:

```accent
import accent.runtime.Async;

final customer =
    Async.run(() -> this.customerService.findRequired(id)).await();
```

Prefer the ordinary synchronous call in that case:

```accent
import accent.runtime.Async;

final customer =
    this.customerService.findRequired(id);
```

### 5. Kotlin-Style Data Class Model Declarations (`model`)

Accent provides native support for data classes via the `model` keyword (`public model User(final String! id, String! name, int age)`). Accent `model` classes are conceptually equivalent to Kotlin `data class` and eliminate the need for Lombok annotations (`@Data`, `@Value`, `@Getter`, `@Setter`, etc.):

```java
public model User(
    final String! id,   // final     -> Immutable (getter id() ONLY, no setter)
    String! name,       // default   -> Mutable   (getter name(), setter name(...))
    int age             // default   -> Mutable   (getter age(), setter age(...))
) {}
```

#### Usage Example

```java
final user = new User("U101", "Lemuel", 42);

// Access getters
final id = user.id();
final name = user.name();

// Setters for mutable fields
user.name("Lemuel A.");
user.age(43);

// user.id("U102"); // Compile-time error: 'id' is final!

// Kotlin-style copy method
final userCopy = user.copy("U101", "Lemuel A.", 43);

// Formatted toString output
System.out.println(user); // User[id=U101, name=Lemuel A., age=43]
```

#### Model Features & Semantics

Getters and setters share the field name: `user.name()` reads it, and `user.name("Ann")` updates it. Setters return `void`; no `set`-prefixed methods are generated.

* **Default Mutability**: Fields without `final` are **mutable** by default, generating getters (`name()`, `age()`) AND setters (`name(...)`, `age(...)`).
* **Explicit Immutability (`final`)**: Fields prefixed with `final` are **immutable** (`private final String id;`), generating a getter only (`id()`) and NO setter (`id(...)`). Calling a setter on a `final` field results in a compile-time error.
* **Kotlin-style `copy(...)` Method**: Automatically generates a `copy(...)` method for convenient object cloning.
* **Value-based `equals()` and `hashCode()`**: Generates value-based equality and hashing implementations.
* **Automatic `toString()`**: Emits formatted string representation (`User[id=..., name=..., age=...]`).
* **Built-in `java.io.Serializable`**: Implements `java.io.Serializable` with `serialVersionUID = 1L` out of the box.

#### `model` vs `record` Comparison

| Feature | Accent `record` | Accent `model` |
| :--- | :--- | :--- |
| **Primary Purpose** | 100% Immutable Tuple / Data Holder | Kotlin-style Data Class |
| **Java Target** | Java `record` | `public final class` |
| **Field Mutability** | Always 100% Immutable | Mutable by default (or Immutable with `final`) |
| **Setters Generated** | None | Yes (for non-final fields) |
| **`copy()` Method** | None | Yes |
| **Serialization** | `toJson()`, `toXml()` | `toJson()`, `toXml()`, `java.io.Serializable` |

### 6. Native JSON and XML serialization

Accent records and models automatically receive public `toJson()` and `toXml()` methods:

```java
public record Address(String city) {}
public model Profile(String name, Address address) {}

final profile = new Profile("Lem", new Address("Manila"));
final json = profile.toJson();
// {"name":"Lem","address":{"city":"Manila"}}
final xml = profile.toXml();
// <Profile><name>Lem</name><address><city>Manila</city></address></Profile>
```

Nested records and models, collections, maps, arrays, and scalar values are supported. Null object fields are omitted. JSON uses the existing `accent.json.Json` serializer and its default naming rules. XML uses the type name for the root, field names for child elements, `<item>` elements for collection and array values, and `<entry><key>...</key><value>...</value></entry>` for map entries. XML omits null values and escapes text; invalid XML names or characters produce errors. Circular references produce serialization errors; repeated references without cycles are allowed.

Native collection serialization is restricted to **lists of records/models** and **maps whose values are records/models**. Map keys may use other types. Declare the element/value type explicitly:

```java
List<Profile> profiles = List.of(new Profile("Lem", new Address("Manila")));
final json = profiles.toJson();
final xml = profiles.toXml(); // <List><item>...</item></List>

Map<String, Profile> profilesById = Map.of("U1", new Profile("Ana", new Address("Manila")));
final profilesJson = profilesById.toJson();
final profilesXml = profilesById.toXml(); // <Map><entry><key>U1</key><value>...</value></entry></Map>

final optionalJson = nullableProfiles?.toJson(); // null when the list is null
```

`List<String>`, `List<Integer>`, `Map<String, String>`, raw containers, and containers with unknown or `Object` element/value types do not receive these methods. Typed empty containers are supported. Concrete implementations such as `ArrayList<Profile>` and `LinkedHashMap<String, Profile>` are supported too. XML roots are consistently named `List` and `Map`; empty containers serialize as `[]` / `{}` in JSON and `<List></List>` / `<Map></Map>` in XML. Serialization uses native `toJson()` and `toXml()` methods; the former `Json.stringify()` and `Xml.stringify()` APIs have been removed.

An explicit zero-argument `toJson()` or `toXml()` method takes precedence over the corresponding generated method. These methods require the Accent runtime on the Java classpath.

### 7. Native Singleton Declarations (Kotlin `object` Equivalence)

Use `singleton` before `class`, `record`, or `model` to declare one lazily initialized instance. The previous standalone `singleton Name` syntax is no longer accepted:

```java
public singleton class CacheManager {
    private final Map<String, Object> cache = new HashMap<>();

    public void put(String key, Object val) {
        this.cache.put(key, val);
    }

    public Object get(String key) {
        return this.cache.get(key);
    }
}
```

#### Singleton Usage

```java
final cache = CacheManager.instance();
cache.put("user_1", "Lemuel");

// Or direct method call:
final user = CacheManager.instance().get("user_1");
```

#### Singleton Features & Semantics
* **Kotlin `object` Equivalent**: Provides direct language-level singleton semantics (`singleton class CacheManager { ... }`).
* **Zero Boilerplate**: The compiler automatically generates a private constructor (`private CacheManager() {}`) and single static `.instance()` accessor method.
* **Thread-Safe Lazy Loading**: Emits the **Initialization-on-Demand Holder** JVM idiom, guaranteeing lazy, thread-safe initialization with zero synchronization overhead.
* **Forbidden `new` Instantiation**: Attempting to call `new CacheManager()` produces a compile-time error.
* **Accent & Java Interop**: Access the single instance via `CacheManager.instance()` in both Accent and Java codebase.

#### Singleton records and models

```java
public singleton record Defaults(
    String! theme = "light",
    int retryLimit = 3
) {}

public singleton model AppState(
    final String! id = "app",
    String! theme = "light",
    int attempts = 0
) {}

final defaults = Defaults.instance(); // immutable component bindings
final state = AppState.instance();
state.theme("dark");                 // shared mutable state
```

Every singleton record/model component requires a default expression. Defaults run once, on first access to `.instance()`. Generic singleton declarations are rejected. Classes may declare a private parameterless constructor; record/model constructors are generated and private. All singleton variants are final, disallow direct `new` construction, and reserve `instance`, `copy`, `clone`, `readResolve`, and `__accentCreateSingleton` for compiler use. Singleton models retain setters for mutable components but have no `copy()` method.

A singleton record compiles to a final immutable class rather than a JVM record, so its constructor can be private. It retains component accessors, value methods, native `toJson()`/`toXml()`, and typed List/Map serialization support. Its runtime `Class.isRecord()` is false, and it is not a subtype of `java.lang.Record`. Singleton model/record Java serialization resolves back to the shared instance; JSON/XML typed parsing rejects singleton targets to avoid constructing duplicates. Untyped parsing of their serialized data remains available.

### 8. Immutable and mutable locals

Use `final var` or the `final` shorthand for immutable local bindings (not reassignable):

```java
final var name = "John Doe"; // Explicit final var
final name = "John Doe";     // Shorthand for final var
```

Use `var` for mutable local bindings (reassignable):

```java
var attempts = 0;
attempts++; // Reassignable
```


### 9. Immutable parameters

Method and constructor parameters are implicitly immutable bindings:

```java
public void rename(String! name) {
    final normalizedName = name.trim();
    this.name = normalizedName;
}
```

Reassigning `name` is a compile-time error. The referenced object may still be mutable.

### 10. Named Arguments & Parameter Default Values

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

### 11. Interpolated Strings

Accent includes native support for type-safe, evaluated string interpolation expressions (C#-style `{expr}`) using the leading `$` prefix (`$"..."`, `$``...``$`, or `$"""..."""`).

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
3. **Accent Interpolated String**:
   ```accent
   String interpolated = $"Hello, {name}";
   // Evaluates at runtime to: Hello, Lemuel
   ```
4. **Accent Multiline Interpolated Text Block**:
   ```accent
   String interpolatedBlock = $"""
           Hello, {name}!
           Next year your age will be {age + 1}.
           """;
   // Evaluates at runtime with evaluated expressions inside multiline text block
   ```

#### Syntax Rules and Behavior
* **Activation**: Prefixing a string with `$` (using `$"..."`, `$``...``$`, or `$"""..."""`) enables runtime interpolation.
* **Single & Multiline Support**: Both `$"..."` and triple-quote text blocks `$"""..."""` support multiline string interpolation.
* **Compatibility**: Normal Java strings (`"..."`) and Java text blocks (`"""..."""`) do not interpolate. Braces inside them are treated as literal characters.
* **C#-style Brace Interpolation**: An interpolation expression is marked with `{` and ends with the matching `}` inside an interpolated string.
* **Literal Braces**: Use doubled braces `{{` to produce a literal `{`, and `}}` to produce a literal `}` inside an interpolated string.
* **Evaluation Order**: Expressions inside an interpolated string are evaluated strictly from left to right exactly once.
* **Null Handling**: If an evaluated expression resolves to `null`, it renders as the string `"null"` (equivalent to `String.valueOf(value)`). No `NullPointerException` is thrown from the string mapping itself.
* **Type Behavior**: The entire interpolated string expression resolves to type `java.lang.String`. Void-returning expressions are rejected at compile-time.


### 12. `if` expression

```java
final status = if (order.isPaid()) {
    yield "PAID";
} else {
    yield "PENDING";
};
```

Every reachable branch must yield a compatible result.

### 13. `try` expression

```java
final port = try {
    yield Integer.parseInt(portText);
} catch (NumberFormatException exception) {
    yield 8080;
};
```

### 14. `for` statements and expressions with `else`

Accent uses Java's existing `for` keyword for both enhanced and traditional loops in statement and expression positions. Accent supports an optional `else` branch on `for` loops (`for/else`).

#### `for/else` Semantics
- **Falsy/Empty Iterable**: If the loop iterable is falsy (`null`, empty `List`, empty `Map`, empty array, etc.) or a traditional loop condition starts `false`, the loop body is skipped and the `else` branch executes immediately.
- **Normal Loop Completion**: If the loop executes to completion without encountering a `break` statement, the `else` branch executes.
- **Early Exit via `break`**: If a `break` statement is encountered, the `else` branch is skipped.

#### Enhanced `for/else` Statement

```java
// Executes else branch when list is empty, null, or completed without break
for (var customer : customers) {
    if (customer.id().equals(targetId)) {
        this.process(customer);
        break; // Skips else branch
    }
} else {
    this.logNotFound();
}
```

#### Traditional `for/else` Statement

```java
for (var i = 0; i < maxAttempts; i++) {
    if (this.tryConnect()) {
        break; // Skips else branch on successful connection
    }
} else {
    throw new ConnectionException("All connection attempts failed");
}
```

#### `for/else` Expression

Each executed `yield` contributes an element to the resulting collection. When combined with `else`, if the iterable is empty/falsy, the `else` branch evaluates and yields fallback elements:

```java
final names = for (Customer! customer : customers) {
    yield customer.name();
} else {
    yield "Guest";
};
```

### 15. `while` and `do-while` expressions

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

### 16. Class extensions

Accent can extend an existing Java or Accent class without changing the original class or creating a subclass:

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

### 17. Generator functions

Accent supports generator functions to produce lazy, streamable sequences of values.
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


### 18. Textual boolean operators

Accent supports three textual boolean operators.

#### Operator table

| Textual   | Java equivalent     | Meaning                      |
|-----------|---------------------|------------------------------|
| `not`     | `!`                 | Logical negation             |
| `and`     | `&&`                | Short-circuit logical AND    |
| `or`      | `\|\|`              | Short-circuit logical OR     |

Logical expressions must use `not`, `and`, and `or`. Using unary `!`, `&&`, or `||` produces a compiler error. The non-null type suffix (`String!`) and inequality operator (`!=`) remain supported.

```java
boolean allowed = active and not suspended;
boolean visible = enabled and not hidden;
boolean accepted = valid or trusted or administrator;
```

#### Operator semantics

| Expression     | Java equivalent        |
|----------------|------------------------|
| `not a`        | `!a`                   |
| `a and b`      | `a && b`               |
| `a or b`       | `a \|\| b`             |

#### Truth table

| A     | B     | AND   | OR    |
|-------|-------|-------|-------|
| false | false | false | false |
| false | true  | false | true  |
| true  | false | false | true  |
| true  | true  | true  | true  |

#### Operator precedence (highest to lowest)

1. Parentheses
2. Unary `not`
3. `and`
4. `or`

Binary operators at the same level are left-associative:

```java
not a and b          // parsed as: (not a) and b
a or b and c         // parsed as: a or (b and c)
```

#### Short-circuit behavior

`and` and `or` have the same short-circuit semantics as `&&` and `||`.

#### Type checking

All three textual boolean operators accept truthy/falsy operands of any type and produce boolean results:

```java
// Valid with truthy/falsy operands
boolean result = ready and available;
boolean strResult = "hello" and 42; // true
```


#### Reserved keywords

The following identifiers are reserved as boolean operator keywords and may not be used as variable, method, or type names:

```text
not  and  or
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

    return emailSelected != smsSelected;
}
```

```java
public boolean haveSameStatus(
        boolean firstActive,
        boolean secondActive) {

    return firstActive == secondActive;
}
```


```java
final val = condition ? "Zack" : "Guest";
```

### 19. Truthy and falsy conditions

Accent supports JavaScript-style truthy and falsy condition evaluation. Values of any type can be used directly in conditional control flow statements (`if`, `while`, `do-while`, `for`, ternary `?:`, `if` expressions) as well as logical operators (`not`, `and`, `or`).

#### Truthy / Falsy rules table

| Value / Type | Code Example | Truthiness | Evaluation in Accent `if (x)` |
|---|---|---|---|
| **Null Reference** | `null` | **Falsy** | `false` |
| **Boolean false** | `false` | **Falsy** | `false` |
| **Zero Number** | `0`, `0.0`, `0L`, `0.0f`, `NaN` | **Falsy** | `false` (`n != 0`) |
| **Empty String** | `""` | **Falsy** | `false` (`!s.isEmpty()`) |
| **Empty Generic List / Collection** | `List.of()`, `new ArrayList<>()` | **Falsy** | `false` (`!col.isEmpty()`) |
| **Empty Generic Map** | `Map.of()`, `new HashMap<>()` | **Falsy** | `false` (`!map.isEmpty()`) |
| **Empty Array** | `new String[0]`, `new int[0]` | **Falsy** | `false` (`length > 0`) |
| **Empty Optional** | `Optional.empty()` | **Falsy** | `false` (`opt.isPresent()`) |
| **Boolean true** | `true` | **Truthy** | `true` |
| **Non-Zero Number** | `42`, `-1`, `3.14` | **Truthy** | `true` |
| **Non-Empty String** | `"hello"`, `"0"`, `"false"` | **Truthy** | `true` |
| **Non-Empty Collection / List** | `List.of("item")` | **Truthy** | `true` |
| **Non-Empty Map** | `Map.of("key", "val")` | **Truthy** | `true` |
| **Non-Empty Array** | `new String[]{"item"}` | **Truthy** | `true` |
| **Non-Empty Optional** | `Optional.of("item")` | **Truthy** | `true` |
| **Object Instance** | `new User()`, `new Object()` | **Truthy** | `true` |




#### Examples

```java
final name = "John";
if (name) {
    // Executed because non-empty string is truthy
}

final count = 0;
if (not count) {
    // Executed because 0 is falsy, so (not 0) is true
}
```

### 20. Explicit `this.`

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

### 21. Native Annotations and 3rd Party Annotation Support

Accent fully supports standard Java and framework annotations (such as Spring Boot's `@RestController`, `@Autowired`, etc.) on classes, fields, methods, constructors, and method parameters:

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

### 22. Direct SQL Query Templating

Accent includes native support for SQL template query expressions (using backticks) that automatically parameterize dynamic variables to prevent SQL injection and map results directly to Java record types:

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


### 23. Compile-Time SQL Query Syntax Checking

The compiler automatically parses and validates the SQL syntax of all query literals at compile-time:
* **Mismatched delimiters**: Detects unclosed quotes or mismatched parentheses in the SQL text.
* **Keyword validation**: Enforces correct keyword structure (e.g. `SELECT` requiring `FROM`, `INSERT` requiring `INTO`, `UPDATE` requiring `SET`, and `DELETE` requiring `FROM`).

Any syntax mistakes will immediately halt compilation and raise detailed compiler errors before code is deployed.


### 24. Zero-Boilerplate S  SQL to Record Mapping (ORM)

The runtime automatically maps database result set columns to Java records using constructor reflection:
* **Name-Based Matching**: Maps columns directly to record constructor parameters with matching names (case-insensitive).
* **Positional Fallback**: If compile parameter reflection names are unavailable (e.g., compiled without the `-parameters` flag) or a name match fails, it maps columns to parameters sequentially by select position.
* **Recursive Nested Mapping**: If a record constructor contains a nested record type (e.g., `User(String name, Contact contact)` where `Contact` is `record Contact(String email)`), the mapper recursively instantiates the nested record matching the query columns.


### 25. Synchronous SQL Query Execution for Virtual Threads

Use `Async.run()` to execute a SQL query on a virtual thread, then call `.await()` on its `Async<T>` result:

```accent
import accent.runtime.Async;

final usersAsync = Async.run(() -> sql<User>`SELECT * FROM users`);
// ... concurrent operations ...
final users = usersAsync.await();
```

The query runs on the operation's virtual thread. Actual I/O behavior depends on the JDBC driver and JVM. Database connection-pool limits still apply, and starting more operations does not increase the number of available connections.


### 26. Native Server-Side HTML Components

Accent includes native support for server-side HTML templating via JSX-like markup tags directly integrated into the language.

#### Syntax & Rules
* **HTML Elements**: Lowercase tags (e.g. `<div class="card">`) represent standard HTML elements. Real HTML attribute names (`class`, `for`) are used instead of React-specific names (`className`, `htmlFor`).
* **Accent Components**: Capitalized tags (e.g. `<UserCard user={user} />`) represent custom component classes or records that implement the `accent.html.Component` interface.
* **Property Injection**: Component properties are matched to constructor parameters at compile-time. Property types, names, and presence of required attributes are checked at compile-time.
* **Control Flow**: You can write conditionals (`{if (cond) { ... } else { ... }}`) and loops (`{for (var item : list) { ... }}`) directly inside the markup block to control structure dynamically.
* **Rendering Scope**: Dynamic expressions run when the `Html` is written. Unselected conditional branches are not evaluated, and loop expressions are evaluated within each iteration. Nested components may contain further components and control flow.
* **Boolean Attributes**: `disabled={false}` and `checked={null}` omit the attribute; `disabled={true}` emits `disabled`. Ordinary attributes retain their values, so `title={true}` emits `title="true"`.
* **Fragments**: Use empty tags (`<> ... </>`) to group multiple elements without adding wrapping nodes to the DOM.
* **Compile-Time Optimization**: Adjacent static elements, attributes, and text nodes are merged at compile-time into single static string writes (e.g. `<article class="user-card"><h2>` is optimized to one literal write). There is zero Virtual DOM or runtime overhead.
* **Security & Escaping**: All dynamic text interpolations and standard attribute values are automatically HTML-escaped to prevent XSS. Dynamic URL attributes (like `href` or `src`) are automatically validated at runtime to filter out unsafe protocols (such as `javascript:`). Raw unescaped HTML can be injected using the `TrustedHtml` wrapper class. URL validation removes ASCII tabs and newlines before checking the scheme and uses locale-independent case matching.

#### Example Component:
```java
package accent.html.demo;

import accent.html.Component;
import accent.html.Html;

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

Accent supports standard Java ternary expressions (`{condition ? thenExpr : elseExpr}`). It is fully compatible with Java's standard ternary operator:



### 27. @Logging Annotation

Accent provides a native logging system integrated directly into the language via the `@Logging` annotation, without requiring Lombok, SLF4J, or any external bytecode manipulation.

When you annotate a class, record, or enum with `@Logging`, the compiler automatically injects a static, immutable `log` field bound to that specific type.

```accent
import accent.logging.Logging;

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
private static final accent.logging.Logger log = accent.logging.LogManager.getLogger(BookingService.class);
```

#### Logging Spring Boot Integration

A Spring Boot starter module is available to seamlessly integrate Accent logging with your Spring environment:

```groovy
implementation 'accent:accent-logging-spring-boot-starter:1.0.0'
```

It maps Spring Boot profiles and active environments so that `accent.logging` levels automatically align with your `application.yml` properties. In environments without Spring Boot, it seamlessly falls back to reading `accent-logging.properties`.


### 28. JS-like Symbols

Accent provides a JavaScript-like `Symbol` type in the standard library (`accent.lang.Symbol<T>`) to serve as unique, identity-based keys for metadata, component contexts, and registries without the risk of collisions.

#### Core Semantics
1. **Identity-Based Equality**: Two unique symbols are never equal, even if they share the same description.
2. **Optional Descriptions**: Descriptions exist only for debugging and `toString()` representation.
3. **Generic Type Safety**: Symbols define the type of value they are associated with (e.g., `Symbol<UUID>`).
4. **SymbolMap**: A typed container mapping `Symbol<T>` to `T` with full compile-time type safety.
5. **Thread-Safe Global Registry**: Symbols can be registered globally via `Symbol.forKey("key")`.

#### Unique Symbols
A unique symbol is created with `create(...)`. Identical descriptions still produce completely separate symbol objects.

```accent
static final Symbol<User> CURRENT_USER = Symbol.create("currentUser");
static final Symbol<UUID> TENANT_ID = Symbol.create("tenantId");

Symbol<String> first = Symbol.create("name");
Symbol<String> second = Symbol.create("name");

assert first != second;
```

#### Global Registered Symbols
If you need a shared symbol across components by a string key, use the global registry:

```accent
Symbol<Object> first = Symbol.forKey("application.user");
Symbol<Object> second = Symbol.forKey("application.user");

assert first == second;
```
The registry is thread-safe and scoped to the class loader. `Symbol.keyFor(...)` retrieves a registered symbol's registry key.

#### Typed Metadata using SymbolMap
To avoid String-based key collisions in metadata contexts, use `SymbolMap`:

```accent
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


### 29. Slugs (`accent.web`)

Java does not provide a built-in slug type or standard slugification utility. Developers often have to use third-party libraries or build custom logic combining `Normalizer`, regular expressions, and locales. Accent fills this gap by providing a standardized, immutable value type: `accent.web.Slug`.

#### Basic Example

```accent
import accent.web.Slug;

Slug slug = Slug.from("Aircon Cleaning Services");
System.out.println(slug);
```

Output:
```text
aircon-cleaning-services
```

#### Generation vs. Parsing

Accent makes an important distinction between generating a slug from arbitrary input text versus parsing/validating a value expected to already be a canonical slug.

1. **Generation (`Slug.from`)**:
   - Converts arbitrary user input into a canonical slug format (e.g. lowercasing, resolving diacritics, replacing invalid character runs with separators, collapsing and trimming separators, and enforcing length bounds).
   ```accent
   Slug slug = Slug.from("Café Déjà Vu"); // cafe-deja-vu
   ```
2. **Parsing (`Slug.parse`)**:
   - Validates that the input is *already* in a canonical, normalized slug format. It does not perform any corrections, lowercasing, or transformations, and will throw an `IllegalArgumentException` for non-canonical input.
   ```accent
   Slug slug = Slug.parse("cafe-deja-vu"); // Ok
   Slug.parse("Café Déjà Vu"); // Throws IllegalArgumentException: Invalid slug
   ```
3. **Checking (`Slug.isValid`)**:
   - Validates that a string is a valid canonical slug without throwing an exception, returning a `boolean`.
   ```accent
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
   ```accent
   Slug slug = Slug.from("Café Déjà Vu"); // cafe-deja-vu
   ```

2. **UNICODE**:
   - Preserves Unicode letter and digit symbols while transforming spaces and punctuation to separators.
   ```accent
   Slug slug = Slug.from(
       "東京 レストラン",
       SlugOptions.builder()
           .characterPolicy(SlugCharacterPolicy.UNICODE)
           .build()
   ); // 東京-レストラン
   ```

#### Domain Model & API Integrations

Using `Slug` inside records or domain entities provides strict type safety over raw strings:

```accent
record Service(
    UUID id,
    String name,
    Slug slug
) {}
```

#### Append Example

You can combine slugs cleanly:

```accent
Slug serviceSlug = Slug.from("Home Services")
        .append("Aircon Cleaning");
System.out.println(serviceSlug); // home-services-aircon-cleaning
```

You can test this implementation via the included demo:
```bash
# Compile and run the Slug Demo
./gradlew :accent-compiler:runSlugDemo
```


## Java interoperability

Java interoperability is a first-class requirement.

The target guarantees are:

1. Java APIs are directly callable from Accent.
2. Public Accent classes compile to ordinary JVM classes.
3. Java code can instantiate, call, extend, annotate, and reflect on Accent classes.
4. Accent annotations and generic signatures are preserved.
5. Accent nullability is retained as metadata while remaining compatible with JVM descriptors.
6. Class extensions lower to normal static Java methods.
7. `Async.run(() -> expression)` can run ordinary Java or Accent expressions.
8. Accent requires no modified JVM.
9. Mixed Java and Accent projects produce normal JAR files.
10. Frameworks should not need to know whether a class originated from Java or Accent.


## Requirements

- JDK 21
- Gradle 9.6.1 or a compatible Gradle installation

The build uses a Java 21 toolchain and compiles with `--release 21`.


## Using Accent in Your Project

Use JDK 21 or newer. The following Gradle setup builds against a local Accent checkout, so it does not depend on a remote package having been published.

### 1. Connect Your Accent Checkout

Clone this repository next to your application directory. Add this to your application's `settings.gradle` (adjust the path if needed):

```groovy
rootProject.name = 'my-accent-app'
includeBuild('../accent')
```

Gradle substitutes Accent's actual modules from the included build.

### 2. Add the Spring Boot Starter

Add this to `build.gradle`:

```groovy
plugins {
    id 'java'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web:3.3.1'
    implementation 'accent:accent-spring-boot-starter:0.1.0-alpha.2'
}

sourceSets.main.java.srcDir('build/generated/sources/accent/main')
```

The starter includes `accent-compiler`, which also contains the HTML runtime. There are no separate `accent-html-runtime` or `accent-html-spring` modules. For applications without Spring, depend on `accent:accent-compiler:0.1.0-alpha.2` instead.

Remote Maven, GitHub Packages, or JitPack use requires a separately published and verified artifact; this guide uses the local composite build above.

### 3. Configure the Accent Compile Task

Add this task to the same `build.gradle`. It compiles `.accent` files and saves the generated Java before the application's Java controllers are compiled:

```groovy
tasks.register('compileAccent', JavaExec) {
    group = 'build'
    description = 'Compiles main Accent source files to Java.'
    classpath = configurations.runtimeClasspath
    mainClass = 'accent.cli.AccentCli'
    workingDir = projectDir

    args 'compile', 'src/main/accent', '-d', 'build/classes/java/main',
         '-cp', configurations.compileClasspath.asPath,
         '--save-java', 'build/generated/sources/accent/main'

    inputs.dir('src/main/accent')
    inputs.files(configurations.compileClasspath)
    outputs.dir('build/generated/sources/accent/main')
}

tasks.named('compileJava') {
    dependsOn 'compileAccent'
}
```

Run `./gradlew classes` to compile the component and controller below. In an existing Spring Boot application, keep your normal Boot plugin and application entry point.

### 4. Create and Use HTML Components in Spring Boot

Place your `.accent` files in `src/main/accent/`.

**src/main/accent/UserCard.accent**:
```accent
package com.example.demo;

import accent.html.Component;
import accent.html.Html;

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
import accent.html.Component;

@Controller
public class UserController {

    @GetMapping(value = "/user", produces = "text/html")
    public Component getUser() {
        return new UserCard("Alice", "alice@example.com");
    }
}
```

The `accent-spring-boot-starter` intercepts returned `Component` and `Html` values in both `@Controller` and `@RestController` methods, renders them, and streams `text/html;charset=UTF-8` to the browser. Declare `produces = "text/html"` on HTML endpoints. Requests that accept only incompatible content types receive HTTP 406. Other response types retain Spring's normal response handling, including JSON.

`ResponseEntity<Component>` and `ResponseEntity<Html>` also render HTML through Spring's message converters, preserving the response status and headers. HTML defaults to UTF-8; an explicitly configured response charset is respected.

Accent preserves parameter annotations such as `@RequestParam`, `@PathVariable`, `@RequestBody`, and constructor `@Qualifier`, along with reflection-visible parameter names. This also works with classes compiled directly by Accent, without recompiling the generated Java.

HTML auto-configuration activates only in servlet MVC applications, so the starter can also be used in non-web applications. JDBC integration activates separately when Spring JDBC and a single candidate `DataSource` are available. Accent SQL then uses transaction-aware connections, so statements participate in Spring-managed commit and rollback; JDBC remains optional for applications that do not use a database.

### 5. File-Based Routing

Accent supports file-based routing dynamically at startup. By mapping your package structure to URL endpoints, you can avoid writing manual `@GetMapping` mappings.

* Place your page components inside the `.routes` subpackage (e.g. `com.example.demo.routes`).
* Declare a component class/record named `Page` that implements `accent.html.Component`.
* Directory path parameters use an underscore prefix (e.g. `_name`).

#### File Structure Example:
```text
src/main/accent/routes/
├── Page.accent               // Package com.example.demo.routes -> Maps GET "/"
└── users/
    └── _name/
        └── Page.accent       // Package com.example.demo.routes.users._name -> Maps GET "/users/{name}"
```

#### Page.accent Constructor Parameter Injection:
Dynamic path variables (like `{name}`) and query parameters are automatically mapped and injected into the constructor parameters of the target `Page` component at runtime using reflection (including recursive instantiation of nested record types).

```accent
package com.example.demo.routes.users._name;

import accent.html.Component;
import accent.html.Html;
import com.example.demo.User;

public record Page(User user) implements Component {
    @Override
    public Html render() {
        return (
            <h1>Hello, {this.user.name()}!</h1>
        );
    }
```

#### Nested Layouts (`Layout.accent`)
You can define layout components named `Layout.accent` (producing a class named `Layout` implementing `Component`) in any routing directory. Layouts automatically wrap child page components:
* Root Layout: `routes.Layout`
* Section Layout: `routes.users.Layout`

Layouts accept an `accent.html.HtmlChildren` parameter to render dynamic nested child pages or nested sub-layouts:

```accent
package com.example.demo.routes;

import accent.html.Component;
import accent.html.Html;
import accent.html.HtmlChildren;

public record Layout(HtmlChildren children) implements Component {
    @Override
    public Html render() {
        return (
            <!DOCTYPE html>
            <html lang="en">
                <head>
                    <title>Accent Site</title>
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

#### Decoupled Server Data Loading (`Loader.accent`)

Accent supports decoupled server-side data loading. If a class/record named `Loader` is found in the same package as the `Page` component, its `load(...)` method is executed first at request time to fetch data. The resolved model is then injected directly into the `Page` component's constructor:

```accent
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
```accent
package com.example.demo.routes;

import accent.html.Component;
import accent.html.Html;
import accent.html.spring.Prerender;

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
./gradlew :accent-html-demo-routing:runRoutingDemo
```
* **Root landing page (SSG / File-based routes):** [http://localhost:8080/](http://localhost:8080/)
* **Dynamic parameter page (File-based routes):** [http://localhost:8080/users/Alice](http://localhost:8080/users/Alice)
* **Raw HTML Fragment rendering:** [http://localhost:8080/fragment](http://localhost:8080/fragment)

#### Running the SQL & ORM Demo
Runs a database-integrated application showing SQL prepared statement executions, virtual thread concurrency, and record ORM mapping:
```bash
./gradlew :accent-html-demo-sql:runSqlDemo
```
* **Dynamic User DB parameter route:** [http://localhost:8080/users/Mel](http://localhost:8080/users/Mel) (reads from seeded database row)
* **Database mapping ORM test endpoint:** [http://localhost:8080/](http://localhost:8080/)

#### Running the Symbols Demo
Runs a simple console application demonstrating unique symbol identity, global registry, and `SymbolMap` usage:
```bash
./gradlew :accent-compiler:runSymbolDemo
```

#### `@Logging` Demo

Runs a simple console application demonstrating the natively injected `log` field, multiple log levels, and exception handling:
```bash
./gradlew :accent-compiler:runLoggingDemo
```

#### Named Arguments & Default Parameters Demo

Runs a simple console application demonstrating the use of parameter default values and named arguments in Accent:
```bash
./gradlew :accent-compiler:runNamedArgsDemo
```

## Build & Testing

Build the compiler, runtime, starter, and tooling with the included Gradle wrapper:

```bash
JAVA_HOME=$PWD/jdk ./gradlew clean build
```

On Windows:

```cmd
gradlew.bat clean build
```

### Running All Tests

Run the complete unit and integration test suite:

```bash
JAVA_HOME=$PWD/jdk ./gradlew check test
```

### Running E2E Demo Tests

Run the Spring Boot web routing and SQL database E2E tests:

```bash
JAVA_HOME=$PWD/jdk ./gradlew :accent-html-demo-routing:test
JAVA_HOME=$PWD/jdk ./gradlew :accent-html-demo-sql:test
```

### Running Standalone Demos

Run the standalone feature demos:

```bash
JAVA_HOME=$PWD/jdk ./gradlew runStringInterpolationTest runSymbolDemo runLoggingDemo runNamedArgsDemo runSlugDemo
```


## Run the current CLI

Check whether the example source is lexically valid:

```bash
gradle run --args="check examples/HelloAccent.accent"
```

Print its token stream:

```bash
gradle run --args="tokens examples/HelloAccent.accent"
```

Run the convenience verification task:

```bash
gradle checkAccent
```

Current CLI usage:

```text
accent <check|tokens> <source.accent>
```

At this stage, `check` performs lexical validation only.



## Mutability declaration

The first visible Accent feature will be local mutability declarations:

```java
public class Main {
    public static void main(String[] args) {
        final message = "Hello from Accent"; // 'final' (or 'final var') for immutable variable

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
        final var message = "Hello from Accent";
        var count = 1;

        count++;

        System.out.println(message);
        System.out.println(count);
    }
}
```


## JSON Standard Library (`accent.json`)

Accent provides JSON parsing through `accent.json.Json` and native serialization through `toJson()` on records, models, and supported typed containers.

### Key Features
- **Untyped or typed parsing**: `Json.parse(text)` returns Maps, Lists, and JSON scalar values. `Json.parse(text, Type.class)` maps objects into records or Accent models and rejects ordinary Java classes.
- **Constructor Validation**: Typed parsing invokes the record canonical constructor or the model constructor.
- **Zero-Dependency**: No Jackson, no Gson. It is built natively into Accent, perfectly avoiding large shaded JAR issues.
- **Deep Compatibility**: Supports primitives, `java.time.*` (ISO-8601), `java.util.UUID`, Enums, `Optional<T>`, generic lists/maps, and deeply nested generic records.
- **Customizable**: Control serialization with `JsonOptions` and override property names via `@JsonName`.


### Parsing objects, Lists, and Maps

```java
import accent.json.Json;
import accent.xml.Xml;

Object jsonTree = Json.parse(text); // JSON object -> Map; array -> List
Object xmlTree = Xml.parse(xmlText); // XML data object -> Map; <List> -> List; <Map> -> Map

Profile profile = Json.parse(profileJson, Profile.class);
List<Profile> profiles = Json.parseList(profilesJson, Profile.class);
Map<String, Profile> indexed = Json.parseMap(indexedJson, Profile.class);

Profile xmlProfile = Xml.parse(profileXml, Profile.class);
List<Profile> xmlProfiles = Xml.parseList(profilesXml, Profile.class);
Map<String, Profile> xmlIndexed = Xml.parseMap(indexedXml, Profile.class);
```

Both parsers also accept `Object.class`, `List.class`, and `Map.class` targets. A List or Map target must match the input shape. Typed objects and container values may be records or Accent models; nested records/models, lists, maps, arrays, and primitive values are mapped recursively. Use native `toJson()` / `toXml()` to serialize again.

Untyped JSON preserves boolean, numeric, string, and null values. Untyped XML preserves scalar text as strings, because the XML format carries no scalar type metadata; typed XML parsing converts text using the target field types. XML parsing follows the serializer's `<item>` and `<entry><key>...</key><value>...</value></entry>` conventions. XML attributes, mixed text and elements, duplicate fields/keys, DTDs, and external entities are rejected. Null properties omitted during XML serialization cannot be distinguished from missing properties on parsing. Empty nested XML elements are ambiguous without a target type; typed parsing distinguishes empty strings, containers, and data objects using their declared fields.

## Native JSON Literals

Accent extends standard JSON support with language-level **Native JSON Literals**, allowing you to write JSON structures directly inline with string interpolation!

This leverages Accent's template string syntax (`json<Target>"""..."""`) and lowers directly into highly-optimized `accent.json.Json.parse` calls at compile time.

```accent
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
import accent.json.Json;
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
        if (firstName == null or firstName.isBlank()) {
            throw new IllegalArgumentException("firstName cannot be blank");
        }
    }
}

public class Main {
    public static void main(String[] args) {
        User user = new User(UUID.randomUUID(), "Lemuel", "Adane", LocalDate.of(2000, 1, 1));
        
        // Serialize
        String json = user.toJson();
        
        // Parse directly into a strongly-typed record!
        User parsedUser = Json.parse(json, User.class);
        
        System.out.println(parsedUser.firstName());
    }
}
```

You can test this implementation via the included demo:
```bash
# Compile and run the JSON Demo
javac -cp accent-json/build/classes/java/main accent-compiler/src/e2e/examples/JsonDemo.java
java -cp accent-json/build/classes/java/main:accent-compiler/src/e2e/examples JsonDemo
```


You can test this implementation via the included demo:
```bash
# Compile and run the JSON Literal Demo
./gradlew :accent-compiler:classes
java -cp accent-compiler/build/classes/java/main accent.cli.AccentCli compile accent-compiler/src/e2e/examples/JsonLiteralDemo.accent -d accent-compiler/build/classes/java/demo -cp accent-json/build/classes/java/main --save-java
java -cp accent-compiler/build/classes/java/demo:accent-json/build/classes/java/main JsonLiteralDemo
```

## Project status

Accent is currently in the **compiler-foundation stage**.

The repository already contains:

- a Java 21 Gradle project
- a **lexer** for Accent keywords and operators
- a **parser** producing a full abstract syntax tree
- **semantic analysis** with symbol resolution and type checking
- a **lowering** pass that transforms Accent AST constructs to Java-compatible forms
- a **Java source emitter** for transpilation output
- source-file and diagnostic abstractions
- a CLI with `check`, `tokens`, and `checkAccent` commands
- a virtual-thread runtime with `async` / `await` shorthand and `Async.run()` / `.await()` methods
- JUnit 5 tests (including end-to-end compiler tests)
- an example `.accent` source file

The compiler pipeline is functional from lexing through Java emission. Work continues on expanding language feature coverage and hardening the transpilation output.

## IDE Support & Tooling

Accent includes complete, production-ready IDE support for standard editor features (syntax highlighting, completions, hover info, definition lookup, diagnostics, and code actions):

### 1. `accent-language-tools` (Shared Tooling Core)
The backend service layer that leverages the compiler frontend to provide editor services.
* **Error-Tolerant Parsing**: Recovers gracefully from syntax errors (e.g. trailing dots `obj.`, unclosed braces) to keep giving completions and structure.
* **Semantic Analysis**: Stores type mappings on AST expressions to answer hover and type-check queries.
* **Transpiler Bridge**: Emits generated Java source in-memory.

### 2. `accent-language-server` (Language Server Protocol)
An LSP-compliant server built with LSP4J communicating via `stdin`/`stdout`.
* Writes server logs clean of LSP protocol strictly to `stderr`.
* Built and run via Gradle:
  ```bash
  ./gradlew :tools:accent-language-server:installDist --no-configuration-cache
  ```
  The packaged script is located at:
  `tools/accent-language-server/build/install/accent-language-server/bin/accent-language-server`

### 3. `accent-vscode` (VS Code Extension)
Provides out-of-the-box support for VS Code:
* **Syntax Highlighting**: Custom TextMate grammar (`.accent` language mode) highlighting interpolated strings, JSX HTML tags, and both `async` / `await` shorthand and `Async.run()` / `.await()` calls.
* **LSP Client**: Boots the Language Server from local Gradle build automatically.
* **Updated syntax package**: Install [accent-vscode-0.1.6.vsix](tools/accent-vscode/accent-vscode-0.1.6.vsix) for `async` / `await` shorthand alongside `Async.run()` / `.await()` methods. Older archives retain their original grammar. See the [VS Code installation and build instructions](tools/accent-vscode/README.md#building-updated-editor-support).
* **Commands**: Run command `Accent: Show Generated Java` to transpile on-the-fly and display raw Java side-by-side.

### 4. `accent-intellij` (IntelliJ Plugin)
A native custom-language plugin built using Gradle IntelliJ Platform SDK Plugin 2.x:
* Custom file type registration (`.accent` files and icon).
* Code commenter and brace matcher.
* Connects completion providers and diagnostics directly to `accent-language-tools` for consistent, compiler-accurate Intellisense.
* Compile and test via:
  ```bash
  ./gradlew :tools:accent-intellij:compileKotlin --no-configuration-cache
  ```

## Design goal

A Java developer should be able to read Accent immediately.

Accent keeps Java as the baseline:

- classes, interfaces, records, enums, and annotations
- packages and imports
- constructors and method overloading
- generics and Java collections
- checked and unchecked exceptions
- Java libraries and frameworks
- JVM bytecode and the standard Java runtime

Accent adds focused syntax while preserving Java-shaped code.

## Guiding principle

> **Accent should remove Java's accidental complexity without hiding the programmer's intention.**




