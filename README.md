# Accent

Accent's JVM compiler and shared runtime. Accent compiles Java-shaped source into
Java and JVM bytecode, with explicit nullability, concise data types, and Java interoperability.
This repository owns the language reference below. Status: **alpha**, `0.1.0-alpha.2`.

| Artifact | Responsibility |
| --- | --- |
| `accent:accent` | Lexer, parser, AST, semantic analysis, lowering, Java emitter, compiler API and CLI |
| `accent:accent-runtime` | Language helpers, async, symbols, JSON/XML and logging; no external production dependencies |

The compiler depends on the runtime. Neither artifact depends on HTTP, Spring,
AccentSQL, AccentOrm, or a database driver. HTML and SQL syntax are compiled here;
programs using those features must supply their separate runtime libraries.

## Read this guide

- [Quick start](#quick-start) and [CLI reference](#cli-reference)
- [Java interoperability](#java-interoperability) and [syntax overview](#syntax-at-a-glance)
- [Nullability and access rules](#core-language-and-safety)
- [Conditions and control flow](#conditions-and-control-flow)
- [Functions, records, models and extensions](#functions-and-data-modeling)
- [Async, JSON and XML](#concurrency-and-data-exchange)
- [Annotations and logging](#framework-integration-and-logging)
- [Symbols](#symbols-and-typed-metadata), [optional integrations](#optional-integrations), and [limitations](#experimental-features)

## Quick start

Use a JDK 21 toolchain and the included Gradle wrapper. From this repository:

```sh
./gradlew test assemble :accent:installDist
accent/build/install/accent/bin/accent run examples/hello/Hello.accent
```

The example prints `Hello, Accent!` and needs no server or database libraries:

```accent
class Hello {
    static void main(String[] args) {
        println("Hello, Accent!");
    }
}
```

Use `gradlew.bat` and the generated `.bat` launcher on Windows. If Gradle cannot
find JDK 21, pass `-Dorg.gradle.java.installations.paths=/path/to/jdk-21`.

## CLI reference

```text
accent check <file-or-dir>
accent tokens <source.accent>
accent compile <file-or-dir> [-d output-dir] [-cp classpath] [--save-java java-dir]
accent run <main-file-or-class> [-cp classpath]
```

`check` validates syntax and semantics; check a directory to resolve references
between files. `compile` generates Java and compiles it. `run` compiles the main
file's directory and launches the program. Use `--help`, `--version`, or `--verbose`.
The default outputs are `build/classes/accent` and `build/generated/sources/accent`.
Pass third-party libraries with `-cp`; use `:` between entries on Unix and `;` on Windows.
The installed launcher resolves source paths from your current directory.

## Java interoperability

Accent compiles to Java source and then ordinary JVM bytecode targeting Java 21. Java APIs are callable from Accent, and Java code can use the generated classes with ordinary JVM tooling. Classes, interfaces, records, enums, annotations, generics, packages, and imports form the language's foundation.

The compiler/runtime lives in this repository; mixed Java and Accent builds can produce ordinary JARs. Class extensions lower to static Java helpers. Accent enforces non-null defaults and explicit nullable contracts with compiler checks and runtime guards, and preserves return and collection-element contracts in compiled-library metadata. The markers are not part of Java names or JVM type descriptors; Java callers use ordinary method names.

Accent has deliberate differences from Java, including [visibility](#4-visibility-and-non-public-member-names), explicit `this`/`super` for instance members, and textual boolean operators. Third-party libraries must be present on the compilation and execution classpaths.

## Syntax at a glance

These are the rules you will use throughout the examples. The detailed sections below explain their contracts and edge cases.

| Intent | Accent syntax | Meaning |
| --- | --- | --- |
| Non-null reference | `String name = "John";` | Must be initialized and cannot hold null |
| Nullable reference | `String nickname? = null;` | Keep `?` when using the binding: `nickname?` |
| Null fallback | `nickname? else "Guest"` | Evaluate the fallback only for null |
| Optional access | `customer?.getName()` | Skip the call if the receiver is null; the overall result is nullable |
| Non-null list and elements | `List<String> names` | Neither the list nor its elements may be null |
| Nullable list and elements | `List<String> names??` | Both the list and its elements may be null |
| Nullable method result | `String nickname?()` → `customer.nickname()?` | Mark the declaration and the result at the call site |
| Binding that cannot be reassigned | `final count = 1;` | Does not make a referenced object deeply immutable |
| Reassignable binding | `var count = 1;` | Allows reassignment |
| Current / inherited instance member | `this.name` / `super.name` | Identify where the selected instance member is declared |
| Static member in its declaring class | `static.count` / `static.increment()` | Access shared state or behavior explicitly |
| Static member from another class | `Counter.count` / `Counter.increment()` | Name the class that declares the member |
| Non-public member | `protected int _count;` | Use an explicit modifier and an underscore prefix |
| Logical expression | `ready and not stopped` | Use `not`, `and`, and `or` |
| Conditional block | `if (ready) { run(); }` | Braces are required for every `if` and `else` body |
| Console output | `println("Hello");` | Shorthand for `System.out.println(...)` |
| Logging | `log("Started");` / `log.info("Started");` | Use the current type's logger without imports or annotations |
| Diagnostic context | `log.put("requestId", id);` | Attach context fields to log events on the current thread |

The collection entries show declaration shapes; non-null local variables and fields still require an initializer. For independent collection and element nullability, see the [complete table](#non-null-array-and-list-elements).

## Core language and safety

### 1. Nullable and non-null reference types

References are **non-null by default**. Add `?` to a binding name to allow null,
and keep that marker when using the nullable binding:

```java
String name = "John";
String nickname? = null;
println(nickname? else name);
```

Non-null fields and local variables require initializers. Assigning null or a
value that may be null to them is a compile error. Nullable fields and explicitly
typed nullable locals may omit their initializer. Parameters receive their values
from callers. Empty strings (`""`) and empty collections are valid non-null values;
`''` is not a supported string literal. Primitive types cannot be nullable; use
boxed types such as `Integer` when needed.

A nullable name remains marked after a null check: `if (nickname? != null) { ... }`.
The check can prove the value safe for a non-null assignment. Truthiness checks
are separate: they do not currently supply the same nullability proof.

#### Non-null array and list elements

Collection nullability and element nullability are independent. See the runnable
[nullable collections example](https://github.com/lemadane/AccentServerSide/blob/main/accent-compiler/src/e2e/examples/NullableCollectionsDemo.accent)
for all four combinations:

| Declaration | Collection may be null | Elements may be null |
| --- | --- | --- |
| `List<String> names` | No | No |
| `List<String?> names` | No | Yes |
| `List<String> names?` | Yes | No |
| `List<String> names??` | Yes | Yes |
| `String[] names` | No | No |
| `String?[] names` | No | Yes |
| `String[] names?` | Yes | No |
| `String[] names??` | Yes | Yes |

`List<String> names??` is shorthand for `List<String?> names?`;
`String[] names??` is shorthand for `String?[] names?`.
A nullable collection can use `?.` for a call that is skipped when it is null.
A trailing `?` after a call or index marks a nullable element result:

```accent
class Names {
    void example(String user?) {
        java.util.List<String> names = new java.util.ArrayList<String>();
        names.add("John");
        if (user? != null) {
            names.add(user?);
        }
        String first = names.get(0);

        java.util.List<String?> mixed = new java.util.ArrayList<String?>();
        mixed.add(null);
        String missing? = mixed.get(0)?;

        java.util.List<String> optional?? = null;
        optional?.add(null);
        String skipped? = optional?.get(0)?;

        String[] labels = {"John", "Jane"};
        String label = labels[0];
        String?[] aliases = {"John", null};
        String alias? = aliases[1]?;
    }
}
```

`names.add(null)` is a compile error for `List<String>`. A nullable value needs
compiler proof, as in the explicit null check above. Nullable-element lists
accept both null and non-null values. These contracts do not guarantee a non-empty
collection, a valid index, or immutable contents. Invalid indices still throw.

Readability counts: ordinary access such as `names.get(0)` is non-null by default;
`mixed.get(0)?` visibly identifies a nullable element. Explicit nullable markers
replace the former `!` and `!!` non-null markers.

Array access follows the same distinction: `labels[0]` accesses a non-null element;
`aliases[0]?` accesses a nullable element; `optionalArray?[0]` skips indexing a
null array; and `optionalArray?[0]?` also marks nullable elements. Skipping access
makes the overall result nullable even when the element type is non-null.

Mutable aliases must preserve the element contract. Runtime guards copy and
validate ordinary list inputs, reject null mutations of non-null-element lists,
and validate reference arrays at boundaries. Direct Java writes to exposed JVM
arrays cannot be intercepted; subsequent guarded Accent reads reject nulls.
These are element contracts, not deep immutability. For HTTP binding, specify
parameter names explicitly in annotations when collection guards rename JVM parameters.

#### Non-null method results

Reference-returning methods are non-null by default. A `?` after the method name
in its declaration permits a nullable result. At the call site, place `?` after
the closing parenthesis:

```accent
class Customer {
    String getName() { return "John"; }
    String getNickname?() { return null; }
}
```

```accent
class Greeting {
    String greet(Customer customer) {
        String nickname? = customer.getNickname()?;
        return nickname? else customer.getName();
    }
    String optional(Customer customer?) {
        return customer?.getName() else "Unknown";
    }
}
```

An optional call such as `customer?.getName()` has a nullable overall result
because the receiver may be null, even though `getName()` promises a non-null
value. A non-null method must return a non-null value on every completing path;
overrides cannot weaken inherited non-null return contracts. JVM method names
remain unchanged. Separately compiled libraries retain contract metadata.

Unannotated Java reference results remain nullable. Use a null check or `else`
fallback when supplying a non-null Accent binding or return value. Java source
retains its own declaration syntax.

### 2. Optional chaining

```java
final city? = customer.address()?.city();
```

If a nullable receiver is `null`, the rest of the optional chain is skipped and the expression evaluates to `null`.

### 3. Null coalescing

```java
Integer port = config.port? else 8080;
final city = customer.address()?.city() else "Unknown";
```

The `else` operator evaluates its right-hand expression only when the left-hand
value is `null`. Empty strings, empty collections, zero, and `false` keep their
original values. Write `user? else fallback` when using a nullable binding.
The old coalescing spelling `??` is reserved for nullable collection and element
markers, such as `List<User> users??` and `User[] users??`.

The old `?:` operator is a syntax error. In `condition then yes else no`, the first unparenthesized `else` ends the `then` branch. Parenthesize a null fallback inside that branch: `active then (nickname? else "Guest") else "Inactive"`. Outside a conditional branch, `value? else fallback` is null coalescing; chained fallbacks associate to the right.

<a id="visibility"></a>

### 4. Visibility and non-public member names

Modifiers determine access. Protected and private field/method names **must begin with `_`** as a visual marker; the prefix remains part of the Java member name.

| Accent declaration | Generated Java visibility | Access |
| --- | --- | --- |
| `public int total;` | `public` | Everywhere the declaring type is accessible |
| `protected int _step;` | `protected` | Same package and subclasses, following Java's protected-access rules |
| `private int _cache;` | Package-private | Same package only |

**Counter.accent**:

```accent
class Counter {
    public int total;
    protected int _step = 1;
    private int _cache;

    public void increment() { this.total = this.total + this._step; }
    public String label() { return "counter"; }
    protected void _grow() { this._step++; }
    private void _reset() { this.total = 0; this._cache = 0; }
}
```

`private int cache;` and `protected void grow() {}` are compile errors: use `_cache` and `_grow`. A trailing underscore (`cache_`) does not satisfy this rule. Bare underscore-only member names are rejected.

Members without a visibility modifier are public. Underscores alone do not set access: `_name` and `__name` have no special visibility meaning. Use explicit `private` or `protected` for every non-public member. Existing name-based declarations must be migrated: `int _cache;` becomes `private int _cache;`, and `int __step;` becomes `protected int _step;` with matching call-site renames (keeping `__step` is also valid).

Accent `private` remains package-private, not class-private. Same-package classes may access these members through an object reference. Protected/private members declared in the current class use `this._member`; inherited instance members accessed on the current object use `super._member`. See [instance member access](#5-explicit-this-and-super) for override and dispatch examples.

* Only one visibility modifier is allowed per declaration.
* Top-level types, declared constructors, and interface members remain public; explicit `public` is optional. `private` and `protected` are rejected in those positions.
* Parameters and local variables have no visibility prefix requirement. Public member names may start with `_`, but that does not restrict their access.
* Each `.accent` file declares at most one ordinary top-level type. Extension blocks may appear alone or accompany it. Type names cannot start with `_`.
* Singletons cannot declare constructors; the compiler generates a Java private constructor. Generated implementation fields (including record/model storage and `@Logging`'s `log`) are exempt from source naming rules.
* Imported `.java` sources and Java libraries retain their names and Java visibility. An Accent override declared `protected` still needs `_`; to override a Java protected method without an underscore, preserve its name and widen the override to `public`.
* `public`, `protected`, and `private` cannot be used as identifiers.

### 5. Explicit `this` and `super`

Use the qualifier that identifies where the selected instance field or method is declared:

| Member access | Required form |
| --- | --- |
| Declared in the current class, including an override | `this.member` / `this.method()` |
| Inherited from a parent, on the current object | `super.member` / `super.method()` |
| Explicit parent implementation of an overridden method | `super.method()` |
| A member of another object | `object.member` / `object.method()` |
| Static member declared in the current class | `static.member` / `static.method()` |
| Static member declared in another class, including a parent | `ClassName.member` / `ClassName.method()` |
| Statically imported member | `member` / `method()` |

The rule applies to public, protected, and accessible package-private instance members. An inherited field or matching method overload cannot be accessed through `this`. A same-named method with different parameters is an overload, not an override. If a child declares a same-named field, `this.field` selects the child's field and `super.field` selects the parent's field.

**FastCounter.accent**, using `Counter` from [Visibility](#4-visibility-and-non-public-member-names):

```accent
class FastCounter extends Counter {
    private int _bonus = 2;

    @Override
    public void increment() {
        super.increment();
        super.total = super.total + this._bonus;
    }

    @Override
    public String label() { return "fast"; }

    public String summary() {
        return this.label() + " / " + super.label();
    }
}
```

`this.label()` selects the override declared in `FastCounter`; `super.label()` selects the parent implementation. `super.total` is required because `total` is inherited. `this._bonus` is required because `_bonus` belongs to `FastCounter`.

Normal calls on another object still dispatch dynamically. For example, `counter.increment()` can invoke `FastCounter.increment()` even when `counter` is declared as `Counter`. `this.method()` also retains virtual dispatch, so a further subclass can override it. `super.method()` deliberately bypasses overrides in the current and further subclasses.

#### Static member access

Use `static.` for static fields and methods declared in the class you are writing. This rule applies inside both static and instance methods, constructors, field initializers, and lambdas. Bare calls such as `increment()` and same-class calls such as `Counter.increment()` are rejected inside `Counter`.

```accent
class Counter {
    static int count = 0;

    static void increment() {
        static.count++;
    }

    void update() {
        static.increment();
    }
}
```

From another class, use `Counter.increment()` or `Counter.count`. In a subclass, inherited static members still use their declaring parent's name, such as `Counter.increment()`. `static.` cannot access instance members; use `this.` or `super.` for those. Static members cannot be accessed through an object reference.

The compiler lowers `static.` to the declaring Java class name; it does not change the JVM member name or dispatch behavior. Ordinary Java source retains Java's access syntax. The built-in `print(...)`, `println(...)`, and `log(...)` calls are explicit compiler shorthands and are exempt from user-defined member qualification. The automatic `log` binding also supports `log.info(...)` and context methods.

#### Static imports

Use `import static` to call a static method or read a static field without its class name. No `$` prefix is required.

```accent
import static java.lang.Math.sqrt;
import static java.lang.Math.PI;

class Circle {
    static double radius(double area) {
        return sqrt(area / PI);
    }
}
```

`import static java.lang.Math.*;` imports the accessible static members of `Math`. Named imports take precedence over wildcard imports. Ambiguous names require an explicit import or a class-qualified reference. Normal overload resolution, visibility, and nullability rules still apply; imports do not make private or instance members accessible. Mutable imported fields can be assigned or incremented.

Local variables shadow imported fields. Members declared in the current class keep their `this.` or `static.` qualification requirement. Built-in `print(...)`, `println(...)`, and `log(...)` retain their built-in meaning even when a static import has the same name.

`super` is unavailable in static contexts and extension blocks. Inside an extension, `this` refers to the extension's target object, so calls such as `this.length()` remain valid. A class with no explicit parent may use `super` for its implicit `Object` parent. Abstract parent methods have no implementation to call through `super`; implement an override before calling them through `this`.

Run the complete example from the repository root after building the CLI distribution:

```bash
accent-compiler/build/install/accent/bin/accent run accent-compiler/src/e2e/examples/inheritance/Main.accent
```

It prints `fast / counter` and `3` on separate lines. All example files are in [the inheritance demo](https://github.com/lemadane/AccentServerSide/blob/main/accent-compiler/src/e2e/examples/inheritance).

### 6. Local variables and reassignment

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

### 7. Parameters and object mutation

Method and constructor parameters cannot be reassigned. This restriction applies to the binding, not the contents of the object it references:

```java
void rename(String name) {
    final normalizedName = name.trim() else "";
    this.name = normalizedName;
}
```

Reassigning `name` is a compile-time error. The referenced object may still be mutable. For example, `items.clear()` is allowed on a mutable list parameter. Nullability contracts do not make a collection immutable.

## Conditions and control flow

### 8. Boolean operators: `not`, `and`, `or`

Use words for logical operations. Each operator accepts truthy/falsy operands and returns a boolean.

| Operator | Java equivalent | Behavior |
| --- | --- | --- |
| `not a` | `!a` | Negates the truthiness of `a` |
| `a and b` | `a && b` | Evaluates `b` only when `a` is truthy |
| `a or b` | `a \|\| b` | Evaluates `b` only when `a` is falsy |

```accent
boolean allowed = authenticated and not suspended;
boolean visible = owner or administrator;
boolean exactlyOneSelected = emailSelected != smsSelected;
```

Among these operators, `not` binds first, then `and`, then `or`. Binary operators at the same level are left-associative. Use parentheses to make grouping explicit:

```accent
boolean canAccess = (authenticated and not suspended) or administrator;
```

Unary `!`, `&&`, and `||` are compile errors in Accent. Equality still uses `==` and `!=`. The words `not`, `and`, and `or` are reserved; longer identifiers such as `notification` remain valid.

The conditional expression uses `condition then yes else no`. It selects a branch by truthiness. Null coalescing, `value? else fallback`, selects a fallback only when the value is null.

### 9. Truthy and falsy conditions

Accent supports JavaScript-style truthy and falsy condition evaluation. Values of any type can be used directly in conditional control flow statements (`if`, `while`, `do-while`, `for`, conditional `condition then yes else no`, `if` expressions) as well as logical operators (`not`, `and`, `or`).

Truthy conditions still require braced `if` and `else` bodies. Braces do not
change truthiness or nullability analysis: use an explicit `user? != null` check
when proving a nullable value safe for insertion into a list with non-null elements.

#### Truthiness by value

| Value | Falsy when | Truthy when |
| --- | --- | --- |
| Reference | Null | A non-null ordinary object, subject to the rules below |
| Boolean | `false` | `true` |
| Number | Zero or `NaN` | Non-zero and not `NaN` |
| String | Empty (`""`) | Non-empty, including `"0"` and `"false"` |
| Collection, map, or array | Empty | Contains at least one entry |
| `Optional` | Empty | Contains a value |

```accent
String name = "John";
if (name) {
    println("Name is present and non-empty");
}

int count = 0;
if (not count) {
    println("Count is zero");
}
```

A truthy collection can still contain null elements when its element contract permits them. Truthiness checks presence or emptiness; it does not validate every element.

<a id="11-if-expression"></a>

### 10. Braced conditionals and `if` expressions

**Braces are mandatory for every `if` and `else` body, even for a single
statement.** Write `if (user? != null) { names.add(user?); }`; the unbraced form
is a compile error. `else if (...) { ... }` chains remain valid, with a braced
body for each condition and the final `else`. An empty branch must use `{}`,
not `;`. This rule also applies to the `else` body of `for/else` and `while/else` loops.
If-expressions and HTML conditionals already require braced bodies.

Explicit block boundaries make the scope of each condition visible and avoid
ambiguous nested branches. Readability counts. This is a compiler requirement,
not just a formatting convention.

For statements, brace every branch, including nested conditions:

```java
if (score > 0) {
    if (score > 100) {
        println("Above target");
    }
} else if (score < 0) {
    println("Negative");
} else {
    println("Zero");
}
```

When migrating existing code, wrap the entire branch body in `{ ... }`.
For example, change `if (ready) return;` (rejected) to
`if (ready) { return; }`. Change `else return;` to `else { return; }`.
An intentionally empty branch is `if (ready) {}`. Ordinary `.java` files keep
Java's syntax; this requirement applies to Accent source.

An `if` expression also uses braced branches:

```java
final status = if (order.isPaid()) {
    yield "PAID";
} else {
    yield "PENDING";
};
```

Every reachable branch must yield a compatible result.

### 11. `try` expressions

Use a `try` expression when successful execution and exception handling both produce a value:

```java
final port = try {
    yield Integer.parseInt(portText);
} catch (NumberFormatException exception) {
    yield 8080;
};
```

### 12. `for` loops with `else`

Use `for (item in list) { ... }` for collection iteration. Accent infers the element type, and `item` is implicitly final: it cannot be reassigned or incremented.

To migrate existing code, replace `each (item from list)` with `for (item in list)`. The old `each/from` syntax is no longer supported. Apply the same change to loop expressions and loops inside HTML markup; their bodies and optional `else` branches keep the same behavior. `in` is contextual and remains usable as an identifier outside the separator position.

Explicit types such as `for (Customer item in customers)` and `for (final Customer item in customers)` are also supported. Explicit `final` remains optional and compatible, including `for (final item in list)`. Final applies to the binding, not to the mutability of the referenced object.

Both the `for in` body and its optional `else` body require braces.

Traditional counting loops use `for (i = 0; i < limit; i++) { ... }`. Existing enhanced `for (var item : list)` loops remain compatible, including their expression and `else` forms. Prefer `for in` in new collection-processing code.

The `else` body must use `{ ... }`, even for a single statement or an empty
fallback. See the [conditional brace rules](#10-braced-conditionals-and-if-expressions).

#### Loop completion and `else`
- **Falsy/Empty Iterable**: If the loop iterable is falsy (`null`, empty `List`, empty `Map`, empty array, etc.) or a traditional loop condition starts `false`, the loop body is skipped and the `else` branch executes immediately.
- **Normal Loop Completion**: If the loop executes to completion without encountering a `break` statement, the `else` branch executes.
- **Early Exit via `break`**: If a `break` statement is encountered, the `else` branch is skipped.

#### `for/else` statement

```java
// Executes else branch when list is empty, null, or completed without break
for (customer in customers) {
    if (customer.id().equals(targetId)) {
        this.process(customer);
        break; // Skips else branch
    }
} else {
    this.logNotFound();
}
```

#### Traditional `for/else` Statement

Traditional loops infer new counters from their initializers:

```accent
for (i = 0; i < maxAttempts; i++) { println(i); }
```

An undeclared `i` becomes a mutable local with its type inferred from `0`. It is scoped to the loop header and body, and is not visible in `else` or after the loop. Unlike a `for in` binding, the counter is mutable so `i++` is allowed. An initializer is required to infer a new variable. Writing `var`, an explicit type, or `final` in a traditional `for` initializer is a syntax error. Ordinary local declarations and compatible enhanced `for (var item : list)` loops keep their existing syntax.

If `i` already names a local variable, the bare form assigns to that variable instead of declaring another one; its final value remains available after the loop. Existing final variables and parameters cannot be reassigned, and fields retain their normal qualification rules. The same initializer rules work in `for` expressions and with `else`.

```java
for (i = 0; i < maxAttempts; i++) {
    if (this.tryConnect()) {
        break; // Skips else branch on successful connection
    }
} else {
    throw new ConnectionException("All connection attempts failed");
}
```

#### `for/else` expression

Each executed `yield` contributes an element to the resulting collection. With `else`, the branch also contributes values whenever the loop completes without `break`, including when it was empty. In the example below, `"Guest"` is appended after normal iteration as well as for an empty iterable:

```java
final names = for (customer in customers) {
    yield customer.name();
} else {
    yield "Guest";
};
```

| Each expression execution | Result |
| --- | --- |
| Body yields `1`, `2`; normal completion yields `3` from `else` | `[1, 2, 3]` |
| Empty or null collection; `else` yields `9` | `[9]` |
| Body yields `1`, then breaks | `[1]`; `else` is skipped |

The iterable is evaluated once. Iteration variables are scoped to the body and are not visible in `else`; use the normal `?` marker when declaring and referencing nullable items. `continue` does not suppress `else` after normal completion, and breaking an inner loop does not suppress the outer loop's `else`. The expression form ends with `;` after its complete body or `else` block.

### 13. `while` and `do-while` expressions

`while (condition) { ... } else { ... }` follows the same completion rules as `for/else`:

- `else` runs when the condition becomes falsy, including before the first iteration.
- A `break` exiting this loop skips its `else`; breaking a nested loop does not.
- `continue` still allows `else` after normal completion. A `return` or escaping exception bypasses it.
- Both bodies of `while/else` require braces. Variables declared inside the loop body are not visible in `else`.

```accent
int attempts = 0;
while (attempts < 3) {
    attempts++;
    if (this.tryConnect()) {
        break;
    }
} else {
    println("All connection attempts failed");
}
```

A `while` expression collects `yield` values from both the loop and its `else` when that branch runs:

```accent
int count = 0;
final values = while (count < 2) {
    count++;
    yield count;
} else {
    yield 3;
}; // [1, 2, 3]
```

The expression returns a list of the values yielded by the branches that actually execute; `else` does not replace values already collected. End the complete expression with `;`.

| Expression execution | Values yielded by the body | Values yielded by `else` | Result |
| --- | --- | --- | --- |
| Normal completion | `1`, `2` | `3` | `[1, 2, 3]` |
| Zero iterations | None | `9` | `[9]` |
| `break` after yielding `7` | `7` | Skipped | `[7]` |

If no executed branch yields a value, the result is an empty list. `continue` skips the remaining statements of the current iteration but does not suppress `else` when the loop later completes normally.

See the [runnable while/else example](https://github.com/lemadane/AccentServerSide/blob/main/accent-compiler/src/e2e/examples/whileelse/Main.accent) and [expression regression tests](https://github.com/lemadane/AccentServerSide/blob/main/accent-compiler/src/test/java/accent/compiler/WhileElseTest.java).

Plain `while` and `do-while` expressions remain supported.

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

#### `do-while/else` statements and expressions

Use `do { ... } while (condition) else { ... }` for a loop that must execute at least once. There is one loop body, after `do`; no second block follows `while (condition)`. Both the loop body and the `else` body require braces.

```accent
int attempts = 0;
do {
    attempts++;
    if (this.tryConnect()) {
        break;
    }
} while (attempts < 3) else {
    println("All connection attempts failed");
}
```

The condition is checked after each iteration. `else` runs when it becomes falsy; a `break` exiting this loop skips `else`. A nested loop's `break` does not suppress the outer loop's `else`. `continue` checks the condition before the next iteration, and still allows `else` on normal completion. A `return` or escaping exception bypasses `else`.

The expression form collects every executed `yield`, including those in `else`:

```accent
int count = 0;
final values = do {
    count++;
    yield count;
} while (count < 2) else {
    yield 3;
}; // [1, 2, 3]
```

| Expression execution | Body yields | Else yields | Result |
| --- | --- | --- | --- |
| Normal completion | `1`, `2` | `3` | `[1, 2, 3]` |
| Condition is false on the first check | `7` | `9` | `[7, 9]` |
| Break after yielding `7` | `7` | Skipped | `[7]` |

Unlike `while/else`, a `do-while/else` loop never has zero iterations. Variables declared inside the loop body remain scoped to that body. With `else`, place no semicolon between `while (condition)` and `else`; an expression ends with `;` after the complete `else` block. Ordinary `do { ... } while (condition);` retains its existing syntax.

See the [runnable do-while/else example](https://github.com/lemadane/AccentServerSide/blob/main/accent-compiler/src/e2e/examples/dowhileelse/Main.accent).

## Functions and data modeling

### 14. Named arguments and default parameters

Method and constructor parameters can define default values, which can then be invoked optionally or using named arguments.

#### Parameter Default Values:
```java
int calculate(int base, int multiplier = 2, int offset = 1) {
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

### 15. String interpolation

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

#### Console output

Use `println(...)` to write a value followed by a newline, `println()` for a blank line, and `print(...)` to write without a newline:

```accent
print("Hello, ");
println("Accent!");
println(42);
println();
```

These built-in calls use `print` and `println`, including the standard output stream's formatting and redirection. They need no import or receiver. Explicit `System.out` calls remain valid. If you declare your own method named `print` or `println`, access it through `this.` or `static.`; a bare call selects the built-in shorthand.

For log levels, timestamps, file output, and request context, use [logging](#22-logging-and-diagnostic-context). The runnable [console and logging demo](https://github.com/lemadane/AccentServerSide/blob/main/accent-compiler/src/e2e/examples/console/ConsoleLoggingDemo.accent) shows both APIs.

### 16. Data models and records

Use a `record` for data with final component bindings. Use a `model` when some components need setters. Both generate accessors and value-based equality; a model also generates `copy(...)`.

Omit braces when a record or model has no explicit members: `record Address(String city)` and `model Profile(String name, Address address)`. Empty `{}` bodies, including comment-only bodies, are syntax errors. Use braces only when declaring members such as methods or constructors. The same rule applies to singleton records and models; Java source retains Java syntax.

```java
model User(
    final String id,   // final     -> Immutable (getter id() ONLY, no setter)
    String name,       // default   -> Mutable   (getter name(), setter name(...))
    int age             // default   -> Mutable   (getter age(), setter age(...))
)
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
println(user); // User[id=U101, name=Lemuel A., age=43]
```

#### Model Features & Semantics

Getters and setters share the field name: `user.name()` reads it, and `user.name("Ann")` updates it. Setters return `void`; no `set`-prefixed methods are generated.

* **Default Mutability**: Components without `final` are mutable by default, generating getters (`name()`, `age()`) and setters (`name(...)`, `age(...)`). Nullable reference getters use a trailing `?` after the call, such as `user.nickname()?`; non-null getters remain unmarked.
* **Final bindings (`final`)**: A component such as `final String id` generates a getter (`id()`) and no setter. Calling its setter is a compile-time error. Final bindings can still reference mutable objects.
* **Kotlin-style `copy(...)` Method**: Generates a non-null copy using the supplied component values.
* **Value-based `equals()` and `hashCode()`**: Generates value-based equality and hashing implementations.
* **Automatic `toString()`**: Emits formatted string representation (`User[id=..., name=..., age=...]`).
* **Built-in `java.io.Serializable`**: Implements `java.io.Serializable` with `serialVersionUID = 1L` out of the box.

#### `model` vs `record` Comparison

| Feature | Accent `record` | Accent `model` |
| :--- | :--- | :--- |
| **Primary Purpose** | Data holder with final components | Kotlin-style Data Class |
| **Java Target** | Java `record` | `public final class` |
| **Field Mutability** | Final component bindings (shallow immutability) | Mutable by default (or Immutable with `final`) |
| **Setters Generated** | None | Yes (for non-final fields) |
| **`copy()` Method** | None | Yes |
| **Serialization** | `toJson()`, `toXml()` | `toJson()`, `toXml()`, `java.io.Serializable` |

Record components and `final` model fields may still reference mutable objects. `copy(...)` takes all component values; it is a shallow copy, except that non-null-element collection components apply the collection boundary validation and copying rules described above.

### 17. Singleton declarations

Use `singleton` before `class`, `record`, or `model` to declare one lazily initialized instance. The previous standalone `singleton Name` syntax is no longer accepted:

```java
singleton class CacheManager {
    private final Map<String, Object> _cache = new HashMap<>();

    void put(String key, Object val) {
        this._cache.put(key, val);
    }

    Object get(String key) {
        return this._cache.get(key);
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
* **Accent & Java Interop**: Access the single instance via `CacheManager.instance()` in Accent and `CacheManager.instance()` in Java.

#### Singleton records and models

```java
singleton record Defaults(
    String theme = "light",
    int retryLimit = 3
)

singleton model AppState(
    final String id = "app",
    String theme = "light",
    int attempts = 0
)

final defaults = Defaults.instance(); // immutable component bindings
final state = AppState.instance();
state.theme("dark");                 // shared mutable state
```

Every singleton record/model component requires a default expression. Defaults run once, on first access to `.instance()`. Generic singleton declarations are rejected. Singletons may not declare constructors: the compiler generates a private one for every variant, so initialize state with field initializers. All singleton variants are final, disallow direct `new` construction, and reserve `instance`, `copy`, `clone`, `readResolve`, and `$accentCreateSingleton` for compiler use. Singleton models retain setters for mutable components but have no `copy()` method.

A singleton record compiles to a final immutable class rather than a JVM record, so its constructor can be private. It retains component accessors, value methods, native `toJson()`/`toXml()` calls in Accent, and typed List/Map serialization support. Its runtime `Class.isRecord()` is false, and it is not a subtype of `java.lang.Record`. Singleton model/record Java serialization resolves back to the shared instance; JSON/XML typed parsing rejects singleton targets to avoid constructing duplicates. Untyped parsing of their serialized data remains available.

### 18. Class extensions

Accent can extend an existing Java or Accent class without changing the original class or creating a subclass:

```java
extension String {
    String normalized() {
        return this.trim().toUpperCase() else "";
    }
}
```

Usage:

```java
final normalizedName = name.normalized();
```

Class extensions lower to ordinary static Java helper methods. Real instance members take precedence over extension members.

## Concurrency and data exchange

### 19. Concurrency with virtual threads

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

### 20. JSON and XML

Records and models receive public `toJson()` and `toXml()` methods that return non-null strings:

```java
record Address(String city)
model Profile(String name, Address address)

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

final optionalJson? = nullableProfiles?.toJson(); // null when the list is null
```

`List<String>`, `List<Integer>`, `Map<String, String>`, raw containers, and containers with unknown or `Object` element/value types do not receive these methods. Typed empty containers are supported. Concrete implementations such as `ArrayList<Profile>` and `LinkedHashMap<String, Profile>` are supported too. XML roots are consistently named `List` and `Map`; empty containers serialize as `[]` / `{}` in JSON and `<List></List>` / `<Map></Map>` in XML. Serialization uses native `toJson()` and `toXml()` calls in Accent; the former `Json.stringify()` and `Xml.stringify()` APIs have been removed.

An explicit zero-argument method named `toJson` or `toXml` takes precedence over its generated counterpart. Use a trailing `?` after a call when the explicit method declares a nullable return contract. These methods require the Accent runtime on the Java classpath.

#### JSON Standard Library (`accent.json`)

Accent provides JSON parsing through `accent.json.Json` and native serialization through `toJson()` on records, models, and supported typed containers.

##### Key Features
- **Untyped or typed parsing**: `Json.parse(text)` returns Maps, Lists, and JSON scalar values. `Json.parse(text, Type.class)` maps objects into records or Accent models and rejects ordinary Java classes.
- **Constructor Validation**: Typed parsing invokes the record canonical constructor or the model constructor.
- **No external JSON library**: The Accent runtime implements JSON parsing and serialization without Jackson or Gson.
- **Deep Compatibility**: Supports primitives, `java.time.*` (ISO-8601), `java.util.UUID`, Enums, `Optional<T>`, generic lists/maps, and deeply nested generic records.
- **Customizable**: Control serialization with `JsonOptions` and override property names via `@JsonName`.


##### Parsing objects, Lists, and Maps

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

#### Native JSON Literals

Use `json<Target>"""..."""` to write an interpolated JSON document and parse it into a target type. The compiler emits `accent.json.Json.parse` calls; parsing happens at runtime.

```accent
// 1. Define your strict target Record
record User(
    String id,
    String firstName,
    String lastName
)

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

##### Quick Start

```java
// User.accent
import java.util.UUID;
import java.time.LocalDate;

record User(
    UUID id,
    String firstName,
    String lastName,
    LocalDate birthDate
) {
    User(UUID id, String firstName, String lastName, LocalDate birthDate) {
        // Executed for direct construction and JSON parsing.
        if (firstName == null or firstName.isBlank()) {
            throw new IllegalArgumentException("firstName cannot be blank");
        }
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.birthDate = birthDate;
    }
}

// Main.accent (one top-level type per file)
import accent.json.Json;
import java.util.UUID;
import java.time.LocalDate;

class Main {
    static void main(String[] args) {
        User user = new User(UUID.randomUUID(), "Lemuel", "Adane", LocalDate.of(2000, 1, 1));

        // Serialize
        String json = user.toJson();

        // Parse directly into a strongly-typed record!
        User parsedUser = Json.parse(json, User.class);
        println(parsedUser.firstName());
    }
}
```

The legacy Java JSON demo lives in AccentServerSide. From that checkout, after
installing its dependencies:
```bash
./gradlew :accent-compiler:installDist
javac -cp "accent-compiler/build/install/accent/lib/*" -d accent-compiler/build/classes/java/demo accent-compiler/src/e2e/examples/JsonDemo.java
java -cp "accent-compiler/build/classes/java/demo:accent-compiler/build/install/accent/lib/*" JsonDemo
```


In the same AccentServerSide checkout, the Accent JSON literal demo keeps `JsonLiteralDemo.accent`, `User.accent`, and `Address.accent` in `accent-compiler/src/e2e/examples/json/`, with one type per file. Compile and run them together:
```bash
./gradlew :accent-compiler:runJsonLiteralDemo
```

## Framework integration and logging

### 21. Java and framework annotations

Use Java and framework annotations, such as Spring's `@RestController` and `@Autowired`, on classes, fields, methods, constructors, and parameters:

```java
@RestController
@RequestMapping("/api")
class ProductController {

    @Autowired
    private ProductService _productService;

    @GetMapping("/{id}")
    Product getProduct(@PathVariable String id) {
        return this._productService.findById(id);
    }
}
```

### 22. Logging and diagnostic context

Every Accent type can use `log` without an import, annotation, or field declaration. The non-null `Logger` binding combines severity-based logging and MDC-style diagnostic context in one API. The `Logger` type itself is implicitly available, like `String` or `Object`.

| Call | Meaning |
| --- | --- |
| `log("Started")` | Shorthand for static `Logger.log("Started")` (INFO) |
| `log("Failed", exception)` | Logs at INFO with exception details; use `log.error(...)` for ERROR severity |
| `log.trace(...)`, `.debug(...)`, `.info(...)`, `.warn(...)`, `.error(...)` | Select a severity; each accepts a message and an optional exception |
| `final log = new Logger()` | Create a logger instance named after the calling class; no import needed |
| `log.put(...)`, `.get(...)`, `.remove(...)`, `.clear()` | Manage diagnostic context on the current thread |

```accent
class Application {
    static void main(String[] args) {
        println("Application starting");
        log("Started");
        final log = new Logger();
        log.info("Ready to accept work");
        Logger.log("Also available as a static call");
    }
}
```

Use explicit levels in application code:

```accent
class BookingService {
    Booking create(BookingRequest request) {
        log.debug($"Creating booking for {request.customerName()}");

        try {
            Booking booking = this.save(request);
            log.info($"Booking {booking.id()} was created");
            return booking;
        } catch (Exception exception) {
            log.error($"Failed to create booking", exception);
            throw exception;
        }
    }
}
```

The automatic logger uses the enclosing type's fully qualified name. It is obtained lazily through Accent's cached `LogManager`, so unused logging does not add an eagerly initialized field to every class. Logger handles are shared by name; diagnostic context is shared only within the current thread.

The built-in `log` binding cannot be reassigned. A local variable or parameter named `log` shadows it; an explicitly declared field named `log` follows normal member qualification. Explicit imports or application types named `Logger` take precedence over the implicit Accent type. Bare `log(...)` always calls static `accent.logging.Logger.log(...)`, even when a local variable is named `log`; `log.info(...)` uses that local instance. `new Logger()` uses the calling class name, and `new Logger("category")` selects an explicit category. Static logging shares the current thread's context with logger instances. Bare `log(...)` selects the shorthand; call your own methods named `log` through `this.` or `static.`.

Existing `@Logging` code remains supported: the annotation generates the same static final logger field as before. It is optional for the native shorthand. No Lombok or SLF4J dependency is required.

#### Diagnostic context

Every `Logger`, including the automatic `log` binding, can attach string fields to log events on the current thread. For example:

```accent
import java.util.Map;

class CustomerService {
    void process(String requestId) {
        log.withContext(Map.of("requestId", requestId), () -> {
            log.info("Processing customer");
            // Output ends with: Processing customer {requestId=...}
        });
    }
}
```

Use `log.put(key, value)`, `log.get(key)`, `log.remove(key)`, and `log.clear()` to manage fields. `log.clearContext()` remains an equivalent spelling. Keys and values must be non-null strings. `get` returns null for a missing key. `log.contextMap()` returns an immutable snapshot (an empty map when unset); `log.setContextMap(map)` replaces the current context with a defensive copy. `log.withContext(map, body)` adds fields for the duration of `body` and then restores the previous context, even if `body` throws.

The context is per thread, not per logger: fields set through one logger appear on events from every logger on the same thread.

Each log event captures its own immutable snapshot. Console and file output append nonempty context as `{customerId=C1, requestId=R1}`, sorted by key. Output without context keeps its existing format.

Both `async expression` and `Async.run(...)` capture the context when the task is submitted, including void tasks. Nested tasks inherit their parent's snapshot. Changes in a task do not change its parent or sibling tasks, and task context is cleared on completion or failure. Arbitrary Java threads, external executors, and Spring request handling do not automatically propagate the Accent context; use `contextMap()`/`setContextMap(...)` explicitly at those boundaries. The Accent context is independent of SLF4J MDC.

#### Logging configuration

Place `accent-logging.properties` in the process working directory to configure application logs:

```properties
rootLevel=INFO
consoleEnabled=true
fileEnabled=true
filePath=application.log
```

Console logging sends TRACE/DEBUG/INFO to stdout and WARN/ERROR to stderr. Enabled file output receives all events allowed by `rootLevel`. Throwable overloads include stack traces. `rootLevel=OFF` disables Accent log output.

Use `LogManager.configure(...)` or `reload()` to apply configuration changes rather
than mutating the active configuration object.

For request-specific context and Spring logging configuration, see
[ServerSide logging](https://github.com/lemadane/AccentServerSide#logging-in-web-applications).

## Symbols and typed metadata

Use `accent.lang.Symbol<T>` as an identity-based key when string keys could collide between modules. `SymbolMap` associates a symbol with a value of its declared type.

| API | Purpose |
| --- | --- |
| `Symbol.create(description)` | Creates a new identity every time, even with the same description |
| `Symbol.forKey(key)` | Shares an identity by key through a thread-safe, class-loader-scoped registry |
| `Symbol.keyFor(symbol)` | Retrieves the key of a registered symbol |
| `SymbolMap.put(symbol, value)` / `.get(symbol)` | Stores or retrieves a typed metadata value |

```accent
import accent.lang.Symbol;
import accent.lang.SymbolMap;

Symbol<String?> statusKey? = Symbol.create("status");
SymbolMap context = new SymbolMap();
context.put(statusKey?, "ACTIVE");
String status? = context.get(statusKey?);
println(status? else "UNKNOWN");
```

The nullable markers above reflect the unannotated Java API results and the possibility of a missing map entry. A symbol's description is for debugging; it does not determine identity. Symbols are useful for request attributes and plugin metadata, but are not persistent database IDs and should not be treated as serialization keys.

See the runnable [symbols example](https://github.com/lemadane/AccentServerSide/blob/main/accent-compiler/src/e2e/examples/SymbolDemo.accent) for unique identities, the global registry, and typed values. `symbol` is not a reserved keyword.

## Optional integrations

- [SQL templates and execution](https://github.com/lemadane/AccentSQL#sql-templates-and-bound-parameters): the compiler recognizes templates; AccentSQL executes them and AccentOrm maps results.
- [HTML components](https://github.com/lemadane/AccentServerSide#html-components): the compiler recognizes markup; ServerSide renders it and serves HTTP.
- [Editor support](https://github.com/lemadane/AccentEditors): shared language services, the LSP server, and editor clients.
- [Android plan and compatibility](https://github.com/lemadane/AccentAndroid#compatibility): use the compiler on the build host; Android runtime support is not implemented yet.

## Build, tests, and publication

```sh
./gradlew clean build
./gradlew publishToMavenLocal
```

The two libraries publish version `0.1.0-alpha.2`, including sources. CI builds and
tests Core independently; release workflows publish to GitHub Packages. Other
repositories may consume Maven Local artifacts or opt into a Core source checkout.
The standalone tests include compiler/runtime boundary checks and executable
language-documentation examples. ServerSide retains additional cross-library regressions.

## Experimental features

### Generator syntax

The parser and emitter recognize `generator` methods and `emit` statements. Return-name markers are not supported for experimental generators. The declared return type is the element type: `generator int numbers(...)` emits a Java method returning `Iterable<Integer>`.

The emitter currently calls `accent.runtime.AccentGenerator.of(...)`, but that helper is absent from this checkout. Frontend checks and Java-emission tests do not establish runnable generator support. Treat generators as incomplete until the runtime and execution tests are supplied.

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

## Related repositories

| Repository | Owns |
| --- | --- |
| [Accent](https://github.com/lemadane/Accent) | Compiler, language specification, CLI, async, JSON/XML, logging, symbols |
| [AccentServerSide](https://github.com/lemadane/AccentServerSide) | HTTP server, HTML rendering, Spring web integration, routing |
| [AccentEditors](https://github.com/lemadane/AccentEditors) | VS Code, IntelliJ IDEA, and Android Studio integrations and shared language services |
| [AccentSQL](https://github.com/lemadane/AccentSQL) | JDBC execution, SQL template usage, Spring database integration |
| [AccentOrm](https://github.com/lemadane/AccentOrm) | JDBC row-to-object mapping |
| [AccentSqlite](https://github.com/lemadane/AccentSqlite) | SQLite driver and connection setup |
| [AccentAndroid](https://github.com/lemadane/AccentAndroid) | Planned Android integration; currently a scaffold |

## License

MIT; see [LICENSE](LICENSE).
