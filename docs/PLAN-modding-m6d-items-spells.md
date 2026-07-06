# PLAN: M6d — Remished item/spell API + item/spell 脚本移植

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M6
> 前置:M6b 已合 master(`72c13386d` feature,merge 后 :core:test 通过,240 tests)
> 并行:M6c(buff)同时开发。**约定 RpdApi 分块 + 独立测试类,降低冲突**
> D5 = (a) B 全量;D5' = (a) 禁 luajava

## Goal

扩展现有 LuaItem/LuaSpell 与窄 inventory/item/spell API,移植 Remixed `scripts/items/` 与 `scripts/spells/` 的代表内容,解决 M6-fast 发现的非武器类型结构性错配与 M6b 预测的 ScriptedThief/背包摩擦点,但继续禁 luajava/object 句柄。

## Context

M6-fast 证明 C 路径只适合武器 reskin:材料套 `LuaItem extends MeleeWeapon` 会变成"材料名武器"。M6d 必须补类型能力,而不是继续硬套 LuaItem。

M6b 预测:item/spell API 估 2–4 天。关键摩擦点是 ScriptedThief/背包/item 句柄:遍历背包、随机选择 item、移除/转移 ownership、loot 持久化都属于 `chr:method()` 深度实例直调。建议窄 id API(`randomBackpackItem`/`stealItem`/`lootName`)而非开放 object 句柄。

Remished 脚本规模:
- item:22 个,21–461 行,总 1444 行。M6-fast 已搬 3 个材料数据皮但语义错配
- spell:32 个,28–103 行,总 1461 行

## Files

- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaItem.java`:保留为 weapon wrapper(`extends MeleeWeapon`),只做小幅 hydrate/price 支持;不再承载材料语义
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaMaterial.java`(新增):plain `Item` wrapper,hydrate `id/name/desc/image/price/stackable/quantity`,override `isSimilar`/bundle restore/value/identified/upgradable
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaItemRegistry.java`:registry 仍按 item id 存 Lua table;固定两个入口:`createItem(id): Item` 给通用路径/API/Generator,`createWeapon(id)`/旧 `create(id): LuaItem` 仅允许 weapon 并对 material 明确返回 null+日志,避免返回类型与分发行为矛盾
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaItemPool.java` + `items/Generator.java`:pool/random 返回 `Item`,`LUA_ITEM` category superClass 改成 `Item.class` 或保持生成点显式返回 `Item`,避免材料被强制视作 weapon
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaSpell.java`:继续作为 consumable `Item`;扩 hydrate 元数据(`castTime`/`targeting`/`spellCost` 可先仅保存),保持 `onUse(heroId)` 回调和 stackable 消耗语义
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaEngine.java`:放宽 `register_item` 校验;若 `type='material'`/`kind='material'` 不要求 `tier`,weapon item 仍要求/默认 `tier`;`register_spell` 接收代表 Remished spell 元数据但不引入 `scripts/lib/`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/RpdApi.java`:新增 `// M6d item/spell API` 分块,提供 id/index/cell API,不暴露 Java object 句柄
- `core/src/main/assets/mods/test_mod/scripts/items/`:把 `rotten_organ`/`bone_shard`/`toxic_gland` 改为 `type='material'`;新增/保留 2–3 个 weapon 代表(`hooked_dagger`/`bone_saw`/`kunai` 视最小可测性)
- `core/src/main/assets/mods/test_mod/scripts/spells/`:新增 6–8 个代表 spell(`heal`/`haste`/`charm`/`lightning_bolt`/`town_portal`/`summon_beast`/`raise_dead`/`sprout`),用现有 `RPD.*` 窄 API 近似移植
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/DataSkinItemTest.java`:改写 M6-fast 结构性错配断言为 M6d 修复断言(材料不再是 `MeleeWeapon`,stackable/price 生效)
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/RpdApiItemSpellTest.java`(新增):覆盖 M6d API、LuaMaterial bundle/merge、代表 spell 注册、sandbox 无 `luajava`
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModToggleRegressionTest.java`:更新 exact IDs/sizes;M6c 合并时取并集

## Steps

1. **材料 wrapper 与注册分发**
   - 新增 `LuaMaterial extends Item`,字段:`luaItemId/nameStr/descStr/priceValue/defaultQuantity`;instance init `stackable=true`;hydrate 元数据读取 `id/name/desc(info alias)/image/price/stackable`,首次创建时单独应用脚本默认 `quantity`。
   - `LuaMaterial.storeInBundle/restoreFromBundle` 持久化 `lua_item_id`,先 `super.restoreFromBundle` 恢复真实 quantity,再用 `LuaItemRegistry.getTable` rehydrate 元数据且**不得覆盖 bundle quantity**;`isSimilar` 仅同 class 且同 id 合并;`value()` 返回 `priceValue * quantity()`;`isUpgradable=false`,`isIdentified=true`。
   - `LuaItemRegistry.createItem(id)` 根据 table `type` 或 `kind` 分发:material → `LuaMaterial`,否则 weapon → `LuaItem`;保留 `create(id): LuaItem` 作为 weapon-only legacy 入口并新增/委托 `createWeapon(id)`,material 传入该入口时明确返回 null+日志,测试覆盖 material 不被 legacy 路径静默当武器。
   - `LuaEngine.RegisterItemFunction` 校验改为:id/name 必填;`type/kind=material` 不要求 tier;非 material weapon 仍要求/默认 tier 以保持旧 weapon scripts 可用。

2. **M6-fast 材料脚本迁移**
   - 将 `rotten_organ.lua`/`bone_shard.lua`/`toxic_gland.lua` 改为 `type='material'`,desc 去掉 C-path 错配说明,保留 Remished 数据字段(`price`, `stackable=true`, image placeholder)。
   - 更新 `DataSkinItemTest`:三材料通过 `LuaItemRegistry.createItem`,断言 `instanceof LuaMaterial`,不是 `MeleeWeapon`,stackable=true,price/value 生效,isSimilar 同 id 合并/不同 id 不合并,bundle round-trip 在脚本默认 quantity 与保存 quantity 不同时仍保留保存 quantity,split/detach/value 不回退默认数量。

3. **M6d item/inventory API 分块**
   - 在 `RpdApi.build()` 的 M6b 后新增 `// M6d item/spell API` 注册: `giveItem(charId,itemId,qty)`, `randomBackpackItem(charId)`, `itemName(charId,index)`, `removeBackpackItem(charId,index,qty)`, `stealRandomItem(mobId,targetId)`, `stolenLootName(mobId)`。不暴露 Lua-side `createItem`;Java 内部只用 `LuaItemRegistry.createItem`。
   - API 规则:charId 必须 resolve 为 `Hero`/`Mob` 所需类型;背包仅支持 Hero target;item 引用用 1-based backpack index 或 Lua item id,返回 string/index/int/nil,不返回 Java object/userdata。
   - `giveItem` 用 `LuaItemRegistry.createItem` 并 `quantity(qty).collect(hero.belongings.backpack)`;`randomBackpackItem` 从 `hero.belongings.backpack.items` 随机返回 1-based index;`removeBackpackItem` 调 `detach`/`detachAll` 而不是手写 quantity;`stealRandomItem` 从 target hero 背包 detach 一件,放入 Java 内部 `Mob.loot` 的 `Item` 路径并把 `lootChance=1`(需要 package helper 或安全 setter),`stolenLootName` 返回当前 loot item 名称。测试覆盖 `Mob.createLoot()` 返回被偷 item 且 bundle 预测记录持久化缺口/后续风险。

