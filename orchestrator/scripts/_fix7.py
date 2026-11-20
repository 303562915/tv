import pathlib, re
p = pathlib.Path(r"G:\trae\tv\orchestrator\staging\F025\files\java\feature\f025\F025Activity.kt")
s = p.read_text(encoding="utf-8")
# 把在 KDoc / 行注释内部出现的 `video/*` 字面量改写成不含注释结束符的描述
s = s.replace('arrayOf("video/*")', 'arrayOf("video/")')
p.write_text(s, encoding="utf-8")
print("open:", len(re.findall(r"/\*", s)), "close:", len(re.findall(r"\*/", s)))
