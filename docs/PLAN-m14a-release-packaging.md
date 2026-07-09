# PLAN: M14a — R8 keep 审计 + v3.4.0 versionCode bump + release 打包

## Goal
为 v3.4.0 发布做准备:验证新 M12/M13 反射点过 R8 minify 不破 + bump versionCode 896→900 / versionName 3.3.8→3.4.0 + 构建签名 release APK + smoke(install + launch 不崩 + mod 特性在 release 下可用)。**不 git tag**(supervisor 在 M14a/b 都合并后统一打 v3.4.0,确保 tag 含 C3/平衡修复)。与 M14b 零文件冲突(gradle/proguard vs 测试/assets)。

## Context(2026-07-09 核实)
- **versionCode 定义**:`build.gradle:17 appVersionCode = 896`,`:18 appVersionName = '3.3.8'`(ext 块 `:13`)。v3.4.0 = 900 / '3.4.0'。
- **debug/release buildType**(`android/build.gradle:45+`):debug 带 `.indev` suffix + `-INDEV` versionNameSuffix;release 启用 R8(`minifyEnabled true`,CLAUDE.md C5)。
- **签名配置**:`signingConfigs.release`(`:33-40`)读 `android/keystore.properties`(test keystore `/home/yujk01/.android/spd-release.jks`,alias `spd-release`,CLAUDE.md)。M9d 已证 release 可签名打包。
- **现有 R8 keep**(`android/proguard-rules.pro`):
  - `-keepnames com.shatteredpixel.**` + `com.watabou.**`(宽规则,**覆盖 modding.* 全包**,含 M12/M13 新类 ModInstaller/ModImporter/AndroidSafModImporter/WndCustomLevels)
  - `-keep org.luaj.vm2.**`(M1 luaj 反射)
  - `-keep class * extends Gizmo/Bundlable`(vanilla + WndCustomLevels 走 Gizmo)
  - M2 RpdApi buff 类 keep
- **新 M12/M13 反射风险**(低):
  - `ModInstaller`(java.util.zip,无反射)、`ModImporter`(interface + Holder,无反射)、`AndroidSafModImporter`(SAF,无反射)、`register_level` 校验(字符串校验,无反射)、`WndCustomLevels`(WndOptions 子类 → Gizmo keep 覆盖)
  - luaj 加载 lua 脚本(remished_lite/regression_demo)走 luaj keep
  - 结论:宽 `keepnames` + luaj keep 已覆盖,M14a 主要是**构建验证 + smoke**,可能零 keep 改动。
- **M9d 既例**:M9 已构建签名 release + 装机不崩(R7 放开后)。M14a 照此 + 新 M12/M13。

**设计决策**:
- versionCode 896→900,versionName '3.3.8'→'3.4.0'(`build.gradle:17-18`)。900 而非 897 给后续 patch 留码段。
- 构建 `./gradlew :android:assembleRelease` → `android/build/outputs/apk/release/android-release.apk`(签名)。
- **R8 smoke 策略**:release APK install 到 device(`adb -s 20210119085654`)+ launch + logcat 查 FATAL/crash;若 device EGL/UI 可用,进一步验「模组管理器 + 导入按钮 + 暂停菜单自定义关卡入口」在 release 下可见(M12b/c + M13b)。若 EGL 仍坏(M12c 已知偶发),降为「launch 不崩 + logcat 无 FATAL」+ 文档记录 device UI 验证 deferred。
- keep 改动:仅当 release smoke 暴露 R8 剥离导致的崩溃/NoSuchMethod 时加针对性 keep;否则不改 proguard-rules.pro。
- **不 git tag**(supervisor 统一打)。

## Files (worker)
- **`build.gradle`**:`ext { appVersionCode = 900; appVersionName = '3.4.0' }`(line 17-18)。
- **`android/proguard-rules.pro`**(条件):仅 smoke 暴露问题时加 keep(预期零改动,宽规则已覆盖)。
- **验证产物**(不 commit):`android/build/outputs/apk/release/android-release.apk` + smoke 报告(send_message 里描述)。
- **可选 `docs/RELEASE-v3.4.0.md`**(若 worker 愿):简短发布笔记(versionCode/smoke 结果/已知 device 限制)。非必须(M14c 没选;roadmap changelog 由 supervisor 写)。

