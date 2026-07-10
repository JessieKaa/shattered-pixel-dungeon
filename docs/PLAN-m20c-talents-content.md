# PLAN — M20c: remixed_full Lua talents 内容补全

## Goal
为 `remixed_full` mod 补全 **talents** 内容类型(当前 `scripts/talents/` 缺失)。交付 2 个 remixed 风格自定义天赋,正确声明 tier(1 + 2)+ 每个天赋一个 `on_upgrade` 回调。

## Context
- LuaEngine 的 M7e loader 自动扫描 `mods/remixed_full/scripts/talents/*.lua` → **只加文件,不碰 entry.lua**。
- `register_talent` 定义自定义天赋:tier(1-4)+ 回调(`on_upgrade`)。
- `RemixedFullPackTest` 计数不含 talent → **不要改它**(M20g 独占)。
- test_mod PoC:`scripts/talents/hearty_meal.lua`、`iron_will.lua`、`mod_example.lua`、`mod_tier34.lua`(覆盖 tier1-4 + 各类回调)。
- 参考已有测试 `LuaTalentRegistryTest.java` / `LuaTalentOverrideTest.java` 学注册与 tier 断言。

## 引擎约束(Worker 阶段1 调研事实,决定 Files 选择)

`register_talent` 的硬约束(来自 `LuaEngine.RegisterTalentFunction` + `LuaTalentRegistry.register`):

1. **id 必须是 Talent.java 预声明的 `MOD_` enum 槽位**,Lua 不能运行时 mint 新 id。全局只有 4 个槽位:
   - `MOD_EXAMPLE_TALENT(219, cap=2)` — tier 1/2,`class` key
   - `MOD_SECOND_TALENT(220, cap=2)` — tier 1/2,`class` key
   - `MOD_TIER3_TALENT(221, cap=3)` — tier 3,`subclass` key
   - `MOD_TIER4_TALENT(222, cap=4)` — tier 4,`armor_ability` key
2. **tier↔cap 绑定**:cap=2 槽位只能 tier 1/2;cap=3 只能 tier 3;cap=4 只能 tier 4。
3. **tier↔key 互斥**:tier 1/2 只能带 `class`(不能 subclass/armor_ability)。
4. **name 必填**(MOD_ 槽位无 `.title` properties key,否则 UI 渲染 `!!!MOD_*.title!!!`)。
5. **registry 是全局静态 upsert**(key=Talent enum)。多 mod 同时 enable 注册同一 enum → last-call-wins。但 test_mod / demo_m58 / regression_demo 都 `default_enabled=false`,且本 feature 测试照搬 `RemixedFullPackTest.enableRemixedFull()` 显式 disable 它们 → remixed_full 独占这两个槽位,无冲突。
6. **回调只有 `on_upgrade`** 有 Java 单点 hook(`Talent.onTalentUpgraded` → `LuaTalentRegistry.dispatchTalentUpgraded`),签名 `(heroId:int, points:int)`。`onLevel`/`onAct`/`onDamage` 等无 hook,不实现。
7. **entry.lua 无需改**:LuaEngine 自动 enumerate `scripts/talents/*.lua`,talent 注册不走 entry。
8. **PLAN 要 tier1+tier2 两个天赋** → 正好分占 `MOD_EXAMPLE_TALENT`(tier1)+ `MOD_SECOND_TALENT`(tier2),引擎完全支持(`LuaTalentRegistryTest.secondSlot_injectsIntoTier1` 证明 tier1 合法,cap=2 槽位 tier1/tier2 均可)。

## Files(细化)

1. **新增 `core/src/main/assets/mods/remixed_full/scripts/talents/rf_initiative.lua`**
   - 先攻/速度向,ROGUE tier1,激活 `MOD_EXAMPLE_TALENT` 槽位。
   - `id="MOD_EXAMPLE_TALENT", tier=1, class="ROGUE", name="先机(Lua)", maxPoints=2`。
   - `on_upgrade = function(heroId, points) RPD.giveItem(heroId, "remixed_full_rusty_coin", points) end`(升级送锈币,盗贼轻量向材料)。

2. **新增 `core/src/main/assets/mods/remixed_full/scripts/talents/rf_thick_skin.lua`**
   - 减伤/厚皮向,WARRIOR tier2,激活 `MOD_SECOND_TALENT` 槽位。
   - `id="MOD_SECOND_TALENT", tier=2, class="WARRIOR", name="厚皮(Lua)", maxPoints=2`。
   - `on_upgrade = function(heroId, points) RPD.giveItem(heroId, "remixed_full_dark_gold", points) end`(升级送暗金,护甲硬派材料)。

