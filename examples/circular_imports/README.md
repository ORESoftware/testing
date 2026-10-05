# Circular imports

Oreslang intentionally permits import cycles. Run:

```bash
mvn -q -DskipTests exec:java -Dexec.args="examples/circular_imports/a.ores"
```

`a.ores` imports `b_value` from `b.ores`, while `b.ores` imports
`a_value` from `a.ores`.

The host loader parses and validates the complete reachable graph, links every
code unit, then runs file-level `fnc init() => void` hooks. The A/B strongly
connected component is therefore fully linked before either init hook executes.

Expected output:

```text
init-a:B|init-b:A|main:AB
```

Init order inside a cycle is deterministic by normalized code-unit id. The
important semantic guarantee is the barrier: no init in the cycle can execute
while another member of that cycle is still unloaded.
