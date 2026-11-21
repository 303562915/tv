# 发布流程

## 0. 双通道说明

- **开发版**：每次 `push` 到 `main` 都会由 `development-release.yml` 自动构建 release APK，
  以 `TVPlayer-dev-<run_number>.apk` 追加到滚动的 `dev` 预发布 Release（每次构建是独立版本，
  不互相覆盖），并刷新下载页 `docs/development.json`。
- **稳定版**：打 `v*` 标签发布正式 Release，刷新下载页 `docs/latest.json`。
- 两个渠道同一签名（配置了 `KEYSTORE_*` Secrets 时为正式签名，否则回退 debug 签名），可互相覆盖安装。

## 1. 定版本

```bash
python orchestrator/scripts/version_bump.py --version 1.2.0 --dry-run      # 先看
python orchestrator/scripts/version_bump.py --version 1.2.0 --apply --changelog
```

- `versionCode` 每次自增 1，`versionName` 用语义化版本 `主.次.修`。
- `--changelog` 会从 `git log` 汇总本次变更（过滤掉自动提交与编排器提交），插入 `CHANGELOG.md` 顶部。

## 2. 本地过门禁

```powershell
pwsh -File orchestrator/scripts/quality_gate.ps1
python orchestrator/scripts/i18n_audit.py
```

## 3. 推主线 / 打标签

```bash
# 只想发开发版：push 即可，CI 自动构建并追加新的 dev 构建
git push origin main

# 要发稳定版：打标签
git tag v1.2.0
git push origin main --tags
```

`.github/workflows/build.yml` 的 `release` job 会在 `v*` 标签上构建并发布；仓库配置了
`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 四个 Secrets 时出签名包，
否则自动回退 debug 签名包，保证下载页永远有能装的 APK。

## 4. 核对下载页

`docs/latest.json`、`docs/development.json` 与 `docs/apk/` 由 Pages 工作流生成，触发条件：
`docs/**` 变更、Build / Development APK 完成、每天 03:17 UTC。
发版后看一眼 <https://303562915.github.io/tv/> 的开发版 / 稳定版两个渠道是否指向新版本与新的 SHA-256。
