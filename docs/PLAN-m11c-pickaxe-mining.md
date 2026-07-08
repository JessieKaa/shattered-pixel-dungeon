# PLAN: M11c — pickaxe/mining + terrain API

## Goal
让 `remixed_pickaxe` 能执行挖矿 action:破坏可挖掘地形(WALL/WALL_DECO/DOOR/BARRICADE)并掉落物品(DarkGold/MysteryMeat),同时给 LuaItem 增加通用 `actions/execute/defaultAction` 支持和 per-instance `state` 持久化。

## Context
M10a 把 `remixed_pickaxe.lua` 注册为纯武器占位,降级清单记录:ACMINE 动作 + terrain 改写 + 掉落 + per-instance `bloodStained` 状态 + glowing。

调研结论:
- `LuaItem` 当前不支持 `actions/execute/defaultAction`;`LuaMaterial` 已实现该模式(M11b)。
- SPD 无通用 `dig` 方法;挖掘靠各别类实现。
- `Terrain.flags` 有 SOLID;地形改写用 `Level.set(cell, terrain)`;掉落用 `Dungeon.level.drop(item, cell)`。
- LuaItem 当前无 per-instance state,需参考 LuaBuff 加 `state` 表 + Bundle 序列化。

## Files
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaItem.java` — 加 action/execute/defaultAction + state Bundle 持久化(参考 LuaBuff storeState/loadState)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaItemCallbacks.java` — execute 调用的分发器,传 state 参数
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/RpdApi.java` — 新增 `terrain(cell)`/`setTerrain(cell,terrainId)`/`isWall(cell)`/`isSolid(cell)`/`dig(cell)`/`dropItem(cell,itemId,qty)`
- `core/src/main/assets/mods/test_mod/scripts/items/remixed_pickaxe.lua` — 加 `actions`、`defaultAction`、`execute`/`onUse` 挖矿 + bloodStained state + attackProc 触发血染 + 血染后 glowing
- 新测试:`LuaItemActionTest.java`/`LuaItemStateTest.java`
- 扩测试:`RpdApiItemSpellTest.java` 加 terrain/dig API

## Steps (implemented)
1. **LuaItem state + action** (`core/.../modding/LuaItem.java`):
   - Added `private LuaTable state`, persisted via `lua_item_state` Bundle rows using the same Base64 row encoding as `LuaBuff`.
   - `attackProc/onEquip/onDeactivate/execute/onUse` all receive `state` as the last argument.
   - `defaultAction()` supports both literal strings and `function(state)` returning a string; falls back to `AC_EQUIP` when absent.
   - `actions(Hero)` merges explicit Lua `actions` array on top of upstream EQUIP/UNEQUIP/ABILITY/DROP/THROW.
   - `execute(Hero,String)` for Lua-only actions skips `super.execute` entirely (Item.execute unconditionally calls `GameScene.cancel()`, which NPEs without a live scene and is only UX cleanup a Lua action never needs); it sets `curUser/curItem` for parity then dispatches to Lua `execute(heroId, action, state)`, falling back to `onUse` for M11b material compatibility. Built-in actions (DROP/THROW/EQUIP/UNEQUIP/ABILITY) still go through `super.execute` wrapped in the same headless NPE guard LuaMaterial uses.
   - Added `glowing()` hook reading a Lua `glowing(state)` callback returning `{color=0xRRGGBB, period=seconds}` or a numeric color.

2. **RPD API 新增** (`core/.../modding/RpdApi.java`):
   - `terrain(cell)` → `Dungeon.level.map[cell]` (nil if outside/headless).
   - `setTerrain(cell, terrainId)` → `Level.set(cell, terrainId)`, but only for a safe-target whitelist `{EMPTY, EMPTY_DECO, EMPTY_SP, EMBERS}` (same set as LuaPainterAdapter) so scripts can't overwrite exits/traps/locked doors.
   - `isWall(cell)` → true for `WALL`/`WALL_DECO`; `isSolid(cell)` → `Dungeon.level.solid[cell]`.
   - `dig(cell)` → whitelist `{WALL, WALL_DECO, DOOR, BARRICADE, SECRET_DOOR}`; replaces with `EMPTY` (or `EMBERS` if flammable); returns false for non-diggable cells.
   - `dropItem(cell, itemId, qty)` → creates via `LuaItemRegistry.createItem`; if that returns null, falls back to a small native whitelist (`dark_gold`→DarkGold, `mystery_meat`→MysteryMeat) so pickaxe loot works; sets quantity and puts into `Dungeon.level.heaps` (headless-safe path avoiding `Level.drop`'s `Game.scene` dependency).
   - `RPD.Terrain` constants extended with `SECRET_DOOR` and `BARRICADE`.

3. **remixed_pickaxe.lua 升级**:
   - `actions={"MINE"}` only (upstream EQUIP/UNEQUIP/ABILITY/DROP/THROW are auto-merged by `LuaItem.actions`, which filters duplicates and skips UNEQUIP/ABILITY when not equipped); `actionNames.MINE="挖矿"`.
   - `defaultAction=function(state) return state.equipped and "MINE" or "EQUIP" end`.
   - `execute` handles `MINE`: `findDigTarget(heroPos)` (a module-local helper) finds nearest solid cell in front/adjacent to hero via `RPD.isSolid`, calls `RPD.dig`, then 50% drops `dark_gold` and 25% drops `mystery_meat`.
   - `attackProc` checks defender name for bat and sets `state.bloodStained=true`.
   - `glowing=function(state)` returns red aura when `bloodStained`.

4. **测试**:
   - `LuaItemActionTest` — pickaxe actions/default, action names, execute/onUse dispatch, state mutation via execute, glowing callback, function defaultAction.
   - `LuaItemStateTest` — bloodStained state survives a Bundle round-trip (inline weapon + MARK_BLOOD action + glowing callback); E2E `pickaxeMineDigsAdjacentWall` builds a 16x16 DataDrivenLevel, sets hero.pos, runs `MINE`, asserts the adjacent wall becomes EMPTY; Bundle round-trip preserves explicit action list + default action.
   - `RpdApiItemSpellTest` — terrain/setTerrain/isWall/isSolid/dig/dropItem null-level guards, whitelist behavior, bad input handling.

## Acceptance
- [x] LuaItem 支持 defaultAction/actions/execute/onUse,保留 MeleeWeapon 原有 EQUIP/UNEQUIP/ABILITY/DROP/THROW
- [x] LuaItem state Bundle 持久化(参考 LuaBuff storeState/loadState)
- [x] RPD 新增 terrain/setTerrain/isWall/isSolid/dig/dropItem
- [x] remixed_pickaxe 有 MINE action,能 dig 墙/门并掉落 dark_gold/mystery_meat
- [x] attackProc 击杀 Bat 后 state.bloodStained=true(持久化)
- [x] `./gradlew :core:test` 全绿(556 tests, 0 failures/errors/skipped)
- [x] C3 不破(只新增 fork 子包代码,未改上游生成/掉落平衡)

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,不 assign codex_reviewer
- `dig` 白名单只接受可破坏地形,避免破坏关键结构
- `dropItem` 在 headless 测试中使用 `heaps.put` 直接放置,绕过 `Level.drop` 的 `GameScene` 依赖;真机路径仍通过脚本正常调用 `RPD.dropItem` 返回 true
- 当前降级/未接(记录给后续 milestone):
  - **MINE 目标选择简化**:pickaxe 的 `findDigTarget` 先扫英雄正东方(同行 +1..+3 格)、再扫 `RPD.levelWidth()` 计算的相邻 8 格,未走 `RPD.cellRay`/英雄朝向感知。宽度已 width-agnostic,但"面向"仍是 east-first 而非真实朝向 —— 真机上挖得到相邻墙,但方向不精确。
  - **Bat 识别用 name 字符串匹配**:`attackProc` 通过 `RPD.charName(defender)` 匹配 "bat"/"蝙蝠",而非 class identity(RPD 未暴露 className)。本地化名变动会让血染失效,但不会崩溃。
  - **dropItem 真机 vs headless 路径**:headless 走 `heaps.put` 直接放置(无 scene);真机上 `hasLiveScene()` 为真时额外 `GameScene.add(heap)` 给 sprite,`heap.pos = cell` 保证 save/load 落位正确。
  - **LuaItem state 持久化的 key 类型编码比 LuaBuff 更严**:M11c Phase-2 review 后,LuaItem 的 `encodeKey`/`decodeKey` 用 `n:`/`s:` 前缀让数字 key 正确 round-trip;**LuaBuff 仍是旧的 `tojstring()` 实现(数字 key 会塌成字符串 "1")**,这是共享的既有 debt,本次未一并改(M11b/M6c 范围),记录给后续 milestone。

## Codex review fixes
**Phase 1(方案评审 → 实施):**
1. `remixed_pickaxe.lua` `findDigTarget` 原误写在 `register_item` 表内作字段、却被当 bare global 调用 → 提为 module-local function。
2. `RPD.dropItem` 原仅 `LuaItemRegistry.createItem`,无法掉落 native `dark_gold`/`mystery_meat` → 加 `createNativeDropItem` 白名单 fallback。
3. `RPD.setTerrain` 原接受任意 terrainId(C3 风险:可覆写出口/陷阱/锁门)→ 收敛 target 到 `{EMPTY, EMPTY_DECO, EMPTY_SP, EMBERS}` 白名单。
4. `LuaItem.actions` 原会重复/暴露不适用的 built-in(如未装备时 UNEQUIP)→ 去重 + 按装备态过滤;pickaxe Lua 只声明 `MINE`。
5. 测试覆盖补齐:`bloodStainedStateRoundTripsThroughBundle` + `pickaxeMineDigsAdjacentWall` E2E。
6. 实施 review 阶段发现 `LuaItem.execute` 在 headless+level 时仍 NPE(`GameScene.cancel`)→ Lua-only action 改为跳过 `super.execute`,仅 built-in 走 super + headless guard。

**Phase 2(实施评审 round 1,4 个 must-fix 全部修复):**
7. `RPD.setTerrain` 只校验 target 不校验 source → 仍可把 ENTRANCE/陷阱/墙"重涂"为 EMPTY。加 `SOURCE_WHITELIST`({EMPTY/EMPTY_DECO/EMPTY_SP/EMBERS/GRASS/HIGH_GRASS/FURROWED_GRASS});source 不在白名单即拒绝。测试:`setTerrainRejectsProtectedAndBadTarget`(原 `setTerrainChangesTile` 误用 ENTRANCE cell,已改用 floor cell)。
8. LuaItem state 数字 key 不 round-trip(序列化为 `"1"`、反序列化为 string key,破坏 array/ipairs)。重写 `writeStateRows`/`loadState` 用 `encodeKey`/`decodeKey` 的 `n:`/`s:` 类型前缀 + `LuaValue` key。测试:`numericAndNestedKeysRoundTripThroughBundle`。
9. `loadState` 对坏 bundle 数据可能 NPE(无 null 防御)。加 `rows==null` 早返 + `row==null` 跳过。测试:`malformedStateBundleDoesNotCrash`。
10. `findDigTarget` 邻居偏移写死 width=16 → 非 16 宽 level 会挖错格。新增 `RPD.levelWidth()`(int,headless nil),Lua 据此计算 `±(w±1)/±w/±1`。测试:`levelWidthReturnsActualWidth`。
11. (nit)`LuaItem.actions` 进一步收敛:Lua 只贡献非 built-in action(`isLuaAction` 过滤),built-in 一律由 `super.actions` 按装备/职业态提供。

**Phase 2(实施评审 round 2,1 个 must-fix 已修复):**
12. `RPD.dropItem` 新建 `Heap` 后未设 `heap.pos = cell` → save/load 时 loot 会落到 cell 0(`Heap.storeInBundle` 写 pos)。改为镜像 `Level.drop`:`heap.pos = cell` 后再 put/drop。测试:`dropItemCreatesHeapOnFloor` 加 `heap.pos == 17` 断言。

**Phase 2(实施评审 round 3,2 个 must-fix 已修复 —— 已达 3 轮上限,不再 review):**
13. `RPD.dig`/`RPD.setTerrain` 改 `Level.map` 后不刷新 tilemap/FOV → 真机挖墙后视觉不更新。加 `hasLiveScene()` 守卫(`Game.instance != null && scene instanceof GameScene`),dig 后 `GameScene.updateMap(cell)` + `Dungeon.observe()`(hero 非空时),setTerrain 后 `GameScene.updateMap(cell)`。headless 无 scene → no-op,测试不受影响。
14. `RPD.dropItem` 新 heap 绕过 `GameScene.add(heap)` → 真机掉落无 sprite。加 `if (hasLiveScene()) GameScene.add(heap)`;headless 仍走 `heaps.put` 直放路径。
- 说明:dig/setTerrain/dropItem 的 live 视觉/FOV/sprite 刷新被 `hasLiveScene()` 守卫,只在真机生效;headless 测试只验证数据层(`map[]`/`heaps`/`heap.pos`),on-device 视觉由手动/desktop run 验证(本轮未加 headless 测试,因为无 GameScene)。
