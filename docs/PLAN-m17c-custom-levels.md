# PLAN: M17c — 精选自定义关卡(参考 remished tmx 重绘)

## Goal
参考 remished `TiledMaps/`(Town/Inn/Library 等 tmx)的布局设计,用 fork 的 DataDrivenLevel json 格式**重绘 2 个精选关卡**,放入 `remixed_full` 内容包,引用 m17a 新落地的 6 个城镇 NPC,丰富 remixed_full 可玩内容。

## Context
fork 已有 4 个自定义关卡 json(remished_lite_hub / remixed_full_alpha_hub / regression_demo_level / test_safezone),但 remixed_full_alpha_hub 只是 12×12 的小 showcase。remished `TiledMaps/` 有 19 个 tmx(Town/Inn/Library/Church/Theater…),是真正的城镇/副本关卡,但格式与 fork 不兼容。

**格式差异**(调研确认,关键):
- **fork DataDrivenLevel json**:`{id, name, width, height, tiles:[语义 tile 名数组], entrance, exit, safe, mobs:[{type,pos}], items:[{type,pos,quantity?}]}`。`pos` = tile 索引(row*width+col)。mobs 的 `type` 支持:`lua_npc:<id>` / `lua_mob:<id>` / `lua_shop:<id>` / vanilla mob id。见 `DataDrivenLevel.java`(L73 `LUA_NPC_PREFIX`,L220-227 解析;`lua_mob:`/`lua_shop:` 同理)。
- **remished tmx**:Tiled 编辑器 XML,gid 数字引用外部 .tsx tileset,10 layer。**不是格式转换,是参考重绘** —— 读 tmx 的房间布局/物件摆放意图,用 fork 支持的 tile 名重画。

**tile 名**:参考已有 hub(remished_lite_hub.json 用 "wall" 等)。worker 实施前**必须读 `DataDrivenLevel.java` 的 TerrainParser / tile 名白名单**(或 `TileParser`),只用 fork 支持的 tile 名 —— 未知名会 fallback 或报错。不确定的 tile 用最保守的(wall / 空地等价物)。

**m17a NPC 可用**(已在 master):`lua_npc:remixed_full_drunkard` / `_bard` / `_black_cat` / `_barman` / `_bishop` / `_inquirer`。remixed_full 已有 mob(`lua_mob:remixed_full_kobold` / `_black_rat` 等)与 shop(`lua_shop:remixed_full_alpha_shop`)。

## 选型(2 个关卡,参考 remished,精简尺寸)
1. **`remixed_full_tavern`(酒馆)** — 参考 remished `Inn.tmx`/`Town.tmx`。放氛围 NPC:drunkard(醉汉)+ bard(吟游)+ barman(酒保)。尺寸 16×16。1 个 shop(复用 alpha_shop)+ 若干 gold item。
2. **`remixed_full_chapel`(小教堂/图书馆)** — 参考 remished `Church.tmx`/`Library.tmx`。放 bishop(主教)+ inquirer(调查者)+ black_cat(黑猫)。尺寸 16×16。若干 remixed_full item(dark_gold/ration)。

(尺寸保守:16×16 = 256 tile 手工布局,可控;不贪 32×32 的 1024 tile。若 worker 觉得 16×16 太小可放到 20×20。)

## Files
预计改/新增(路径相对 worktree 根):
- `core/src/main/assets/mods/levels/remixed_full_tavern.json`(新)
- `core/src/main/assets/mods/levels/remixed_full_chapel.json`(新)
- `core/src/main/assets/mods/remixed_full/entry.lua`(改:加 2 个 `register_level`)
- 测试:`core/src/test/.../RemixedFullPackTest.java`(扩展:断言 2 新关卡在 LuaLevelRegistry 注册 + 引用的 NPC/mob/shop id 全部存在)
- 参考(不改):
  - 已有 hub 范本:`core/src/main/assets/mods/levels/remixed_full_alpha_hub.json`(json 结构 + mobs/items 格式)、`remished_lite_hub.json`(更大尺寸范本)
  - remished 布局参考:`../remixed-dungeon/TiledMaps/{Inn,Town,Church,Library}.tmx`(读布局意图,不直接转)
  - tile 名白名单:`DataDrivenLevel.java`(TerrainParser / tile 解析)

避免改动:
- 不改 fork Java(DataDrivenLevel / LuaLevelService 已就绪)
- 不改 m17a NPC 脚本(只引用)
- 不直接转换 tmx(参考重绘,不是格式搬运)

## Steps
1. 读 `DataDrivenLevel.java` 确认 tile 名白名单 + mobs/items 解析(`lua_npc:`/`lua_mob:`/`lua_shop:` 前缀处理 + pos 语义)。读 `remixed_full_alpha_hub.json` 掌握 json 结构。
2. 读 remished `Inn.tmx` / `Church.tmx`(或 Town/Library)的布局意图(房间划分、物件摆放)—— 只看 Tiled 编辑器里的视觉布局截图级别的信息(layer name + object 位置),不纠结 gid。
3. 重绘 `remixed_full_tavern.json`(16×16):用 fork tile 名画墙壁/地面/门;`entrance`/`exit`/`safe` 设合法 tile;`mobs` 放 drunkard/bard/barman(lua_npc:)+ shop(lua_shop:);`items` 放 gold。
4. 重绘 `remixed_full_chapel.json`(16×16):bishop/inquirer/black_cat(lua_npc:)+ 若干 remixed_full item。
5. `entry.lua` 加 2 个 `register_level{ id=..., name=... }`(levels 无目录自动扫描,必须 entry 注册)。
6. 扩展 `RemixedFullPackTest`:断言 2 新关卡注册 + 每个 NPC/mob/shop id 在对应 Registry 存在(LuaNpcRegistry/LuaMobRegistry/LuaShopRegistry)。
7. `./gradlew :core:test` 绿。
8. **codex 评审**:必须 `assign("codex_reviewer", ...)`;若 assign 失败/不可用,**跳过评审并在回报告知 dispatcher 裁决,不要直接调用 codex-cli / codex exec**。

