# PLAN — M20f: remixed_full Lua 关卡内容扩展

## Goal
为 `remixed_full` 新增 1-2 个自定义关卡(level),用 `DataDrivenLevel` JSON 定义,JSON 内通过 `lua_mob:` / `lua_item:` spec 引用现有 remixed_full mob/item。**本方向独占 `entry.lua`**(其他 M20 worker 不碰它,无冲突)。

## Context
- `entry.lua` 位于 mod 根(`core/src/main/assets/mods/remixed_full/entry.lua`),手动 `register_level{id=, name=}` 注册每个关卡。现有 3 个:`remixed_full_alpha_hub` / `_tavern` / `_chapel`。`register_level` 只记 id→table;JSON 由 `LuaLevelService.loadLevelById` 按 id 解析(本 worker 独占 entry.lua,仅追加)。
- 关卡 JSON **实际目录是 `core/src/main/assets/mods/levels/`**(不是 `mods/remixed_full/`):现有 `remixed_full_alpha_hub.json` / `_tavern.json` / `_chapel.json` 都在那。`LuaLevelService.LEVELS_DIR = "mods/levels/"`,builtin mod 按 classpath `mods/levels/<id>.json` 解析。
- `DataDrivenLevel.fromJsonValue` 强校验:`width>0 & height>0`、`tiles.length == width*height`、`entrance ∈ [0, width*height)`。tile 名必须在 `TILE_NAMES` map 内(`wall`/`floor`/`entrance`/`exit`/`statue`/`pedestal`/`door`/`bookshelf`/…)。JSON 内允许 `_comment` 字段(被忽略)。
- mob spec `{"type":"lua_mob:<id>","pos":N}`:`createMobs` 要求 `passable[pos]`(不能落在 wall/statue/pedestal)。item spec `{"type":"lua_item:<id>","pos":N[, "quantity":Q]}`:只校验 pos 在 `[0,length)`。M19d 已在 tavern/chapel 验证此机制。
- 现有可引用 mob id(6 个):`remixed_full_{kobold,black_rat,cold_spirit,fetid_rat,hedgehog,bandit}`。tavern 已用 kobold+black_rat,chapel 已用 cold_spirit → 本 level 用**未被引用**的 `bandit`/`hedgehog`/`fetid_rat`(扩展展示面)。
- 现有可引用 item id(14 个,含 M19e port):`battle_axe/bone_shard/dark_gold/fried_fish/hooked_dagger/kunai/lantern_blade/mace/remixed_ration/rotten_fish/rotten_organ/rusty_coin/toxic_gland/vile_essence`。tavern/chapel 已用 battle_axe/dark_gold/remixed_ration/rotten_organ/toxic_gland → 本 level 用**未被引用**的 `kunai`/`vile_essence`/`rusty_coin`。
- `RemixedFullPackTest` 对 level 是 `assertTrue(...registered)` 风格(第 ~188-194 行),**不是计数** → 加新 level 不破坏现有断言,**不要改它**。headless 读 asset 已验证可用:`DataDrivenLevel.fromAsset(path,id)` + `Gdx.files.internal(path).readString("UTF-8")`(见 RemixedFullPackTest:368、SafeZoneEnterTest:74)。

## Files
- 改 `core/src/main/assets/mods/remixed_full/entry.lua` — **追加** `register_level{id="remixed_full_rf_arena", name="Remixed Full RF Arena"}` 块;**不删**现有 3 个 level 注册。
- 新增 `core/src/main/assets/mods/levels/remixed_full_rf_arena.json` — **16x16**(与 3 个 sibling level 同尺寸,最低风险;PLAN 草拟的 12x12 改为 16x16 以匹配已验证约定)= 256 tiles;wall 边框 + floor 内部 + 4 根 statue 柱(arena 感)+ entrance@23 + exit@231;≥1 `lua_mob:` + ≥1 `lua_item:` spec,引用现有 id。
- 新增 `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/RemixedFullLevelContentTest.java` — enable remixed_full → `LuaLevelRegistry.contains("remixed_full_rf_arena")`;`DataDrivenLevel.fromAsset` 解析新 JSON 不抛 + width/tiles 一致 + mobs/items 各含 ≥1 个 `lua_mob:` / `lua_item:` spec。

