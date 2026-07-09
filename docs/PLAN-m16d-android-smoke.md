# PLAN: M16d — Android 真机 smoke 验证

## Goal
在真机(Honor TYH201H, `adb -s 20210119085654`)上对当前 master(含 M15+M16)做一次端到端 smoke:确认 debug APK 可构建、安装、启动不崩,并产出一份**自动化 + 人工**结合的 smoke 记录文档,作为 M16 可交付的实测依据。

## Context
M16a/b/c 已合并进 master,Lua item/spell/mob 有 `spriteFile` runtime 支持,`remixed_full` alpha 内容包可玩,mod diagnostics UI 可见错误。但这些都只过了 `:core:test` 单元测试,没有真机验证。本 feature 做最小真机 smoke,不追求完整 playtest。

已知设备特性(见 CLAUDE.md):
- 设备 `20210119085654`(Honor TYH201H, MIUI)。
- 已知 EGL_BAD_DISPLAY 偶发,与代码无关。
- SPD 默认 libgdx logLevel=2(LOG_ERROR),要可靠调试用 `Gdx.app.error`。
- debug 包带 `.indev` 后缀,debug updates/news services。

真机 UI 交互(SAF 导入 zip、启用 mod、进地牢、捡物品)无法可靠自动化,这部分作为**人工 checklist**记录;worker 负责能自动化的部分:构建、安装、启动、logcat 抓 fatal。

## Files
预计会改/新增:
- `docs/SMOKE-M16.md` (新):smoke 记录。包含:
  - 环境(设备/版本/commit/构建时间)
  - 自动化结果(`:android:assembleDebug` 通过、install 成功、launch 后 logcat 无 fatal crash)
  - **人工 checklist**:启用 `remixed_full` alpha → 进普通地牢 → 观察 Lua item/spell 掉落 + sprite 显示 + Lua mob 刷怪 + Lua shop 购买 + 存档/读档。每项标注 `待验` / `通过` / `失败`。
  - 已知风险(EGL quirk、logLevel 过滤)。
- 可能的**构建/启动修复**(TBD):若 assembleDebug 或 launch 暴露真实回归(非 EGL quirk),做最小修复并补说明;若只是设备 quirk 则不修,记录即可。
- 不改游戏逻辑代码,除非 smoke 暴露 fatal 回归。

避免改动:
- 不为 smoke 改 Gameplay/balance。
- 不 commit build 产物(APK)。
- 不动 M15/M16 已合并的 feature 代码,除非发现 fatal bug。

## Steps
1. 确认当前 worktree 在 master HEAD(本 worktree 从 master 切出),`git log --oneline -1` 记录 commit。
2. `./gradlew :android:assembleDebug` 构建 debug APK,记录产物路径 `android/build/outputs/apk/debug/android-debug.apk` 与大小、BUILD 结果。
3. `adb -s 20210119085654 install -r android/build/outputs/apk/debug/android-debug.apk`,记录 install 结果。
4. `adb -s 20210119085654 logcat -c` 清日志,然后 `adb shell am start` 启动主 Activity(从 `android/src/main/AndroidManifest.xml` 确认 launch activity 全名,debug 注意 `.indev` 后缀)。
5. 等待若干秒后 `adb -s 20210119085654 logcat -d | grep -iE "shattered|System.err|FATAL|AndroidRuntime"` 抓启动期日志,判断是否有 fatal crash 或重复 error。
6. 尝试有限自动验证(可选,失败不阻塞):如 `adb shell dumpsys activity top | grep -i shattered` 确认 Activity 在前台。
7. 若启动 fatal crash,定位是否 M15/M16 回归(对比 v3.4.0 tag 的 release 行为);若是真实回归做最小修复 + 记录,若是 EGL quirk 则记录不修。
8. 写 `docs/SMOKE-M16.md`:自动化结果填实际值,人工项标 `待验`。
9. 运行 `./gradlew :core:test` 确认 smoke 期间无测试回归(若没改代码可跳)。
10. codex 评审:必须 `assign("codex_reviewer", ...)`;如果 assign 失败或 reviewer 不可用,跳过该评审阶段并在最终回报给 dispatcher 裁决,不要直接调用 codex-cli/codex exec。

## Acceptance
- [ ] `:android:assembleDebug` BUILD SUCCESSFUL,产物存在。
- [ ] APK 成功 install 到真机。
- [ ] 应用可启动,logcat 无 fatal crash(或仅有已知 EGL quirk)。
- [ ] `docs/SMOKE-M16.md` 存在,含自动化结果 + 人工 checklist。
- [ ] 若发现真实回归,已最小修复并记录;否则明确标注无回归。
- [ ] 不 commit APK / build 产物。
- [ ] 不提交 `.claude/`,不使用 `git add -A`。

## Notes
- 真机 UI 深度验证(SAF 导入/启用/地牢内行为)是人工 checklist,worker 不必也无法可靠自动化;把自动化能做的做扎实即可。
- EGL_BAD_DISPLAY 偶发是设备 quirk,不是本 fork 回归,不要为此改代码。
- 若设备掉线或 adb 不可用,立即 `[BLOCKED]` 上报 dispatcher(在 send_message 开头加 `[BLOCKED]`),不要空跑。
