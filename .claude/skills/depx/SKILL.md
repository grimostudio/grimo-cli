---
name: depx
description: >-
  ALWAYS use instead of find/javap/jar tf/jar xf/unzip/cd on ~/.gradle or ~/.m2 caches.
  TRIGGER when: inspecting third-party JVM class/method/API, searching dependency JARs,
  extracting/decompiling/unzipping JAR or source JAR contents, reading library internals,
  or class not in project src. DO NOT TRIGGER for project source code in working directory.
allowed-tools: Bash, Read, Write, Glob, Grep
metadata:
  author: samzhu
  version: 0.2.0
  category: development
  tags: [java, jvm, dependency, decompile, gradle, maven]
---

# depx — Dependency Explorer

You are an AI agent that needs to explore JVM dependency source code. Follow these rules strictly.

## Priority Rules (NEVER violate)

1. **NEVER** run `find ~/.gradle/caches`, `find ~/.m2`, `jar tf`, `jar xf`, `javap`, `unzip`, or any Bash command to scan, extract, or inspect local repository caches directly — these trigger repeated permission prompts and are slow. **NEVER** `cd` into `~/.gradle/caches` or `~/.m2` directories. **NEVER** extract JARs or source JARs (`*-sources.jar`) to `/tmp` or any temporary directory. **NEVER** `unzip` source JARs to read library code. This applies to **ALL phases** including index building — use the build tool (Gradle/Maven) to resolve classpath, not `find`.
2. **ALWAYS** check `.depx/manifest/` first using the Grep tool (no Bash needed, no permission prompt)
3. **ALWAYS** check `.depx/source/` for decompiled code using the Read tool (no Bash needed)
4. If `.depx/manifest/` does not exist, is empty, or **does not contain the class/library you need** → run the **Index Build** procedure below (which uses Gradle/Maven, NOT `find`). An outdated or incomplete index is NOT an excuse to fall back to `javap`/`find`/`jar` commands.
5. If decompiled source is needed but missing → run the **Decompile** procedure below
6. **There is NO fallback.** If the index doesn't have what you need, rebuild it. NEVER bypass this skill's procedures by running cache commands directly, even if the index is stale or incomplete.

> **Why this matters:** Without this skill, every dependency lookup requires Bash commands with `$()` substitution that trigger "Do you want to proceed?" prompts. A single investigation can require 5–10 manual approvals. This skill eliminates that by pre-indexing everything into Grep-searchable plain text files.

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

**If the class is NOT found in the index:** The index may be stale or incomplete. **Do NOT fall back to `javap`/`find`/`jar`/`unzip`.** Instead, rebuild the index by running the **Index Build Procedure** below. After rebuilding, retry the Grep search.

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

> **PREREQUISITE:** You MUST have a manifest entry for the artifact before decompiling. If you don't have one, go back and run the **Index Build Procedure** first. **NEVER** hardcode or guess JAR paths from `~/.gradle/caches` — the JAR path MUST come from a manifest `# PATH:` line.

When decompiled source is needed but not yet cached:

1. Find the JAR path by reading the `# PATH:` line from the corresponding `.depx/manifest/<artifact>.index` file. If no manifest exists for this artifact → **stop and run Index Build Procedure first**.
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
   JAR_PATH="<path from manifest>"  # MUST come from .depx/manifest/*.index, NEVER hardcoded
   mkdir -p ".depx/source/$ARTIFACT"
   java -jar .depx/tools/cfr.jar "$JAR_PATH" --outputdir ".depx/source/$ARTIFACT" --silent true 2>/dev/null
   ```
4. Then read the decompiled `.java` file using the Read tool

## Index Build Procedure (one-time setup)

If `.depx/manifest/` does not exist or is empty, build the index automatically.

> **CRITICAL:** You MUST follow Steps 1→2→3 in order. Do NOT skip Step 1 and manually `find` individual JARs. Do NOT construct manifest files by hand-crafting `find ~/.gradle/caches` commands for each dependency. The correct approach is to resolve the **full classpath at once** using the build tool (Gradle or Maven), then generate manifests from that list.

### Step 1: Resolve Classpath (MANDATORY — do NOT skip)

Use the project's build tool to resolve all dependency JAR paths at once. **NEVER** use `find` to locate JARs individually.

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