4. **M6d spell/cell API 分块**
   - `LuaSpell` 接收 `castTime`(默认 `TIME_TO_USE`)并在 `execute` spend 对应时间;`targeting`/`spellCost` 仅元数据保存到字段以便测试和后续 UI。
   - `RpdApi` 新增 spell 辅助:`teleportChar(charId,pos)`, `zapEffect(fromCell,toCell)`, `cellRay(fromCell,toCell)`, `spawnMobNear(mobId,centerCell)`;复用已有 `healChar/affectBuff/damageChar/spawnMob/blink/enterTown`。
   - 所有 cell API guard `Dungeon.level == null`、out-of-map、blocked cell;返回 int/table/nil,不暴露 Ballistica/Level/Char object。

5. **代表脚本移植**
   - item:3 个材料完整迁移;weapon 代表至少 2 个(`hooked_dagger` bleeding proc,`bone_saw` 或 `kunai` 简化行为)继续走 `LuaItem` weapon wrapper。
   - spell:新增 7 个代表: `heal`(healChar),`haste`(affectBuff Haste),`charm`(affectBuff Vertigo/Slow 近似),`lightning_bolt`(damageChar + zapEffect),`town_portal`(enterTown),`summon_beast`(spawnAlly/spawnMobNear),`raise_dead`(spawnMobNear skeleton/zombie 占位或记录因 mob asset 缺口降级)。避免 `require scripts/lib/*` 和 luajava。

6. **测试与回归**
   - 新增 `RpdApiItemSpellTest`:直接用 `LuaSandbox.exposedGlobals()+RpdApi.build()` 测 API bad-input/nil、give/remove/random index、`type(RPD.giveItem(...))` 等均非 userdata、steal 后 `Mob.createLoot()` 可取回 `Item`、cell guard、luajava stripped。
   - 更新 `LuaSpellTest` 对新增 spell count/metadata 的断言;更新 `ModToggleRegressionTest` exact item/spell IDs + sizes。
   - 跑 `./gradlew :core:test --no-daemon`;若 UI/desktop 不可启动,明确只完成 headless core acceptance。

7. **回填 M6e 预测**
   - 在 PLAN 末尾填 item/spell 完成数量、wrapper/API 数、仍受 luajava 禁限制的脚本、M6c 依赖和 M6e 平衡风险。

## Acceptance

- [x] 正确承载至少 3 个非武器 Remished item(材料/消耗品/护盾之一),不再把材料当 MeleeWeapon
- [x] 移植至少 6–8 个代表 spell,覆盖 heal/buff/teleport/summon/projectile 中的多数类别
- [x] 提供窄 inventory/item/spell API,解决 ScriptedThief/背包摩擦的一部分或明确记录不可做项
- [x] 不开 luajava,不暴露 Java object 句柄,不引入 Remished `scripts/lib/`
- [x] 新增 `RpdApiItemSpellTest`;既有 tests 不回归(253 tests,0 failures,从 240 → 253)
- [x] 回填 M6e 预测:完成度/缺口/平衡风险/与 M6c 集成点

## M6e 预测(完成后回填)

