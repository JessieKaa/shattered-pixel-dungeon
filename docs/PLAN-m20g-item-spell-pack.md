# PLAN — M20g: remixed_full items/spells 内容池扩充

## Goal
扩充 `remixed_full` 现有的 **items** 与 **spells** 内容池:新增 2-3 个 remixed 风格 item + 1-2 个 spell。**本方向独占 `RemixedFullPackTest.java` 的计数行**(其他 M20 worker 不碰它,无冲突)。

## Context
- LuaEngine loader 自动扫描 `mods/remixed_full/scripts/items/*.lua` 与 `scripts/spells/*.lua` → **加文件即可,不碰 entry.lua**。
- `RemixedFullPackTest.java:205-209` 硬编码计数:`14 items` / `5 spells` / `6 mobs` / `6 npcs` / `1 shop`。**你加 item/spell 后,必须同步更新第 205 行(item 数)与第 206 行(spell 数)**,否则测试红。这是本方向独占改动(其他 worker 不碰该文件)。
- 第 123-162 行有 `assertTrue("xxx registered", ...)` 模式,你为新 item/spell 加同样的断言行。
- spriteFile 约定:M19e 确认 LuaMaterial 的 `spriteFile` 必须是 `sprites/items/item_*.png`(相对 mod 根)。先 `cat` 一个现有 remixed_full item(如 `bone_shard.lua`)确认 sprite 引用方式,复用现有贴图或引用路径。
- test_mod 有大量 item/spell PoC(`scripts/items/*.lua`、`scripts/spells/*.lua`)。

## Files(已核对,2026-07-10)
新增 3 个 item + 2 个 spell,全部从 test_mod PoC 改编,**只用 fork 已验证的 callback 模式**:
- `core/src/main/assets/mods/remixed_full/scripts/items/rf_bone_saw.lua` — weapon,`attackProc` 命中给目标 Bleeding(范式:hooked_dagger/kunai,test `enabled_weaponAttackProcFiresAndAppliesBuff` 验证过)。spriteFile `sprites/items/item_BoneSaw.png`(贴图已存在)。
- `core/src/main/assets/mods/remixed_full/scripts/items/rf_tengu_liver.lua` — food material,`defaultAction="EAT"` + `onEat` 调 `RPD.healChar`(范式:rotten_fish onEat + heal spell healChar,test `m19e_rottenFish_onEatAppliesPoison` 验证过 onEat 路径)。spriteFile `sprites/items/item_TenguLiver.png`。
- `core/src/main/assets/mods/remixed_full/scripts/items/rf_soul_shard.lua` — 纯声明式 stackable material(范式:bone_shard/vile_essence)。spriteFile `sprites/items/item_SoulShard.png`。
- `core/src/main/assets/mods/remixed_full/scripts/spells/rf_haste.lua` — self `onUse` 调 `RPD.affectBuff(heroId,"Haste",5)`(范式:iron_skin affectBuff + blink Haste fallback,test `enabled_blinkSpellPrefersTeleportOverHasteFallback` 验证 Haste 落得下来)。spriteFile `mods/remixed_full/sprites/spells/spell_Haste.png`(沿用现有 5 spell 的 absolute 路径约定)。
- `core/src/main/assets/mods/remixed_full/scripts/spells/rf_lightning_bolt.lua` — cell `onUseAt`,cellRay + charAtCell + damageChar 打射线全程伤害(范式:magic_arrow,test `enabled_cellSpellMagicArrowDamagesTargetOnRay` 验证过 damageChar)。spriteFile `mods/remixed_full/sprites/spells/spell_LightningBolt.png`。
- 改 `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/RemixedFullPackTest.java`:
  - 第 205 行 `14` → `17`(items 14→17)
  - 第 206 行 `5` → `7`(spells 5→7)
  - 在 item registered 区(fried_fish 断言后,约第 151 行后)加 3 行 `assertTrue("rf_xxx registered", LuaItemRegistry.contains("rf_xxx"))`
  - 在 spell registered 区(ignite 断言后,约第 163 行后)加 2 行 `assertTrue("rf_xxx registered", LuaSpellRegistry.contains("rf_xxx"))`
  - **不动**第 207-209 行(mobs/npcs/shop 计数)

## 已核实事实(2026-07-10 探索)
- `entry.lua` 只 register_level 3 个关卡,**不**逐个列 item/spell** → loader 自动扫描 `scripts/items/*.lua` + `scripts/spells/*.lua`,加文件即可(与 M19e 加 4 item 未改 entry 一致)。
- 5 个新贴图均已存在:`item_BoneSaw.png` / `item_TenguLiver.png` / `item_SoulShard.png` / `spell_Haste.png` / `spell_LightningBolt.png`。
- item spriteFile 用 relative-to-mod-root 形式(`sprites/items/...`,M19e bone_shard 即此形,m19e 测试 line 855-856 断言此形);spell spriteFile 沿用现有 5 spell 的 absolute 形式(`mods/remixed_full/sprites/spells/...`,无测试强制 relative)。
- 新文件**不被**任何 forbidden-token lint 覆盖(现有 lint 只查 6 NPC + 4 M19e item 的固定路径),但仍只 fork-supported API。
- 5 个新 id 与现有 14 item / 5 spell 无重名(前缀 `rf_` 区别于 `remixed_full_`)。

## Steps
1. `cat core/src/main/assets/mods/remixed_full/scripts/items/bone_shard.lua`(及 1-2 个同类)学现有 item schema + spriteFile 写法。
2. `cat test_mod/scripts/items/` 与 `scripts/spells/` 选 2-3 个适合改编的 PoC。
3. 写 rf item ×2-3 + rf spell ×1-2,id 前缀 `rf_`,避免与现有 14 item / 5 spell 重名。
4. 改 `RemixedFullPackTest.java`:205 行数字、206 行数字、加 registered 断言行。
5. `./gradlew :core:test` 绿 —— **RemixedFullPackTest 是你的关键覆盖,必须通过**。(flaky: Generator 概率断言,重跑。)

## Acceptance
- [ ] 新 item/spell 注册成功。
- [ ] `RemixedFullPackTest` 第 205/206 行计数同步更新,且新 registered 断言通过。
- [ ] `:core:test` 全绿(flaky 重跑)。
- [ ] 未改 `entry.lua`、其他共享测试。

## Constraints(强制)
- 只在自己 worktree 改动。
- **独占** `RemixedFullPackTest.java`(只改计数行 205/206 + 加 registered 断言;不改 mobs/npcs/shop 计数 207-209)。
- 绝不改 `entry.lua`(M20f 独占)。
- 绝不 `git add -A` / commit `.claude/` / force push / reset --hard。

## 评审协议
完成 + 测试绿后,用 **`assign("codex_reviewer", ...)`** 评审(先 PLAN 再实现)。严禁直接 codex-cli。
- assign 失败/静默 → 跳过,回报说明,dispatcher 决定是否亲审。
- 复用同一 reviewer terminal。

## 回报协议
`send_message`(无 receiver_id)回报 caller:`[DONE]`/`[BLOCKED]` + commit hash + reviewer terminal_id/轮数(或跳过) + 测试结果 + 新 item/spell id 清单 + 更新后的计数(如 "items 14→16, spells 5→6")。
