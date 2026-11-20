# -*- coding: utf-8 -*-
"""多语言资源审计：比对 values/ 与 values-<lang>/ 里的字符串键差异。

课程：
  python orchestrator/scripts/i18n_audit.py            # 默认审计 values-en
  python orchestrator/scripts/i18n_audit.py --lang en  # 指定目标语言
  python orchestrator/scripts/i18n_audit.py --strict   # 有缺失时退出码 1
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "tv_project" / "app" / "src" / "main" / "res"
KEY_RE = re.compile(r'<string name="([^"]+)"')


def keys_in(folder: Path) -> dict[str, str]:
    """{键: 文件}，同名键以第一次出现为准（重复键会在资源合并阶段报错）。"""
    out: dict[str, str] = {}
    if not folder.exists():
        return out
    for f in sorted(folder.glob("*.xml")):
        text = f.read_text(encoding="utf-8", errors="replace")
        for k in KEY_RE.findall(text):
            out.setdefault(k, f.name)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", default="en")
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()

    base = keys_in(RES / "values")
    target = keys_in(RES / f"values-{args.lang}")

    missing = sorted(set(base) - set(target))
    extra = sorted(set(target) - set(base))

    print(f"基准 values/：{len(base)} 键")
    print(f"目标 values-{args.lang}/：{len(target)} 键")
    print(f"缺失 {len(missing)}，多余 {len(extra)}")

    if missing:
        print("\n缺失（需要补翻译）：")
        for k in missing[:80]:
            print(f"  - {k}   (定义于 {base[k]})")
    if extra:
        print("\n多余（基准里没有，可能是拼写错或已删）：")
        for k in extra[:80]:
            print(f"  - {k}   (位于 {target[k]})")

    # 补全模板：直接可粘进 values-en/strings.xml
    if missing:
        print("\n可粘贴模板：")
        print('<?xml version="1.0" encoding="utf-8"?>')
        print("<resources>")
        for k in missing:
            print(f'    <string name="{k}">TODO</string>')
        print("</resources>")

    bad = bool(missing or extra)
    return 1 if (bad and args.strict) else 0


if __name__ == "__main__":
    sys.exit(main())
