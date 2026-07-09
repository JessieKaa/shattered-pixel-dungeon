# PLAN: M15d — Lua shop 卖任意 Lua item/spell(让 remixed 商品可交易)

## Goal
让 `register_shop` 的 `items` 列表能卖任意 Lua item id 和 Lua spell id,不只 vanilla 消耗品白名单。这样 remixed 的装备/法术/材料可以通过 Lua NPC shop 获得,是玩家获取 Lua 内容的重要途径(与 M15a/c 掉落互补)。**与 M15a/b/c/e 零文件冲突**(只改 LuaShopItems/LuaShopNpc)。

## Context(Explore 2026-07-09 核实)
- `LuaShopItems.java`(`core/.../modding/LuaShopItems.java:37-54`) 是 hardcoded switch,只支持 8 个 vanilla 消耗品 id。未知 id 返回 null。
- `LuaShopNpc.attemptBuy`(`LuaShopNpc.java`) 调用 `LuaShopItems.create(id)`,null 时跳过。
- `LuaItemRegistry.createItem(id)` 能创建任意 Lua item(weapon/material)。
- `LuaSpellRegistry.create(id)` 能创建任意 Lua spell。

**设计决策**:
- **扩展 `LuaShopItems.create(id)` 查找顺序**:
  1. `LuaItemRegistry.contains(id)` → `LuaItemRegistry.createItem(id)`
  2. `LuaSpellRegistry.contains(id)` → `LuaSpellRegistry.create(id)`
  3. fallback 到现有 vanilla switch whitelist(potion_of_healing 等)
  4. 都未命中 → null + error log(现有行为)
- **安全性**:Lua item/spell 的 create 路径已经过 registry 校验(register 时 fromJson/脚本验证),不通过反射创建任意类,所以比 reflection 安全。保持"未知 id 返回 null 不崩"的防御。
- **价格/数量**:仍由 Lua shop table 的 `price`/`quantity` 控制(现有行为),本 feature 不改。
- **C3**:shop 是玩家主动交互,不污染 main game 掉落/刷怪;卖 Lua spell 让玩家能施法,但 spell 本身 balance 由作者控制。

## Files (worker-verified)
- **`core/.../modding/LuaShopItems.java`**:
  - 在 switch 前加 Lua registry 查询:
    ```java
    if (LuaItemRegistry.contains(id)) return LuaItemRegistry.createItem(id);
    if (LuaSpellRegistry.contains(id)) return LuaSpellRegistry.create(id);
    ```
  - 保留现有 vanilla whitelist 作为 fallback。
  - 更新 javadoc(whitelist 扩展为 Lua registry + vanilla)。
- **`core/.../modding/LuaShopNpc.java`**(可能小改):
  - 若 attemptBuy 对 Lua item/spell 有特殊处理(如堆叠、子弹窗),需适配。大概率通用(返回 Item → doPickUp)。
- **测试**(`LuaShopNpcTest` 或新):构造一个 Lua shop 卖 Lua item id 和 Lua spell id → `attemptBuy` 成功拿到对应实例;未知 id 仍 null。

### 显式延后
- **价格自动计算**:仍由 Lua table 显式写;后续可接入 `Item.value()` 自动。
- **任意 vanilla item id**:仍只支持 whitelist(安全),本 feature 只扩展到 Lua registry。
- **shop 卖 Lua mob/buff**:不合理,不做。

## Steps
1. 读 `LuaShopItems` + `LuaShopNpc.attemptBuy` + registry create 方法签名。
2. 改 `LuaShopItems.create`:先查 LuaItemRegistry,再查 LuaSpellRegistry,再 fallback vanilla whitelist。
3. 验证 `LuaShopNpc.attemptBuy` 对 Lua item/spell 通用(必要时小修)。
4. 写测试:shop 卖 Lua item / Lua spell / vanilla / unknown。
5. `:core:test` 全绿。
6. codex 评审(assign 优先,失败上报)。

## Acceptance
- [ ] `LuaShopItems.create` 能返回 Lua item 实例(`LuaItemRegistry.createItem`)
- [ ] `LuaShopItems.create` 能返回 Lua spell 实例(`LuaSpellRegistry.create`)
- [ ] vanilla 消耗品白名单仍工作(fallback)
- [ ] 未知 id 仍 null + log,不崩
- [ ] Lua shop 配置里写 `id=remished_lite_lantern_blade` 即可售卖
- [ ] `:core:test` 全绿
- [ ] 与 M15a/b/c/e 零文件冲突

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- **新 codex 政策**:assign codex_reviewer,失败上报 dispatcher
- 不引入反射;只走 registry create 路径(已验证安全)
- 保持 "unknown id → null" 的 fail-soft
- 与 M15a/b/c/e 零重叠
