# -*- coding: utf-8 -*-
"""批量流水线：apply → 单次构建校验 → 逐项提交。

只有「touch_files 全部到齐」的功能才会进入流水线，避免半套文件把构建弄坏。
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "orchestrator" / "scripts"))
import orchestrator as orch  # noqa: E402

PY = r"G:\Python312\python.exe"
ORCH = str(ROOT / "orchestrator" / "scripts" / "orchestrator.py")


def call(*args: str) -> int:
    return subprocess.run([PY, ORCH, *args], cwd=str(ROOT)).returncode


def missing_files(tid: str) -> list[str]:
    """期望文件里还没写进 staging 的（空清单=齐了）。"""
    t = orch.task_of(tid)
    base = orch.STAGING / tid / "files"
    miss = []
    for rel in t.get("touch_files", []):
        norm = rel.replace("\\", "/")
        if norm.startswith("java/com/tv/ball/"):
            norm = "java/" + norm[len("java/com/tv/ball/"):]
        if norm.startswith("test/java/com/tv/ball/"):
            norm = "test/java/" + norm[len("test/java/com/tv/ball/"):]
        if not (base / norm).exists():
            miss.append(rel)
    return miss


def candidates(batch: int | None, ids: list[str]) -> tuple[list[str], dict[str, list[str]]]:
    data = orch.load_tasks()
    st = orch.load_state()["tasks"]
    ready, partial = [], {}
    for t in data["tasks"]:
        tid = t["id"]
        if ids and tid not in ids:
            continue
        if batch is not None and t["batch"] != batch:
            continue
        if st.get(tid, {}).get("status") == "done":
            continue
        base = orch.STAGING / tid / "files"
        if not base.exists() or not any(base.rglob("*")):
            continue
        miss = missing_files(tid)
        if miss:
            partial[tid] = miss
        else:
            ready.append(tid)
    return ready, partial


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["run"])
    ap.add_argument("--batch", type=int)
    ap.add_argument("--ids", nargs="*", default=[])
    ap.add_argument("--dry", action="store_true")
    a = ap.parse_args()

    ready, partial = candidates(a.batch, a.ids)
    for tid, miss in partial.items():
        print(f"[pipeline] 未齐 {tid}: 缺 {', '.join(miss)}")
    if not ready:
        print("[pipeline] 没有已齐套的待处理项")
        return 0
    print(f"[pipeline] 待处理 {len(ready)}: {' '.join(ready)}")
    if a.dry:
        return 0

    applied = [tid for tid in ready if call("apply", tid) == 0]
    if not applied:
        print("[pipeline] 全部 apply 失败")
        return 1

    print("[pipeline] 单次构建校验 ...")
    if call("verify") != 0:
        print("[pipeline] 构建失败，先修再提交")
        return 1

    ok, bad = [], []
    for tid in applied:
        (ok if call("commit", tid) == 0 else bad).append(tid)
    print(f"[pipeline] 提交 {len(ok)} / 失败 {len(bad)}")
    if bad:
        print("  失败: " + " ".join(bad))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
