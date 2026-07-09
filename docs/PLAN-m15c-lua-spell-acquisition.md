# PLAN: M15c — Lua spell 获取路径(让 remixed spell 可被玩家获得)

## Goal
让 Lua spell 能进入玩家背包,从而可施放。当前 `LuaSpell` extends `Item` 但没有任何获取路径(不掉落、不商店、不初始)。本 feature 新增 `Generator.Category.LUA_SPELL` 掉落池,由 `mod.json` balance 的 `lua_spell_drop_prob` 控制。玩家在正常流程中捡到 spell(像 scroll/wand 一样)。**与 M15a/b/d/e 零文件冲突**(Generator/LuaSpell/LuaSpellRegistry vs 其他)。

## Context(Explore 2026-07-09 核实)
- `LuaSpell`(`core/.../modding/LuaSpell.java:49`) extends `Item`;execute 有 USE action + targeting 模式。
- `LuaSpellRegistry.create(id)`(`core/.../modding/LuaSpellRegistry.java:49-53`) 创建实例。
- `Generator` 现有 category 包括 SCROLL/WAND/RING/ARTIFACT 等,但没有 LUA_SPELL。
- `LuaItemPool.random()` 只从 `LuaItemRegistry` 取,不覆盖 spell。

**设计决策**:
- **新增 `Generator.Category.LUA_SPELL`**:在 `Generator.java` enum 里加一项 `(0, 0, LuaSpell.class)`(默认 prob 0,C3)。提供 runtime setter `Generator.setLuaSpellDropProbability(float first, float second)`,由 ModRegistry 在解析 `mod.json` balance `lua_spell_drop_prob` 后调用。
- **新增 `LuaSpellPool.random()`**(`core/.../modding/LuaSpellPool.java`):uniform random from `LuaSpellRegistry.ids()`,类似 `LuaItemPool`。
- **掉落位置**:LUA_SPELL 进入 `Generator.random()` 标准池,可被 `RegularLevel.createItems` / special rooms / grass 等调用。行为像 scroll。
- **UI 已存在**:LuaSpell 一旦在背包,`execute()` 提供 USE action + selectCell targeting,无需新 UI。
- **balance key**:`lua_spell_drop_prob`(≥0,默认 0),同 M15a 的 `lua_item_drop_prob` 风格。

## Files (worker-verified)
- **`core/src/main/java/.../items/Generator.java`**:
  - enum 新增 `LUA_SPELL(0, 0, LuaSpell.class)` 在 `SCROLL` 之后、`STONE` 之前(注释 fork)。
  - 新增 `public static void setLuaSpellDropProbability(float first, float second)` setter 直接写 `categoryProbs`(覆盖 first/secondProb 默认值 0)。
  - `random(Category cat)` 的 `switch` 增加 `case LUA_SPELL: return LuaSpellPool.random();`。
  - **bundle ordinal 兼容**: `restoreFromBundle` 将 `GENERAL_PROBS` float[] 按 `Category.values().length` 映射(第 956 行),新增 enum 项会让旧存档的 `probs.length` 比当前 `values().length` 少 1,整段恢复会跳过,不会错位;旧存档的各 cat.probs 按 `cat.name().toLowerCase() + CATEGORY_PROBS` 恢复,完全按名称,不按 ordinal,无兼容问题。说明在 PLAN 里。
- **`core/src/main/java/.../modding/LuaSpellPool.java`**(新):`public static Item random()` 从 `LuaSpellRegistry.ids()` uniform pick + `LuaSpellRegistry.create(id)`;注册表空时返回 `null`(Generator 已处理)。
- **`core/src/main/java/.../modding/LuaSpellRegistry.java`**:新增 `public static String randomId()`(内部用 `Random.oneOf(ids())`),供 `LuaSpellPool` 使用。
- **`core/src/main/java/.../modding/ModRegistry.java`**:在 `applyEnabledBalanceOverrides()` 中识别 `lua_spell_drop_prob`(≥0)并调用 `Generator.setLuaSpellDropProbability((float) v, (float) v)`(first/second 同值,保持两个 deck 一致)。
- **测试**:单元测试用 `LuaSpellRegistry.register` 注册 2 个 dummy spell → 设置 prob → 多次 `Generator.random()` 验证出现 LuaSpell;prob=0 时验证不出现。`:core:test` 全绿。

### 显式延后
- **Starter spellbook / per-hero 初始法术**:本 feature 只走掉落;hero init 发 spell 留后续。
- **Shop 卖 spell**:M15d 扩展 LuaShopItems 后可卖;本 feature 只覆盖掉落。
- **按 targeting/cost 加权掉落**:uniform MVP。

## Steps
1. 读 `Generator.java` enum + `random()` + bundle save/load(第 923-1002 行)确认 category 恢复按 `Category.values()[i]` 长度判断,新增 enum 项会让旧存档跳过整段 categoryProbs 恢复(不会错位);各 cat.probs 按 `cat.name()` 恢复,无 ordinal 依赖。
2. 加 `LUA_SPELL` category + `setLuaSpellDropProbability(first, second)` setter;`random(Category)` 增加 `case LUA_SPELL`。
3. 实现 `LuaSpellPool.random()` + `LuaSpellRegistry.randomId()`(使用 `com.watabou.utils.Random`)。
4. `ModRegistry.applyEnabledBalanceOverrides()` 接 `lua_spell_drop_prob`。
5. 测试:掉落出现 + C3 无 prob 不出现;跑 `:core:test`。
6. codex 评审(assign 优先,失败上报)。

## Acceptance
- [ ] `Generator.Category.LUA_SPELL` 存在,默认 prob 0
- [ ] `LuaSpellPool.random()` 能创建随机 LuaSpell
- [ ] `lua_spell_drop_prob > 0` 时主游戏掉落 LuaSpell
- [ ] prob=0 时不掉落(C3)
- [ ] 掉落出的 LuaSpell 可在背包 USE 施放(targeting 已存在)
- [ ] `:core:test` 全绿
- [ ] 与 M15a/b/d/e 零文件冲突

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- **新 codex 政策**:assign codex_reviewer,失败上报 dispatcher
- 新增 enum 项会改变 `Generator` 的 ordinal/values,需检查 bundle save/load 是否序列化 ordinal(`Generator.java:923-1002`);若存 ordinal 需兼容。读代码确认后再实施。
- LuaSpell 作为 Item 走现有 belongings bundle,无需额外序列化
- 与 M15a/b/d/e 零重叠
