# PLAN: M19e — Remixed item content pack

## Goal
从 remixed `scripts/items` 选择 3-5 个低 API 依赖 item 搬入 `remixed_full/scripts/items`,增加可被 M18d level 放置的真实 Lua item 内容。

## Context
当前 remixed_full 已有若干 weapon/food/material,但 remixed 仍有 BoneShard/RottenFish/VileEssence/shields/fish/PlagueDoctorMask 等未搬。此 feature 只做内容搬运,不改 Java API。若候选依赖缺失 API,诚实跳过并记录 portability assessment。

## 候选评估矩阵(API portability assessment)

**关键差异**:fork 是**声明式** `register_item { 字段... }`,remixed 源是**函数式** `item.init{ desc=function()... }` 并 `require "scripts/lib/commonClasses"` / `scripts/lib/item` / `scripts/lib/shields`。搬运=把 remixed desc 表 + callback 翻译成 fork 声明式字段(非 1:1 复制)。

**fork LuaMaterial 已支持字段**(LuaMaterial.hydrate):`id/name/desc|info/image/spriteFile/price/stackable/defaultAction(EAT|USE)/actions/onEat/onUse/onThrow/energy/burnTransform|burn_transform/freezeTransform|freeze_transform/poisonTransform|poison_transform`。AC_EAT 路径:detach → `callOpt(table,"onEat",heroId,itemId)` → `Hunger.satisfy(energy)` → spend。AC_EAT/AC_USE 由 Java 拥有动作经济,Lua 只拿到 hero id。

**fork LuaItem(weapon) 已支持**:`tier/image/spriteFile + attackProc callback`(RPD.affectBuff 可用,见 kunai/mace)。

**Hunger 量纲**:`HUNGRY=300f, STARVING=450f`;remixed_ration energy=150(半饱)。energy 即 satisfy 点数。

### 选定搬运(4 个,全部 material/food,纯 fork 声明式字段)

| id | 源 | fork 映射 | 降级记录 |
|----|----|-----------|-----------|
| `remixed_full_bone_shard` | BoneShard.lua | type=material, name="骨片", desc, image=5, price=5, stackable, spriteFile="sprites/items/item_BoneShard.png" | 零依赖,完美映射(纯 desc) |
| `remixed_full_vile_essence` | VileEssence.lua | type=material, name="恶秽精华", desc, image=97, price=10, stackable, spriteFile="sprites/items/item_VileEssence.png" | **去掉 glowing**(`itemLib.makeGlowing` 纯视觉发光,fork LuaMaterial 无 glowing 支持);材料属性保留 |
| `remixed_full_rotten_fish` | RottenFish.lua | type=material, name="腐烂的鱼", desc, image=15, price=8, stackable, defaultAction="EAT", energy=75(HUNGRY 300 的 1/4), onEat=function(hero) RPD.affectBuff(hero,"Poison",4) end, spriteFile="sprites/items/item_RottenFish.png" | 中毒时长 remixed 用 `2*math.random(1,hero:lvl())` 依赖 `hero:lvl()`(fork 不暴露)→ 固定 4 回合;taste 文案("RottenFish_Taste")fork 无 taste 机制 → 丢弃;**核心"吃腐鱼中毒"保留** |
| `remixed_full_fried_fish` | FriedFish.lua | type=material, name="烤鱼", desc, image=13, price=30, stackable, defaultAction="EAT", energy=450(STARVING 全饱), poisonTransform="remixed_full_rotten_fish", spriteFile="sprites/items/item_FriedFish.png" | taste 文案丢弃;poison 转换保留(指向 rotten_fish,fork LuaMaterial.poisonTransformId 支持,lazy;即便引擎不主动触发也无害);**核心"烤鱼饱腹"保留** |

4 个候选的 sprite png 均已存在于 `remixed_full/sprites/items/`(见 SPRITE-MAP.md,219 个 item sprite 已导入),无需新增美术资源。

### spriteFile 路径约定(评审修正)

`ModSpriteCache.resolveFileHandle` 对 builtin mod 解析为 `Gdx.files.internal("mods/" + mod.id + "/" + path)` —— 即 `spriteFile` 必须是**相对 mod 根目录**的路径(引擎自动加 `mods/<mod.id>/` 前缀),写 `sprites/items/item_X.png`。`LuaSpriteFileTest` 对 test_mod 断言 `spriteFile()=="sprites/items/item_HookedDagger.png"` 印证此约定。

**已知遗留问题(本 feature 不修)**:现有 remixed_full item(kunai/mace/toxic_gland 等)的 spriteFile 写成 `mods/remixed_full/sprites/items/...`,会双前缀化 → 解析为 `mods/remixed_full/mods/remixed_full/...` → 文件不存在 → 静默回退 atlas 图。这是既有 bug,修正它需改现有 item 脚本,超出"搬 3-5 个新 item"范围。新 item 用正确相对路径,不继承此问题。

### 跳过清单(fork API 缺失,核心机制无法保真)

