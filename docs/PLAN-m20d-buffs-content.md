# PLAN — M20d: remixed_full Lua buffs 内容补全

## Goal
为 `remixed_full` mod 补全 **buffs** 内容类型(当前 `scripts/buffs/` 缺失)。交付 ≥2 个 remixed 风格 Lua buff:1 个护盾型(用 RpdApi 盾 API),1 个回血/光环型。

## Context
- LuaEngine 的 M6c loader 自动扫描 `mods/remixed_full/scripts/buffs/*.lua` → **只加文件,不碰 entry.lua**。
- `register_buff` 回调丰富:`defenseProc`/`attackProc`/`act`/`damage`/`affectBuff`/`attachTo` 等;RpdApi 有 `RPD.absorbShield`/`RPD.charShield`/`RPD.addShield`/`RPD.healChar`/`RPD.affectBuff`。
- `Remished` 传统有大量 buff(盾/光环/状态),test_mod 移植了 20+,参考其写法。
- `RemixedFullPackTest` 计数不含 buff → **不要改它**。
- test_mod PoC:`scripts/buffs/test_buff.lua`(最简)、`mana_shield.lua`/`shield_left.lua`(盾:声明 `shieldAmount`,`defenseProc` 用 `RPD.absorbShield`)、`body_armor.lua`(drBonus)。

## Files(已核对:loader = `LuaEngine.loadBuffScripts` 扫描每个 enabled mod 的 `scripts/buffs/*.lua`;无需 entry.lua/mod.json 登记)
- 新增 `core/src/main/assets/mods/remixed_full/scripts/buffs/remixed_full_mana_shield.lua` — id `remixed_full_mana_shield`,`shieldAmount=15` + `shieldType="mana"`,`defenseProc` 用 `RPD.absorbShield` 消耗,`RPD.charShield<=0` 时 `RPD.detachBuff` 自分离(参考 test_mod `mana_shield.lua`,但用 remixed_full_ 命名约定 + 不同数值)。
- 新增 `core/src/main/assets/mods/remixed_full/scripts/buffs/remixed_full_regen_aura.lua` — id `remixed_full_regen_aura`,`act(selfId,targetId,state)` 内 `RPD.healChar(targetId,2)` 周期回血(return 10s cooldown)+ `regenerationBonus` 喂 SPD Regeneration + `setGlowing` 绿色光环视觉。
- 新增 `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/RemixedFullBuffContentTest.java` — enable remixed_full 后断言两个 buff 注册 + 盾 defenseProc 吸收/耗尽自分离 + 回血 act 实际抬 HP。

## 命名约定
- remixed_full 现有内容一律 `remixed_full_` 前缀(文件名 + register id),例:`items/remixed_full_bone_shard.lua` → id `remixed_full_bone_shard`。本 feature 沿用,不用 PLAN 草稿里的 `rf_` 简写。

## 设计决策(已核实)
- **光环只回血携带者,不遍历附近友方**:fork 的 `RPD.*` 未暴露 char-iteration API(无 getAllChars/forEachChar/neighbors),sandbox 也不暴露 `Dungeon`。"光环"语义靠 `setGlowing` 视觉 + 携带者周期回血实现,这是仅用文档化 API 的稳健选择。
- **mana_shield 用 number 形 shieldAmount**:与 test_mod mana_shield(shieldAmount=10)区分用 15 + remished 风格 name/info/icon;不做 level-scale 以免 attach 时机依赖。
- **regen_aura 同时用 act(healChar) + regenerationBonus**:前者保证周期 trickle,后者接 SPD 原生 Regeneration(脱战回血),两者独立机制,均为 fork 支持的回调。

## register_buff schema(已从 PoC 核实)
```lua
register_buff{
    id = "remixed_full_xxx",          -- 必填,唯一
    name = "Xxx",                      -- 必填
    info = "...",                      -- 描述
    icon = N,                          -- buffs.png tile index
    shieldAmount = 15,                 -- 盾型:declarative,attach 时 seed 共享池
    shieldType = "mana",               -- 可选元数据
    attachTo = function(targetId, state) return true end,   -- false=拒绝 attach
    act = function(selfId, targetId, state) return 10 end,  -- number=spend 秒;true=spend TICK;false/nil=detach
    defenseProc = function(selfId, enemyId, damage) ... return left end,
    regenerationBonus = function(selfId) return 1 end,
    setGlowing = function(selfId, state) return { color=0x55AA55, rays=6 } end,
}
```

## Steps
1. ✅ 读 PoC + 测试(已确认 schema:`mana_shield.lua`/`shield_left.lua`/`champion_of_earth.lua` + `RpdApiBuffTest`/`RemixedFullPackTest`)。
2. 新建 `mods/remixed_full/scripts/buffs/` 目录,写 `remixed_full_mana_shield.lua`:`shieldAmount=15`、`shieldType="mana"`、`defenseProc` 内 `RPD.absorbShield(selfId,damage)` + 池空 `RPD.detachBuff(selfId,"remixed_full_mana_shield")`、`attachTo` return true、`act` return false。
3. 写 `remixed_full_regen_aura.lua`:`attachTo(targetId,state)` 初始化 state(无 side-effect)、`act(selfId,targetId,state)` 调 `RPD.healChar(targetId,2)` return 10、`regenerationBonus` return 1、`setGlowing` return `{color=0x55AA55,rays=6}`。
4. 写 `RemixedFullBuffContentTest.java`:仿 `RemixedFullPackTest.enableRemixedFull()`(`ModRegistry.scanDir(realModsHandle())` + setEnabled remixed_full true + 关掉其它 mod)→ `LuaEngine.init()` → `assertTrue(LuaBuffRegistry.contains("remixed_full_mana_shield"))` / `contains("remixed_full_regen_aura")`;再加 shield defenseProc 吸收 + 耗尽自分离、regen_aura act 抬 HP 的行为断言。
5. `./gradlew :core:test` 绿(flaky: GeneratorLuaItem/SpellTest 概率断言,单独重跑)。

## Acceptance
- [ ] `mods/remixed_full/scripts/buffs/` 下 ≥2 个 `.lua`,enable remixed_full 后 `LuaBuffRegistry.contains` 命中两个 id。
- [ ] `RemixedFullBuffContentTest` 通过(注册 + 盾吸收/自分离 + 回血)。
- [ ] `:core:test` 全绿。
- [ ] 未改 `entry.lua` / `RemixedFullPackTest.java` / `mod.json`。

## Constraints(强制)
- 只在自己 worktree 改动。
- 绝不改 `entry.lua`(M20f 独占)、`RemixedFullPackTest.java`(M20g 独占)。
- 绝不 `git add -A` / commit `.claude/` / force push / reset --hard。

## 评审协议
完成 + 测试绿后,用 **`assign("codex_reviewer", ...)`** 评审(先 PLAN 再实现)。严禁直接 codex-cli。
- assign 失败/静默 → 跳过,回报说明,dispatcher 决定是否亲审。
- 复用同一 reviewer terminal。

## 回报协议
`send_message`(无 receiver_id)回报 caller:`[DONE]`/`[BLOCKED]` + commit hash + reviewer terminal_id/轮数(或跳过) + 测试结果 + 文件清单。