- 已完整移植 item 数:**5 / 22**(3 材料 rotten_organ/bone_shard/toxic_gland 走 LuaMaterial;2 weapon 代表 hooked_dagger/kunai 走 LuaItem。其余 ~17 个 Remished item 多为 shield/armor/fish/pickaxe,需要新 wrapper 或美术资源,M6e)
- 已完整移植 spell 数:**8 / 32**(heal/haste/charm/lightning_bolt/town_portal/summon_beast/raise_dead/sprout + 既有 test_spell。覆盖 heal/buff/projectile/teleport/summon 五类;其余 24 个 spell 深度依赖 Remished `scripts/lib/` + skillLevel + targeting UI,M6e)
- 新 wrapper/API 数:**1 wrapper**(LuaMaterial)+ **9 新 RPD API**(giveItem/randomBackpackItem/itemName/removeBackpackItem/stealRandomItem/stolenLootName/teleportChar/charAtCell/cellRay/zapEffect/spawnMobNear)+ LuaItemRegistry `createItem`/`createWeapon` 双入口 + LuaMob `stolenLoot`/`stolenLoot(Item)`
- 仍受 luajava 禁限制的脚本:所有 Remished 原件均 `require "scripts/lib/*"`(item/spell/commonClasses),本 feature 全部改写为窄 `RPD.*` API,未引入任何 `scripts/lib/`;`raise_dead`/`summon_beast` 的真实 mob 资产(skeleton/zombie sprite + 平衡数据)未搬,M6e 接入
- 与 M6c 的依赖项:无直接代码依赖。RpdApi 用 `// M6d item/spell API` 分块,未碰 buff 分块;`ModToggleRegressionTest` exact counts 已更新到 M6d 全集,M6c 合并时取并集即可。spell 里的 haste/charm 用既有 BuffWhitelist(Haste/Vertigo),未重复实现 buff API
- M6e 平衡风险:
  - `giveItem(charId, itemId, qty)` 无来源限制,脚本可无限刷材料/武器 → M6e 需加 cooldown/source gate 或限制为 debug
  - `stealRandomItem` 把 stolen item 放进 `Mob.loot`,但 `Mob.storeInBundle` **不持久化** generic Item loot,save/load 后被偷物品丢失(M6d 已记录,非完整 thief 闭环)
  - `spawnMobNear`/`summon_beast`/`raise_dead` 复用 test_mob,无专属 sprite/平衡,实战会刷出敌对 test_mob;M6e 需 ally 化 + 专属 mob 资产
  - `lightning_bolt`/`cellRay` 无 VFX,只有逻辑伤害;targeting UI(self/char/cell)目前都是 self 施法占位,真实目标选择 M6e
  - LuaMaterial `value()` = price × quantity,材料进 Generator LUA_ITEM 池后会和武器混在一起随机掉落(firstProb=0 仍不进 vanilla deck,但 `Generator.random(LUA_ITEM)` 现在可能返回材料)

## M6e 处置结果(2026-07-06 回填)

5 项风险逐项处置(2 修 / 3 接受降级留 M7):

- **#1 `giveItem` 刷分 = 修**:`GiveItem` 加 per-hero-per-depth 累计配额(`GIVE_ITEM_CAP_PER_DEPTH = 20`),fork-local static `Map<heroId, Map<depth, count>>`,超限 → `valueOf(false)`;不触 Hero 字段/不进 bundle;`RpdApi.resetGiveQuota()` 测试钩子。单测 `giveItemQuotaBlocksRunawaySpam` 绿。
- **#5 LuaMaterial 池混掉 = 修**:`LuaItemRegistry.isMaterial(String id)` 暴露;`LuaItemPool.random()` 默认 weapons-only(跳过 material id);新增 `randomMaterial()` 独立入口。`Generator.random(LUA_ITEM)` 不再返回材料。单测 `defaultPoolExcludesMaterials` 绿。
- **#2 stolen loot 持久化 = 接受降级,留 M7**:`Mob.storeInBundle` 不存 generic Item loot;LuaMob 已 override bundle,M7 廉价补(存 `STOLEN_LOOT` key + `createLoot` 兼容)+ thief 返回 UI。M6e 记录,不阻塞。
- **#3 summon/raise_dead sprite = 接受降级,留 M7**:复用 test_mob 占位,需 skeleton/zombie sprite + 平衡数据(资产缺口,非 M6e 范围)。
- **#4 targeting UI = 接受降级,留 M7**:cast 全 self 占位,真实目标选择需新 UI 工作(M7)。

M6d 的 item/spell + 11 API 在 M6e C3 全量回归中保持绿(8 registry disabled=0/enabled=full,273 tests)。

## Parallel notes(M6c)

M6c 与 M6d 并行。共享冲突点:`RpdApi.build()` 注册区、`ModToggleRegressionTest` registry exact counts。约定:
- M6c 用 `// M6c buff API` 分块 + `RpdApiBuffTest`
- M6d 用 `// M6d item/spell API` 分块 + `RpdApiItemSpellTest`
- 合并时若 `ModToggleRegressionTest` exact sizes 冲突,取并集(类似 M6a/M6-fast)
