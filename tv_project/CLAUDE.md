# 🚀 构建与发布规则（CI 唯一）

## 📌 工作目录
`G:\trae\tv`（Android 子工程 `tv_project/`）

---

## ⛔ 禁止本地构建（硬规则）

**不跑任何 `gradlew` 命令。** 不 `assembleDebug`、不 `assembleRelease`、不在本地出 APK。
构建 100% 走 GitHub Actions。

本地只允许：读代码、改代码、静态检查（XML 解析 / grep）、`git` 操作。

---

## 🔁 每次改完代码的标准流程

### 1️⃣ 提交

```bash
git add -A
git commit -m "type(scope): 说明"
```

### 2️⃣ 推送（触发 CI）

```bash
git push origin main
```

`push` 到 `main` 即触发 `.github/workflows/development-release.yml`：构建 release APK →
把 `TVPlayer-dev-<run_number>.apk` 追加到 `dev` 预发布 Release（每次构建独立命名，不覆盖）
→ Pages 刷新 `docs/development.json`。

### 3️⃣ 看 CI 结果

```bash
gh run list -L 3
gh run watch <run-id>
```

- 成功 → 产物在 `dev` Release（开发版）与下载页开发版渠道。
- 失败 → **读日志改代码再推**，不要转去本地构建。

---

## 📦 产物位置（全部由 CI 生成）

| 类型 | 地址 |
| --- | --- |
| 开发版（每次 push 新增一个构建） | https://github.com/303562915/tv/releases/tag/dev |
| 稳定版（`v*` 标签） | `git tag v1.2.0 && git push origin v1.2.0` |
| 下载页（双渠道 + SHA-256） | https://303562915.github.io/tv/ |

---

## 🎯 触发词

「运行 / run / debug / 安装 / 部署 / 构建 / 出包 / 发版」→ 一律理解为
**提交 + push + 看 CI**，不是本地编译。

---

## 🔄 应用内更新（App 内自更新）

改这一块必须守住顺序（详见仓库根 `AGENTS.md` A3）：

1. 检查：默认**开发版**渠道 `https://303562915.github.io/tv/development.json`
   （关于页可切稳定版 `latest.json`；镜像拿不到退 GitHub API / Release 页面）
2. 下载：私有缓存目录 + 体积/SHA-256 校验
3. 安装：`PackageInstaller` → 系统安装器（FileProvider）→ **失败回退 Shizuku**
   （`pm install -r -t <path>`）→ 全失败才给网页链接
4. 普通设备（无 Shizuku / 无 root / 无安装权限）必须能正常降级提示，不许崩

---

## ⚠️ 版本号

改功能就要升 `versionCode`（必须严格递增）与 `versionName`（`app/build.gradle.kts`），
否则应用内更新无法识别新版本。`docs/latest.json` 的 `version_code` 由 CI 从 Gradle 读取。