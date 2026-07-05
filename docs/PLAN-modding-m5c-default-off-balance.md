# PLAN: M5c — 默认关闭打包 + 平衡回归(mod 目录化,让开关真正生效)

## Goal

让 `ModRegistry.isEnabled` **真正控制所有 Lua 内容加载**:`default_enabled=false` ⇒ 原版一周目零 Lua 内容注册(C3 回归基线核心)。手段 = **脚本目录化**:把现有扁平 `scripts/<type>/test_*.lua` 物理移到 `mods/test_mod/scripts/<type>/`,`LuaEngine.loadXxxScripts` 改为遍历 enabled mod 扫对应 mod 子目录。disabled mod → 该 mod 脚本不扫 → 零内容。

**M5c 是 M5 收尾**:M5a(清单+版本门)+ M5b(UI+entry)+ M5c(目录化+默认关闭+回归)三件齐备 = 玩家可开关扩展包 + 原版体验不变(ROADMAP M5 验收)。

## Context

M5a/b 后,开关机制对 **entry-mod** 生效(test_mod/init.lua 的 test_mod_item 受开关控制)。但**扁平 `scripts/<type>/test_*.lua` 仍由 `loadXxxScripts` 全量加载,不受 mod 开关控制**:

- `LuaEngine.loadItemScripts` → `loadScriptsFrom("scripts/items", ...)` 扫 `scripts/items/*.lua` 全部(test_sword/test_axe/test_dagger/test_proc_weapon/test_equip_buff),无论 test_mod 开关
- 同理 loadMobScripts(test_mob)/loadAllyScripts(test_ally)/loadHeroScripts(test_hero)/loadSpellScripts(test_spell)/loadNpcScripts(test_npc/town_portal/town_return)/loadShopScripts(test_shop)
- 结果:test_mod `default_enabled=false` 时,这些扁平脚本**仍加载** → 原版一周目仍有 Lua 内容 → **C3 回归基线守不住**

M5c 把这 13 个扁平 test 脚本移到 `mods/test_mod/scripts/<type>/`,loadXxxScripts 改扫 mod 目录。test_mod disabled → 零 Lua 内容(M5b 的 entry test_mod_item 也 skip)→ C3 守住。

**保留 M5b entry 机制**:test_mod/init.lua(test_mod_item)仍走 entry(M5b 范式),不目录化 —— 两种加载路径(entry + 目录)共存,WndModManager 开关 test_mod 同时控制两者。

**调研要点**(worker Phase 1 先做):
- `LuaEngine.loadScriptsFrom(dir, label, registrySize)`(L267):接收 dir 字符串,`listScriptNames(dir)` 列 .lua + `findResource` 编译。**M5c 复用此 helper,只改 loadXxxScripts 的 dir 来源**
- `LuaEngine.listScriptNames(dir)`:确认对 `mods/<id>/scripts/<type>/` 的 classpath 列举行为(M5a ModScanner 类似场景用 `realModsHandle()` 绕过 classpath 不确定性 —— listScriptNames 是否同样问题?worker 验证)
- `LuaEngineTest @BeforeClass initHeadless`(L30):`HeadlessApplication` + `LuaEngine.resetForTests()`,**无 mod 设置** → M5c 需在此(或 @Before)加 `ModRegistry` 初始化 + enable test_mod
- 现有所有 `Lua*Test` 类清单(LuaItemTest/LuaMobTest/LuaNpcTest/LuaShopTest/LuaAllyTest/LuaHeroTest/LuaSpellTest/LuaEngineTest/LuaLevelInjectTest 等):每个的 setup 改造点
- M5a `ModScannerTest` 的 versionCode + GameSettings prefs setup 模式(测试隔离)

## Files

- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaEngine.java`(改)— 7 个 loadXxxScripts(item/mob/ally/hero/spell/npc/shop)各加 enabled-mod 遍历层,dir 从 `"scripts/<type>"` 改为遍历 `"mods/<id>/scripts/<type>"`
- `core/src/main/assets/mods/test_mod/scripts/items/{test_sword,test_axe,test_dagger,test_proc_weapon,test_equip_buff}.lua`(新,git mv from `scripts/items/`)
- `core/src/main/assets/mods/test_mod/scripts/mobs/test_mob.lua`(mv)
- `core/src/main/assets/mods/test_mod/scripts/allies/test_ally.lua`(mv)
- `core/src/main/assets/mods/test_mod/scripts/heroes/test_hero.lua`(mv)
- `core/src/main/assets/mods/test_mod/scripts/spells/test_spell.lua`(mv)
- `core/src/main/assets/mods/test_mod/scripts/npcs/{test_npc,town_portal,town_return}.lua`(mv)
- `core/src/main/assets/mods/test_mod/scripts/shops/test_shop.lua`(mv)
- 删除 `core/src/main/assets/scripts/{items,mobs,allies,heroes,spells,npcs,shops}/test_*.lua` + town_*.lua(移动后)
- `core/src/main/assets/scripts/init.lua`(保留,legacy INIT_SCRIPT 全局,不归属 mod)
- 现有 `Lua*Test`(改)— setup 加 `ModRegistry.scan()` + `ModRegistry.setEnabled("test_mod", true)`(test_mod default_enabled=false,测试需显式 enable 才能注册 test 内容)
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModToggleRegressionTest.java`(新)— C3 回归:disabled → 各 Registry 全空;enabled → 各 Registry 有内容
- **上游改动**:0(纯 modding/ + assets 重组)

## LuaEngine loadXxxScripts 改造(定稿)

**当前**(M5b):
```java
private void loadItemScripts() {
    loadScriptsFrom(ITEMS_DIR, "Lua items", LuaItemRegistry::size);   // ITEMS_DIR = "scripts/items"
}
```

**M5c 后**:
```java
private void loadItemScripts() {
    for (ModManifest mod : ModRegistry.all()) {              // all() lazy-scan(M5a)
        if (!ModRegistry.isEnabled(mod.id)) continue;        // disabled skip
        loadScriptsFrom("mods/" + mod.id + "/scripts/items", "Lua items (" + mod.id + ")", LuaItemRegistry::size);
    }
}
```

7 个 loadXxxScripts 同此模式(type 替换)。`loadScriptsFrom` helper **不变**(接收 dir 字符串,listScriptNames + findResource + compile)。

**关键决策**:
- **复用 loadScriptsFrom**:helper 接收任意 dir 字符串,M5c 只改调用方的 dir 来源(从固定 `scripts/<type>` 改为遍历 `mods/<id>/scripts/<type>`)。零 helper 改动。
- **loadXxxScripts 顺序**:外层 mod 循环,内层 loadScriptsFrom(扫目录)。mod 间顺序按 ModRegistry.all()(M5a scan 排序)。
- **空目录容错**:loadScriptsFrom 已处理"目录无 .lua"(log + return,M5c 后 mod 无某类型脚本时正常)。
- **ITEMS_DIR 等常量**:可保留(legacy 注释)或删。M5c 后不再用 `scripts/<type>` 路径。

## 脚本目录化(定稿)

**移动清单**(13 脚本,git mv 保留历史):
| from | to |
|---|---|
| `scripts/items/test_{sword,axe,dagger,proc_weapon,equip_buff}.lua` | `mods/test_mod/scripts/items/` |
| `scripts/mobs/test_mob.lua` | `mods/test_mod/scripts/mobs/` |
| `scripts/allies/test_ally.lua` | `mods/test_mod/scripts/allies/` |
| `scripts/heroes/test_hero.lua` | `mods/test_mod/scripts/heroes/` |
| `scripts/spells/test_spell.lua` | `mods/test_mod/scripts/spells/` |
| `scripts/npcs/{test_npc,town_portal,town_return}.lua` | `mods/test_mod/scripts/npcs/` |
| `scripts/shops/test_shop.lua` | `mods/test_mod/scripts/shops/` |

