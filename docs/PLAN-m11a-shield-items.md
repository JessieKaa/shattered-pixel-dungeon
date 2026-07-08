# PLAN: M11a — shield/item 回补(5 盾接 M10c 回调)

## Goal
消掉 M10a item 降级中 5 个盾类的主要行为缺口:让 wooden/tough/strong/royal/chaos shield 装备时通过 LuaBuff 提供 `drBonus`/`speedMultiplier`/damage/attackProc 等效果;ChaosShield 实现 owner damage hooks + 充能升降级。

## Context
M10a 已把 5 个盾移植为 `LuaItem weapon` 占位,但 PLAN §Degradation 记录:`drBonus/blockChance/blockDamage/recharge + left_hand 槽` 未实现,ChaosShield 额外缺 `ownerTakesDamage/ownerDoesDamage` 充能升降级。M10c 已落地 `LuaBuff` 的 `drBonus`/`speedMultiplier`/`damage`/`attackProc` 等桥接,可以低侵入回补。

调研结论:
- 盾脚本在 `core/src/main/assets/mods/test_mod/scripts/items/{wooden,tough,strong,royal,chaos}_shield.lua`。
- `LuaItem.java` 只支持 `attackProc/onEquip/onDeactivate`,无 `defenseProc/ownerTakesDamage/ownerDoesDamage`。
- 低侵入方案:盾继续是 `LuaItem`(装备/背包占位),真正战斗效果拆到装备时挂的 `LuaBuff`。`onEquip` → `RPD.permanentBuff(owner,buffId)`, `onDeactivate` → `RPD.removeBuff(owner,buffId)`。
- ChaosShield 充能用 buff `state` 存 level/charge,`attackProc` 近似 ownerDoesDamage,`damage` 或 `defenseProc` 近似 ownerTakesDamage。

## Files
- `core/src/main/assets/mods/test_mod/scripts/items/{wooden,tough,strong,royal,chaos}_shield.lua` — 加 `onEquip/onDeactivate` 挂/卸对应 guard buff;保留 LuaItem weapon 占位、tier/price/image;移除 inert `drBonus`/`owner*` DEGRADED 字段,把 desc 降级文案缩到 left_hand/真实格挡槽
- `core/src/main/assets/mods/test_mod/scripts/buffs/{wooden,tough,strong,royal,chaos}_shield_guard.lua` — 5 个新 LuaBuff,承载 `drBonus`/`speedMultiplier`/Chaos charge callbacks
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/M10aItemsRegisteredTest.java` — 断言 5 个盾 item 暴露 `onEquip/onDeactivate`,且不再暴露 inert item `drBonus`/`owner*`
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/RpdApiBuffTest.java` — 更新 test_mod buff 总数并验证 shield guard 注册、永久挂载/卸载、DR/速度/Chaos charge 行为
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaBuffCallbackTest.java` — 将 shield guard 纳入 canonical callback 断言
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModToggleRegressionTest.java` — 同步 test_mod 全量加载精确 buff 数量 16→21,并加代表性 guard contains 断言

## Steps
1. **为每个盾新增 LuaBuff**:
   - `wooden_shield_guard`: `drBonus(selfId)=1`,无减速。
   - `tough_shield_guard`: `drBonus(selfId)=2`,`speedMultiplier(selfId)=0.95`。
   - `strong_shield_guard`: `drBonus(selfId)=3`,`speedMultiplier(selfId)=0.90`。
   - `royal_shield_guard`: `drBonus(selfId)=4`,`speedMultiplier(selfId)=0.85`。
   - `chaos_shield_guard`: `attachTo(targetId,state)` 初始化 `state.level=3,state.charge=0`;`drBonus` 返回 `state.level`;`attackProc` 按命中伤害给 `charge += max(1,floor(damage/2))`,达到 `ceil(5*level^1.5)` 时 level+1(封顶 5)且 charge=0;`damage` 近似 ownerTakesDamage,按入伤 `charge -= max(1,floor(damage/3))`,低于 0 时 level-1(保底 1)并设置下一等级半 charge;`setGlowing` 在 charge>0 时返回紫色 aura。
2. **改盾 item 脚本**:
   - 每个脚本在 `register_item` 前声明 `local SHIELD_LEVEL = <数字>` 和 `local GUARD_BUFF = "<id>_guard"`;`shieldLevel = SHIELD_LEVEL`;`onEquip(heroId)` 调 `RPD.permanentBuff(heroId, GUARD_BUFF, SHIELD_LEVEL)`。不要在 table literal 函数里直接引用字段名 `shieldLevel`,Lua 会按全局查找导致 nil。
   - 每个 `onDeactivate(heroId)` 调 `RPD.removeBuff(heroId, GUARD_BUFF)`。
   - 只用已存在 RPD API;不加 Java API;保留 weapon-type 占位和 C3 loot/spawn 隔离行为。
3. **测试**:
   - `M10aItemsRegisteredTest`: 盾 item 注册后 `onEquip/onDeactivate` 是 function,旧 item `drBonus/ownerDoesDamage/ownerTakesDamage` 不再是 function;新增 headless glue 测试直接对 5 个盾 table 执行 `LuaItemCallbacks.callOpt(table, "onEquip", LuaItemCallbacks.arg(hero.id()))`,断言对应 guard buff 挂到 hero,再执行 `onDeactivate` 断言卸除。
   - `RpdApiBuffTest`: buff loader 从 16 更新为 21;5 个 guard buff 注册;`RPD.permanentBuff/removeBuff` 能挂卸 `wooden_shield_guard`;`wooden/royal` DR 分别 +1/+4;`tough/strong/royal` 速度倍率分别 0.95/0.90/0.85;Chaos `attackProc` 升级、`damage` 降级且输出伤害不被改写。
   - `LuaBuffCallbackTest`: canonical callback 列表新增 shield guard 的 `drBonus/speedMultiplier/damage/attackProc/setGlowing` 字段检查。
4. **更新 PLAN 降级清单**:left_hand 槽/真实盾装备槽/Remished 随机混沌格挡特效仍降级;`drBonus` 和 Chaos owner damage hooks 通过 LuaBuff 近似回补。

## Acceptance
- [ ] 5 个盾装备后挂对应 LuaBuff,卸下后移除
- [ ] `drBonus` 经 M10c 桥接生效
- [ ] ChaosShield 有最小充能/降级逻辑(ownerDoesDamage/ownerTakesDamage 近似)
- [ ] M10a item 注册测试仍绿,新增 shield 回补测试绿
- [ ] `./gradlew :core:test` 全绿(510 现有 + 新增)
- [ ] C3 不破(不进 vanilla loot/spawn pool)

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,不要 assign codex_reviewer
- 新行为优先 Lua 脚本实现,少改 Java;只有发现 RPD API 缺必要能力才加小 API
- 不做真正 left-hand 槽/新装备类型,那是 M12+ UI/装备系统议题