3. **新增 `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/RemixedFullTalentContentTest.java`**
   - 同包 `modding`,可访问 `LuaTalentRegistry.defs()` / `ModTalentDef.tier`(package-private)。
   - 照搬 `RemixedFullPackTest` 的 `@BeforeClass`(headless + Game.versionCode=896 + Game.version="test")/`@AfterClass`/`@Before`(GameSettings fake prefs + ModRegistry.resetForTests + ModTestSupport.resetLuaState + BalanceConfig.resetToDefaults + Generator.setLuaItemProbability(0,0))/`enableRemixedFull()`(scanDir + setEnabled remixed_full true + disable remished_lite/test_mod/regression_demo)。`resetLuaState()` 已含 `LuaTalentRegistry.clear()`。
   - 4 个 @Test:
     - `enabled_registersBothModTalents`:init → `isKnownModTalent("MOD_EXAMPLE_TALENT")` + `isKnownModTalent("MOD_SECOND_TALENT")` + `size() >= 2`。
     - `enabled_injectsIntoCorrectClassAndTier`:`initClassTalents(ROGUE)` → tier0(idx0)含 `MOD_EXAMPLE_TALENT`;`initClassTalents(WARRIOR)` → tier1(idx1)含 `MOD_SECOND_TALENT`;且 ROGUE tier1 不含 `MOD_SECOND_TALENT`(互斥)。
     - `enabled_talentsDeclareCorrectTier`:遍历 `LuaTalentRegistry.defs()`,断言 `MOD_EXAMPLE_TALENT` def.tier==1、`MOD_SECOND_TALENT` def.tier==2。
     - `enabled_onUpgrade_deliversRemixedItem`:newHero(ROGUE)+Dungeon.hero+`initClassTalents(ROGUE)`+`upgradeTalent(MOD_EXAMPLE_TALENT)` → 背包含 `LuaMaterial`(rusty_coin);同理 newHero(WARRIOR)+`upgradeTalent(MOD_SECOND_TALENT)` → 背包含 LuaMaterial(dark_gold)。`newHero` 照搬 `LuaTalentRegistryTest.newHero`(设 HT/HP/Belongings + Actor.add),try/finally 还原 Dungeon.hero + Actor.remove。

## Steps(可执行粒度)

1. ✅ 读 PoC + `LuaTalentRegistry.java` + `RegisterTalentFunction` + `RemixedFullPackTest` + `ModTestSupport`,确认 schema/约束/enable 模式(已做,见上"引擎约束"节)。
2. 写 `rf_initiative.lua` + `rf_thick_skin.lua`(字段见 Files)。
3. 写 `RemixedFullTalentContentTest.java`(4 用例,见 Files)。
4. `./gradlew :core:test` 绿(已知 flaky:GeneratorLuaItemTest/GeneratorLuaSpellTest 概率断言,单独重跑 `--tests` 即过,非本 feature 引入)。
5. commit:按文件名 stage(绝不 `-A`,绝不 `.claude/`),message `feat(m20c-talents-content): add 2 remixed talents + content test`。

## Acceptance
- [ ] `scripts/talents/` 下 2 个 `.lua`(rf_initiative tier1 + rf_thick_skin tier2),注册成功且 tier 正确。
- [ ] `RemixedFullTalentContentTest` 4 用例全通过。
- [ ] `:core:test` 全绿(扣除已知 flaky)。
- [ ] 未改 `entry.lua` / `RemixedFullPackTest.java` / `Talent.java`。

## Constraints(强制)
- 只在自己 worktree 改动。
- 绝不改 `entry.lua`(M20f 独占)、`RemixedFullPackTest.java`(M20g 独占)。
- 绝不改 `Talent.java`(MOD_ 槽位已声明够用,新增 enum 会动上游文件面)。
- 绝不 `git add -A` / commit `.claude/` / force push / reset --hard。

## Pending Issues
(阶段1/2 第3次未决 issues 追加于此,目前无)

## 评审协议
完成 + 测试绿后,用 **`assign("codex_reviewer", ...)`** 评审(先 PLAN 再实现)。严禁直接 codex-cli。
- assign 失败/静默 → 跳过,回报说明,dispatcher 决定是否亲审。
- 复用同一 reviewer terminal:首次 assign 创建,之后 send_message。

## 回报协议
`send_message`(无 receiver_id)回报 caller:`[DONE]`/`[BLOCKED]` + commit hash + reviewer terminal_id/轮数(或跳过说明) + 测试结果 + 文件清单。
