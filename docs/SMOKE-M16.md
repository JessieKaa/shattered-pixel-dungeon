# SMOKE: M16 — Android 真机 smoke 验证

> Feature: `m16d-android-smoke` · Branch: `feature/m16d-android-smoke`
> 对应规划: [`PLAN-m16d-android-smoke.md`](./PLAN-m16d-android-smoke.md)
> 执行日期: 2026-07-09 ( Asia 22:2x CST )

本文档分两部分:**自动化结果**(worker 实测,可复现) + **人工 checklist**(真机 UI 深度交互,
无法可靠自动化,留待人工逐项勾验)。自动化部分覆盖 PLAN 的 Acceptance 1–3。

---

## 1. 环境

| 项 | 值 |
|---|---|
| 设备 | Honor TYH201H (`HNR552T`) |
| 系统 | Android 9,API 28 |
| adb serial | `20210119085654`(transport 14) |
| 测试 commit | `1e7931e59bf8abb81fc4610e0ab319b288e141da` (== master,m16c 合并点,含 M15+M16a/b/c) |
| versionCode / Name | **900 / 3.4.0**(注:CLAUDE.md 记的 896/3.3.8 已 stale,以 build.gradle 为准) |
| debug applicationId | `com.shatteredpixel.shatteredpixeldungeon.indev`(`.indev` 后缀) |
| launch activity | `com.shatteredpixel.shatteredpixeldungeon.android.AndroidLauncher` |
| 构建时间 | 2026-07-09 22:26:59 +0800 |

---

## 2. 自动化结果

### 2.1 构建 `:android:assembleDebug` — ✅ PASS

```
> Task :android:assembleDebug
BUILD SUCCESSFUL in 14s
49 actionable tasks: 49 executed
```

- 产物: `android/build/outputs/apk/debug/android-debug.apk`
- 大小: **33.8 MB**
- sha256: `54f68eb0166e2e60a0d50403b619c49a0baa7e2eacfeeaa1256c1a106ff8a8be`

### 2.2 安装 — ✅ PASS

```
adb -s 20210119085654 install -r android-debug.apk
Performing Streamed Install
Success
```

### 2.3 启动 + 崩溃检查 — ✅ PASS(无 fatal crash)

流程:`logcat -c` → `am start -n ...indev/...AndroidLauncher` → 等 22s → 抓 logcat。

- `am start` 返回 `START_SUCCESS`,进程 pid=**7226** 拉起。
- `dumpsys activity activities` → `ResumedActivity = ...AndroidLauncher`,应用在前台。
- 进程 22s 后仍存活(state `S`,RSS ~84 MB)。
- **无 `FATAL` / `AndroidRuntime` 崩溃,无 ANR,无 dropbox data_app_crash。**
- app pid(7226)的日志里**没有任何 Java Exception**(libgdx logLevel=2,本就只记 ERROR,详见已知风险)。
- 截屏:唤醒后 `screencap` 产出 **1.6 MB / 720×1600 RGBA** PNG —— 体积证明屏幕有真实渲染内容
  (全黑帧压到 KB 级)。**标题画面具体内容核验属人工 checklist**(见 §3.1)。

### 2.4 唯一 app 端 error:EGL_BAD_DISPLAY(已知设备 quirk,非回归)

```
07-09 22:27:31.624  7226  7292 E OpenGLRenderer: error : EGL_BAD_DISPLAY, because eglTerminate has been called.
```

- 仅出现 **1 次**,来自 app pid 7226,未复发,未导致进程退出或 Activity 重建。
- 与 CLAUDE.md「已知 EGL_BAD_DISPLAY 偶发,与代码无关」一致。**判定为设备 quirk,不改代码。**

### 2.5 误报排除(非本应用)

logcat 里另有大量 `System.err: NoClassDefFoundError: org.apache.http.entity.ByteArrayEntity`,
但 pid 是 **5756**(`TencentNLP.apk`,Honor 预装系统应用 `com.tencent.beacon`),**不是本应用**,
与本 smoke 无关。grep `System.err` 时需按 pid 过滤,否则会误判。

