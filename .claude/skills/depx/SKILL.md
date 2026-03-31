---
name: depx
description: >-
  MUST USE when inspecting JVM dependency source code, SDK internals, or library
  APIs. Uses .depx/ index — fast search without permission prompts. Use INSTEAD
  of find/javap/bash on JAR files. Triggers for: SDK研究, library源碼, verify
  API, check how library works, 看原始碼, 確認SDK. NOT for project source code.
allowed-tools: Bash, Read, Write, Glob, Grep
metadata:
  author: samzhu
  version: 0.1.0
  category: development
  tags: [java, jvm, dependency, decompile, gradle, maven]
---

# depx — Dependency Explorer

You are an AI agent that needs to explore JVM dependency source code. Follow these rules strictly.

## Priority Rules (NEVER violate)

1. **NEVER** run `find ~/.gradle/caches`, `find ~/.m2`, or scan local repository caches directly
2. **ALWAYS** check `.depx/manifest/` first using the Grep tool (no Bash needed, no permission prompt)
3. **ALWAYS** check `.depx/source/` for decompiled code using the Read tool (no Bash needed)
4. If `.depx/manifest/` does not exist or is empty → run the **Index Build** procedure below
5. If decompiled source is needed but missing → run the **Decompile** procedure below

## Phase 1: Search the Index (Grep — no permission prompt)

When you need information about a dependency class or method:

```
Grep pattern="<ClassName>" in path=".depx/manifest/" with output_mode="content" and context=5
```

The manifest files contain class signatures in this format:

```
## fully.qualified.ClassName
public class ClassName extends Parent implements Interface {
  private String fieldName
  public ReturnType methodName(ParamType)
  public static Builder builder()
}
```

- Search by class name: `Grep pattern="## .*ClassName"`
- Search by method name: `Grep pattern="methodName"` in `.depx/manifest/`
- The `# PATH:` header in each .index file tells you which JAR contains the class

## Phase 2: Read Decompiled Source (Read — no permission prompt)

If you need the full implementation (not just signatures):

1. Find the artifactId from the manifest (the .index filename without extension)
2. Check if `.depx/source/<artifactId>/` exists using Glob
3. If it exists, read the `.java` file:
   ```
   Glob pattern=".depx/source/<artifactId>/**/<ClassName>.java"
   Read the matched file
   ```

If the source directory does not exist for this artifact, proceed to Phase 3.

## Phase 3: Decompile on Demand (Bash — one-time cost)

When decompiled source is needed but not yet cached:

1. Find the JAR path from the manifest file (`# PATH:` line)
2. Ensure CFR decompiler exists:
   ```bash
   if [ ! -f .depx/tools/cfr.jar ]; then
     mkdir -p .depx/tools
     curl -fSL -o .depx/tools/cfr.jar "https://github.com/leibnitz27/cfr/releases/download/0.152/cfr-0.152.jar"
   fi
   ```
3. Decompile the entire JAR (one-time, subsequent reads are free):
   ```bash
   ARTIFACT="<artifactId>"
   JAR_PATH="<path from manifest>"
   mkdir -p ".depx/source/$ARTIFACT"
   java -jar .depx/tools/cfr.jar "$JAR_PATH" --outputdir ".depx/source/$ARTIFACT" --silent true 2>/dev/null
   ```
4. Then read the decompiled `.java` file using the Read tool

## Index Build Procedure (one-time setup)

If `.depx/manifest/` does not exist or is empty, build the index automatically.

### Step 1: Resolve Classpath

Detect the build tool and extract dependency JAR paths.

**For Gradle projects** (build.gradle or build.gradle.kts exists):

```bash
mkdir -p .depx

# Write temporary init script
cat > /tmp/depx-init.gradle << 'INITEOF'
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
INITEOF

# Execute and save classpath
gradle --init-script /tmp/depx-init.gradle depxClasspath -q > .depx/classpath.txt 2>/dev/null
rm -f /tmp/depx-init.gradle
```

**For Maven projects** (pom.xml exists):

```bash
mkdir -p .depx
mvn -q dependency:build-classpath -Dmdep.outputFile=.depx/classpath-raw.txt -DincludeScope=compile
# Convert colon-separated to one-per-line
tr ':' '\n' < .depx/classpath-raw.txt > .depx/classpath.txt
rm -f .depx/classpath-raw.txt
```

### Step 2: Generate Manifests

For each JAR in `.depx/classpath.txt`, generate a manifest file:

```bash
mkdir -p .depx/manifest

while IFS= read -r jarpath; do
  [ -z "$jarpath" ] && continue
  [ ! -f "$jarpath" ] && continue

  # Extract artifact name from JAR filename
  artifact=$(basename "$jarpath" .jar)

  # Skip if manifest already exists and JAR hasn't changed
  indexfile=".depx/manifest/${artifact}.index"
  if [ -f "$indexfile" ]; then
    jar_mtime=$(stat -f %m "$jarpath" 2>/dev/null || stat -c %Y "$jarpath" 2>/dev/null)
    cached_mtime=$(grep "^# MTIME:" "$indexfile" 2>/dev/null | cut -d' ' -f3)
    [ "$jar_mtime" = "$cached_mtime" ] && continue
  fi

  # Get JAR modification time
  jar_mtime=$(stat -f %m "$jarpath" 2>/dev/null || stat -c %Y "$jarpath" 2>/dev/null)

  # Write manifest header
  echo "# JAR: $(basename "$jarpath")" > "$indexfile"
  echo "# PATH: $jarpath" >> "$indexfile"
  echo "# MTIME: $jar_mtime" >> "$indexfile"
  echo "" >> "$indexfile"

  # List all public classes and their signatures
  jar tf "$jarpath" 2>/dev/null | grep '\.class$' | grep -v '\$' | sed 's/\.class$//' | sed 's|/|.|g' | while read -r classname; do
    echo "" >> "$indexfile"
    echo "## $classname" >> "$indexfile"
    javap -public -classpath "$jarpath" "$classname" 2>/dev/null | tail -n +2 >> "$indexfile"
  done

done < .depx/classpath.txt
```

**Important performance notes:**
- This processes all JARs, which can take a few minutes for large projects
- Use an Agent tool with `run_in_background: true` if available
- SNAPSHOT JARs are re-indexed when their mtime changes
- Inner classes (`$` in name) are excluded from top-level listing to keep manifests clean

### Step 3: Confirm Success

After indexing, verify:
```
Glob pattern=".depx/manifest/*.index"
```

Report to the user how many JARs were indexed.

## Rebuilding the Index

If the user explicitly asks to rebuild the index, or if dependencies have changed:

1. Remove stale data: `rm -rf .depx/manifest/ .depx/classpath.txt`
2. Re-run the Index Build Procedure above
3. Optionally remove cached source: `rm -rf .depx/source/` (will be re-decompiled on demand)

## Notes

- `.depx/` should be added to `.gitignore`
- All manifest files are plain text, optimized for Grep tool searches
- Decompiled source is cached per-artifact; decompiling one class decompiles the whole JAR
- For detailed format specs and troubleshooting, see [reference.md](reference.md)
