# -*- coding: utf-8 -*-
"""静态预检：编译前抓 binding / 资源引用不匹配，省一轮 2 分钟的构建。

资源解析范围 = 主仓库 res + 该任务 staging 里的 res（因为新资源还没 apply）。

python precheck.py F008 F009 ...
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STAGING_ROOT = ROOT / "orchestrator" / "staging"
RES = ROOT / "tv_project" / "app" / "src" / "main" / "res"

ID_RE = re.compile(r'android:id="@\+id/([A-Za-z0-9_]+)"')
STR_RE = re.compile(r'<string name="([A-Za-z0-9_]+)"')
COLOR_RE = re.compile(r'<color name="([A-Za-z0-9_]+)"')
DIMEN_RE = re.compile(r'<dimen name="([A-Za-z0-9_]+)"')
STYLE_RE = re.compile(r'<style name="([A-Za-z0-9_.]+)"')


def snake_to_camel(name: str) -> str:
    parts = name.split("_")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def collect(kind: str, roots: list[Path]) -> set[str]:
    names: set[str] = set()
    for root in roots:
        if not root.exists():
            continue
        if kind == "string":
            for p in root.glob("values*/*.xml"):
                names |= set(STR_RE.findall(p.read_text(encoding="utf-8", errors="replace")))
        elif kind == "color":
            for p in root.glob("values*/*.xml"):
                names |= set(COLOR_RE.findall(p.read_text(encoding="utf-8", errors="replace")))
            if (root / "color").exists():
                names |= {p.stem for p in (root / "color").rglob("*") if p.is_file()}
        elif kind == "dimen":
            for p in root.glob("values*/*.xml"):
                names |= set(DIMEN_RE.findall(p.read_text(encoding="utf-8", errors="replace")))
        elif kind == "style":
            for p in root.glob("values*/*.xml"):
                names |= set(STYLE_RE.findall(p.read_text(encoding="utf-8", errors="replace")))
        else:  # drawable / layout / menu / xml / anim / font
            base = root / kind
            if base.exists():
                names |= {p.stem for p in base.rglob("*") if p.is_file()}
            for d in root.glob(f"{kind}-*"):
                names |= {p.stem for p in d.rglob("*") if p.is_file()}
    return names


def check(tid: str) -> list[str]:
    base = STAGING_ROOT / tid / "files"
    if not base.exists():
        return []
    res_roots = [RES, base / "res"]
    kt = list(base.rglob("*.kt"))
    layouts = list(base.rglob("res/layout/*.xml"))
    staged_ids: set[str] = set()
    for L in layouts:
        staged_ids |= set(ID_RE.findall(L.read_text(encoding="utf-8", errors="replace")))
    staged_fields = {snake_to_camel(i) for i in staged_ids}

    errs: list[str] = []
    cache: dict[str, set[str]] = {}

    def res(kind: str) -> set[str]:
        if kind not in cache:
            cache[kind] = collect(kind, res_roots)
        return cache[kind]

    for f in kt:
        text = f.read_text(encoding="utf-8", errors="replace")
        if layouts:
            for field in set(re.findall(r"\bbinding\.([A-Za-z0-9_]+)", text)):
                if field == "root":
                    continue
                if field not in staged_fields:
                    errs.append(f"{f.name}: binding.{field} 在布局里没有对应 id")
        for kind, name in sorted(set(re.findall(
                r"\bR\.(string|color|dimen|drawable|layout|menu|xml|anim|style)\.([A-Za-z0-9_]+)", text))):
            if name not in res(kind):
                errs.append(f"{f.name}: R.{kind}.{name} 不存在")

    for L in layouts:
        text = L.read_text(encoding="utf-8", errors="replace")
        for kind, name in sorted(set(re.findall(
                r'@(string|color|dimen|drawable|style|layout)/([A-Za-z0-9_.]+)', text))):
            if name in ("transparent", "black", "white"):
                continue
            if name not in res(kind):
                errs.append(f"{L.name}: @{kind}/{name} 不存在")
    return errs


def main() -> int:
    tids = sys.argv[1:]
    if not tids:
        tids = [p.name for p in STAGING_ROOT.iterdir()
                if p.is_dir() and p.name.startswith("F") and (p / "files").exists()]
    total = 0
    for tid in tids:
        errs = check(tid)
        print(f"[precheck] {tid}: {'OK' if not errs else str(len(errs)) + ' 处问题'}")
        for e in errs[:30]:
            print("   -", e)
        total += len(errs)
    return 0 if total == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
