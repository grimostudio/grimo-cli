# ContentView Line Invariant — Input Area Stability Fix

> **Date:** 2026-04-04
> **Status:** Approved
> **Scope:** `ContentView.java` (4 methods), no other files

## Problem

The input cursor must always stay on the `❯` prompt line — regardless of what the agent returns (multi-line text, error, null, anything). Users type on that line; if the cursor is displaced to a separator or another row, the TUI is broken.

Glossary: Input Area — "**永遠不移動**" (never moves).

Currently, when agent response text contains embedded `\n`, the cursor is displaced from the `❯` line onto the separator line below it.

### Root Cause

`ContentView.appendAiReply(text)` adds the entire text (including `\n`) as a single `AttributedString`:

```
lines.add("⏺ 1,473\nHi.")  ← 1 AttributedString, contains \n
```

- `render()` uses `columnLength()` to measure width — `\n` has zero column width — counts as 1 line
- JLine `Display.update()` sends string to terminal — terminal interprets `\n` as line break — renders 2 visual lines
- Content area occupies 1 extra visual row → `Screen.render()` cursor formula `contentHeight + 1` no longer points to the `❯` line — it points to the separator below
- JLine diff algorithm thinks only 1 line changed → old "thinking..." text persists as rendering artifact

### Affected Methods

| Method | Has `\n` handling | Status |
|--------|:-:|--------|
| `setBannerText()` | Yes | Correct — already splits on `\n` (without `-1` limit, banner text never has meaningful trailing blanks) |
| `appendCommandOutput()` | Yes | Correct — already splits on `\n` (without `-1` limit, trailing blanks in command output are unimportant) |
| `appendAiReply()` | No | **Broken** |
| `appendError()` | No | **Broken** |
| `appendLine()` | No | **Broken** |
| `appendUserInput()` | N/A | Safe — InputView restricts to single-line input. Method is `public` but all callers pass TUI input text which cannot contain `\n`. |

### Call Sites (Representative, Not Exhaustive)

The fix is in `ContentView` itself, so **all callers benefit automatically**. This table lists the primary paths where agent output enters ContentView — not every call site of every method.

| Caller | Methods Called | Path |
|--------|-------------|------|
| `TuiAdapter` callback | `appendAiReply()`, `appendError()`, `appendLine()` | InputPort → ChatDispatcher.dispatch(text, callback) → callback |
| `ChatDispatcher.dispatch(userInput)` | `appendAiReply()`, `appendError()`, `appendLine()`, `removeLastLine()` | TUI-bound direct path (currently unused but code exists) |
| `TuiEventBridge` | `appendAiReply()` | DevModeCompletedEvent handler |
| `TuiKeyHandler` | `appendError()`, `appendLine()` | Agent cancellation, status messages |

### Reproduction

1. Send "hi" to Codex agent (SDK bug returns `"1,473\nHi."`)
2. Input area shifts down by 1 row — cursor on separator line
3. "thinking..." text residue visible

Log evidence:
```
[AGENT-RAW] agent=codex, raw='1,473\nHi.'
[CALLBACK-SUCCESS] isChat=true, resultLen=9, resultPreview='1,473\nHi.'
```

## Design

### Invariant

> Every `AttributedString` in `ContentView.lines` MUST NOT contain `\n`. Each entry corresponds to exactly one terminal visual line.

This invariant guarantees that `Screen.render()` layout calculation matches terminal reality. When it holds, `contentHeight` from `Layout.vertical()` is accurate, cursor formula `contentHeight + 1` always points to the `❯` prompt line, and the input cursor is never displaced.

### Changes

All changes are within `ContentView.java`. No other files are modified.

#### 1. `appendAiReply(String text)` — split multi-line text

```java
public synchronized void appendAiReply(String text) {
    String[] parts = text.split("\n", -1);  // -1 preserves trailing empty strings
    for (int i = 0; i < parts.length; i++) {
        var sb = new AttributedStringBuilder();
        if (i == 0) sb.styled(BRAND_STYLE, "⏺ ");
        else sb.append("  ");  // continuation indent, aligns with appendCommandOutput
        sb.append(parts[i]);
        var line = sb.toAttributedString();
        lines.add(line);
        incrementalCacheUpdate(line);
    }
    lines.add(AttributedString.EMPTY);
    incrementalCacheUpdate(AttributedString.EMPTY);
    scrollToBottomIfAutoFollow();
}
```

