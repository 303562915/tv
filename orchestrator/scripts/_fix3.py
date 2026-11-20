# -*- coding: utf-8 -*-
import pathlib, re
for path in [r"G:\trae\tv\orchestrator\staging\F023\files\java\feature\f023\F023Engine.kt",
             r"G:\trae\tv\orchestrator\staging\F023\files\java\feature\f023\F023Activity.kt"]:
    p = pathlib.Path(path)
    s = p.read_text(encoding="utf-8")
    s = s.replace("private fun matchExt(lastSegment: String?): String?",
                  "private fun matchExtIn(lastSegment: String?): String?")
    s = s.replace("return matchExt(segments.lastOrNull())", "return matchExtIn(segments.lastOrNull())")
    p.write_text(s, encoding="utf-8")
    print("patched", p.name)
