# 质量门禁

`orchestrator/scripts/quality_gate.ps1` 是提交前的最小检查，只做三件能给出确定结论的事：

1. **硬编码色值**：扫描 `app/src/main` 下的 `*.xml` / `*.kt`，命中 `#RRGGBB` 或 `#AARRGGBB` 就报错。
   `res/values/colors.xml` 与 `res/values-night/colors.xml` 是唯一合法色源，二维码黑白是唯一豁免。
2. **TODO / 空实现**：`TODO` / `FIXME` / `NotImplementedError` 一律报错——功能要么真实现，要么不进主干。
3. **编译**：跑 `:app:assembleDebug`，抓 `e:` 与 `AAPT: error`。

用法：

```powershell
pwsh -File orchestrator/scripts/quality_gate.ps1            # 含编译
pwsh -File orchestrator/scripts/quality_gate.ps1 -SkipBuild # 只做静态检查
```

退出码 0 = 通过，1 = 有问题（可直接给 CI 用）。CI 里的 `secret-scan` job 与它互补：那边查凭据，这边查风格与可编译性。
