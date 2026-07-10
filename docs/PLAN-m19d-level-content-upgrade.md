# PLAN: M19d — Level content upgrade with lua_mob/lua_item

## Goal
利用 M18d 的 `lua_mob:`/`lua_item:` 生成能力,升级 remixed_full 的 tavern/chapel/alpha_hub 内容摆放,让 M17c 关卡从展示 NPC/金币变成含真实 Lua mob/item 的可玩 showcase。

## Context
M17c 因 DataDrivenLevel 只能生成 lua_npc/lua_shop/gold,关卡内容保守。M18d 已让 json 可写 `lua_mob:<id>` 和 `lua_item:<id>`(见 `DataDrivenLevel.createMobs/createItems` 的 `LUA_MOB_PREFIX`/`LUA_ITEM_PREFIX` 分支)。本 feature 纯内容/测试,不改 Java API。

## 关键事实(探索核对)
- `DataDrivenLevel.ITEM_TYPES` 白名单只有 `gold`;`MOB_TYPES` 只有 `rat_king`。**所以 hub 现有的 `"remixed_full_dark_gold"` / `"remixed_full_remixed_ration"`(无 `lua_item:` 前缀)在 `createItems()` 里命中 `unknown item type` 被静默 skip** —— 这就是 PLAN 要修的 "latent" spec。
- `lua_mob:` 分支会检查 `passable[spec.pos]`,非通路直接 skip;`lua_item:` 分支只 `drop(item, pos)` 不检查通路。因此 mob 的 pos 必须是 floor。
- 可用 Lua mob(6): kobold / black_rat / fetid_rat / hedgehog / cold_spirit / bandit。
- 可用 Lua item(10): battle_axe / hooked_dagger / lantern_blade / mace / kunai(weapon=LuaItem)+ remixed_ration / dark_gold / rotten_organ / toxic_gland / rusty_coin(material=LuaMaterial)。
- `create()` headless 安全(`Random.pushGenerator(Dungeon.seedCurDepth())` + `build/buildFlagMaps/createMobs/createItems`,无 GameScene 依赖,`DataDrivenLevelTest.luaMobSpawnsFromPrefix` 已证)。

## Files
- `core/src/main/assets/mods/levels/remixed_full_tavern.json`
- `core/src/main/assets/mods/levels/remixed_full_chapel.json`
- `core/src/main/assets/mods/levels/remixed_full_alpha_hub.json`
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/RemixedFullPackTest.java`(新增 1 个 M19d 测试方法)
- `docs/PLAN-m19d-level-content-upgrade.md`

## Steps

### 1. Tavern(+1 mob、+2 item;已有 kobold@50、battle_axe@100 保留)
现有占用 pos:23(entrance)、50、68、100、103、115、140、172、184、198、231(exit)。
新增(均经 grid 核对为 floor、不与现有 pos 重叠):
- `lua_mob:remixed_full_black_rat` @ **162**(row10 col2,左下隔离区)
- `lua_item:remixed_full_dark_gold` @ **158**(row9 col14,孤立角落;material)
- `lua_item:remixed_full_remixed_ration` @ **193**(row12 col1;material)

### 2. Chapel(当前无任何 lua_mob/lua_item;+1 mob、+3 主题 item)
现有占用 pos:23(entrance)、55、74、107、135、180、195、231(exit)。
新增(均 floor、不重叠):
- `lua_mob:remixed_full_cold_spirit` @ **161**(row10 col1,隔离)
- `lua_item:remixed_full_rotten_organ` @ **88**(row5 col8,中殿 —— 主题贴合 chapel)
- `lua_item:remixed_full_toxic_gland` @ **40**(row2 col8)
- `lua_item:remixed_full_dark_gold` @ **130**(row8 col2)

### 3. Alpha hub(把 2 个 latent item 升级到 `lua_item:` 前缀)
- `gold` @ 62 不变
- `remixed_full_dark_gold` @ 63 → **`lua_item:remixed_full_dark_gold`** @ 63
- `remixed_full_remixed_ration` @ 74 → **`lua_item:remixed_full_remixed_ration`** @ 74
- mobs 不变(shop@50、kobold@38、black_rat@40,均已正确前缀)
- hub mobs/items 计数保持 3/3,`hubLevelAsset_isStructurallyValid` 的 count 断言不受影响。

### 4. 测试(RemixedFullPackTest 新增 `m19d_luaMobAndLuaItem_spawnAcrossRemixedFullLevels`)
对 tavern / chapel / hub 三个 asset 各做一遍:
1. `enableRemixedFull()` + `LuaEngine.init()`(让真实 mob/item id 进 registry)。
2. 解析 JSON,收集 expected `lua_mob:`/`lua_item:` spec 的 (id,pos)。
3. `DataDrivenLevel.fromAsset(asset,id).create()`。
4. 断言每个 `lua_mob:` spec → `lvl.mobs` 里存在 `LuaMob` 且 `pos` == spec.pos。
5. 断言每个 `lua_item:` spec → `lvl.heaps.get(pos)` 非空,`peek()` 为 `LuaItem` 或 `LuaMaterial`。
6. 断言所有 spec pos 互不重叠,且 != entrance/exit。
7. 断言每个 `lua_mob:` spec pos 的 `Terrain.flags[map[pos]]` 含 PASSABLE、不含 SOLID(item 不强求,但顺带也断言非 SOLID)。
- 不改写现有 `m17c_levels_areStructurallyValidAndPositionsPassable`(它继续覆盖 tavern/chapel 的全量 pos 通路性,会自动覆盖新增 spec)。

### 5. 验证
- `./gradlew :core:test --tests '*RemixedFullPackTest*'` 绿(含新测试 + 现有 m17c 通路性,会校验新增 pos)。
- 再跑一遍全量 `./gradlew :core:test` 确认无回归(已知 flaky `GeneratorLuaItemTest.luaItemProbabilityPersistsAcrossFullReset` 重跑即过,按 cao memory 处理)。

## Acceptance
- [x] 探索阶段已确认 3 个 level 都将使用 `lua_mob:`/`lua_item:`(tavern/chapel 新增、hub 转前缀)。
- [ ] 3 个 level 通过新 M19d spawn 测试 + 现有 m17c 通路性测试。
- [ ] 不改 Java API,只改 json/tests。
- [ ] 关键 NPC/shop/entrance/exit 不重叠(新 pos 均经 grid 核对)。
- [ ] 不提交 `.claude/`;不 `git add -A`,按文件 add。

## Pending Issues
(无)
