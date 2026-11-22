# AGENTS.md — 本仓库的硬规则（Agent 必读）

面向在本仓库里工作的 AI agent / 协作者。**先读第 0 节，它覆盖后面所有内容。**

> 这些是硬闸，优先级高于任何「顺手跑一下」「先本地验证一下」的便利做法。

---

## 0. 铁律：只改代码，构建全部交给 GitHub Actions

本仓库**不提供也不支持本地构建**。每次完成用户要求的修改后，检查改动、提交并推送到远端，推送完成后直接结束，不等待或跟踪 GitHub Actions 的运行结果。用户明确要求查看或排查构建时再查询。

**绝对不做：**

- 不跑 `gradlew` / `./gradlew.bat` / `gradle`，任何 task 都不跑（包括 `--version`、`tasks`、`help`、`test`、`assemble*`、`bundleRelease`）。
- 不安装任何工具链：JDK、Android SDK / build-tools / NDK / platform-tools、Kotlin、Node.js、npm / pnpm / yarn、Docker、Gradle 发行版、KVM 之类。
- 不 `sdkmanager` / `apt install` / `brew install` / `choco install` / `pip install` / `mise` / `asdf` / `nix` 去补齐构建依赖。
- 不改 `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `JAVA_HOME`，不往 shell profile、`.env`、`local.properties` 里塞 SDK 路径来「让构建能跑」。
- 不下载 Gradle wrapper 分发包、不预热 `~/.gradle` 缓存。
- 不用「本地跑一下看看能不能编译」当作验证手段。验证手段只有一个：看云端 run 的结论。

**允许做：**

- 读 / 写 / grep 仓库里的源码、资源、文档、`.github/workflows/*.yml`。
- `git status` / `add` / `commit` / `push` / `log` / `diff` / `fetch` / `pull`（见第 3 节）。
- `gh` 查 run、看日志（见第 4、5 节）。
- 静态检查：`grep`、XML 解析、YAML 解析、语法审阅。
- 如果需要理解某个编译错误，读云端日志，而不是重建本地环境。

> 本地能产出的东西只有源码改动和静态结论；**本地 APK 不是交付物**，也不要把它当作交付物。

> 环境事实：本机没有 Android SDK（`ANDROID_HOME` 为空）、`gradlew` 也不是给本地跑构建用的。这是**设计状态**，不是待修的 bug；别去「修复」它，也别把它当成「顺手验证一下」的入口。

---

## 1. 仓库与身份

| 项 | 值 |
|---|---|
| 本地路径 | `G:\trae\tv`（Android 子工程 `tv_project/`，Gradle 根在 `tv_project/`） |
| 远程 | `origin` → `https://github.com/303562915/tv.git` |
| 默认分支 | `main` |
| 构建身份 | `gh` 已登录 `303562915`（scopes: repo, workflow, read:org, gist 等） |
| 版本号 | `tv_project/app/build.gradle.kts` → `versionCode` / `versionName`（**不在** `gradle.properties`） |
| 版本号规则 | `versionCode` 必须严格递增，否则应用内更新识别不出新版本 |
| `DEV_BUILD_NUMBER` | CI 用 `TVPLAYER_DEV_BUILD_NUMBER` 注入 run_number；本地/离线构建为 0 |

---

## 2. 工作流清单（唯一的构建入口）

全部在 `.github/workflows/` 下。

| 文件 | 触发 | 干什么 | 产物 |
|---|---|---|---|
| `development-release.yml` 🧪 Development APK | `push` 到 `main` | 构建签名 APK，追加到滚动预发布 Release `dev` | `TVPlayer-dev-<run>.apk`（每次独立命名，不互相覆盖） |
| `build.yml` 🏗️ Build | `push`、`workflow_dispatch` | 构建校验；打 `v*` 标签时发正式 Release | 稳定版 Release |
| `pages.yml` | `push` 到 `main`、`workflow_dispatch` | 把下载页镜像到 GitHub Pages | `github-pages` |

- 下载页：<https://303562915.github.io/tv/>（双渠道：`docs/latest.json` 稳定版 + `docs/development.json` 开发版，由 CI 生成）
- Release：<https://github.com/303562915/tv/releases/latest>（稳定版）、<https://github.com/303562915/tv/releases/tag/dev>（开发版）
- 需要发稳定版：`git tag vX.Y.Z && git push origin vX.Y.Z`（**需用户明确要求**）。
- 需要看构建日志时读 Actions 运行记录，**不要**用本地构建来「确认能不能编」。

---

## 3. 标准工作流：改完就推，推送后直接结束

用户已明确授权：**改完代码直接提交 + 推送，构建交给 GA，不本地构建、不等人催。**

```bash
cd /g/trae/tv          # Windows 下：cd "G:\trae\tv"

git status --short                 # 先确认改了什么，别把无关脏文件一起提交
git add <只列本次改动的文件>
git commit -m "type(scope): <一句话说清修了什么>"
git push origin main               # 不要 --force，不要推空提交试探
```

约定：

- 一次 push 只带一个有意义的改动，方便对照日志定位。
- 提交信息写「修了什么 / 为什么」，不写「改了哪些文件」。
- **不要**为了让 CI 变绿去删测试、跳过测试、加 `|| true`、加 `continue-on-error`、把 `needs` 去掉。要修的是代码。
- **不要** `git push --force`，不要 rebase 掉已推送的历史，不要直接改 `main` 上别人刚推的提交。
- 工作区里可能有**别人**留下的脏文件（未跟踪的临时目录、备份、别的改动）。`git add` 只加本次改的文件，**禁止** `git add -A` 一把梭把无关内容推上去。
- 推送成功后直接汇报「提交了什么、推了什么、CI 结果尚未确认」即可，不要原地等构建。

---

## 4. 取结果 / 取 APK（用户要看时才做）

```bash
gh run list -L 5
gh run watch <run-id> --exit-status          # 阻塞到结束，失败返回非零
gh run view <run-id> --log-failed            # 只看失败的步骤日志
gh run download <run-id> -n <artifact> -D <dir>
```

- 开发版 APK：`dev` Release（<https://github.com/303562915/tv/releases/tag/dev>），每个 run 一个新文件名。
- 稳定版 APK：`v*` 正式 Release。
- 需要只跑构建不跑其它：`gh workflow run build.yml`（`workflow_dispatch`）。

---

## 5. 排查失败：只读日志

1. `gh run view <run-id> --log-failed` 拿到报错段。
2. 按报错定位源码，改，回到第 3 节重新推。
3. **不要**因为「想看完整堆栈」就在本地跑一次构建。
4. **不要**转去本地构建「确认能不能编」；本地没有、也不该有这套能力。

---

## 6. 已知问题与坑（改动前先看）

1. **禁止本地构建是刻意的**：CI 是唯一构建路径。别提交「修 gradlew 权限」「加 local.properties」「配 SDK 路径」这类补丁。
2. **Material3 卡片 + 非 Material3 主题会崩（已修，别踩回去）**：`Base.Widget.Material3.CardView` 的 `stateListAnimator` 指向 `@animator/m3_card_state_list_anim`，里面 `?attr/motionDurationLong2` / `?attr/motionDurationMedium1` 是整数型主题 attr；在 `Theme.AppCompat.*` 下解析不出来，Android 16 上 `TypedArray.getInt` 直接 `parseInt("?2130969487")` 抛 `NumberFormatException` → `MaterialCardView` inflate 崩。因此 `Widget.TVPlayer.Card` 显式设了 `android:stateListAnimator=@null`，**不要删**。新页面一律用 `Theme.影视星球`（Material3），不用 `Theme.AppCompat.NoActionBar`。
3. **应用内更新链路改动必须守住顺序**（见第 7 节 A3 全表）：普通设备（无 Shizuku / 无 root / 无安装权限）不能崩，只能提示并降级。
4. **改功能就升 `versionCode` + `versionName`**，否则应用内更新认不出新版本。只改文档/注释不需要升。
5. **工作区可能有无关脏文件**（`_apk-artifacts/`、`.dsh-handoff*.md`、备份目录等），提交前必须挑文件。
6. **action 版本已统一**：workflow 一律 `actions/checkout@v4` + `actions/setup-java@v4` + `distribution: temurin`；
   `pages.yml` 另外用 `actions/configure-pages@v5` / `upload-pages-artifact@v4` / `deploy-pages@v4`。
   改 workflow 时沿用同一套版本，别混进 `@v3` 或其它 JDK 发行版。
7. **`::set-output` 已全部移除**：跨 step 传值走 `$GITHUB_ENV`（`build.yml` 里 `FALLBACK_DEBUG=1` 就是这么写的），别写回旧语法。
8. **Gradle 缓存是开着的**：`tv_project/gradle.properties` 里 `org.gradle.caching=true`、`org.gradle.parallel=true`；
   `org.gradle.configuration-cache` 目前只是注释预留。将来若真的打开配置缓存，新增的 Gradle 任务/插件凡是读环境变量，
   都必须在任务上声明输入，否则云端会因缓存命中而行为异常。

---

## 7. 应用内更新链路（改动前必须守住）

双通道，数据都来自 CI 生成的下载页站点：

| 通道 | 数据 | 来源 | 判新旧依据 |
|---|---|---|---|
| 稳定版 | `https://303562915.github.io/tv/latest.json` | `v*` 正式 Release | `version_code` vs `BuildConfig.VERSION_CODE` |
| 开发版 | `https://303562915.github.io/tv/development.json` | 滚动 `dev` 预发布 | `build_number` vs `BuildConfig.DEV_BUILD_NUMBER` |

- 默认渠道：**开发版**（`UpdateChannel.DEFAULT = DEV`，未设置过渠道时「我的」页与关于页都走开发版）；
  关于页的渠道芯片可切到稳定版，选择存 `f086_about/update_channel`。
- 检查顺序：只看当前渠道；该渠道的下载页镜像拿不到才退 GitHub API，再退 Release 页面。
- 下载：落到应用私有缓存目录，校验体积 / `sha256`。
- 安装顺序：① 应用内 `PackageInstaller` → ② 系统安装器（`ACTION_VIEW` + FileProvider）→
  ③ 前两步失败时回退 Shizuku（`pm install -r -t <path>`）→ ④ root → ⑤ 全失败才给网页链接。
- 相关权限：`REQUEST_INSTALL_PACKAGES`；Shizuku 需在 `AndroidManifest.xml` 注册
  `rikka.shizuku.ShizukuProvider`。
- `versionCode` 必须单调递增；`DEV_BUILD_NUMBER` 由 CI 注入，本地构建为 0。
- 改这一链路必须保证：没有 Shizuku、没有 root、没有安装权限的普通设备，功能不能崩，只能提示并降级。

---

## 8. 禁止删除

不执行 `rm` / `del` / `Remove-Item`。需要清理就移到 `backups/`。

---

## 9. 提交前自检清单

- [ ] 没有新增任何「本地构建脚本」、`local.properties`、SDK 路径、工具链安装步骤。
- [ ] 没有改 `gradlew` 权限位或 wrapper 分发地址。
- [ ] 没有删/跳过测试，没有 `|| true`。
- [ ] 如果改了 `.github/workflows/*.yml`，确认 YAML 可直接解析、`needs` 依赖的 job 名存在、artifact 名与第 2 节表格一致。
- [ ] 如果改了 `versionCode` / `versionName`，说明这是有意的发版动作。
- [ ] 提交只包含本次改动的文件，没有把工作区里的无关脏文件一起带上。
- [ ] 推送成功后直接汇报提交和推送结果，GitHub Actions 构建结果尚未确认时如实说明。

---

## 10. 用户快捷词：猜错就学，学完就记（强制）

用户会用他自己的**快捷词**说话。规则三条：

1. **优先按词表理解**。用户说出的词先查本节的表。表里有的，直接按表里的含义理解并动手，不要再问、不要再猜、不要往常见技术含义上套。
2. **猜错立刻纠正，不许含糊过去**。如果猜了、猜错了、用户纠正了，这一轮必须做到：承认理解偏了 → 按用户的解释重做 → **把词和含义写进下面的表**。不登记就当作这次纠正没发生。
3. **拿不准先猜，猜完必须落表**。不要因为不认识词就停下问「这是什么意思」；先按上下文猜一版执行，用户一纠正就回填词表。除非猜错会造成不可逆的破坏性操作（删数据、强推、发版），那种情况才先问一句。

登记要求：

- 只记**用户实际用过的词**，不预填、不臆造同义词。
- 一行一个词，写成 `| 词 | 含义 | 首次确认时间 | 备注 |`。
- 含义写**用户的原话意思**，不要改写成你以为更标准的术语；必要时把用户原句摘进备注。
- 同一个词后来含义变了，保留旧行并加一行新的，标 `（含义已更新）`，不要静默覆盖。
- 一有纠正就在**同一轮**里改这个文件，不要攒到最后、不要「下次再说」。

### 10.1 词表

| 词 | 含义 | 首次确认时间 | 备注 |
|---|---|---|---|
| `ga` | GitHub Actions —— 工作流相关的所有事（改 `.github/workflows/*.yml`、查 run、看日志、下载 artifact、发版触发） | 2026-11-21 | 不是 Google Analytics，不是 general availability。见到 `ga` 先想到工作流 |
| `action` / `actions` | 同上，GitHub Actions 工作流 | 2026-11-21 | 与 `ga` 同义 |
| 推新版本 / 往上推 | 提交并 `git push origin main`，由 CI 构建开发版 | 2026-11-21 | 用户原话：「改完之后直接推新版本往上推，不需要我构建，直接走 GA 就行了」 |
| `pet-theme` | 宠物人格主题对话：不进代码、不改 App，把随附图片当角色头像，写「宠物主题」人格设定与台词并按该人格对话 | 2026-11-21 | 首次见 `/pet-theme` + 一张自拍图；已确认选项「宠物人格主题对话」，非 App 主题皮肤、非图片改图 |
| 抄 FlClash 的那个图标 | 抄 FlClash 里**对应功能**的 glyph，不是抄名字最像的那个：视频源页的「刷新」实际是重测延迟，对应 `AppGlyphs.bolt`（闪电），不是 `AppGlyphs.refresh`（环形箭头） | 2026-11-21 | 用户纠正原话：「他那个项目，他的是一个闪电的吧，测速的那个」。含义已更新：按功能语义选字形 |
| 刷短视频 / 短视频 | 指 App 里「首页 → 免费短剧」这条链路（`tv_project/.../presentation/shortdrama`）：短剧列表、详情、竖屏连播 | 2026-11-21 | 我先把「刷短视频」认成 `G:\trae\R1\douyin-auto-fire`，用户纠正原话：「我知道你们短剧在哪，短剧它那个现在报网络错误」。以后听到「刷短视频/短视频」先想到本仓库的短剧模块 |

> 说明：`ga` 默认指 GitHub Actions，但**不是绝对**——若上下文明显在谈别的东西（如统计分析、灰度发布），以当轮上下文为准；判断依据写进备注。默认顺序：先 Actions，再看上下文。