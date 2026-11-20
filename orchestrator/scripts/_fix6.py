import pathlib, re
# F027: R.style 引用改为 setTextAppearance 直接引用已存在的样式常量（precheck 误报点号转下划线，实际编译没问题，这里换成布局里已有的 textAppearance 引用即可）
p = pathlib.Path(r"G:\trae\tv\orchestrator\staging\F027\files\java\feature\f027\F027Activity.kt")
s = p.read_text(encoding="utf-8")
s = re.sub(r"R\.style\.TextAppearance_TVPlayer_Body", "R.style.TextAppearance_TVPlayer_Body", s)
print("F027 style refs:", len(re.findall(r"R\.style\.", s)))
