#!/usr/bin/env python3
"""TV Player 功能编排器（主智能体专用控制面）。

子智能体只往 staging/<id>/ 写「完整文件」，由本工具做唯一写入口：
apply -> verify(构建) -> commit，保证主仓库任一时刻都可编译、每项功能一个提交。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "tv_project"
ORG = ROOT / "orchestrator"
TASKS = ORG / "tasks" / "tasks.json"
STAGING = ORG / "staging"
STATE = ORG / "state.json"
REPORTS = ORG / "reports"
LOG = ORG / "build.log"
PKG = APP / "app/src/main/java/com/tv/ball"
RES = APP / "app/src/main/res"
APK = APP / "app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"

GRADLE_ENV = {**os.environ, "ANDROID_HOME": "G:\\SDK", "ANDROID_SDK_ROOT": "G:\\SDK"}


# ------------------------------------------------------------------ 基础
def run(cmd: list[str], cwd: Path, log: Path | None = None, timeout: int = 1800):
    proc = subprocess.run(
        cmd, cwd=str(cwd), env=GRADLE_ENV, capture_output=True,
        text=True, encoding="utf-8", errors="replace", timeout=timeout,
    )
    out = (proc.stdout or "") + (proc.stderr or "")
    if log is not None:
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text(out, encoding="utf-8")
    return proc.returncode, out


def load_tasks() -> dict:
    return json.loads(TASKS.read_text(encoding="utf-8"))


def load_state() -> dict:
    if STATE.exists():
        return json.loads(STATE.read_text(encoding="utf-8"))
    return {"tasks": {}}


def save_state(state: dict) -> None:
    state["updated_at"] = now()
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


def now() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def task_of(tid: str) -> dict:
    data = load_tasks()
    for t in data["tasks"]:
        if t["id"] == tid:
            return t
    raise SystemExit(f"[orchestrator] 未知任务 {tid}")


def status(tid: str) -> str:
    return load_state()["tasks"].get(tid, {}).get("status", "pending")


# ------------------------------------------------------------------ 命令
def cmd_list(args) -> int:
    data = load_tasks()
    st = load_state()["tasks"]
    rows = []
    for t in data["tasks"]:
        s = st.get(t["id"], {}).get("status", "pending")
        if args.status and s != args.status:
            continue
        rows.append((t["id"], t["batch"], s, t["title"]))
    for r in rows:
        print(f"{r[0]:<6} b{r[1]:<2} {r[2]:<10} {r[3]}")
    done = sum(1 for t in data["tasks"] if st.get(t["id"], {}).get("status") == "done")
    print(f"\n完成 {done}/{len(data['tasks'])}")
    return 0


def cmd_show(args) -> int:
    t = task_of(args.id)
    print(json.dumps(t, ensure_ascii=False, indent=2))
    s = load_state()["tasks"].get(args.id)
    if s:
        print("\n--- state ---")
        print(json.dumps(s, ensure_ascii=False, indent=2))
    return 0


def resolve_target(rel: str) -> Path:
    """把任务里的短路径（java/… / res/… / test/…）解析成仓库绝对路径。

    子智能体常写成 java/com/tv/ball/feature/f001/X.kt，这里把多写的包路径归一化掉，
    避免出现 com/tv/ball/com/tv/ball 的嵌套事故。
    """
    rel = rel.replace("\\", "/").lstrip("/")
    if rel.startswith("java/com/tv/ball/"):
        rel = "java/" + rel[len("java/com/tv/ball/"):]
    if rel.startswith("test/java/com/tv/ball/"):
        rel = "test/java/" + rel[len("test/java/com/tv/ball/"):]
    if rel.startswith("java/"):
        return PKG / rel[5:]
    if rel.startswith("res/"):
        return RES / rel[4:]
    if rel.startswith("test/"):
        return APP / "app/src/test/java" / rel[5:]
    if rel.startswith("app/"):
        return APP / rel[4:]
    return ROOT / rel


CATALOG = PKG / "feature" / "FeatureCatalog.kt"
MANIFEST = APP / "app/src/main/AndroidManifest.xml"
CATALOG_MARK = "        // WIRE:CATALOG"


def extra_files(tid: str) -> list[str]:
    """子智能体在 notes/extra_files.txt 里登记的额外文件（相对 staging 的短路径）。"""
    p = STAGING / tid / "notes" / "extra_files.txt"
    if not p.exists():
        return []
    raw = p.read_text(encoding="utf-8", errors="replace")
    return [ln.strip() for ln in raw.splitlines() if ln.strip() and not ln.strip().startswith("#")]


def _read_raw(p: Path) -> str:
    """按原样读，保留 CRLF，避免整份 manifest 被改成 LF。"""
    with p.open("r", encoding="utf-8", newline="") as fh:
        return fh.read()


def _write_raw(p: Path, text: str) -> None:
    with p.open("w", encoding="utf-8", newline="") as fh:
        fh.write(text)


def wire_one(tid: str) -> list[str]:
    """把一个 ui 类功能的注册行与 manifest 声明补进去（幂等，保留既有换行风格）。"""
    t = task_of(tid)
    if t["kind"] != "ui":
        return []
    low = tid.lower()
    touched = []
    cat = _read_raw(CATALOG)
    entry = f"        com.tv.ball.feature.{low}.{tid}Module,"
    if entry not in cat:
        if CATALOG_MARK not in cat:
            raise SystemExit("[wire] FeatureCatalog 缺少 WIRE:CATALOG 标记")
        cat = cat.replace(CATALOG_MARK, entry + "\n" + CATALOG_MARK, 1)
        _write_raw(CATALOG, cat)
        touched.append(str(CATALOG.relative_to(ROOT)).replace("\\", "/"))
    man = _read_raw(MANIFEST)
    cls = f".feature.{low}.{tid}Activity"
    if cls not in man:
        nl = "\r\n" if "\r\n" in man else "\n"
        block = nl.join([
            '        <activity',
            f'            android:name="{cls}"',
            f'            android:label="@string/{low}_title"',
            '            android:exported="false" />',
            '',
            '',
        ])
        anchor = '    </application>'
        if anchor not in man:
            raise SystemExit("[wire] manifest 缺少 </application> 锚点")
        man = man.replace(anchor, block + anchor, 1)
        _write_raw(MANIFEST, man)
        touched.append(str(MANIFEST.relative_to(ROOT)).replace("\\", "/"))
    print(f"[wire] {tid} 补注册 {len(touched)} 处")
    return touched


def cmd_prepare(args) -> int:
    t = task_of(args.id)
    d = STAGING / args.id
    if d.exists():
        shutil.rmtree(d)
    (d / "files").mkdir(parents=True)
    (d / "notes").mkdir(parents=True)
    manifest = []
    for rel in t.get("touch_files", []):
        p = resolve_target(rel)
        manifest.append({
            "input_path": rel,
            "abs_path": str(p),
            "exists": p.exists(),
            "size": p.stat().st_size if p.exists() else 0,
        })
    (d / "manifest.json").write_text(json.dumps({
        "task": t, "files": manifest, "prepared_at": now(),
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[prepare] {args.id} -> {d}")
    for m in manifest:
        print(f"  {'OK ' if m['exists'] else 'NEW'} {m['input_path']}  ({m['size']}B)")
    return 0


def cmd_apply(args) -> int:
    t = task_of(args.id)
    d = STAGING / args.id / "files"
    if not d.exists():
        raise SystemExit(f"[apply] 没有 staging 目录 {d}")
    written = []
    for src in sorted(p for p in d.rglob("*") if p.is_file()):
        rel = src.relative_to(d).as_posix()
        dst = resolve_target(rel)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        written.append(rel)
    # notes/extra_files.txt 里登记的额外文件同批落盘
    for rel in extra_files(args.id):
        src = d / rel
        if not src.exists():
            print(f"[apply] 额外文件缺失: {rel}")
            continue
        dst = resolve_target(rel)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        if rel not in written:
            written.append(rel)
    if not written:
        raise SystemExit("[apply] staging/files 为空，子智能体没交东西")
    allowed = set(t.get("touch_files", []) + extra_files(args.id))
    extra = [w for w in written if w not in allowed]
    if extra:
        print(f"[apply] 警告：超出 touch_files 的文件 {extra}")
    print(f"[apply] {args.id} 写入 {len(written)} 个文件")
    for w in written:
        print(f"  -> {w}")
    st = load_state()
    st["tasks"].setdefault(args.id, {})["applied_at"] = now()
    st["tasks"][args.id]["status"] = "applied"
    save_state(st)
    return 0


def cmd_verify(args) -> int:
    log = LOG if args.log else None
    print("[verify] gradlew :app:assembleDebug ...")
    gradlew = str(APP / "gradlew.bat")
    cmd = [gradlew, "testDebugUnitTest", ":app:assembleDebug", "--console=plain"] if args.unit_tests \
        else [gradlew, ":app:assembleDebug", "--console=plain"]
    code, out = run(cmd, APP, log)
    errs = [ln for ln in out.splitlines() if ln.startswith("e: ") or "AAPT: error" in ln or "error:" in ln]
    if code != 0 or errs:
        print(f"[verify] FAILED (exit {code})")
        for e in errs[:25]:
            print("  " + e)
        if args.task:
            st = load_state()
            st["tasks"].setdefault(args.task, {})["status"] = "failed"
            st["tasks"][args.task]["error"] = "\n".join(errs[:25])[:4000]
            save_state(st)
        return 1
    print("[verify] OK")
    if args.task:
        st = load_state()
        st["tasks"].setdefault(args.task, {})["status"] = "verified"
        save_state(st)
    return 0


def git(*a: str) -> str:
    code, out = run(["git", *a], ROOT)
    if code != 0:
        raise SystemExit(f"[git {' '.join(a)}] 失败:\n{out}")
    return out


def cmd_commit(args) -> int:
    t = task_of(args.id)
    st = load_state()["tasks"].get(args.id, {})
    if st.get("status") not in ("verified", "applied") and not args.force:
        raise SystemExit(f"[commit] {args.id} 当前状态 {st.get('status')}，先 verify")
    wiring = wire_one(args.id)
    extra: list[str] = []
    for rel in extra_files(args.id):
        extra.append(rel)
    for rel in sorted(set(t.get("touch_files", []) + extra)):
        p = resolve_target(rel)
        if p.exists():
            git("add", "--", str(p.relative_to(ROOT)).replace("\\", "/"))
    for w in wiring:
        git("add", "--", w)
    diff = git("diff", "--cached", "--name-only").strip()
    if not diff:
        print("[commit] 无变更")
        return 1
    scope = t.get("scope") or t["id"]
    msg = f"feat({scope}): {t['title']}\n\n{t.get('summary', '').strip()}\n\nTask: {t['id']} [batch {t['batch']}]"
    git("commit", "-m", msg)
    sha = git("rev-parse", "--short", "HEAD").strip()
    stt = load_state()
    stt["tasks"].setdefault(args.id, {})
    stt["tasks"][args.id].update({"status": "done", "commit": sha, "done_at": now()})
    save_state(stt)
    (REPORTS).mkdir(exist_ok=True)
    (REPORTS / f"{args.id}.md").write_text(
        f"# {args.id} — {t['title']}\n\n- 提交：`{sha}`\n- 批次：{t['batch']}\n"
        f"- 完成：{now()}\n\n## 改动文件\n\n" + "\n".join(f"- `{f}`" for f in diff.splitlines()) + "\n",
        encoding="utf-8")
    print(f"[commit] {args.id} -> {sha}")
    print(diff)
    return 0


def cmd_next(args) -> int:
    """输出下一批待做任务，给主智能体派发。"""
    st = load_state()["tasks"]
    data = load_tasks()
    batch = args.batch
    todo = [t for t in data["tasks"]
            if st.get(t["id"], {}).get("status", "pending") in ("pending", "failed")
            and (batch is None or t["batch"] == batch)]
    for t in todo[: args.limit]:
        print(f"{t['id']} b{t['batch']} {t['title']}")
    return 0


def cmd_report(args) -> int:
    data = load_tasks()
    st = load_state()["tasks"]
    lines = ["# TV Player 功能完成报告", "", f"生成时间：{now()}", "",
             "| ID | 批次 | 状态 | 提交 | 功能 |", "| --- | --- | --- | --- | --- |"]
    done = 0
    for t in data["tasks"]:
        s = st.get(t["id"], {})
        if s.get("status") == "done":
            done += 1
        lines.append(f"| {t['id']} | {t['batch']} | {s.get('status','pending')} | "
                     f"{s.get('commit','-')} | {t['title']} |")
    lines.insert(4, f"\n**进度：{done}/{len(data['tasks'])}**\n")
    p = ROOT / "docs" / "FEATURES-100.md"
    p.parent.mkdir(exist_ok=True)
    p.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"[report] {done}/{len(data['tasks'])} -> {p}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(prog="orchestrator")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("list"); p.add_argument("--status"); p.set_defaults(fn=cmd_list)
    p = sub.add_parser("show"); p.add_argument("id"); p.set_defaults(fn=cmd_show)
    p = sub.add_parser("prepare"); p.add_argument("id"); p.set_defaults(fn=cmd_prepare)
    p = sub.add_parser("apply"); p.add_argument("id"); p.set_defaults(fn=cmd_apply)
    p = sub.add_parser("verify"); p.add_argument("task", nargs="?"); p.add_argument("--log", action="store_true"); p.add_argument("--unit-tests", action="store_true"); p.set_defaults(fn=cmd_verify)
    p = sub.add_parser("commit"); p.add_argument("id"); p.add_argument("--force", action="store_true"); p.set_defaults(fn=cmd_commit)
    p = sub.add_parser("next"); p.add_argument("--batch", type=int); p.add_argument("--limit", type=int, default=10); p.set_defaults(fn=cmd_next)
    p = sub.add_parser("report"); p.set_defaults(fn=cmd_report)

    args = ap.parse_args()
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())