| 源 | 跳过原因 |
|----|----------|
| WoodenShield/ToughShield/StrongShield/RoyalShield/ChaosShield | 依赖 `scripts/lib/shields.makeShield` + `equipable="left_hand"` 盾牌格挡机制;fork 无 shield 装备/格挡 API |
| BoneSaw | attackProc 依赖 Doctor 类(`getHeroClass`==DOCTOR)、暴击、`animatedDrop`、`ItemFactory:itemByName`;降级后等同已有 hooked_dagger(Bleeding),失真 |
| Tomahawk2 | `equipable="left_hand"` 副手槽,fork 无副手;降级为无特色钝器 |
| PlagueDoctorMask | `luajava.bindClass(Accessory)` + `RPD.Slots.artifact` + `permanentBuff(GasesImmunity)`;fork 无 accessory/装备槽 API |
| TenguLiver | `luajava.newInstance(WndChooseWay)` 转职窗口 + `RPD.Badges`;核心是转职道具 |
| RemixedPickaxe | fork 已有 `RPD.terrain/setTerrain/isWall/isSolid/dig` + `RPD.Terrain` 常量(地形操作可用),但完整挖矿行为还依赖 `GameScene:updateMap`/`Sfx.*`/`collectAnimated`/`effectiveSTR`/关卡类型判定/尸体掉落等 fork 缺失环节 → 无法保真 |
| FrozenFish | fork 已有 `LuaMaterial.onThrow` + `RPD.spawnMob`,但 `spawnMob` 只接 `LuaMobRegistry` 的 Lua mob id,无法生成 vanilla Piranha(`MobFactory:mobByName("Piranha")`)→ "扔水里变食人鱼"核心特性无法保真 |
| RawFish | eat 中毒与 RottenFish 重复 + burn/freeze/poison 三转换链依赖完整 fish 集(FrozenFish 未搬)→ 链断裂;FriedFish 已代表饱腹食物 |

## Files
- 新增 `core/src/main/assets/mods/remixed_full/scripts/items/remixed_full_bone_shard.lua`
- 新增 `core/src/main/assets/mods/remixed_full/scripts/items/remixed_full_vile_essence.lua`
- 新增 `core/src/main/assets/mods/remixed_full/scripts/items/remixed_full_rotten_fish.lua`
- 新增 `core/src/main/assets/mods/remixed_full/scripts/items/remixed_full_fried_fish.lua`
- 改 `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/RemixedFullPackTest.java`(更新计数 + 加 3 个 test)
- `docs/PLAN-m19e-item-content-pack.md`
- **不改任何 Java 文件**(不动 LuaItem/LuaMaterial/RpdApi/register_item)

## Steps
1. [已完成] 读现有 remixed_full item 写法、register_item/LuaMaterial API、RemixedFullPackTest、RpdApi、Hunger 量纲、候选源。
2. 写 4 个 lua:`bone_shard`(纯材料)、`vile_essence`(纯材料,去 glowing)、`rotten_fish`(EAT+onEat Poison+energy=75)、`fried_fish`(EAT+energy=450+poisonTransform→rotten_fish)。文案硬编码中文 name/desc(沿用现有 remixed_full 惯例,不走 Messages.get)。
3. 更新 `RemixedFullPackTest.enabled_loadsFullAlphaManifest`:`10 items` → `14 items`(注释 `5 weapons + 9 materials`),加 4 个 `LuaItemRegistry.contains(...)` 断言。
4. 加表字段断言 test `m19e_items_declareExpectedFields`:enable remixed_full + LuaEngine.init,对 4 个新 id `LuaItemRegistry.getTable(id)`,断言:
   - 4 个均 `type=="material"`、`stackable==true`、`spriteFile=="sprites/items/item_*.png"`、`price` 为正;
   - bone_shard/vile_essence 无 defaultAction(纯材料);
   - rotten_fish `defaultAction=="EAT"`、`energy==75`、`onEat` 为 function;
   - fried_fish `defaultAction=="EAT"`、`energy==450`、`poisonTransform=="remixed_full_rotten_fish"`。
   抓内容搬运漏字段(defaultAction/energy/poisonTransform/spriteFile 路径)。
5. 加 forbidden-token lint test `m19e_items_useOnlyForkSupportedApis`:读 4 个新 lua 源文本,strip 行注释,断言不含 remished-only 构造(`luajava/require/itemLib/item[.]init/makeGlowing/makeShield/[:]*eat[(]/[:]*lvl[(]/MobFactory/ItemFactory/RPD[.]Slots/equipable/getHeroClass/WndChooseWay/animatedDrop/collectAnimated/playSound/[.]Sfx/RPD[.]Badges/RPD[.]Buffs`)。镜像 `townNpcs_useOnlyForkSupportedApis` 写法。(注:fork 已支持的 `RPD.spawnMob/permanentBuff/terrain/dig` 不列入 forbidden —— 它们存在,且声明式 material 不会用到。)
6. 加 onEat callback test `m19e_rottenFish_onEatAppliesPoison`:enable remixed_full + LuaEngine.init,取 rotten_fish table,`LuaItemCallbacks.callOpt(table,"onEat",heroId,itemId)`,断言 `hero.buff(Poison.class) != null`(类比 `enabled_weaponAttackProcFiresAndAppliesBuff`)。证明降级后核心效果真实工作。
7. `./gradlew :core:test --tests '*RemixedFullPackTest*'` 绿(注意:flaky GeneratorLuaItemTest 与本 feature 无关,见 CAO memory)。
8. 送 codex_reviewer review 实施(diff vs master)。

## Acceptance
- [x] 至少 3 个新 item 成功注册(本方案 4 个)+ 明确 API-blocked 甄别表(见上"跳过清单")
- [x] 不改 Java API(纯 lua + test)
- [x] 新 item 可被 `lua_item:remixed_full_bone_shard` 等放进 DataDrivenLevel(沿用现有 register_item → LuaItemRegistry → lua_item:<id> 链路)
- [x] 不提交 `.claude/`;不用 `git add -A`(按文件名 add)
