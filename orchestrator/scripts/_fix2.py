# -*- coding: utf-8 -*-
import pathlib
base = pathlib.Path(r"G:\trae\tv\orchestrator\staging")

# --- F021 Engine: stats() 里 entries 是函数，且 items 未定义 ---
p = base / "F021/files/java/feature/f021/F021Engine.kt"
s = p.read_text(encoding="utf-8")
s = s.replace("""        Stats(
            entryCount = entries.size,
            totalBytes = items.sumOf { it.length() },
            hitCount = hitCount,
            missCount = missCount
        )""", """        val items = listEntries()
        Stats(
            entryCount = items.size,
            totalBytes = items.sumOf { it.length() },
            hitCount = hitCount,
            missCount = missCount
        )""")
p.write_text(s, encoding="utf-8")

# --- F021 Activity: EvictResult 字段名 ---
a = base / "F021/files/java/feature/f021/F021Activity.kt"
t = a.read_text(encoding="utf-8")
t = t.replace("result.evictedCount, fmt(result.freedBytes), \"\"",
              "result.removedCount, fmt(result.removedBytes), fmt(result.remainingBytes)")
t = t.replace("result.evictedCount, fmt(result.freedBytes)",
              "result.removedCount, fmt(result.removedBytes)")
a.write_text(t, encoding="utf-8")

# --- F023 Activity: nullable receiver ---
b = base / "F023/files/java/feature/f023/F023Activity.kt"
u = b.read_text(encoding="utf-8")
u = u.replace("RuleSet.normalizeDir(it).equalsIgnoreCase(normalized)",
              "RuleSet.normalizeDir(it)?.equals(normalized, ignoreCase = true) == true")
b.write_text(u, encoding="utf-8")
print("patched")