**保留**:
- `scripts/init.lua`(legacy INIT_SCRIPT,LuaEngine.init 直接加载,不归属 mod)
- `mods/test_mod/init.lua`(M5b entry,register test_mod_item —— 保留,M5b 范式示范)
- `mods/test_mod/mod.json`(已含 entry + default_enabled=false)

**test_mod_item 仍走 entry**(M5b,不目录化):它由 `mods/test_mod/init.lua` register,不在 scripts/items/。test_mod disabled → entry skip(M5b loadModEntryScripts)+ 目录 skip(M5c)→ test_mod_item 不注册。

## 测试 setup 改造(定稿)

**问题**:M5c 后 test_sword 等归 test_mod(default_enabled=false)。现有 Lua*Test(如 `LuaEngineTest.initRegistersTestSwordFromAssets`)直接 `LuaEngine.init()` 后断言 test_sword 注册 —— M5c 后会**挂**(test_mod disabled → test_sword 不加载)。

**改造**:所有 Lua*Test 的 setup(@BeforeClass 或 @Before)加:
```java
Game.versionCode = 896;                          // 沿 M5a ModScannerTest 模式(若尚未设)
ModRegistry.scan();                              // 扫到 test_mod(version 匹配)
ModRegistry.setEnabled("test_mod", true);        // 显式 enable(覆盖 default_enabled=false)
// 然后 LuaEngine.init() / resetForTests() ...
```

**适用测试类**(worker Phase 1 列全):LuaEngineTest / LuaItemTest / LuaMobTest / LuaAllyTest / LuaHeroTest / LuaSpellTest / LuaNpcTest / LuaShopTest / LuaLevelInjectTest(M4d,用 test_safezone + town NPC)/ LuaModEntryTest(M5b)/ 其他引用 test_* 注册的测试。

**隔离**:沿 M5a ModScannerTest 的 GameSettings fake-prefs 注入(避免 mod_enabled_* 跨测试污染)。worker 评估 @BeforeClass(一次)vs @Before(每测)—— ModRegistry scan 幂等(M5a),setEnabled 写 prefs,@Before 更稳。

## C3 回归测试(定稿,新建 ModToggleRegressionTest)

断言必须**精确到 ID**,不能只看 `size() > 0`(否则 test_mod_item 单独注册就能让 items size>0,掩盖 items 目录扫描失败)。每个 @Before 调 `ModTestSupport.enableTestMod()` + `ModTestSupport.resetLuaState()`(见下)。

```
@test disabled_mod_loadsZeroLuaContent:
  enableTestMod(); ModRegistry.setEnabled("test_mod", false);   # 覆盖 enableTestMod 的 true
  LuaEngine.init();
  # 7 个 Registry size 全 0
  assertEquals(0, LuaItemRegistry.size());  assertEquals(0, LuaMobRegistry.size());
  assertEquals(0, LuaAllyRegistry.size()); assertEquals(0, LuaHeroRegistry.size());
  assertEquals(0, LuaSpellRegistry.size());assertEquals(0, LuaNpcRegistry.size());
  assertEquals(0, LuaShopRegistry.size());
  # 代表性 ID 全部 contains==false(覆盖 entry + 各目录)
  for id in [test_mod_item, test_sword, test_axe, test_dagger, test_proc_weapon, test_equip_buff,
             test_mob, test_ally, test_hero, test_spell, test_npc, town_portal, town_return, test_shop]:
      assertFalse(相关 Registry.contains(id))

@test enabled_mod_loadsAllTestContent:
  enableTestMod();   # test_mod=true
  LuaEngine.init();
  # 精确 ID(目录脚本 + entry)
  assertTrue(LuaItemRegistry.contains("test_sword"));     # + test_axe/test_dagger/test_proc_weapon/test_equip_buff/test_mod_item
  assertTrue(LuaMobRegistry.contains("test_mob"));
  assertTrue(LuaAllyRegistry.contains("test_ally"));
  assertTrue(LuaHeroRegistry.contains("test_hero"));
  assertTrue(LuaSpellRegistry.contains("test_spell"));
  assertTrue(LuaNpcRegistry.contains("test_npc"));  assertTrue(LuaNpcRegistry.contains("town_portal"));  assertTrue(LuaNpcRegistry.contains("town_return"));
  assertTrue(LuaShopRegistry.contains("test_shop"));
  # size 精确(items = 5 目录脚本 + 1 entry = 6;其余各 1,NPC=3)
  assertEquals(6, LuaItemRegistry.size());
  assertEquals(3, LuaNpcRegistry.size());

@test toggle_idempotent:
  enableTestMod(); LuaEngine.init();  → 记 items size N1, npc size N3
  resetLuaState(); LuaEngine.init(); again(同 enabled=true)→ size N1/N3 不变(不重复注册)
```

