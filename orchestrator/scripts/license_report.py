# -*- coding: utf-8 -*-
"""从 Gradle 依赖生成第三方许可证清单（Markdown）。

用法：
  python orchestrator/scripts/license_report.py            # 写到 docs/LICENSES.md
  python orchestrator/scripts/license_report.py --stdout    # 只打印
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "tv_project" / "app" / "build.gradle.kts"
LIBS = ROOT / "tv_project" / "gradle" / "libs.versions.toml"
OUT = ROOT / "docs" / "LICENSES.md"

# 已知依赖的许可证；新依赖默认标 UNKNOWN，逼着来登记一次
KNOWN = {
    "androidx.media3": "Apache-2.0",
    "exoplayer": "Apache-2.0",
    "okhttp": "Apache-2.0",
    "okio": "Apache-2.0",
    "glide": "Apache-2.0",
    "room": "Apache-2.0",
    "gson": "Apache-2.0",
    "kotlinx-coroutines": "Apache-2.0",
    "material": "Apache-2.0",
    "appcompat": "Apache-2.0",
    "constraintlayout": "Apache-2.0",
    "core-ktx": "Apache-2.0",
    "fragment-ktx": "Apache-2.0",
    "activity-ktx": "Apache-2.0",
    "lifecycle": "Apache-2.0",
    "swiperefreshlayout": "Apache-2.0",
    "viewpager2": "Apache-2.0",
    "nanohttpd": "BSD-3-Clause",
    "junit": "EPL-1.0",
    "espresso": "Apache-2.0",
}


def license_of(coordinate: str) -> str:
    low = coordinate.lower()
    for needle, lic in KNOWN.items():
        if needle.lower() in low:
            return lic
    return "UNKNOWN"


def collect() -> list[tuple[str, str]]:
    """从 build.gradle.kts 的 implementation/api 行里抽坐标。"""
    rows: list[tuple[str, str]] = []
    seen: set[str] = set()
    if not GRADLE.exists():
        return rows
    text = GRADLE.read_text(encoding="utf-8", errors="replace")
    for line in text.splitlines():
        s = line.strip()
        m = re.match(r'(implementation|api|ksp|testImplementation|androidTestImplementation)\s*\(\s*"([^"]+)"\s*\)', s)
        if m:
            coord = m.group(2)
            if coord not in seen:
                seen.add(coord)
                rows.append((coord, license_of(coord)))
            continue
        m = re.match(r'(implementation|api|ksp|testImplementation|androidTestImplementation)\s*\(\s*libs\.([\w.]+)', s)
        if m:
            ref = m.group(2)
            coord = f"libs.{ref}"
            if coord not in seen:
                seen.add(coord)
                rows.append((coord, license_of(ref)))
    # 版本目录里的别名 → 坐标（便于人读）
    alias_versions: dict[str, str] = {}
    if LIBS.exists():
        for line in LIBS.read_text(encoding="utf-8", errors="replace").splitlines():
            m = re.match(r'\s*([\w-]+)\s*=\s*\{\s*module\s*=\s*"([^"]+)"\s*,\s*version\.ref\s*=\s*"([^"]+)"', line)
            if m:
                alias_versions.setdefault(m.group(2), alias_versions.get(m.group(2), ""))
    resolved: list[tuple[str, str]] = []
    for coord, lic in rows:
        if coord.startswith("libs."):
            ref = coord[5:]
            # 把 a.b.c 映射成 version catalog 的 a-b-c
            alias = ref.replace(".", "-")
            resolved.append((f"libs.{ref} (libs.versions.toml: {alias})", lic))
        else:
            resolved.append((coord, lic))
    return resolved


def render(rows: list[tuple[str, str]]) -> str:
    lines = [
        "# 第三方依赖与许可证",
        "",
        "由 `orchestrator/scripts/license_report.py` 从 `tv_project/app/build.gradle.kts` 生成，",
        "新增依赖后重跑本脚本即可。`UNKNOWN` 表示还没登记许可证，需要人工补。",
        "",
        "| 依赖 | 许可证 |",
        "| --- | --- |",
    ]
    for coord, lic in rows:
        lines.append(f"| `{coord}` | {lic} |")
    unknown = [c for c, l in rows if l == "UNKNOWN"]
    lines += ["", f"共 {len(rows)} 项，其中 {len(unknown)} 项待登记。", ""]
    if unknown:
        lines += ["待登记：", ""] + [f"- `{c}`" for c in unknown] + [""]
    lines += [
        "## 分发要求",
        "",
        "以上依赖以 Apache-2.0 / BSD-3-Clause 为主，与本项目的 MIT 协议兼容。",
        "分发 APK 时需保留各依赖的版权声明与许可证全文；`docs/LICENSES.md` 随仓库公开即可作为清单。",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stdout", action="store_true")
    a = ap.parse_args()
    rows = collect()
    text = render(rows)
    if a.stdout:
        print(text)
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"写入 {OUT}，共 {len(rows)} 项")
    return 0


if __name__ == "__main__":
    sys.exit(main())
