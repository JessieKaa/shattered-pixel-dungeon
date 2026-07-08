# PLAN: M11b — food/material use API(onUse/onThrow/transform)

## Goal
消掉 M10a food/material 降级的核心缺口:让 LuaMaterial/LuaItem 支持最小可用的 `EAT/use`、`onThrow`、burn/freeze/poison 转换,使 raw/fried/frozen/rotten_fish、tengu_liver、vile_essence 从只注册材料变成可用内容。

## Context
M10a item 把食物/材料注册为 `LuaMaterial`,但降级清单记录:LuaMaterial 无 use 派发,食物 eat/onThrow/burn/freeze/poison 转换未实现。M11b 补一个最小可用动作层。

调研结论:
- 食物脚本: `core/src/main/assets/mods/test_mod/scripts/items/{raw,fried,frozen,rotten}_fish.lua`, `tengu_liver.lua`, `vile_essence.lua`。
- `LuaMaterial.java` 目前只数据/堆叠/价格,无 `actions/execute/onThrow/burn/freeze/poison`。
- 原生 `Item.java` 负责 `actions/execute/throw`, `Food.java` 负责 `AC_EAT`/detach/spend/busy/satisfy。
- RPD API 已有 `affectBuff/healChar/removeBackpackItem/restoreMana/spendMana`,缺 `satisfyHunger`/变换 API 等。

设计取舍:
1. **最小动作层**:LuaMaterial 支持 `defaultAction`, `actions`, `execute(action, heroId)`,`onUse(heroId,itemId)` / `onEat(heroId)`。
2. **食物语义**:Java 侧统一 EAT 的 detach+spend+busy,并始终按脚本 `energy` 处理饱食。Lua `onEat` 只处理毒/治疗/buff,不接管饱食。不新增 `RPD.satisfyHunger`,避免 Lua 重复写饱食。
3. **行动经济**:Java 侧 `execute` 唯一负责 `hero.busy()` 和 `hero.spend(...)`,Lua 不暴露 `heroSpend`/`spend`/busy API。  
4. **转换语义**:支持 `burnTransform`/`freezeTransform`/`poisonTransform` 字段,在 Heap burn/freeze 路径把当前 LuaMaterial 替换为目标 id。poison 无现成 Heap hook,实际转换留 M11b2。

## Files
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaMaterial.java` — hydrate `defaultAction`/`actions`/`energy`/transform fields; implement `actions(Hero)`, `execute(Hero,String)`, `onThrow(int)`, `burnTransform()`/`freezeTransform()` helpers.
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaItemRegistry.java` — already has `createItem(id)` returning `LuaMaterial` for material ids; no new factory required unless tests expose a gap.
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/RpdApi.java` — 本 feature 不新增 API;`RPD.satisfyHunger` 不加入,因为 LuaMaterial 由 Java 统一按 `energy` 处理饱食。Lua `onEat` 只能调用现有 `healChar/affectBuff/restoreMana` 等 API 处理非饥饿效果。
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java` — add narrow hooks in `burn()`/`freeze()` for `LuaMaterial` transform replacement. There is no item poison heap path in current code, so poison transform is recorded as follow-up.
- `core/src/main/assets/mods/test_mod/scripts/items/{raw,fried,frozen,rotten}_fish.lua`, `tengu_liver.lua`, `vile_essence.lua` — add `defaultAction`, `actions`, `energy`, `onEat`/`onUse`/`onThrow`, and transform fields.
- Tests: new/extended modding unit tests for action/defaultAction/execute fields, callback dispatch, and burn/freeze transform helpers.

## Steps
1. **LuaMaterial action fields**:
   - In `hydrate`, cache the backing `LuaTable`, `defaultAction` string, script `actions` array/table, `energy` float, and transform ids. Cache whether `onUse` is defined as a function.
   - `actions(Hero)` starts with `super.actions(hero)`, appends explicit `actions` list if provided; otherwise adds `EAT` when `defaultAction == "EAT"`, `USE` when `defaultAction == "USE"`, or `USE` when no `defaultAction` is set but `onUse` is defined. Unconfigured materials keep only `DROP`/`THROW`.
   - `defaultAction()` returns the script default action if present.
