import pathlib
p = pathlib.Path(r"G:\trae\tv\orchestrator\staging\F025\files\java\feature\f025\F025Activity.kt")
s = p.read_text(encoding="utf-8")
s = s.replace("（MIME `video/*`）", "（只筛视频类型）")
s = s.replace('`video/*`', '视频类型')
p.write_text(s, encoding="utf-8")
import re
print("open:", len(re.findall(r"/\*", s)), "close:", len(re.findall(r"\*/", s)))