## Steps
1. (已做)探索确认:level JSON 在 `mods/levels/`;读 tavern/chapel JSON 学结构;确认 mob/item id;确认 headless asset 读取模式。
2. `entry.lua` 末尾追加新 `register_level{id="remixed_full_rf_arena", name="Remixed Full RF Arena"}` 块(保留现有 3 个不动)。
3. 写 `remixed_full_rf_arena.json`:16x16=256 tiles。tile 生成(程序化避免计数错):border=wall;y=1..14,x=1..14 内部=floor;`entrance` tile@(7,1)=cell23;`exit` tile@(7,14)=cell231;4 根 statue 柱@(4,4)=68 / (11,4)=75 / (4,11)=180 / (11,11)=187。
   - mobs(均落 passable floor,非 statue/wall/entrance/exit):`lua_mob:remixed_full_bandit`@102 (6,6)、`lua_mob:remixed_full_hedgehog`@105 (9,6)、`lua_mob:remixed_full_fetid_rat`@199 (7,12)。
   - items:`lua_item:remixed_full_kunai`@136 (8,8) qty1、`lua_item:remixed_full_vile_essence`@51 (3,3)、`lua_item:remixed_full_rusty_coin`@204 (12,12)、`gold`@165 qty100、`gold`@170 qty75。
   - `safe: true`(与 sibling 一致;ephemeral,不污染主存档)。
4. 写 `RemixedFullLevelContentTest.java`(mirror RemixedFullPackTest 的 HeadlessApplication + enableRemixedFull + LuaEngine.init 模式):
   - `@Test rfArenaLevelRegistered()`:enable remixed_full → `LuaEngine.init()` → `assertTrue(LuaLevelRegistry.contains("remixed_full_rf_arena"))`。
   - `@Test rfArenaJsonParsesAndStructurallyValid()`:读 asset → `DataDrivenLevel.fromAsset("mods/levels/remixed_full_rf_arena.json", id)` 非 null;`assertEquals(16, lvl... )` 需先 build();校验 tiles 数 == 256、entrance 在界;遍历 JSON mobs/items 断言 ≥1 type 以 `lua_mob:` / `lua_item:` 开头。
5. `./gradlew :core:test` 绿(flaky: GeneratorLuaItemTest/GeneratorLuaSpellTest 概率断言,单独重跑即过)。

## Acceptance
- [ ] 新 level `remixed_full_rf_arena` 注册成功,JSON 合法可解析。
- [ ] JSON 含 ≥1 `lua_mob:` + ≥1 `lua_item:` spec,引用现有 id。
- [ ] `RemixedFullLevelContentTest` 通过。
- [ ] `entry.lua` 现有 3 个 level 注册**未被删除/破坏**。
- [ ] `:core:test` 全绿。
- [ ] 未改 `RemixedFullPackTest.java`。

## Constraints(强制)
- 只在自己 worktree 改动。
- **独占** `entry.lua`,但只能**追加**,绝不删现有 register_level 块。
- 绝不改 `RemixedFullPackTest.java`(M20g 独占其计数行;你加 level 不破坏它的 assertTrue 风格断言,无需改)。
- 绝不 `git add -A` / commit `.claude/` / force push / reset --hard。

## 评审协议
完成 + 测试绿后,用 **`assign("codex_reviewer", ...)`** 评审(先 PLAN 再实现)。严禁直接 codex-cli。
- assign 失败/静默 → 跳过,回报说明,dispatcher 决定是否亲审。
- 复用同一 reviewer terminal。

## 回报协议
`send_message`(无 receiver_id)回报 caller:`[DONE]`/`[BLOCKED]` + commit hash + reviewer terminal_id/轮数(或跳过) + 测试结果 + 文件清单 + 新 level JSON 路径。