2. **Java-owned execute semantics**:
   - `execute(Hero,String)` calls `super.execute` first so DROP/THROW remain upstream.
   - For `EAT`: detach one from `hero.belongings.backpack`, call Lua `onEat(heroId,itemId)` and `onUse(heroId,itemId,"EAT")`, run Food-like visual/log hooks, `hero.busy()`, and `hero.spend(Food.TIME_TO_EAT)`. Java then applies `energy` via `Buff.affect(hero,Hunger.class).satisfy(energy)`; Lua `onEat` must NOT satisfy hunger on its own.
   - For `USE`: detach one, call `onUse(heroId,itemId,"USE")`, then `hero.busy()` and `hero.spend(1f)`. Lua cannot choose spend/busy duration.
   - `itemId` is passed as a string Lua argument; Java objects are never exposed.
3. **RPD API**:
   - 本 feature 不在 RpdApi 中新增函数。Lua `onEat`/`onUse` 仅使用现有 `healChar/restoreMana/affectBuff` 等 API;饱食由 Java `energy` 统一处理。
4. **onThrow**:
   - Override `onThrow(int cell)`, invoke `super.onThrow(cell)` so physical drop behavior remains native, then call optional Lua `onThrow(cell,itemId)` for effects.
5. **burn/freeze transform**:
   - `LuaMaterial.burnTransform()` / `freezeTransform()` create a replacement item through `LuaItemRegistry.createItem(targetId)` preserving quantity.
   - `Heap.burn()` and `Heap.freeze()` replace only when helper returns non-null, preserving native heap refresh/destroy logic.
6. **Script upgrades**:
   - Fish get `defaultAction="EAT"`, `energy`, `onEat` effects (毒/治疗/buff via existing RPD APIs), and transform ids: raw burn→fried/freeze→frozen/poison→rotten (poison field only), frozen burn→fried, fried poison→rotten (field only).
   - `tengu_liver` gets minimum EAT: `energy` + small heal/buff via existing RPD APIs; no subclass chooser in this feature.
   - `vile_essence` gets minimum USE: restore mana via `RPD.restoreMana` and consume; glowing remains outside scope.
7. **Tests**:
   - Unit-test LuaMaterial registration/action metadata without requiring a live scene.
   - Unit-test callback dispatch using Lua functions that mutate Lua globals.
   - Unit-test burn/freeze transform helper preserves quantity and creates target material.
   - Unit-test that unconfigured materials (e.g. `rotten_organ`) only expose `DROP`/`THROW` actions.

## Acceptance
- [ ] LuaMaterial 支持 defaultAction/actions/execute(onEat/onUse)
- [ ] 至少 raw/fried/frozen/rotten_fish 可 EAT/use 并消耗一件
- [ ] tengu_liver/vile_essence 有最小可用效果
- [ ] onThrow 工作或明确降级记录
- [ ] burn/freeze transform 工作；poisonTransform 字段保留但实际 poison 环境转换降级到 follow-up
- [ ] `./gradlew :core:test` 全绿(510 现有 + 新增)
- [ ] 行动经济不被 Lua 任意破坏(Java 侧统一 spend/busy)

## Downgraded / Follow-up Scope
- `poisonTransform` 字段会先注册在 LuaMaterial/脚本中，但当前 Java 代码没有对应 Heap poison 入口；实际 poison→rotten 转换留 M11b2，在找到合适 gas/heap hook 后补。
- `tengu_liver` 的 Remished 选子职业窗口不进入本 feature；M11b 只给最小食用效果并消耗。
- `vile_essence` 的 glowing/makeGlowing 表现不进入本 feature；M11b 只给最小 USE 效果并消耗。

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,不要 assign codex_reviewer
- 先做最小可用食用/use,transform 如果 hook 面大可以拆 follow-up,但不能悄悄丢,要写 PLAN 降级
- 避免把 LuaSpell 的 cast 语义套给 food;food/material 应留在 LuaMaterial