## Phase 1 调研结论(worker 实测,2026-07-06)

### R1 结论:listScriptNames 对 mod 子目录行为 = 低风险,无需 realModsHandle 改造

`LuaEngine.listScriptNames(dir)`(L296)两阶段:
- **Stage 1**(主路径,tests/desktop 生效):`getClassLoader().getResource(dir)` → 若 `file:` 协议,`new File(url.toURI()).listFiles()` 真实 FS 列举。gradle 把 `core/src/main/assets` 以**真实目录**放 classpath,故 `getResource("mods/test_mod/scripts/items")` 与现有 `getResource("scripts/items")` **同机制**返回 file URL → `listFiles()` 列 .lua。**子目录深度不影响行为**。
- **Stage 2**(fallback,Android/packaged):`Gdx.files.internal(dir).list()`。

M5a ModScanner 的 "classpath listing unreliable" 注释指的是 `Gdx.files.internal("mods").list()`(libgdx ClasspathFileHandle),**不是** `getResource+File.listFiles`。后者是真实 IO,tests 稳定。**结论**:loadXxxScripts 复用 loadScriptsFrom(内含 listScriptNames),无需沿 realModsHandle 改 listScriptNames。完整验证靠 gradle test 跑通。

`findResource(path)`(L346,`Gdx.files.internal(path).read()`)对 `mods/test_mod/scripts/<type>/<name>.lua` 已被 M5b entry 加载(`mods/test_mod/init.lua`)证明可达。

### DIR 常量(L33-39):M5c 后 dead,删除

`ITEMS_DIR` 等 7 常量仅在 7 个 loadXxxScripts 引用。M5c 改为 mod 遍历后无引用 → 删除。同步更新 LuaEngine 类 javadoc + 7 方法 javadoc 里 `scripts/<type>/*.lua` 路径表述 → `mods/<id>/scripts/<type>/*.lua`。

### 测试改造清单(精确,10 个 Lua*Test + 1 个 assertion 修正)

**需加 enable-test_mod setup 的 10 个测试类**(均调用 `LuaEngine.init()` 依赖 test_* 脚本加载,LuaLevelInjectTest 含一个 init-based town NPC 测试):

| 测试类 | 现状 setup | 改造 |
|---|---|---|
| `LuaEngineTest` | @BeforeClass(reset) | +versionCode=896/+Before(enableTestMod) |
| `LuaItemCallbackTest` | @BeforeClass(reset) | 同上 |
| `GeneratorLuaItemTest` | @BeforeClass(reset) | 同上 |
| `LuaMobTest` | @BeforeClass(reset)+helper reset | 同上 |
| `LuaAllyTest` | @BeforeClass(reset)+helper reset | 同上 |
| `LuaHeroTest` | @BeforeClass(reset)+helper reset | 同上 |
| `LuaSpellTest` | @BeforeClass(reset) | 同上 |
| `LuaNpcTest` | @BeforeClass(reset)+helper reset | 同上 |
| `LuaShopTest` | @BeforeClass(reset)+@Before(resetGold) | +versionCode+@Before(enableTestMod)(JUnit 多 @Before OK) |
| `LuaLevelInjectTest` | @BeforeClass(reset)+@Before(resetPerTest 清 NPC) | 同上(仅 L275 init 测试需 test_mod;手动 register 测试不受影响) |

