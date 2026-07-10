# PLAN: M18d — DataDrivenLevel 生成扩展(lua_mob + 自定义 item)

## Goal
给 `DataDrivenLevel` 的 `createMobs` / `createItems` 加 `lua_mob:` 和自定义 item 生成分支,让关卡 json 能放置 lua_mob 和 lua item(解除 m17c 关卡只能放 lua_npc:/lua_shop:/gold 的限制)。

## Context
M17c 关卡(tavern/chapel)只能用可生成 spec(lua_npc:/lua_shop:/gold),因为 `DataDrivenLevel.createMobs` 只处理 lua_npc:/lua_shop:/vanilla,`createItems` 只处理 gold + vanilla item;`lua_mob:` 和 remixed_full 自定义 item 走不到生成路径,**静默 skip**(m17c commit message + reviewer 阶段2 都确认了这个 latent 行为)。

**调研确认改动面非常干净**(纯 DataDrivenLevel 内部,不碰 RpdApi):
- `createMobs()`(L218)已有分支:`lua_npc:`(L223,调 `LuaNpcRegistry.create`)+ `lua_shop:`(L241,调 `LuaShopRegistry.create`)+ vanilla mob。照抄加 `lua_mob:` 分支调 `LuaMobRegistry.create(id)`(**该方法已存在,L43**)。
- `createItems()`(L272)处理 gold + vanilla item。加 `lua_item:`(或商定的前缀)分支调 `LuaItemRegistry.createItem(id)`(**已存在,L61**)。
- **Registry.create 方法全部已存在**,本 feature 只在 DataDrivenLevel 加分支调用,零新 Java API,不碰 RpdApi.java。

**前缀命名**:worker 调研 `DataDrivenLevel` 顶部常量区(L73 `LUA_NPC_PREFIX`、L75 `LUA_SHOP_PREFIX`),加 `LUA_MOB_PREFIX = "lua_mob:"`。item 前缀同理(看 createItems 现有 vanilla item 处理,决定 `lua_item:` 前缀)。

## Files
预计改(路径相对 worktree 根):
- `core/src/main/java/.../modding/DataDrivenLevel.java`:
  - 顶部常量区(L73 `LUA_NPC_PREFIX`、L75 `LUA_SHOP_PREFIX` 之后)加 `LUA_MOB_PREFIX = "lua_mob:"` + `LUA_ITEM_PREFIX = "lua_item:"`
  - `createMobs()`(L218)在 lua_shop 分支(L241-255)之后、`MOB_TYPES` 查询(L256)之前插 `lua_mob:` 分支(照 lua_npc 模式 L223-237:`LuaMobRegistry.create(id)` → null 检查 → `passable[pos]` 校验 → `mob.pos=spec.pos; mobs.add(mob)`)
  - `createItems()`(L272)在循环开头、`ITEM_TYPES.get` 查询(L274)之前插 `lua_item:` 分支(照现有 bounds-only 校验 L279:`LuaItemRegistry.createItem(id)` → null 检查 → bounds 校验 → `drop(item, spec.pos)`)。**不收紧 passable 校验**(现有 gold/vanilla item 也不查 passable,保持一致)
- 测试:`core/src/test/.../modding/DataDrivenLevelTest.java` 加 4 个用例(详见 Steps)
- 示例验证:`core/src/main/assets/mods/levels/remixed_full_tavern.json` 加 1 个 `lua_mob:remixed_full_kobold` + 1 个 `lua_item:remixed_full_battle_axe`(desktop 端到端验证用)
- 参考(不改,已核对):
  - `DataDrivenLevel.java` L223-255(lua_npc:/lua_shop: 分支范本)
  - `LuaMobRegistry.create(String)→LuaMob`(L43,已存在)
  - `LuaItemRegistry.createItem(String)→Item`(L61,已存在,typed-dispatch:material→LuaMaterial / else LuaItem)
  - **同包,无需新 import**(`LuaMob`/`LuaMobRegistry`/`LuaItemRegistry` 与 DataDrivenLevel 同在 `...modding` 包;`Item` 已 import)

避免改动:
- 不碰 `RpdApi.java`(那是 m18a 范围,并行 worktree)
- 不改 Registry 类(它们 create 方法已就绪)
- 不改 m17a NPC 脚本

## Steps
1. (已做)读 `DataDrivenLevel.java` createMobs(L218-269)+ createItems(L272-286)、两个 Registry 的 create 方法签名、现有测试 fixture 形状、remixed_full 真实注册 id。核对结论见上 Files 节。
2. **常量**(L73-75 之后):加 `LUA_MOB_PREFIX = "lua_mob:"` + `LUA_ITEM_PREFIX = "lua_item:"`(带 javadoc 注释,风格照 LUA_NPC_PREFIX)。
3. **createMobs lua_mob 分支**:在 lua_shop 分支(L255 `continue;` 之后)、`MOB_TYPES.get`(L256)之前插入。结构:
   ```java
   if (spec.type != null && spec.type.startsWith(LUA_MOB_PREFIX)) {
       String mobId = spec.type.substring(LUA_MOB_PREFIX.length());
       LuaMob mob = LuaMobRegistry.create(mobId);
       if (mob == null) { Gdx.app.error(TAG, "unknown lua_mob id: " + mobId + " — skipping"); continue; }
       if (spec.pos < 0 || spec.pos >= length() || !passable[spec.pos]) {
           Gdx.app.error(TAG, "lua_mob " + mobId + " pos " + spec.pos + " invalid — skipping"); continue;
       }
       mob.pos = spec.pos;
       mobs.add(mob);
       continue;
   }
   ```