## Acceptance
- [ ] 2 个新关卡 json(`remixed_full_tavern` / `remixed_full_chapel`)落地,`register_level` 注册成功。
- [ ] 关卡引用的 NPC(lua_npc:remixed_full_*)全部是 m17a 已注册 id;mob/shop id 存在;无悬空引用。
- [ ] tile 名全部 fork 支持(读 DataDrivenLevel 白名单核对);entrance/exit/safe pos 合法(非墙、在边界/合理位置);mobs/items pos 不重叠墙。
- [ ] `:core:test` 绿;RemishedFullPackTest 扩展覆盖新关卡。
- [ ] 不改 fork Java;不改 m17a NPC;不提交 `.claude/`;不用 `git add -A`。

## Notes
- 这是**手工关卡设计**(参考 remished 布局 + fork tile 重绘),不是格式转换。重点:tile 名合法、pos 语义正确、NPC 引用真实。布局美观度次要(可玩 > 精致)。
- 若 16×16 放不下预期 NPC/物件,可放到 20×20;不要 32×32(手工量过大)。
- remished tmx 的 layer/object 细节(特定 tile 的语义)不必忠实复刻 —— fork tile 名有限,做到"房间 + 门 + NPC + 物件摆放合理"即可。
- 关卡内 NPC 交互(showDialog 等)由 m17a NPC 脚本定义,本 feature 只负责放置(坐标 + 引用)。

## Verified findings(worker 阶段 1 核对,2026-07-10)

读 `DataDrivenLevel.java`(实际类名,非 PLAN 早期草拟的 TileParser)+ `LuaLevelService.java` + `RemixedFullPackTest.java` 后确认:

### Runtime spawn surface(关键约束,决定 mobs/items 选型)
`DataDrivenLevel.createMobs()` / `createItems()` 只识别下列 spec,其余 **静默 skip**(log "unknown … type — skipping"):
- **mobs**:`lua_npc:<id>`(LUA_NPC_PREFIX)、`lua_shop:<id>`(LUA_SHOP_PREFIX)、vanilla `rat_king`(`MOB_TYPES` 唯一条目)。**`lua_mob:` 前缀无处理路径** —— alpha_hub.json 里的 `lua_mob:remixed_full_kobold` / `_black_rat` 实际不生成(pre-existing,本 feature 不改 Java)。
- **items**:仅 `gold`(`ITEM_TYPES` 唯一条目,带 `quantity`)。alpha_hub 的 `remixed_full_dark_gold` / `remixed_full_remixed_ration` 同样静默 skip。

**决策**:新关卡只用**可生成** spec,保证"可玩 > 精致":
- tavern:`lua_npc:remixed_full_{drunkard,bard,barman}` + `lua_shop:remixed_full_alpha_shop` + `gold`(若干 quantity)。
- chapel:`lua_npc:remixed_full_{bishop,inquirer,black_cat}` + `gold`。
(原 PLAN chapel 写"dark_gold/ration item",但那些 item 在 DataDrivenLevel 不生成;改用 `gold` 以保证物件真实落地。test 仍断言"引用的 npc/shop id 全部存在"。)

### Tile 白名单(全部 fork 支持)
`DataDrivenLevel.TILE_NAMES`(L337–379)。关卡设计将只用其中安全子集:`wall` / `floor`(=EMPTY 别名)/ `entrance` / `exit` / `bookshelf`(solid 装饰)/ `pedestal`(passable 装饰)/ `statue`(solid 装饰)。未知/未列名 tile → fallback wall。solid 装饰(bookshelf/statue)只放在不阻断连通性的位置(单格或带缺口的线段)。

### pos 语义
`pos = row*width + col`(0-index)。mob spec 校验 `!passable[spec.pos]` → 必须落在 passable tile(floor/pedestal/entrance/exit),不能在 wall/bookshelf/statue。item spec 不校验 passable 但应落 floor。entrance/exit cell 各放一个 `entrance`/`exit` tile(alpha_hub 范例)。

### 测试影响面
- `RemishedFullPackTest.enabled_loadsFullAlphaManifest` 无 `LuaLevelRegistry.size()` 断言(只 `contains`),新增 2 关卡不破坏 size 断言;`LuaShopRegistry.size()==1` / `LuaNpcRegistry.size()==6` 不变(不新增 npc/shop)。
- `DataDrivenLevelTest.registryHoldsRegisteredLevels` 自带 `LuaLevelRegistry.clear()`,隔离,不受影响。
- remished tmx 不在 sibling 路径(`../remished-dungeon/` 不可达)→ 按酒馆/小教堂概念手工布局,不复刻 tmx。

### 生成方式
256 tile 手写易错,用一次性 Python 脚本(放 /tmp,不入仓)按上述布局生成 2 个 json 并自检(tiles 长度==256、mobs/items pos 落在 floor、entrance/exit tile 位置正确),再 Read 回读核对,最后 `:core:test` 验证。
