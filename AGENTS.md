# AGENTS.md — 本仓库的硬规则（Agent 必读）

> 这些是硬闸，优先级高于任何"顺手跑一下""先本地验证一下"的便利做法。

## A1 构建只走 GitHub Actions（绝对）

**本项目禁止任何本地构建。** 不跑 `gradlew`、不跑 `./gradlew.bat`、不跑 `assembleDebug` /
`assembleRelease` / `bundleRelease`，也不在本地生成 APK。

唯一的构建路径是远端流水线：

| 场景 | 触发方式 | 产物 |
| --- | --- | --- |
| 每次 `push` 到 `main` / `master` | 自动 | 滚动更新 `latest` 预发布 Release（APK 覆盖更新） |
| 打 `v*` 标签 | `git tag vX.Y.Z && git push origin vX.Y.Z` | 正式 Release |
| 手动 | Actions → **Build** → Run workflow | 同上 |

- 工作流：`.github/workflows/build.yml`（构建 + 出包 + 发布）、`.github/workflows/pages.yml`（镜像到下载页）
- 下载页：<https://303562915.github.io/tv/>（数据源 `docs/latest.json`，由 CI 生成）
- Release：<https://github.com/303562915/tv/releases/latest>
- 需要看构建日志时读 Actions 运行记录，**不要**用本地构建来"确认能不能编"。
- 本地能被允许做的只有：读代码、改代码、静态检查（`grep` / XML 解析 / 语法审阅）、`git` 操作。
- 交付前自检必须靠"跑 CI + 读 CI 日志"，本地不产出 APK，也不把本地 APK 当作交付物。

改完代码的正确收尾动作：

```bash
git add -A && git commit -m "type(scope): 说明"
git push origin main          # CI 自动构建并刷新 latest Release
gh run list -L 3              # 看这次构建有没有过
gh run watch <run-id>         # 需要时盯构建
```

CI 失败就**读日志改代码再推**，不要转去本地构建。

## A2 不擅自 push / 打 tag / 发版

`push`、`git tag v*`、`gh release` 这类对外动作，需用户明确要求才执行。

## A3 应用内更新链路（改动前必须守住）

- 检查更新：`https://303562915.github.io/tv/latest.json` 优先（国内可达），GitHub API 兜底。
- 下载：落到应用私有缓存目录，校验体积/`sha256`。
- 安装顺序：① 应用内 `PackageInstaller` → ② 系统安装器（`ACTION_VIEW` + FileProvider）→
  ③ 前两步失败时回退 Shizuku（`pm install -r -t <path>`）→ ④ 全失败才给网页下载链接。
- 相关权限：`REQUEST_INSTALL_PACKAGES`；Shizuku 需在 `AndroidManifest.xml` 注册
  `rikka.shizuku.ShizukuProvider`。
- 改这一链路必须保证：没有 Shizuku、没有 root、没有安装权限的普通设备，功能不能崩，
  只能提示并降级。

## A4 禁止删除

不执行 `rm` / `del` / `Remove-Item`。需要清理就移到 `backups/`。