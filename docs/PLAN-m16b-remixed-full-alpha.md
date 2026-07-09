# PLAN: M16b — remixed_full minimal playable alpha

## Goal
把 M15e 的 `remixed_full` 从“498 个 PNG 的资产/文档包”推进到“最小可玩 alpha 内容包”:启用后,玩家能在普通地牢中遇到少量 remixed 风格 Lua mob,捡到 Lua item/spell,并在 Lua shop 购买 Lua 内容。

## Context
M15 已打通主游戏接入点:
- `lua_item_drop_prob`:Lua item 可进入 Generator 标准掉落池。
- `lua_spell_drop_prob`:Lua spell 可进入 Generator 标准掉落池。
- `lua_mob_spawn_prob`:Lua mob 可替换 vanilla spawn rotation 中一个槽位。
- Lua shop 可卖任意 Lua item/spell id。
- M15e 已导入 remixed sprites 并生成 `SPRITE-MAP.md`。

限制:
- `../remixed-dungeon` 原 Lua 脚本大量依赖 `luajava` / `commonClasses.lua`,不能直接搬运行逻辑。
- 本 feature 只做少量重写内容,不追求完整 Remixed 移植。
- runtime spriteFile 支持由 M16a 负责;本 feature 可以先写 `spriteFile` 字段并保留 `image` fallback,最终合并后显示 PNG。
- 当前 Java runtime 中 mob `sprite` 字段只能解析预定义的 vanilla sprite 白名单(`rat`/`crab`/`gnoll`/`brute`/`skeleton`/`bat`/`slime`),因此 mob 脚本必须提供该字段作为渲染 fallback;spriteFile 仅作为 M16a 启用后的额外元数据。

## 内容清单
### 10 个 Lua item
| id | 类型 | 用途 | fallback image |
|----|------|------|----------------|
| remixed_full_hooked_dagger | weapon | 命中出血 | 0 |
| remixed_full_battle_axe | weapon | 高伤低速 | 8 |
| remixed_full_lantern_blade | weapon | 照亮 + 火焰附加 | 22 |
| remixed_full_mace | weapon | 眩晕 | 15 |
| remixed_full_kunai | weapon | 远程/命中毒 | 13 |
| remixed_full_remixed_ration | material | 食物 | 20 |
| remixed_full_rotten_organ | material | 堆叠材料 | 4 |
| remixed_full_dark_gold | material | 金币材料 | 9 |
| remixed_full_toxic_gland | material | 毒材料 | 27 |
| remixed_full_rusty_coin | material | 交易材料 | 5 |

### 5 个 Lua spell
| id | 目标 | 效果 | fallback image |
|----|------|------|----------------|
| remixed_full_magic_arrow | cell | 单体伤害 | 1 |
| remixed_full_heal | self | 回血 | 2 |
| remixed_full_blink | self | 短距离闪烁 | 3 |
| remixed_full_iron_skin | self | 临时护甲 | 4 |
| remixed_full_ignite | cell | 点燃目标 | 5 |

### 6 个 Lua mob
| id | sprite fallback | 层数 | 特征 |
|----|-----------------|------|------|
| remixed_full_kobold | gnoll | 1-5 | 基础怪 |
| remixed_full_black_rat | rat | 1-5 | 弱小怪 |
| remixed_full_hedgehog | rat | 1-5 | 带刺 |
| remixed_full_cold_spirit | bat | 6-10 | 冰伤 |
| remixed_full_fetid_rat | rat | 6-10 | 毒 |
| remixed_full_bandit | gnoll | 6-10 | 偷金币 |

### 1 个 shop
| id | 位置 | 售卖 |
|----|------|------|
| remixed_full_alpha_shop | alpha hub | 2 Lua item + 2 Lua spell + 1 食物 |

### 1 个 custom level
| id | 入口 | 用途 |
|----|------|------|
| remixed_full_alpha_hub | 自定义关卡入口 | 展示 shop、几个 mob、散落物品 |

