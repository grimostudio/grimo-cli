# depx Reference

## Manifest Format Specification

Each `.depx/manifest/<artifactId>.index` file follows this format:

```
# JAR: jackson-databind-2.17.0.jar
# PATH: /Users/samzhu/.gradle/caches/modules-2/files-2.1/com.fasterxml.jackson.core/jackson-databind/2.17.0/<hash>/jackson-databind-2.17.0.jar
# MTIME: 1711872000

## com.fasterxml.jackson.databind.ObjectMapper
public class com.fasterxml.jackson.databind.ObjectMapper extends com.fasterxml.jackson.databind.ObjectCodec implements ... {
  public com.fasterxml.jackson.databind.ObjectMapper();
  public <T> T readValue(java.lang.String, java.lang.Class<T>) throws ...;
  public java.lang.String writeValueAsString(java.lang.Object) throws ...;
}

## com.fasterxml.jackson.databind.JsonNode
public abstract class com.fasterxml.jackson.databind.JsonNode extends ... {
  public abstract com.fasterxml.jackson.databind.JsonNode get(java.lang.String);
  public java.lang.String asText();
}
```

### Header Fields

| Field | Description |
|-------|-------------|
| `# JAR:` | JAR filename (for display) |
| `# PATH:` | Absolute path to the JAR file (used for decompilation) |
| `# MTIME:` | JAR file modification timestamp (epoch seconds, for SNAPSHOT invalidation) |

### Class Entries

- Each class starts with `## fully.qualified.ClassName`
- Followed by `javap -public` output (class declaration, public fields, public methods)
- Inner classes (containing `$`) are excluded from top-level `##` entries to keep manifests scannable

## Gradle Init Script Details

The init script registers a `depxClasspath` task in all projects:

```groovy
allprojects {
    tasks.register("depxClasspath") {
        doLast {
            def seen = new HashSet()
            configurations.findAll { it.canBeResolved }.each { config ->
                try {
                    config.resolvedConfiguration.resolvedArtifacts.each { a ->
                        if (seen.add(a.file.absolutePath)) {
                            println a.file.absolutePath
                        }
                    }
                } catch (e) { /* skip unresolvable configs */ }
            }
        }
    }
}
```

**Key behaviors:**
- `canBeResolved` filter: skips declarable-only configurations (avoids Gradle errors)
- `HashSet` dedup: prevents duplicate JAR paths from multiple configurations
- `try/catch`: gracefully skips configurations that fail to resolve (e.g., platform-specific deps)
- Outputs one absolute JAR path per line to stdout

**Execution:**
```bash
gradle --init-script /tmp/depx-init.gradle depxClasspath -q
```

The `-q` flag suppresses Gradle's own output, leaving only JAR paths.

### Multi-module Gradle projects

The init script uses `allprojects` so it covers all submodules. If you need a specific submodule:

```bash
gradle --init-script /tmp/depx-init.gradle :submodule:depxClasspath -q
```

## Maven Commands

### Extract classpath

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=.depx/classpath-raw.txt -DincludeScope=compile
tr ':' '\n' < .depx/classpath-raw.txt > .depx/classpath.txt
```

### For specific scopes

```bash
# Compile + provided (good for web apps)
mvn -q dependency:build-classpath -Dmdep.outputFile=.depx/classpath-raw.txt -DincludeScope=provided

# All scopes including test
mvn -q dependency:build-classpath -Dmdep.outputFile=.depx/classpath-raw.txt -DincludeScope=test
```

## CFR Decompiler

### Download

```bash
mkdir -p .depx/tools
curl -fSL -o .depx/tools/cfr.jar "https://github.com/leibnitz27/cfr/releases/download/0.152/cfr-0.152.jar"
```

### Usage

Decompile an entire JAR:
```bash
java -jar .depx/tools/cfr.jar <jar-path> --outputdir .depx/source/<artifactId>/ --silent true
```

Decompile a single class:
```bash
java -jar .depx/tools/cfr.jar <jar-path> --outputdir .depx/source/<artifactId>/ --silent true --methodname "" --jarfilter <fully.qualified.ClassName>
```

**Note:** Whole-JAR decompilation is preferred (Jackknife design insight: one-time 3-5s cost, then all subsequent reads are free filesystem access).

### CFR output structure

```
.depx/source/<artifactId>/
└── com/
    └── example/
        ├── MyClass.java
        ├── MyInterface.java
        └── utils/
            └── Helper.java
```

Each `.java` file contains the decompiled source of one class, ready for Read tool access.

## SNAPSHOT Handling

SNAPSHOT JARs change frequently. The index uses mtime-based invalidation:

1. Each manifest records `# MTIME:` (file modification epoch seconds)
2. On re-index, compare current JAR mtime with cached mtime
3. If different, regenerate that manifest
4. Decompiled source for SNAPSHOTs should be cleaned when re-indexing:
   ```bash
   rm -rf .depx/source/<snapshot-artifactId>/
   ```

## Troubleshooting

### Gradle: "Task 'depxClasspath' not found"

The init script was not applied. Check:
- File path is correct in `--init-script`
- Gradle version supports `tasks.register` (4.9+)

### javap: "Error: class not found"

Some JARs contain non-standard class files. The indexing script uses `2>/dev/null` to suppress these errors. The affected classes will be missing from the manifest but other classes in the same JAR are still indexed.

### Large projects: indexing is slow

For projects with 200+ dependencies, indexing can take several minutes because `javap` is invoked per-class. Strategies:
- Run in background if the agent supports it
- Index only specific JARs by filtering `.depx/classpath.txt`
- Use `jar tf` without `javap` for a fast class-listing-only index (less useful but much faster)

### CFR download fails

If GitHub is unreachable, manually place cfr.jar:
```bash
# Alternative: download from Maven Central
curl -fSL -o .depx/tools/cfr.jar "https://repo1.maven.org/maven2/org/benf/cfr/0.152/cfr-0.152.jar"
```

## .gitignore

Add to project `.gitignore`:
```
.depx/
```

The entire `.depx/` directory is regenerable and should not be committed.
