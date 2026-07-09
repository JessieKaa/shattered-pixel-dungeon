# PLAN: M15a — Lua item 进主游戏掉落池(让 remixed 装备/消耗品在 dungeon 掉落)

## Goal
让 `Generator.random()` 标准掉落池能 roll 到 Lua 物品,玩家在正常地下城流程中获得 remixed 装备/消耗品。当前 `Generator.Category.LUA_ITEM` 的 `firstProb/secondProb` 是 0,drop deck 永不选它。本 feature 提供 runtime 概率覆盖,且 C3 安全(只改概率,不改 vanilla 类别权重总量,save/load bundle 兼容)。**与 M15b/c/d/e 零文件冲突**(Generator/LuaItemPool/ModManifest vs 其他子系统)。

## Context(Explore 2026-07-09 核实)
- `Generator.Category.LUA_ITEM`(`core/.../items/Generator.java:261`) = `(0, 0, Item.class)`;注释说明故意 prob=0,C3。
- `Generator.generalReset()`(`:647-652`) 把每个 category 的 `firstProb/secondProb` 复制进 `private static HashMap<Category, Float> categoryProbs`(`:684-691` 是 random draw)。`categoryProbs` 只有一份,当前 deck 由 `usingFirstDeck` 决定,写入 `cat.firstProb` 或 `cat.secondProb`。
- `LuaItemPool.random()`(`core/.../modding/LuaItemPool.java:32`) 武器-only;`randomMaterial()` 材料-only。均从 `LuaItemRegistry.ids()` 均匀随机。
- `ModManifest.balance`(`ModManifest.java:151-165`) 目前只传给 `BalanceConfig`,是 numeric KV,用于 mana/shield/regen。
- `BalanceConfig`(`BalanceConfig.java:36-40`) 当前字段:MANA_BASE/MANA_PER_LEVEL/MANA_REGEN_DELAY/SHIELD_MAX/SHIELD_DECAY_PER_TURN。
- `ModRegistry.applyEnabledBalanceOverrides()`(`ModRegistry.java:90-97`) 先 `BalanceConfig.resetToDefaults()` 再遍历 enabled mods 调用 `BalanceConfig.applyModOverrides`,是注入 balance 覆盖的最佳 hook。

**设计决策**:
- **不硬改 enum constructor**(`LUA_ITEM(0,0,…)` 不动),避免破坏 M6e 的 "opt-in toggle" 注释语义 + 简化 merge。
- **Runtime category prob override**:在 `Generator.generalReset()` 之后,允许外部调用 `Generator.setLuaItemProbability(float first, float second)` → 同时写 `categoryProbs.put(LUA_ITEM, usingFirstDeck ? first : second)`;并保存 `luaFirstProb`/`luaSecondProb` 静态字段供 `generalReset()` 重新应用,因为 `fullReset()` 每局调用多次且会重新 `generalReset()`。
- **谁触发 override?** `ModManifest.balance` 新 key `lua_item_drop_prob`(单值),`BalanceConfig` 识别并暂存到 `BalanceConfig.LUA_ITEM_DROP_PROB`;`ModRegistry.applyEnabledBalanceOverrides()` 在 `BalanceConfig.applyModOverrides` 后调用 `Generator.setLuaItemProbability(BalanceConfig.LUA_ITEM_DROP_PROB, BalanceConfig.LUA_ITEM_DROP_PROB)`。无 mod 声明时默认 0(C3 vanilla)。
- **掉落池内容**:调用 `Generator.random(LUA_ITEM)` → `LuaItemPool.random()`(武器-only)。若要让材料也掉落,需新增 `LuaItemPool.randomAny()` 或 balance 里分 `lua_item_weapon_prob`/`lua_item_material_prob`。本 feature MVP 只支持武器掉落(材料走 shop/关卡/合成,后续扩展)。
- **C3/save**:Generator 的 bundle save/restore(`:923-1002`)存 `categoryProbs` 和 per-category `dropped`;Lua item 作为普通 Item 实例参与,无特殊处理。`lua_item_drop_prob` override 是 runtime-only,不持久化,每次新游戏由 ModRegistry 重新应用。

## Files (worker-verified)
- **`core/.../items/Generator.java`**:
  - 新增私有静态字段 `private static float luaFirstProb = 0f; private static float luaSecondProb = 0f;`
  - 新增 `public static void setLuaItemProbability(float first, float second)`:保存到 `luaFirstProb`/`luaSecondProb`,并立即 `categoryProbs.put(LUA_ITEM, usingFirstDeck ? first : second)`。
  - 修改 `generalReset()`:在最后对所有 category 写完默认概率后,再 `categoryProbs.put(LUA_ITEM, usingFirstDeck ? luaFirstProb : luaSecondProb)`,保证 `fullReset()` 切换 deck 后 Lua 概率仍生效。
  - 概率值必须 `>= 0`;负数 log error 并当 0 处理。
