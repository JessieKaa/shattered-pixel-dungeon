# PLAN: M11d — spell stub 回补(curse_item/order/possess)

## Goal
把 M10a 中零消耗 stub 的三个 spell(`curse_item`/`order`/`possess`)做成最小可用版本,让玩家施放后有实际游戏效果,而不再只是 GLog 提示。

## Context
M10a 把三个 spell 注册但标记为降级:targeting 和 onUse/onUseAt 里仅 `RPD.GLogW("...需要 API")`,spellCost=0。M11d 优先用 Lua-only 实现,必要时加 1-2 个窄 RPD API。

调研结论:
- 当前 RPD 有 `randomBackpackItem`/`itemName`/`removeBackpackItem`、`affectBuff`(白名单含 Charm/Terror/Sleep/Drowsy/...)、`damageChar`/`healChar` 等。
- 缺少:给背包物品设 cursed、把敌人真正变友方(Corruption/AllyBuff)、真正"强控睡眠"。
- **codex round-1 修正后的最小可用映射**(原 Charm/Sleep 方案有语义缺陷,见下):
  - `curse_item`:随机选一件背包物品 → 设 `cursed=true`(单向,不可解咒)
  - `order`:对目标敌人施加 **Terror**(命令敌人逃跑)。原计划用 Charm,但 SPD 的 `Charm` 只有 `Charm.object == source.id()` 时才让 `isCharmedBy` 返 true(友方判定),而白名单 applier 走 `Buff.prolong` 不设 object → Charm 实际是 no-op。source-aware charm 需第二个 RPD 函数(超 "1 窄 API" 预算),故改 Terror(真正让敌逃跑,复用现有 affectBuff)。
  - `possess`:对目标敌人施加 **MagicalSleep**(真正强控:`paralysed++` + `state=SLEEPING`)。原计划用 `Sleep`,但 `Sleep` 仅是 FlavourBuff(只覆盖 `fx` idle 特效,无任何控制语义);真正控制路径是 `MagicalSleep`。把 `MagicalSleep` 加进 `affectBuff` 白名单(非新函数,只是新暴露的 buff id,约 3 行 putFlavour-style entry)。