4. **createItems lua_item 分支**:在循环体开头(L274 `ITEM_TYPES.get` 之前)插入。结构与现有 gold/vanilla 一致(bounds-only,**不加 passable 校验**):
   ```java
   if (spec.type != null && spec.type.startsWith(LUA_ITEM_PREFIX)) {
       String itemId = spec.type.substring(LUA_ITEM_PREFIX.length());
       Item item = LuaItemRegistry.createItem(itemId);
       if (item == null) { Gdx.app.error(TAG, "unknown lua_item id: " + itemId + " — skipping"); continue; }
       if (spec.pos < 0 || spec.pos >= length()) {
           Gdx.app.error(TAG, "lua_item " + itemId + " pos " + spec.pos + " invalid — skipping"); continue;
       }
       drop(item, spec.pos);
       continue;
   }
   ```
   （quantity 对 lua item 无意义,忽略,每次生成 1 个;与 `createItem` 返回单例语义一致。）
5. **测试**(`DataDrivenLevelTest`):headless harness 已能 `new LuaMob`/`new LuaItem`。新增用例,统一调用 `lvl.create()`(全生命周期,会 buildFlagMaps 填 `passable[]` + 初始化 `mobs`/`heaps`):
   - `luaMobSpawnsFromPrefix`:LuaMobRegistry.register("dd_test_mob", mobTable{id,name,hp,attack,defense}) → json mobs 含 `lua_mob:dd_test_mob`@passable pos(如 50)→ create() 后 `lvl.mobs` 含 1 个 LuaMob 在 pos 50。@After 清理 registry。
   - `luaMobUnknownIdSkipped`:json mobs 含 `lua_mob:does_not_exist` → create() 后 mobs 空,无异常。
   - `luaItemSpawnsFromPrefix`:LuaItemRegistry.register("dd_test_item", itemTable{id,name,desc,tier,image,type}) → json items 含 `lua_item:dd_test_item`@pos(如 90)→ create() 后 `lvl.heaps.get(90)` 非空且含 LuaItem。
   - `luaItemUnknownIdSkipped`:`lua_item:does_not_exist` → 该 pos 无 heap,无异常。
   （fixture table 形状直接抄 LuaMobTest.baseTable / LuaShopTest.luaItemTable 的最小字段集。）
6. **示例 json**(`remixed_full_tavern.json`):mobs 加 `{"type":"lua_mob:remixed_full_kobold","pos":50}`(y=3,x=2,floor,passable,不与现有 NPC/exit 重叠);items 加 `{"type":"lua_item:remixed_full_battle_axe","pos":100}`(y=6,x=4,floor)。仅 desktop 端到端验证用,不塞满关卡。
7. `./gradlew :core:test` 绿(已知 flaky: `GeneratorLuaItemTest.luaItemProbabilityPersistsAcrossFullReset` 单测失败直接重跑即可,非本 feature 回归)。
8. **codex 评审**:必须 `assign("codex_reviewer", ...)`;若 assign 失败/不可用,**跳过评审并在回报告知 dispatcher 裁决,不要直接调用 codex-cli / codex exec**。

## Acceptance
- [ ] 关卡 json 写 `lua_mob:<id>` → 关卡 build 后生成对应 LuaMob(放 pos);未知 id 照 lua_npc 模式 skip + error log。
- [ ] 关卡 json 写 lua item(商定前缀)→ 生成对应 LuaItem 放 pos。
- [ ] 不破坏现有 lua_npc:/lua_shop:/gold/vanilla mob+item 生成(m17c tavern/chapel 仍正常)。
- [ ] `:core:test` 绿;DataDrivenLevelTest 扩展覆盖 lua_mob + lua item 分支。
- [ ] 不碰 RpdApi.java / Registry 类;不提交 `.claude/`;不用 `git add -A`。

## Notes
- 这是 M18 批次**风险最低**的(纯生成分支扩展,Registry.create 已存在,照 lua_npc: 模式抄)。适合和 m18a 并行跑(零文件冲突)。
- lua_mob 是敌对 mob(faction hostile),放置 pos 要在合理位置(别和 NPC/exit 重叠);item 放地面 pos。
- item 前缀命名 worker 定(建议 `lua_item:`,和 lua_npc:/lua_shop:/lua_mob: 风格一致)。
- 示例扩展 m17c 关卡时,只加 1-2 个验证用,不要把关卡塞满(可玩性是 m17c 的事,本 feature 只验证生成路径)。