## Files
预计会改/新增:
- `core/src/main/assets/mods/remixed_full/mod.json`:声明 id/name/version/default_enabled/balance,设置较低 opt-in 概率。
- `core/src/main/assets/mods/remixed_full/entry.lua`:注册 alpha 内容与测试/商店/关卡。
- `core/src/main/assets/mods/remixed_full/scripts/items/*.lua`:约 10 个 Lua item/material/weapon/utility。
- `core/src/main/assets/mods/remixed_full/scripts/spells/*.lua`:约 5 个 Lua spell。
- `core/src/main/assets/mods/remixed_full/scripts/mobs/*.lua`:约 6 个 Lua mob。
- `core/src/main/assets/mods/remixed_full/scripts/shops/*.lua`:1 个 shop,售卖 Lua item/spell。
- `core/src/main/assets/mods/levels/remixed_full_alpha_hub.json`:一个快速验证 hub/展示关。
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/RemixedFullPackTest.java` (新):注册数量、掉落/刷怪/shop 基本链路。
- 可能更新 `docs/MODDING-ROADMAP.md` 或 `docs/MOD-SPRITES.md` 的 alpha 状态(最终收口可由 dispatcher 做;worker 仅必要更新)。

内容范围上限:
- 10 item:例如 Hooked Dagger、Battle Axe、Lantern Blade、Remixed Ration、Ore/Material 等。
- 6 mob:前 10 层可用的低/中威胁怪,不要复杂 AI。
- 5 spell:damage/heal/blink/buff/utility 各一类。
- 1 shop + 1 hub level。

## Steps
1. 读取现有 `remished_lite`、`test_mod`、`regression_demo` 的 mod.json/entry.lua/scripts 模式,复用当前 API 风格。
2. 在 `remixed_full/mod.json` 中声明 `id=remixed_full`、`name=Remixed Full (Alpha)`、`version=0.1.0`、`spd_version=896`、`default_enabled=false`、`entry=entry.lua`;声明 `balance.lua_item_drop_prob=8`、`balance.lua_spell_drop_prob=6`、`balance.lua_mob_spawn_prob=0.05`。
3. 编写 `entry.lua`:注册 `remixed_full_alpha_hub` 关卡,打印加载横幅。
4. 编写 item scripts (scripts/items/*.lua):约 10 个,weapon 使用 tier/image/attackProc;material 使用 type="material"/stackable/price;每个声明 `spriteFile` 与 `image` fallback。
5. 编写 spell scripts (scripts/spells/*.lua):覆盖 cell/self 目标、伤害/治疗/位移/增益/工具,每个声明 `spriteFile` 与 `image` fallback。
6. 编写 mob scripts (scripts/mobs/*.lua):6 个低层 mob,使用 sprite 白名单 fallback(rat/gnoll/bat),可带简单 attackProc/spawn 回调,不依赖 luajava。
7. 编写 `scripts/shops/remixed_full_alpha_shop.lua`:售卖 2 个 Lua item、2 个 Lua spell、1 个 ration。
8. 编写 `mods/levels/remixed_full_alpha_hub.json`:小型 12x12 安全关卡,包含 entrance、shop、3 个 mob、若干散落金币与 Lua item。
9. 新增 `RemixedFullPackTest`:
   - 启用 remixed_full 后验证 item≥10、spell≥5、mob≥6、shop 可创建、hub 可注册;高概率覆盖下 Generator.random() 能产出 Lua item/spell,MobSpawner 出现 LuaMobFactory,createMob 产出真实 LuaMob。
   - **未启用 remixed_full 时:所有 remixed_full 注册项 item/spell/mob/shop/hub 均不出现,Generator.random() 不产出该包 Lua item/spell,MobSpawner rotation 不含 LuaMobFactory,确保 C3 基线不受污染。**
10. 运行 `./gradlew :core:test`。
11. codex 评审:必须 `assign("codex_reviewer", ...)`;如果 assign 失败或 reviewer 不可用,跳过该评审阶段并在最终回报给 dispatcher 裁决,不要直接调用 codex-cli/codex exec。

## Acceptance
- [ ] `remixed_full` 有有效 `mod.json` 和 `entry.lua`,可被 ModScanner/LuaEngine 加载。
- [ ] 至少注册 10 个 Lua item/material/weapon。
- [ ] 至少注册 5 个 Lua spell。
- [ ] 至少注册 6 个 Lua mob。
- [ ] 至少 1 个 Lua shop 可售卖 Lua item 和 Lua spell。
- [ ] 至少 1 个 alpha hub/custom level 可从自定义关卡入口发现。
- [ ] `balance` opt-in 后主游戏可掉落/刷出 remixed_full 内容。
- [ ] 内容全部不依赖 luajava。
- [ ] `./gradlew :core:test` 通过。
- [ ] 不提交 `.claude/`,不使用 `git add -A`。

## Notes
- 不要一次移植整个 Remixed;本 milestone 的目标是“玩家可见的闭环 alpha”。
- 若和 M16a 的 `spriteFile` runtime 字段产生冲突,保留脚本字段,把 Java runtime 让给 M16a。
- 平衡保守:默认启用与否由 worker 根据现有 mod 策略判断;若 default_enabled=true,概率必须很低并确保 C3 测试不污染旧断言。