## Files
- `core/src/main/assets/mods/test_mod/scripts/spells/curse_item.lua` — 随机选背包物品 + 调用新 RPD API 诅咒
- `core/src/main/assets/mods/test_mod/scripts/spells/order.lua` — enemy targeting + Terror(逃跑)
- `core/src/main/assets/mods/test_mod/scripts/spells/possess.lua` — enemy targeting + MagicalSleep(强控)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/RpdApi.java` — 新增单向 `setItemCursed(heroId,index)` 窄 API + `BuffWhitelist` 加 `MagicalSleep` entry(非新函数)
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/RpdApiItemSpellTest.java` — 新增 `setItemCursed` 索引 API 测试 + curse_item.lua `onUse` 集成测试(用真实可诅咒武器,不用 Gold)
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaSpellTest.java` — 断言三个 spell 注册后 spellCost>0 + desc 不再含降级文案

## Steps
1. **RPD API 新增**(最小,单向): `setItemCursed(heroId, index)` — **codex round-1 #1**
   - 在 `RpdApi.build()` 注册 `rpd.set("setItemCursed", new SetItemCursed())`。
   - `SetItemCursed` 继承 `TwoArgFunction`(无 boolean 参 — 单向诅咒,不可解咒,避免做成 uncursing bridge)。
   - 先 `resolveHero(heroId, "setItemCursed")`;校验 `index` 为 int;按 `index-1` 取 `hero.belongings.backpack.items`;越界/非 hero/非 int → log + 返回 NIL。
   - 命中:`item.cursed = true; item.cursedKnown = true;`。返回值:`true` 若 `false→true` 改变了;`false` 若物品已 cursed(no-op);NIL 若参数非法。Lua 据 `true/false/nil` 决定 GLog 文案(诅咒了 X / 已是诅咒物品 / 无目标)。
2. **MagicalSleep 加白名单** — **codex round-1 #3**
   - `BuffWhitelist` static 块加 `ENTRIES.put("MagicalSleep", (t, amt) -> Buff.affect(t, MagicalSleep.class));` + `BUFF_CLASSES.put("MagicalSleep", MagicalSleep.class);`。`MagicalSleep` 非 FlavourBuff(extends Buff,无 duration 参,attachTo 内部自管),故不能用 putFlavour,用显式 entry。amount 被忽略(MagicalSleep 自管 STEP)。import `MagicalSleep`。
3. **curse_item**:
   - `targeting="self"`, `spellCost=5`, `useMode="mana"`, `castTime=0`
   - `onUse(heroId, spellId)`: `local idx = RPD.randomBackpackItem(heroId)`;idx 为 nil → `GLogW("背包空")`;否则 `local ok = RPD.setItemCursed(heroId, idx)`;`ok==true` → `GLog("诅咒了 " .. RPD.itemName(heroId,idx))`;`ok==false` → `GLogW("已是诅咒物品")`。
4. **order** — **codex round-1 #2(Terror 代替 Charm)**:
   - `targeting="enemy"`, `spellCost=8`, `useMode="mana"`, `castTime=1`
   - `onUseAt(heroId, spellId, cell)`: `local enemy = RPD.charAtCell(cell)`;非 nil 时 `RPD.affectBuff(enemy, "Terror", 10)` + `GLog("命令敌人逃跑")`。
5. **possess**(MagicalSleep 强控) — **codex round-1 #3 / round-2 amount 修正**:
   - `targeting="enemy"`, `spellCost=10`, `useMode="mana"`, `castTime=1`
   - `onUseAt`: `local enemy = RPD.charAtCell(cell)`;非 nil 时 `RPD.affectBuff(enemy, "MagicalSleep", 1)` + `GLog("附身敌人沉睡")`。**注**:`affectBuff` 的 `validAmount` 要求 amount>0,故传 `1`(MagicalSleep 的 applier 忽略 amount,attachTo 内部自管 STEP;传 0 会被拒导致不 attach)。
6. **测试** — **codex round-1 #4**:
   - `RpdApiItemSpellTest.setItemCursedIsOneWayCurse()`:hero 背包放一件真实可诅咒装备(`new Shortsword()` 等 MeleeWeapon;**不用 Gold** — cursed 对非装备物无玩法语义);Lua `RPD.setItemCursed(id, 1)` 后断言 `item.cursed==true && item.cursedKnown==true` 且返回 true;再调一次断言返回 false(已诅咒,no-op);越界 index 返回 NIL。
   - `RpdApiItemSpellTest.curseItemOnUseCursesViaLua()`:**集成测试** — 背包放 1 件武器,取 `LuaSpellRegistry.getTable("curse_item")` 的 `onUse`,`LuaItemCallbacks.callOpt(tbl,"onUse",heroId)`;断言该武器 `cursed==true`(证明 Lua→RPD 接线正确,不只是 Java API)。
   - `RpdApiItemSpellTest.magicalSleepAndTerrorInWhitelist()`:断言 `BuffWhitelist.lookupClass("MagicalSleep")!=null && lookupClass("Terror")!=null`;对 live mob `affectBuff(mobId,"MagicalSleep",1)` 后 `mob.buff(MagicalSleep.class)!=null` 且 `mob.paralysed>0`(强控生效;amount 传 1 因 validAmount 拒 0)。
   - `LuaSpellTest.m11dStubSpellsHaveCosts()`:断言 `curse_item`/`order`/`possess` 的 `spellCost` 为 5/8/10,`useMode=="mana"`,`targeting` 正确(self/enemy/enemy);desc 不含 `"降级"`/`"零消耗"`/`"无效"`。
7. **C3 守卫** — **codex round-1 #5**:
   - 跑 `ModToggleRegressionTest.disabled_mod_loadsZeroLuaContent`(确认仍绿);`test_mod/mod.json` `default_enabled=false` 不动;**不碰** `Generator`/vanilla spawn/loot 路径。spell 数量不变(仍 31,改的是现有 3 个 stub 的内容,非新增),故 `ModToggleRegressionTest` 的 `assertEquals(31, LuaSpellRegistry.size())` 与 `LuaSpellTest.m6dRepresentativeSpellsRegisteredByEngineInit` 无需改。
8. **codex 评审**: 用 `codex exec --sandbox read-only "评审 docs/PLAN-m11d-spell-stubs.md"`;最多 3 轮,第 3 轮仍不通过则记录 Pending Issues 继续。
9. **实施评审**: 改完代码后 `git diff main...HEAD` 给 codex;最多 3 轮。

## Acceptance
- [ ] curse_item/order/possess 不再是零消耗 stub,有实际效果
- [ ] spellCost>0(5/8/10),mana 正常扣除
- [ ] curse_item 能诅咒随机背包物品(setItemCursed 单向,设 cursed+cursedKnown)
- [ ] order 对 enemy 施加 Terror(逃跑);possess 对 enemy 施加 MagicalSleep(paralysed++ + SLEEPING)
- [ ] `./gradlew :core:test` 全绿(536 现有 + 新增)
- [ ] C3 不破(ModToggleRegressionTest 绿 + mod.json default_enabled=false 不变 + Generator 未动)

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,不 assign codex_reviewer
- 只加 1 个窄 RPD 函数(`setItemCursed`,单向);`MagicalSleep` 仅是新白名单 entry(非新函数)
- 真正"敌人变永久 ally"(Corruption)/source-aware Charm 留后续 feature
- spell 总数不变(31),改的是现有 3 个 stub 的内容