**仅 assertion 修正(1 个)**:`LuaModEntryTest.entry_disabled_skipsTestModItem`(L105-106)断言 `test_sword` 在 test_mod disabled 时仍注册 —— M5c 后 test_sword 归 test_mod,disabled 时不注册 → 断言改为 `assertFalse`(test_sword 也 mod-gated)。`entry_enabled_loadsTestModItem` 的 `test_sword` 断言不变(enabled 时仍注册,改从 mod 目录)。同时升级 LuaModEntryTest 复用新 helper(可选,降重复)。

**无需改动(3 个)**:
- `DataDrivenLevelTest`(versionCode=802,测 `test_safezone.json` level JSON,无 LuaEngine.init,`mods/levels/` 不移动)
- `LuaSandboxTest`(测 sandbox,无 init/test_ 依赖)
- `ModScannerTest`(M5a,无 LuaEngine.init)

### 共享测试 helper(新,降 10×80 行重复)

10 个 Lua*Test 各自抄 FakePreferences(~80 行)+ realModsHandle + scanDir+setEnabled 是 ~800 行 near-identical boilerplate(M5a/M5b 各抄 1 份尚可接受,10 份过度)。新建:

`core/src/test/java/.../modding/ModTestSupport.java`(package-private final):
```java
final class ModTestSupport {
    static FileHandle realModsHandle() throws Exception { ... }      // 复用 ModScannerTest 模式
    /** Fresh FakePreferences + re-scan real mods + enable test_mod. @Before 调用。 */
    static void enableTestMod() throws Exception {
        GameSettings.set(new FakePreferences());
        ModRegistry.resetForTests();
        ModRegistry.scanDir(realModsHandle());
        ModRegistry.setEnabled("test_mod", true);
    }
    /** 清 7 个 Lua Registry + drop LuaEngine singleton,使下次 init() 重扫。
     *  必须在 @Before 调,否则静态 Registry 残留让 disabled/idempotent 断言失效(codex round-1 issue 3)。 */
    static void resetLuaState() {
        LuaItemRegistry.clear();
        LuaMobRegistry.clear();
        LuaAllyRegistry.clear();
        LuaHeroRegistry.clear();
        LuaSpellRegistry.clear();
        LuaNpcRegistry.clear();
        LuaShopRegistry.clear();
        LuaEngine.resetForTests();
    }
    static final class FakePreferences implements com.badlogic.gdx.Preferences { ... }  // 沿用 M5a 实现
}
```

每个 Lua*Test 改造模式:
```java
private static int savedVersionCode;
@BeforeClass public static void initHeadless() {
    application = new HeadlessApplication(new ApplicationAdapter() {}, config);
    savedVersionCode = Game.versionCode;
    Game.versionCode = 896;                       // 版本门放行 test_mod
    // (不再在此 clear/reset;移到 @Before 以保证每测干净)
}
@Before public void resetState() throws Exception {
    ModTestSupport.enableTestMod();               // fresh prefs + scan + enable test_mod
    ModTestSupport.resetLuaState();               // 清 7 Registry + drop engine singleton
}
@AfterClass public static void shutdown() {
    Game.versionCode = savedVersionCode;          // 还原
    GameSettings.set(new ModTestSupport.FakePreferences());  // 干净收尾
    ...
}
```

