import pathlib
p = pathlib.Path(r"G:\trae\tv\orchestrator\staging\F023\files\java\feature\f023\F023Engine.kt")
s = p.read_text(encoding="utf-8")
s = s.replace("val extHit = matchExt(segments.lastOrNull())", "val extHit = matchExtIn(segments.lastOrNull())")
p.write_text(s, encoding="utf-8")
print("ok")