### 显式延后
- **git tag v3.4.0**:supervisor 在 M14a/b 合并后统一打(确保 tag 含 M14b 的 C3/平衡)。
- **device 全 UI smoke**:EGL 坏则降级(M12c 既例);本 feature 接受 launch-不崩 为最低 gate。
- **release notes / player changelog**:M14c 未选;roadmap changelog 由 supervisor 维护。
- **正式签名 keystore**(非 test):当前用 test keystore(CLAUDE.md),正式发布签名留后续。

## Steps
1. **versionCode bump**:`build.gradle:17-18` → 900 / '3.4.0'。
2. **R8 keep 审计**:读 `proguard-rules.pro`,核对新 M12/M13 类是否被现有 keep 覆盖(宽 `keepnames com.shatteredpixel.**` + luaj keep + Gizmo keep);列出任何潜在缺口。
3. **构建签名 release**:`./gradlew :android:assembleRelease` → 确认 BUILD SUCCESSFUL + APK 生成 + 签名(`apksigner verify` 或 build 输出 sign 信息)。
4. **release smoke**:
   - `adb -s 20210119085654 install -r android/build/outputs/apk/release/android-release.apk`
   - `adb logcat -c` + 启动 + `adb logcat -d | grep -iE "shattered|FATAL|AndroidRuntime"`
   - 验 launch 不崩 + 进程稳定。
   - 若 device UI 可用(非 EGL 黑屏):开模组管理器(验导入按钮 release 可见,M12b/c)+ 暂停菜单自定义关卡入口(M13b)+ remished_lite showcase(M13c,经自定义关卡进入)。
   - 若 EGL 坏:文档记录 device UI deferred,以 launch-不崩 + build 成功为 gate。
5. **R8 问题处理**(若 smoke 暴露):加针对性 keep → 重建 → 再 smoke。预期零。
6. **`:core:test`** 全绿(versionCode bump 不影响测试,但跑一遍守基线)。
7. **codex 评审**(Phase 1/2,`codex exec --sandbox read-only`):重点 —— versionCode bump 正确性、keep 审计完整性、smoke 结果可信。infra 503 则政策 B(build 成功 + smoke + :core:test 为硬验收)。
8. send_message 回报:versionCode 新值 + release APK 路径/大小 + smoke 结果(launch 不崩 + mod 特性 release 可见性)+ 任何 keep 改动。

## Acceptance
- [ ] `build.gradle` appVersionCode=900 / appVersionName='3.4.0'
- [ ] `./gradlew :android:assembleRelease` 成功,签名 APK 生成
- [ ] release APK install + launch 不崩(logcat 无 FATAL)
- [ ] R8 keep 审计:新 M12/M13 反射点被现有 keep 覆盖(或加针对性 keep)
- [ ] release 下 mod 特性可见性(模组管理器导入按钮 / 自定义关卡入口;device UI 可用时验,EGL 坏则文档 deferred)
- [ ] `./gradlew :core:test` 全绿
- [ ] **不 git tag**(留 supervisor)
- [ ] 与 M14b 零文件冲突

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,**不 assign codex_reviewer**(memory:必超时;503 则政策 B:assembleRelease 成功 + smoke + :core:test 硬验收)
- **不 git tag**(supervisor 合并 M14a/b 后统一打 v3.4.0)
- release 用 test keystore(CLAUDE.md,`/home/yujk01/.android/spd-release.jks`);签名配置已在 `android/build.gradle:33-40`
- device EGL 坏是已知偶发(M12c),非代码问题;smoke 降级可接受(launch-不崩 为最低 gate)
- 与 M14b(测试/assets/BalanceConfig)零重叠