**为什么 @Before 而非 @BeforeClass**:PLAN 原文 "@Before 更稳"。setEnabled 写 prefs,@Before 每次 fresh FakePreferences → 测试间零污染(M5a/M5b 既定模式)。LuaEngine.init 幂等 + resetLuaState 强制重 init。**resetLuaState 是关键**:disabled/idempotent 测试要求 Registry 真清空,否则上一测的静态残留让 size==0 断言无意义。

**现有 M5a/M5b 测试**:不动 ModScannerTest(它测的就是 ModRegistry 本身,setup 语义不同)。LuaModEntryTest 迁移到 helper(删自身 FakePreferences,改 @Before 用 `enableTestMod()+resetLuaState()`)—— 它本就是该模式的源头,迁移降重复且统一;同时改 disabled 测试的 test_sword 断言(`assertFalse`,M5c 后 test_sword 也 mod-gated)。

## Steps

### 1. 调研(worker 先做,产出笔记)

- 读 `LuaEngine.loadScriptsFrom`(L267)+ `listScriptNames` + `findResource`:确认对 `mods/<id>/scripts/<type>/` 的列举行为(classpath?M5a ModScanner 的 realModsHandle 模式是否需要在此复用)
- 列全所有 `Lua*Test` + 其他引用 test_*/register 的测试(grep `test_sword|test_mob|LuaItemRegistry|LuaEngine.init` in test/):setup 改造清单
- 读 M5a `ModScannerTest` setup(GameSettings fake-prefs + versionCode 模式):测试隔离范式
- 读 M5b `LuaModEntryTest` setup:test_mod entry 测试如何 enable(参考)
- 确认 `mods/test_mod/scripts/<type>/` 移动后 classpath 可达(listScriptNames 能扫到)
- **产出**:loadXxxScripts 改造方案(7 方法)+ 测试 setup 改造清单(每类)+ listScriptNames 对 mod 子目录行为确认

### 2. 脚本移动(git mv)

- 13 个 test/town 脚本从 `scripts/<type>/` 移到 `mods/test_mod/scripts/<type>/`(git mv 保留历史)
- 保留 scripts/init.lua + mods/test_mod/init.lua + mods/test_mod/mod.json
- 删除空的 `scripts/{items,mobs,allies,heroes,spells,npcs,shops}/` 目录(若移动后空)

### 3. LuaEngine loadXxxScripts 改造

- 7 个方法(item/mob/ally/hero/spell/npc/shop)各加 enabled-mod 遍历层
- dir 改为 `"mods/" + mod.id + "/scripts/<type>"`
- loadScriptsFrom helper 不变
- 评估 ITEMS_DIR 等常量是否删(M5c 后无用)

### 4. 测试 setup 改造

- 所有 Lua*Test + 引用 test_* 注册的测试:setup 加 versionCode + ModRegistry.scan + setEnabled("test_mod", true)
- 沿 M5a ModScannerTest 的 GameSettings fake-prefs 隔离模式
- @Before vs @BeforeClass:worker 定(ModRegistry scan 幂等,setEnabled 写 prefs;@Before 更稳)

### 5. C3 回归测试(ModToggleRegressionTest)

- disabled → 各 Registry 全空(item/mob/ally/hero/spell/npc/shop + entry test_mod_item)
- enabled → 各 Registry 有内容
- toggle idempotent(不重复注册)

### 6. codex 评审 + 回归

- `codex exec --sandbox read-only`(沿 M4d/M5a/M5b workaround)
- `./gradlew :core:test` 全过(M5b 的 213 + M5c 改造 + 新增 ModToggleRegressionTest;**重点:零回归**,所有现有 Lua*Test setup 改造后仍 pass)
- C3 基线:disabled 时零 Lua 内容 → 原版一周目不受影响

## Acceptance