### 2.6 结论

| Acceptance | 状态 |
|---|---|
| `:android:assembleDebug` BUILD SUCCESSFUL,产物存在 | ✅ |
| APK 成功 install 到真机 | ✅ |
| 应用可启动,logcat 无 fatal crash(仅已知 EGL quirk) | ✅ |

**未发现 M15/M16 回归。未改任何游戏逻辑代码。** `:core:test` 因无代码改动按 PLAN Step 9 跳过。

---

## 3. 人工 checklist(待验)

以下为真机 UI 深度交互,worker 无法可靠自动化,需人工逐项核验。建议按序执行。
状态标注:`待验` / `通过` / `失败`。

### 3.1 基础启动

- [ ] `待验` 标题画面正常显示(SPD logo + 菜单:Start / Rankings / Badges / About / …)
- [ ] `待验` 标题界面无贴图错乱/黑块/拉伸

### 3.2 `remixed_full` alpha 内容包(M16b)

前置:Settings → 把界面切到可管理 mod 的入口,启用 `remixed_full` alpha(若需先 SAF 导入 zip,
见 3.5),重启/重载使生效。

- [ ] `待验` 启用 `remixed_full` 后游戏不崩、可返回标题
- [ ] `待验` 进普通地牢能正常生成关卡(非黑屏/非瞬崩)

### 3.3 Lua runtime 资源(M16a `spriteFile` 支持)

在已启用 `remixed_full` 的存档内验证 Lua 注入的物品/法术/怪物:

- [ ] `待验` Lua item 掉落 + 背包图标显示(`spriteFile` 生效)
- [ ] `待验` Lua spell 可拾取/装备 + 图标显示
- [ ] `待验` Lua mob 刷怪 + 精灵显示 + 可交互(可攻击/被攻击)
- [ ] `待验` Lua shop 购买流程(店主界面 + 扣金币 + 入背包)

### 3.4 mod diagnostics(M16c)

- [ ] `待验` mod 管理界面可见 diagnostics(加载错误/警告可见,无静默失败)

### 3.5 SAF 导入(若内容包需从外部 zip 导入)

- [ ] `待验` SAF 选择 zip → 导入成功 → 内容包出现在列表

### 3.6 存档/读档(fork 核心:本地存档槽)

- [ ] `待验` 游戏中存档 → 标题 → 读档继续,进度保留
- [ ] `待验`(可选,涉及本 fork save-slot feature)多命名槽位正常

---

## 4. 已知风险 / 复现注意

1. **libgdx logLevel=2 (LOG_ERROR)**:SPD 默认只记 ERROR,`Gdx.app.log`/`System.err.println`
   在 INFO 级别被过滤。要可靠调试必须用 `Gdx.app.error` 或 `Game.reportException`。
   → 本 smoke 的「无崩溃」结论基于 fatal/error 通道,而非业务 INFO 日志。
2. **EGL_BAD_DISPLAY 偶发**:设备 quirk,非本 fork 回归,不要为此改代码。
3. **设备掉线**:若 adb 不可用,应 `[BLOCKED]` 上报,不要空跑。
4. **`System.err` 误报**:grep logcat 时务必按 app pid 过滤,否则会看到 Honor/Tencent 系统应用
   (`TencentNLP` 等)的无关异常。

---

## 5. 复现命令

```bash
# 构建
./gradlew :android:assembleDebug

# 安装
adb -s 20210119085654 install -r android/build/outputs/apk/debug/android-debug.apk

# 清日志并启动
adb -s 20210119085654 logcat -c
adb -s 20210119085654 shell am start -n com.shatteredpixel.shatteredpixeldungeon.indev/com.shatteredpixel.shatteredpixeldungeon.android.AndroidLauncher

# 等待若干秒后抓 fatal
sleep 12
adb -s 20210119085654 logcat -d | grep -iE "FATAL|AndroidRuntime"

# 确认前台
adb -s 20210119085654 shell dumpsys activity activities | grep ResumedActivity
```
