import pathlib, re
base = pathlib.Path(r"G:\trae\tv\orchestrator\staging")
# F030: 去掉 SeekBar 上不存在的 android:keyProgressIncrement
p = base / "F030/files/res/layout/activity_f030.xml"
lines = p.read_text(encoding="utf-8").splitlines(keepends=True)
p.write_text("".join(l for l in lines if "keyProgressIncrement" not in l), encoding="utf-8")

# F036: 布局引用了 strings 里没有的两个键，补上
q = base / "F036/files/res/values/strings_f036.xml"
s = q.read_text(encoding="utf-8")
missing = []
for name in ("f036_seed_placeholder", "f036_deterministic_pending"):
    if f'name="{name}"' not in s:
        missing.append(name)
add = {
    "f036_seed_placeholder": "输入固定种子，例如 20261120",
    "f036_deterministic_pending": "点「生成序列」后会显示按当前权重抽出的结果",
}
if missing:
    extra = "".join(f'    <string name="{m}">{add[m]}</string>\n' for m in missing)
    s = s.replace("</resources>", extra + "</resources>")
    q.write_text(s, encoding="utf-8")
print("F030/F036 patched, missing:", missing)