Rendering result for `"Hello!\nHow can I help?"`:
```
⏺ Hello!
  How can I help?
```

#### 2. `appendError(String text)` — split multi-line error

```java
public synchronized void appendError(String text) {
    String[] parts = text.split("\n", -1);
    for (int i = 0; i < parts.length; i++) {
        var sb = new AttributedStringBuilder();
        sb.styled(errorStyle, (i == 0 ? "⚠ " : "  ") + parts[i]);
        var line = sb.toAttributedString();
        lines.add(line);
        incrementalCacheUpdate(line);
    }
    lines.add(AttributedString.EMPTY);
    incrementalCacheUpdate(AttributedString.EMPTY);
    scrollToBottomIfAutoFollow();
}
```

#### 3. `appendLine(AttributedString line)` — defensive check

```java
public synchronized void appendLine(AttributedString line) {
    String raw = line.toString();
    if (raw.contains("\n")) {
        // Defensive fallback: split on \n. Note: this discards AttributedStyle
        // from the original line. Acceptable trade-off because current callers
        // (e.g. "⏳ thinking..." indicator) never pass \n in AttributedString.
        // If styled multi-line AttributedString support is needed in the future,
        // use columnSubSequence() to split while preserving style.
        for (String part : raw.split("\n", -1)) {
            var as = new AttributedString(part);
            lines.add(as);
            incrementalCacheUpdate(as);
        }
    } else {
        lines.add(line);
        incrementalCacheUpdate(line);
    }
    scrollToBottomIfAutoFollow();
}
```

Note: current callers (TuiAdapter "thinking...", TuiEventBridge, TuiKeyHandler) never pass `\n` in `AttributedString`. The plain-string fallback loses style information — acceptable as a safety net. If styled multi-line support is needed later, use `columnSubSequence()` to split while preserving style.

#### 4. `removeLastLine()` — sync wrappedCache

```java
public synchronized void removeLastLine() {
    if (!lines.isEmpty()) {
        lines.removeLast();
        // Sync wrappedCache: remove all BufferLine entries belonging to the last logical line.
        // A wide line may have been wrapped into multiple entries (wrapped=true for continuations).
        // Remove backwards: first all wrapped continuation lines, then the head line.
        while (!wrappedCache.isEmpty() && wrappedCache.getLast().wrapped()) {
            wrappedCache.removeLast();
        }
        if (!wrappedCache.isEmpty()) {
            wrappedCache.removeLast();  // remove the head (non-wrapped) entry
        }
    }
}
```

Current code only removes from `lines` but not `wrappedCache`, causing stale buffer state for text selection coordinate mapping. A single logical line may occupy multiple `wrappedCache` entries if it was wider than terminal width (continuation entries have `wrapped=true`). The fix removes backwards until all entries belonging to that logical line are gone.

### What Does NOT Change

- `Screen.render()` — layout calculation and cursor formula (`contentHeight + 1`) are correct when invariant holds
- `InputView` — rendering logic is correct
- `appendCommandOutput()` — already has correct `\n` split pattern
- `appendUserInput()` — InputView restricts to single-line, no `\n` possible

### Verification

| Scenario | Expected |
|----------|----------|
| Agent returns multi-line text (contains `\n`) | Each line rendered separately, Input area stays fixed |
| Agent returns null or blank | `appendAiReply` not called, Input area stays fixed |
| Agent returns single-line text | Identical to current behavior |
| "thinking..." removed, then multi-line reply added | No text residue, Input area stays fixed |
| Error message contains `\n` (e.g. stack trace) | Each line rendered separately, Input area stays fixed |
| Wide line that wraps, then `removeLastLine()` | All wrapped entries removed from `wrappedCache`, no stale entries |

### Unit Test

Add a test that asserts the invariant:

```java
@Test
void appendAiReply_splitsNewlines() {
    var cv = new ContentView();
    cv.appendAiReply("line1\nline2\nline3");
    // 3 content lines + 1 trailing empty = 4 lines total
    var rendered = cv.render(80, 10);
    // No rendered line should contain \n
    for (var line : rendered) {
        assertFalse(line.toString().contains("\n"));
    }
}
```