- **`core/.../modding/LuaItemPool.java`**(本 MVP 不动):
  - `random()` 武器-only;`randomMaterial()` 材料-only。够用。
- **`core/.../modding/BalanceConfig.java`**:
  - 新增 `public static volatile float LUA_ITEM_DROP_PROB = 0f;`
  - `applyModOverrides` 识别 `lua_item_drop_prob`:finite,≥0,≤10000(与 BalanceConfig 其他字段一致),否则 error 跳过。
  - `resetToDefaults()` 重置 `LUA_ITEM_DROP_PROB = 0f`。
- **`core/.../modding/ModManifest.java`**:
  - `parseBalance` 已接受任何 numeric key,无需改。`lua_item_drop_prob` 作为普通 number 透传。
- **`core/.../modding/ModRegistry.java`**:
  - 在 `applyEnabledBalanceOverrides()` 中,`BalanceConfig.applyModOverrides(m.balance)` 之后立即调用 `Generator.setLuaItemProbability(BalanceConfig.LUA_ITEM_DROP_PROB, BalanceConfig.LUA_ITEM_DROP_PROB)`;取 enabled mods 中该 key 最后一个生效值(因 `resetToDefaults` 已清空,`applyModOverrides` 会覆盖)。
- **测试**(`core/.../items/GeneratorLuaItemTest.java` 新):
  - 注册一个 Lua weapon(`LuaItemRegistry.register` + 构造 LuaTable),启用 test mod(balance 含 `lua_item_drop_prob=100`)→ `Generator.fullReset()` → 多次 `Generator.random()` 必出 LuaItem 实例。
  - 测试 balance=0 或 mods 全禁时 `Generator.random()` 不返回 LuaItem。
  - 测试 `fullReset()` 后 `setLuaItemProbability` 仍生效(模拟 deck 切换)。
  - 测试 negative/NaN balance 值被忽略。

### 显式延后
- **材料掉落**:分 weapon/material prob 或 `LuaItemPool.randomAny()`,留后续。
- **子类型加权**:当前 uniform random,后续可按 tier/price 加权。
- **vanilla shop 卖 Lua items**:那是 M15d(LuaShopNpc 扩展),不是 Generator。

## Steps
1. 读 `Generator.java` 精确实现:`categoryProbs` 结构、first/second deck、save/restore、random draw —— **已完成**。
2. 实现 `Generator.setLuaItemProbability(first, second)` + 静态字段 + `generalReset()` hook。
3. `BalanceConfig` 新增 `LUA_ITEM_DROP_PROB` 字段与 `applyModOverrides` 解析。
4. `ModRegistry` 在 applyEnabledBalanceOverrides 末尾调 `Generator.setLuaItemProbability(...)`。
5. 写测试 `GeneratorLuaItemTest`:启用 mod 时掉落、禁用时/无 prob 时不掉落、`fullReset()` 后仍生效、非法 balance 忽略。
6. `:core:test` 全绿。
7. codex 评审(assign codex_reviewer,失败上报 dispatcher 按新 memory 政策)。
8. 可选 desktop smoke:新游戏看dungeon 里是否掉 Lua 武器。

## Acceptance
- [ ] `Generator.random()` 能在 `lua_item_drop_prob > 0` 时掉落 Lua weapon
- [ ] 无 balance 或 prob=0 时,LUA_ITEM 不掉落(C3)
- [ ] `setLuaItemProbability` 是 runtime-only,不持久化到 save bundle,新游戏重置
- [ ] `ModManifest.balance` 接受 `lua_item_drop_prob` numeric key
- [ ] 多 mod 启用时取合理聚合(max;不叠加到无限)
- [ ] `:core:test` 全绿
- [ ] C3 不破:vanilla 类别权重总量不被暴力放大(vanlla probs 不动,只给 LUA_ITEM 非零)
- [ ] 与 M15b/c/d/e 零文件冲突

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- **新 codex 政策**:worker 用 `assign("codex_reviewer",...)`;若失败 send_message 报 dispatcher 裁决,**不自行 codex exec**
- 不动 `LUA_ITEM` enum constructor(保留 0,0 默认),只改 runtime `categoryProbs`
- balance key 是 `lua_item_drop_prob`,作者显式 opt-in;不是 default-on
- save/load bundle 兼容:Generator 自己存 categoryProbs,Lua item 作为 Item 正常 bundle
- 与 M15b/c/d/e 零重叠
