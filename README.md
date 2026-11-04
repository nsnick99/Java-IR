# Java IR Generator

Standalone compiler-oriented Java project analyzer. It accepts Java source trees and compiled JVM bytecode (`.class`, `.jar`, or directories of class files) and emits a normalized JSON IR. It has no dependency on any auditor or migration product.

## Requirements
- JDK 17+
- Maven 3.9+

## Build
```bash
mvn clean package
```
This creates `target/java-ir-generator-1.0.0-jar-with-dependencies.jar`.

## Run
```bash
# Analyze source tree
java -jar target/java-ir-generator-1.0.0-jar-with-dependencies.jar --input ./sample-project --mode source --output ./output/project_ir.json

# Analyze compiled classes or a directory of class files
java -jar target/java-ir-generator-1.0.0-jar-with-dependencies.jar --input ./target/classes --mode bytecode --output ./output/project_ir.json

# Analyze a JAR
java -jar target/java-ir-generator-1.0.0-jar-with-dependencies.jar --input ./app.jar --mode bytecode --output ./output/project_ir.json
```

## IR contents
- Project metadata and diagnostics
- Classes, interfaces, enums, records and annotations
- Fields, constructors, methods, signatures and annotations
- Imports, inheritance and implemented interfaces (source)
- Expressions, method calls and variable-name accesses (source)
- JVM instructions, method/field references and descriptors (bytecode)
- Control-flow edges, branch/switch targets and exception handlers
- ASM `SourceInterpreter` frames showing stack values and producer instruction IDs

## Current limitations
This is a first implementation, not a production compiler frontend. Source-mode CFG is approximate and currently models top-level statements rather than fully lowering nested blocks, loops, switches and exception regions. Source-mode variable accesses are lexical heuristics, not resolved read/write sets or SSA. JavaParser symbol resolution is not yet configured with the target project's Maven/Gradle classpath. Bytecode-mode data-flow frames use ASM's `SourceInterpreter`; they are not a full interprocedural data-flow engine or SSA construction. Bytecode local-variable names depend on debug metadata. Multi-release JAR selection and module/classpath resolution are not yet implemented. These limitations are surfaced through precision metadata where applicable.

## Suggested next steps
1. Resolve Maven/Gradle classpaths and configure JavaParser's `CombinedTypeSolver`.
2. Lower source AST into a language-neutral instruction set and build exact basic blocks.
3. Add dominators, post-dominators, loop detection and SSA (phi nodes).
4. Add reaching-definitions, liveness and interprocedural call-graph algorithms.
5. Add source-to-bytecode mapping using debug line tables and SMAP where present.
6. Add schema validation and golden-file tests for both frontends.

## Design
Both frontends emit the same top-level `classes[]` collection and shared concepts (`methods`, `calls`, `cfg_nodes`, `cfg_edges`). Frontend-specific details are retained under explicit fields (`instructions`, `expressions`, `data_flow_frames`, `origin`) rather than silently pretending source and bytecode expose identical information.
