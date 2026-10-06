# Mixed Java / Oreslang source files

Oreslang source identity is **filesystem-path based**. Do not add Java/Go-style source declarations such as:

```ores
module demo;
```

A file's canonical Unix-style path is its code-unit identity and the base for relative dependency lookup.

## Java inside an `.ores` file

An `.ores` file is Oreslang by default. There are **two deliberately different Java forms**.

`java` is treated as a contextual mixed-source keyword only in these brace forms; it is not being turned into a broad new Oreslang identifier ban. `do` is already a reserved Oreslang control-flow keyword.

### `java { ... }`: inert declarations

`java { ... }` declares Java types. The block is compiled, but its presence does not execute Java code.

```ores
java {
  final class JavaHelper {
    public static String decorate(String value) {
      return "[" + value + "]";
    }
  }
}

pub fnc decorate(String value): String {
  // JavaHelper is automatically available to this .ores source unit.
  return JavaHelper.decorate(value);
}
```

"Inert" means **declaration-only**, not inaccessible. The declared type is automatically imported into the surrounding Oreslang compilation unit and can be constructed or called normally. It only becomes active when ordinary Java semantics require it—for example when Oreslang constructs the class, calls a static method, or otherwise initializes the class.

Merely compiling/linking this source does not initialize the declared class. The runtime loads mixed Java declaration classes with initialization disabled until they are actually used.

A declaration island:

- must appear at Oreslang declaration/source scope, not inside a callable body;
- currently declares exactly one top-level Java class, interface, record, or enum;
- cannot declare its own Java package;
- cannot use the compiler-reserved `__OresJavaDo...` type prefix;
- does not implicitly run a constructor, static method, initializer, or `main`.

If Java is written inside executable Oreslang code, the explicit `do java` form is required.

### `do java { ... }`: execute Java now

`do java { ... }` is a statement-level execution island:

```ores
java {
  final class State {
    private static int count = 0;

    public static void increment() {
      count++;
    }

    public static int count() {
      return count;
    }
  }
}

pub fnc main(): void {
  stdio.println(State.count());

  do java {
    State.increment();
  }

  stdio.println(State.count());
  return;
}
```

The compiler lowers each execution island to an internal Java class equivalent to:

```java
final class __OresJavaDo0 implements Runnable {
  @Override
  public void run() {
    State.increment();
  }
}
```

and the Oreslang statement position behaves equivalently to:

```ores
new __OresJavaDo0().run();
```

The generated helper name is compiler-private and its prefix is reserved.

This gives `do java` ordinary `Runnable.run()` semantics:

- it executes exactly where the Oreslang statement appears;
- `run()` returns `void`, so a `do java` block cannot return a value;
- uncaught checked Java exceptions are rejected by javac because `Runnable.run()` does not declare checked exceptions;
- runtime exceptions propagate back through the Java/Ores interop boundary;
- a `do java` block cannot appear at source declaration scope.

### No implicit Ores-local capture

`do java` intentionally does **not** capture Oreslang lexical locals in this version.

This is rejected by javac:

```ores
pub fnc main(): void {
  val value = 42;

  do java {
    System.out.println(value); // no implicit capture of Ores `value`
  }

  return;
}
```

This avoids inventing hidden boxing, ownership, lifetime, or cross-language mutation semantics. Java can instead communicate through:

- Java types declared by `java { ... }`;
- arguments/results of normal Java methods invoked from Oreslang;
- public Oreslang functions exposed through the generated `Ores` bridge.

For example, a Java declaration or `do java` block in the same generated package can call an exported Oreslang function through `Ores.some_function(...)`.

## Oreslang inside a `.java` file

A `.java` file is Java by default. Use an `ores { ... }` island:

```java
import java.util.ArrayList;

public final class MixedDemo {
  public static void main(String[] args) {
    var values = new ArrayList<String>();
    values.add("java");

    Object same = Ores.identity(values);
    if (same != values) throw new AssertionError("identity changed");

    System.out.println(Ores.count(values));
  }

  ores {
    import class ArrayList as JArrayList from "java:java.util.ArrayList";

    pub fnc identity(JArrayList value): JArrayList {
      return value;
    }

    pub fnc count(JArrayList value): int {
      return value.size();
    }
  }
}
```

The compiler generates a package-local `Ores` facade for public Oreslang functions. Primitive return types are converted to their Java equivalents; imported Java return types remain the Java class. `Ores.call("function_name", args...)` is always available as the dynamic escape hatch.

## Object passing

Inside the same trusted JVM/runtime domain, Java objects cross the Java/Ores boundary **by reference**. They are not serialized into Oreslang collections and reconstructed later. Returning the same Java object from Oreslang therefore preserves Java reference identity.

Java host access is still capability checked. Imported Java classes require `JAVA_INTEROP` plus the exact host-class allowlist. Arbitrary Java source islands additionally require `JAVA_SOURCE_INTEROP`.

Raw Java object references never become a mechanism for crossing an adversarial/untrusted isolate boundary. Private/adversarial isolation must use explicit messages, immutable copies, safe shared buffers, or capability handles instead.

## Security and execution mode

`java { ... }`, `do java { ... }`, and `ores { ... }` source islands are intentionally more privileged than a `java:` class import because compiled Java source can execute with ordinary JVM authority once invoked. `java { ... }` itself remains declaration-only; `do java { ... }` is the explicit execution form.

For that reason:

- `JAVA_SOURCE_INTEROP` and `JAVA_INTEROP` are both required.
- adversarial policies cannot acquire `JAVA_SOURCE_INTEROP`;
- private actors have Java-source and Java-host authority stripped;
- annotation processing is disabled while compiling source islands;
- source-island compilation currently requires `--mode=jit`;
- AOT/hybrid deployments must precompile the Java side instead of compiling arbitrary Java source at runtime.

## Path lookup

Relative Oreslang imports remain Unix-style filesystem lookups:

```ores
import fnc hash from "./crypto/hash.ores";
import class User from "../models/user.ores";
```

Mixed source units may use an explicit `.java` path where that Java file contains an `ores { ... }` island. Extensionless resolution checks `.ores` first, then `.java`.

There is no synthesized Oreslang module name. Canonical paths remain the linker/incremental-compiler identity.
