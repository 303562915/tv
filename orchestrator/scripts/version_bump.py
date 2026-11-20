# -*- coding: utf-8 -*-
"""版本号与变更日志生成。

用法：
  python orchestrator/scripts/version_bump.py --dry-run             # 只看会怎么改
  python orchestrator/scripts/version_bump.py --version 1.2.0       # 改 build.gradle.kts 的 versionName
  python orchestrator/scripts/version_bump.py --version 1.2.0 --apply --changelog
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "tv_project" / "app" / "build.gradle.kts"
CHANGELOG = ROOT / "CHANGELOG.md"

VER_CODE_RE = re.compile(r"versionCode\s*=\s*(\d+)")
VER_NAME_RE = re.compile(r'versionName\s*=\s*"([^"]+)"')


def current() -> tuple[int, str]:
    text = GRADLE.read_text(encoding="utf-8")
    code = int(VER_CODE_RE.search(text).group(1))
    name = VER_NAME_RE.search(text).group(1)
    return code, name


def set_version(version: str, bump_code: int = 1, apply: bool = False) -> str:
    text = GRADLE.read_text(encoding="utf-8")
    code, _ = current()
    new_code = code + bump_code
    text = VER_CODE_RE.sub(f"versionCode = {new_code}", text, count=1)
    text = VER_NAME_RE.sub(f'versionName = "{version}"', text, count=1)
    if apply:
        GRADLE.write_text(text, encoding="utf-8")
    return f"versionCode {code} -> {new_code}, versionName -> {version}"


def git_log(since_tag: str | None) -> list[str]:
    cmd = ["git", "log", "--pretty=%s"]
    if since_tag:
        cmd.append(f"{since_tag}..HEAD")
    else:
        cmd += ["-n", "40"]
    try:
        out = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True, encoding="utf-8")
    except OSError:
        return []
    if out.returncode != 0:
        return []
    return [ln.strip() for ln in (out.stdout or "").splitlines() if ln.strip()]


def append_changelog(version: str, apply: bool = False) -> str:
    entries = git_log(None)
    keep = [e for e in entries if not e.lower().startswith(("chore: 自动提交", "chore(orchestrator)"))]
    block = [f"## {version} — {date.today().isoformat()}", ""]
    block += [f"- {e}" for e in keep[:40]] or ["- 无用户可见变更"]
    block.append("")
    if apply:
        old = CHANGELOG.read_text(encoding="utf-8") if CHANGELOG.exists() else "# 变更日志\n\n"
        # 标题行之后插入，保持最新在上
        if old.startswith("#"):
            head, _, rest = old.partition("\n\n")
            CHANGELOG.write_text(head + "\n\n" + "\n".join(block) + rest, encoding="utf-8")
        else:
            CHANGELOG.write_text("\n".join(block) + old, encoding="utf-8")
    return "\n".join(block)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--changelog", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    code, name = current()
    print(f"当前：versionCode={code} versionName={name}")
    if not a.version:
        print("未指定 --version，只报告当前版本")
        return 0
    if not re.fullmatch(r"\d+\.\d+\.\d+", a.version):
        print("版本号需形如 1.2.3")
        return 2

    apply = a.apply and not a.dry_run
    print(("[apply] " if apply else "[dry-run] ") + set_version(a.version, 1, apply))
    if a.changelog:
        text = append_changelog(a.version, apply)
        print(("" if apply else "[dry-run] ") + "变更日志片段：")
        print(text)
    if not apply:
        print("\n加 --apply 才会真正写文件")
    return 0


if __name__ == "__main__":
    sys.exit(main())