- ✅ 13 个扁平 test/town 脚本移到 `mods/test_mod/scripts/<type>/`(scripts/<type>/ 清空)
- ✅ LuaEngine.loadXxxScripts 7 方法按 enabled-mod 遍历扫 mod 目录
- ✅ **test_mod default_enabled=false → LuaEngine.init → 各 Registry 全空**(C3 回归核心)
- ✅ test_mod enabled → 各 Registry 有 test 内容 + entry test_mod_item 注册
- ✅ 现有所有 Lua*Test setup 改造后全过(零回归)
- ✅ ModToggleRegressionTest ≥3 测试(disabled 空 / enabled 有 / idempotent)
- ✅ 0 上游改动(纯 modding/ + assets 重组)
- ✅ codex_reviewer APPROVED

## 风险 + 注意

- **R1: listScriptNames 对 mod 子目录的 classpath 列举**。M5a ModScanner 遇过 classpath 目录列举不确定性(用 realModsHandle 绕过)。listScriptNames 扫 `mods/test_mod/scripts/items/` 可能同样问题。**对策**:worker Phase 1 验证 listScriptNames 对 mod 子目录行为;若 classpath 列举不稳,沿 realModsHandle 模式或改用 Gdx.files.internal().list()(M5a ModScanner.scanDir 已验可工作)。
- **R2: 测试 setup 改造量大**。13+ 测试类需加 enable test_mod。机械但易遗漏 → 漏一个就测试挂。**对策**:worker grep 全清单 + 改造后全量 `:core:test` 验证。codex round-1 重点查遗漏。
- **R3: loadXxxScripts 7 方法改造**。面广但模式一致(加 mod 遍历层)。codex 评审确认每个方法都改 + dir 正确。
- **R4: scripts/init.lua(legacy INIT_SCRIPT)**。保留在 scripts/,不归属 mod。LuaEngine.init 直接加载(L107,INIT_SCRIPT 常量)。M5c 不动它。若 init.lua 调 register_*,M5c 后仍生效(全局)—— worker 确认 init.lua 内容(若它 register 测试内容,需评估)。
- **R5: M4d town_portal/town_return 归 test_mod**。移动后,test_mod disabled → town_portal NPC 脚本不加载 → LuaNpcRegistry 无 "town_portal" → `injectLevelNpcs` spawn 时 `LuaNpcRegistry.create("town_portal")` 返 null → spawn skip(graceful,不崩)。这是正确的(mod 关了无传送 NPC)。worker 确认 injectLevelNpcs 的 null 容错(M4d PLAN 已有 `if (npc == null) return;`)。
- **R6: M5b LuaModEntryTest**。它依赖 test_mod entry(test_mod_item)。M5c 不动 entry 机制,test_mod_item 仍走 entry。LuaModEntryTest setup 可能需调整(若它假设 test_mod 默认 enabled)—— worker 检查。
- **R7: mod 间脚本名冲突**。两个 mod 声明同名脚本?MVP 不防(mod id 隔离目录,物理不冲突)。codex round-1 可提。
- **R8: C2 包隔离**。M5c 只动 assets + LuaEngine(fork 文件)。0 上游。test_mod 目录在 assets/mods/(C2 OK)。

## 参考

- M5a `ModScanner.scanDir`(Gdx.files.internal 列举模式,绕过 classpath 不确定性)
- M5a `ModScannerTest`(versionCode + GameSettings fake-prefs 测试隔离)
- M5b `LuaEngine.loadModEntryScripts`(entry 加载,M5c 保留)
- M5b `LuaModEntryTest`(test_mod entry 测试 setup 参考)
- M0-M4 `LuaEngine.loadScriptsFrom`(L267 helper,复用)+ `loadItemScripts/loadMobScripts/...`(L154+,7 方法改造点)+ DIR 常量(L33-39)
- `LuaEngineTest.initHeadless`(L30 @BeforeClass,setup 改造起点)
- M4d `LuaLevelInjectTest`(用 test_safezone + town NPC,M5c setup 改造点之一)
- modding 范式 + 约束 C1-C5 + CLAUDE.md
