# Shattered Pixel Dungeon (fork with local save slots)

This is a fork of Evan Debenham's Shattered Pixel Dungeon that adds a **local save slot** feature (save-scum style, à la Remixed Dungeon). Not for upstream PR — design intent is to diverge from upstream's permadeath stance.

## Project facts

- Package: `com.shatteredpixel.shatteredpixeldungeon` (release) / `.indev` suffix (debug)
- Version: 3.3.8 / versionCode 896
- Engine: libgdx 1.14.0, Java 11
- Android: minSdk 21, target/compile 36
- Modules: `SPD-classes`(引擎补丁)、`core`(主游戏逻辑)、`android`/`desktop`/`ios`、`services/{updates,news}`

## Build

```bash
# Desktop debug (fastest iteration)
./gradlew :desktop:debug

# Android debug (with .indev suffix, debug updates/news services)
./gradlew :android:assembleDebug

# Android release (signed, R8 minify on)
./gradlew :android:assembleRelease
```

- Debug APK: `android/build/outputs/apk/debug/android-debug.apk`
- Release APK: `android/build/outputs/apk/release/android-release.apk`
- Release 签名配置: `android/keystore.properties` 引用 `/home/yujk01/.android/spd-release.jks`(测试 keystore,alias `spd-release`)。文件丢失就回退到 unsigned release。
- `minifyEnabled true` 在 release 上对反射(Bundle 反序列化枚举)有风险,新增 keep 类时检查 `android/proguard-rules.pro`

## Install / on-device verify

测试设备: `adb -s 20210119085654`(Honor TYH201H, MIUI)。已知 EGL_BAD_DISPLAY 偶发,与代码无关。

```bash
adb -s 20210119085654 install -r android/build/outputs/apk/debug/android-debug.apk
adb -s 20210119085654 logcat -c
# 触发后:
adb -s 20210119085654 logcat -d | grep -iE "shattered|System.err"
```

注意:SPD 默认 libgdx logLevel=2(LOG_ERROR),`Gdx.app.log`/`System.err.println` 在 INFO 级别会被过滤。要可靠调试用 `Gdx.app.error` 或 `Game.reportException`。

## Local save slot feature (the fork's reason)

- 设计点 C(用户已确认): 多命名槽位 + 死亡读档 + daily/dailyReplay 禁用 + 跨平台复用 libgdx FileHandle
- 槽位路径: `save_slots/{name}/`,与 `game{1..6}` 完全隔离,避免 Rankings/Badges 缓存错乱
- 版本校验: `meta.version != Game.versionCode` 直接拒绝加载,不做迁移
- **`WndResurrect.instance` 必须在读档成功后清空**(`SaveSlotService.loadFromSlot` 末尾已做),否则后续死亡永远不进 Rankings

### 代码组织(expanded 风格子包)

所有 fork 代码集中在 `core/.../saveslot/` 子包,与上游根包/windows 子包解耦:

- `saveslot/SaveSlotService.java` — 服务层 + 死亡读档 hook(`interceptDeath`)+ 菜单注入(`addMenuButtons`)
- `saveslot/SaveSlotMeta.java` — 槽位元数据(从 `SaveSlot.Meta` 提取为顶层类)
- `saveslot/WndSaveSlotSelect.java` — UI 窗口(自带 `txt()` 硬编码 ZH/EN fallback,绕过 Messages.get 跨包失效问题)

上游文件改动面收敛到 4 个(每个都是单点 hook):

- `actors/hero/Hero.java` — `die()` 首行 `if (SaveSlotService.interceptDeath(this, cause)) return;` + `public transient boolean saveSlotPrompted` 字段
- `windows/WndGame.java` — 构造函数末尾 `SaveSlotService.addMenuButtons(this);`(`addButton` 改为 `public`)
- `SPD-classes/.../utils/FileUtils.java` — 新增 `copyDir`(分隔注释标注)
- `android/build.gradle` — keystore 配置(本地用,见下)

### 死亡读档实现:递归 re-entry(非 `proceedOriginalDie`)

旧实现把上游 die() 200+ 行代码搬到 `Hero.proceedOriginalDie()`,v3.3.9 上游改 die() 时会无声 drift。新实现利用 `saveSlotPrompted` 标志位递归重入:

1. 第一次 die():`interceptDeath` 看到 `saveSlotPrompted=false` → 置位 + `WndResurrect.instance = new Object()`(占位抑制 Rankings 提交)+ 渲染线程弹窗 → return
2. 用户取消读档 → onCancel 回调清 `WndResurrect.instance` → 再次调用 `hero.die(cause)`
3. 第二次 die():`interceptDeath` 看到 `saveSlotPrompted=true` → 返回 false → 上游 die() 原位执行(ankh/reallyDie/Rankings 链路 100% 不变)

**优点**:上游 die() body 一字不动,merge 冲突最小。
**注意**:`saveSlotPrompted` 是 `transient`,不参与 Bundle 序列化;每次新 Hero 实例自然重置。

### Ankh 交互(intentional,非回归)

英雄身上有 ankh 时取消读档,会进入上游 ankh 流程:

- **未祝福 ankh**:走 `WndResurrect` 复活窗口(让玩家选择保留哪些物品)
- **祝福 ankh**:上游自动消耗,原地半血复活

这是因为 `interceptDeath` 取消后,上游 die() 会照常扫 ankh。设计上等价于"先问你要不要 SL,不要就按上游规则死"。

### i18n 约定

- `WndSaveSlotSelect.txt()` 用硬编码 ZH/EN map 作为唯一文案源(Messages.get 对 fork 类失效,见下)
- `SaveSlotService.addMenuButtons` 的按钮 label 也用硬编码(不依赖 properties 文件)
- 上游 `windows*.properties` 不再有任何 `wndsaveslotselect.*` / `wndgame.save_slot` / `wndgame.load_slot` 键(已清理)

### 已知未解根因

**SPD-Messages 体系下,跨包查询 `Messages.get(WndSaveSlotSelect.class, ...)` 找不到键**(返回 `!!!NO TEXT FOUND!!!`)。怀疑 libgdx I18NBundle 缓存或加载时序问题,但未证实。`WndSaveSlotSelect.txt()` 用硬编码 fallback 兜底已工作正常,新加 fork 文案时**直接在 `TXT_ZH`/`TXT_EN` map 里加**,不要假设 `Messages.get` 一定可用。

## 不要做

- 不要让 daily/dailyReplay 模式下能存读档(`SaveSlotService.isSaveAllowed()` 是唯一守卫)
- 不要在 Rankings 提交链路上忘记检查 `WndResurrect.instance`
- 不要把槽位路径与 `game{1..6}` 混用,会污染排行榜与 GamesInProgress 缓存
- 不要为了"清理"删除 `Hero.saveSlotPrompted` 字段,死亡读档递归 re-entry 依赖它防止反复弹窗
- 不要把 fork 代码散回上游根包/windows 子包;新增功能加到 `saveslot/` 子包内

## 上游参考

- 主线 SPD 仓库:`https://github.com/00-Evan/shattered-pixel-dungeon`(本 fork 不回流)
- 同工作区下的姐妹项目:`../remixed-dungeon/`(NYRDS,Lua modding 方向)。两者包名/构建系统不可混用。
