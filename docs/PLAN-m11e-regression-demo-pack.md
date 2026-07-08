# PLAN: M11e — 回归 demo mod 包(M6-M11 API 端到端压测)

## Goal
做一个 **builtin demo mod**(`assets/mods/regression_demo/`),端到端压测 M6-M11 全部 Lua API surface(register_item/mob/buff/spell/talent/level/painter/trap + LuaMaterial EAT/USE + LuaItem action/state + spell stubs + shield/tint/sleep-lock + mana spell),配一个集成测试断言所有 registry 被正确填充 + smoke 加载。为 M14 发布硬化提供「一键回归」基线:这个 mod 一绿,说明 M6-M11 平台 API 没回归。**纯加法**(新 mod assets + 新测试文件),与 M12b/c/d 零冲突。

## Context
- M6-M11 累计铺了 ~45 feature 的 Lua API 面(mob/buff/item/spell/talent/level/painter/trap + combat hook + mana + shield + tint + sleep-lock + LuaMaterial use/throw + LuaItem mining/action + spell stubs)。但**分散在各 feature 的 test fixture 里**(test_mod + M6 各 PoC 脚本),没有一个 mod 同时压测全部。
- 现有可参考的 correct lua:`assets/mods/test_mod/`(M5 基线)+ M6 PoC 脚本(M6b mob / M6c buff / M6d item+spell)+ M10/M11 各 feature PLAN 里引用的脚本片段。
- demo mod 是 **builtin**(`default_enabled=false`,C3 守住原版体验),放 `assets/mods/regression_demo/`,classpath 加载(不依赖 M12d external levels;level 用 `mods/levels/regression_demo_level.json` 或 register_level classpath)。
- 集成测试镜像 `LuaEngineExternalLoadTest` / `ModScannerTest` 的 enable-mod + init + 断言 registry 模式。

**设计决策**:
- demo mod 命名 `regression_demo`,id 同名,`default_enabled=false`。
- 每个 API 至少 1 个最小代表元素(不追求好玩,追求**覆盖 registry + 回调签名**)。
- 集成测试:`RegressionDemoModTest` — enable mod + LuaEngine init + 断言每个 registry 含期望 id + 关键回调能调(不崩)。
- 不重复 M6 各 PoC 的 asset 依赖(sprite/png):用占位或复用 test_mod 的 sprite,聚焦 API 覆盖非美术。

## Coverage Matrix(demo mod 至少覆盖)
| 子系统 | API / 回调 | demo 元素 id |
|---|---|---|
| item(M6d/M11c) | `register_item` + actions/execute + state Bundle + glowing | `regression_demo_sword`(MINE action + glowing + per-instance state) |
| material(M11b) | LuaMaterial EAT/USE/onThrow + transform | `regression_demo_ration`(EAT 恢复 + onThrow burn transform) |
| mob(M6b) | `register_mob` + act/attackProc/die/spawn | `regression_demo_rat`(简单 AI + attackProc) |
| buff(M6c/M7a/b/M8a-c) | `register_buff` + combat hook + shield + tint + sleep-lock | `regression_demo aura`(tint)+ `regression_demo_shield`(shieldAmount)+ `regression_demo_sleep_lock` |
| spell(M6d/M7c/d/M11d) | `register_spell` + targeting + mana mode + stubs | `regression_demo_bolt`(targeting + mana)+ `regression_demo_curse`(curse_item)+ `regression_demo_possess`(possess) |
| talent(M8d1-3) | `register_talent` tier2/3/4 + on_upgrade | `MOD_SECOND_TALENT`(tier2 class=MAGE)+ `MOD_TIER3_TALENT`(tier3 subclass=BERSERKER)+ `MOD_TIER4_TALENT`(tier4 armor_ability=HeroicLeap) |
| level(M4a) | register_level 或 `mods/levels/` fixture | `regression_demo_level`(小 SafeZone,32×32 padding) |
| painter/trap(M10b) | register_painter/register_trap | `regression_demo_trap`(setTile 安全闸) |
| RPD API(M11c) | terrain/setTerrain/dig/dropItem | 在 mob/spell 里调用(dig 在 pickaxe-like item) |

(worker 按「每个子系统至少 1 个最小代表」落实,参考现有脚本保证 lua 语法 + 回调签名正确;若某 API 无现成参考,读对应 wrapper Java 的回调槽签名。)

## Files (worker)
- **`core/src/main/assets/mods/regression_demo/mod.json`**:id=`regression_demo`,name,version,spd_version=Game.versionCode,`default_enabled=false`,entry=`entry.lua`,description。
- **`core/src/main/assets/mods/regression_demo/entry.lua`**:调全部 `register_*`(item/mob/buff/spell/talent/level/painter/trap)。
- **`core/src/main/assets/mods/regression_demo/scripts/{items,mobs,buffs,spells,...}/*.lua`**:各代表元素的行为脚本(按 coverage matrix)。sprite 引用 test_mod 占位或 builtin。
- **`core/src/main/assets/mods/levels/regression_demo_level.json`**(若用 classpath level):小 SafeZone(镜像 test_safezone.json 32×32 padding 规范)。
- **`core/src/test/java/.../modding/RegressionDemoModTest.java`**(新):
  - enable `regression_demo` + LuaEngine.init。
  - 断言:`LuaItemRegistry.contains("regression_demo_sword")` + `LuaMobRegistry`/`LuaBuffRegistry`/`LuaSpellRegistry`/`LuaTalentRegistry`/`LuaLevelRegistry` 各含对应 id(逐个)。
  - smoke:某 buff attach/detach 不崩;某 spell cast(self 模式)不崩;某 item action execute 不崩(用最小 mock hero/scene,镜像现有 lua 测试的 setup)。
  - **C3 守护**:mod disabled 时所有 registry 不含 demo id(镜像 ModToggleRegressionTest)。

### 显式延后
- **真机端到端**:测试是 JVM 单元/集成测试,不跑 device。device 上「玩」这个 mod(看 sprite/AI/手感)留 M14 手动回归。
- **平衡**:demo 元素数值随便(聚焦 API 覆盖,非平衡)。
- **完整 asset**:sprite 用占位,不画新美术。

## Steps
1. 读现有脚本建 correct-lua 参考:`assets/mods/test_mod/`、M6 PoC(`mods/` 下各 feature 加的脚本)、各 wrapper Java 的回调槽(LuaItem/LuaMob/LuaBuff/LuaSpell/LuaTalent/LuaMaterial/LuaPainterAdapter/LuaTrap 的回调签名)。
2. 写 `mod.json` + `entry.lua` + 各 scripts/。
3. 写 `RegressionDemoModTest`(enable + init + registry 断言 + smoke + C3 disabled 守护)。
4. `./gradlew :core:test` 全绿(demo mod enabled 不崩 + disabled 零污染)。
5. codex 评审(Phase 1 PLAN + Phase 2 diff):重点在 coverage matrix 是否真覆盖 + lua 回调签名是否正确(读 wrapper 签名核对)+ C3 disabled 守护。
6. (可选)desktop 跑起来手动看 demo mod enabled 后各元素是否正常(非阻塞,单测绿即验收)。

## Acceptance
- [ ] `regression_demo` mod 存在,`default_enabled=false`,entry 加载全部 register_*
- [ ] coverage matrix 每行至少 1 个代表元素(item/material/mob/buff/spell/talent/level/painter/trap + RPD API)
- [ ] `RegressionDemoModTest`:enabled 时各 registry 含 demo id;disabled 时不含(C3)
- [ ] smoke:关键回调(attach/detach/cast/action)不崩
- [ ] `./gradlew :core:test` 全绿(577 + 新增)
- [ ] C3 不破:mod disabled 时原版一周目零污染
- [ ] 与 M12b/c/d 零文件冲突(只加 assets/mods/regression_demo/ + 一个测试文件)

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,**不 assign codex_reviewer**(memory:必超时)
- demo mod **builtin + default_enabled=false**(C3);不是 external mod(不依赖 M12)
- lua 回调签名必须对照 wrapper Java(LuaItem/LuaMob/... 的方法名 + 参数),写错会运行期静默失败
- sprite 用占位(test_mod 或 builtin),不引入新美术 asset
- 聚焦 API 覆盖,非平衡/手感;真机手感留 M14

## Phase 1 细化(Worker 核对结果 — 2026-07-08)

核对来源:`assets/mods/{test_mod,demo_m58}/` 现成脚本 + wrapper Java(LuaItem/LuaMaterial/LuaBuff/LuaSpell/LuaTalent/LuaPainterAdapter/LuaTrap)+ 现成测试(DemoM58LoadTest / ModToggleRegressionTest / ModTestSupport)。全部签名已对照确认。

### 确认的回调签名(写 lua 的唯一事实源)

- **register_item(weapon, LuaItem)**:`{id,name,desc,image,tier, actions={...}, actionNames={...}, defaultAction=str|fn(state), onEquip(heroId,state), onDeactivate(heroId,state), attackProc(atkId,defId,dmg,state), execute(heroId,action,state), onUse(heroId,action,state)[fallback], glowing(state)→{color,period}|nil}`。state 是 per-instance LuaTable,M11c 起随 Bundle 持久化。
- **register_item(type="material", LuaMaterial)**:`{id,type="material",name,desc,image,price,stackable, defaultAction="EAT"|"USE", energy, burnTransform, freezeTransform, poisonTransform, onEat(heroId,itemId), onThrow(cell,itemId), onUse(heroId,action,itemId)}`。transform 是声明式字段(Java 侧 `transformTo` 派发)。
- **register_mob**:`{id,name,hp,ht,attack,defense,sprite}`(最小;AI/act 等回调槽本 MVP 不压测,demo_m58/test_mod rat 也是纯字段)。
- **register_buff**:`{id,name,info,icon, shieldAmount(int 声明式),shieldType, attachTo(targetId,state)→bool, attackProc(selfId,enemyId,dmg), defenseProc(selfId,enemyId,dmg), attackSkill(selfId,atk), defenseSkill(selfId,def), drRoll(selfId,dr), speed(selfId,spd), charAct(selfId,targetId,state), tintChar(selfId,state)→number|{color,rays}|{r,g,b,a}, sleepLock(selfId)→bool}`。
- **register_spell**:`{id,name,desc,image,castTime, useMode="mana",spellCost, targeting="self"|"cell"|"enemy", onUse(heroId)[self], onUseAt(heroId,cell)[cell/enemy]}`。
- **register_talent**:`{id="MOD_*"(必须 Talent.java 预声明), tier(1-4), class|subclass|armor_ability(三者恰好 1 个,匹配 tier), name(必填,MOD_ slot 无 .title 键), maxPoints, desc, on_upgrade(heroId,points)}`。
- **register_trap**:`{id,name, onActivate(cell,charId)}`。
- **register_painter**:`{id="RoomType"(如 ShopRoom), decorate(level,room)}` — level/room 是 Java 构的 per-call table(room.cells / level.tileAt / room.setTile)。
- **register_level**:`{id[, path]}` — 几何在 `mods/levels/<id>.json`。

### RPD API(M11c,在 item/spell 里调用压测)

`charPos`、`charAtCell`、`charName`、`isSolid`、`levelWidth`、`dig`、`dropItem`(pickaxe 路径)+ `cellRay`、`zapEffect`、`damageChar`(lightning 路径)+ `affectBuff`、`giveItem`、`restoreMana`/`spendMana`/`heroMana`/`heroManaMax`、`absorbShield`、`buffLevel`、`GLog`/`GLogW`、`setItemCursed`、`randomBackpackItem`、`itemName`、`Terrain` 常量。

### Talent tier1 scope 决策(已核对)

`Talent.java` 只有 4 个 MOD_ slot:`MOD_EXAMPLE_TALENT(219,2)`、`MOD_SECOND_TALENT(220,2)`、`MOD_TIER3_TALENT(221,3)`、`MOD_TIER4_TALENT(222,4)`。**无 tier1 slot**。RegisterTalentFunction 里 tier1 与 tier2 走**同一条** `class` dispatch 分支(仅 tier∈[1,2] + hasClass 校验),所以 tier2 覆盖即等于 tier1 代码路径覆盖。补 tier1 需在 Talent.java 加 enum 常量(碰上游文件,违反「纯加法 mod」目标)。**决定**:talent 覆盖 tier2(class)+tier3(subclass=BERSERKER)+tier4(armor_ability=HeroicLeap)= 全部 3 条 dispatch 路径,不碰 Talent.java。用 MOD_SECOND_TALENT(避开 test_mod 的 MOD_EXAMPLE_TALENT 语义)+ MOD_TIER3_TALENT + MOD_TIER4_TALENT;测试仅 enable regression_demo,无 slot 抢占。

### 文件 → 元素 id 落实表(纯加法,15 个新文件 + 1 测试)

| 文件 | register_* | id | 覆盖 |
|---|---|---|---|
| `mod.json` | — | regression_demo | default_enabled=false,entry=entry.lua,spd_version=896 |
| `entry.lua` | — | — | 仅 GLog banner(per-type 脚本按目录自动加载,镜像 demo_m58) |
| `scripts/items/regression_demo_pickaxe.lua` | register_item(weapon) | regression_demo_pickaxe | actions={MINE}+execute(dig/dropItem/isSolid/levelWidth/charPos)+glowing(state)+defaultAction(state)+onEquip/onDeactivate+attackProc [M6d item + M11c action/state/glowing + RPD dig] |
| `scripts/items/regression_demo_ration.lua` | register_item(material) | regression_demo_ration | EAT energy+onEat + onThrow + burnTransform [M11b material] |
| `scripts/mobs/regression_demo_rat.lua` | register_mob | regression_demo_rat | hp/ht/attack/defense/sprite [M6b] |
| `scripts/buffs/regression_demo_combat.lua` | register_buff | regression_demo_combat | attachTo + attackProc/defenseProc/attackSkill/defenseSkill/drRoll/speed/charAct [M6c/M7a/b combat hook 全 7 槽] |
| `scripts/buffs/regression_demo_shield.lua` | register_buff | regression_demo_shield | shieldAmount=20 + tintChar({color,rays}) + defenseProc(absorbShield) [M8b shield + M8c tint] |
| `scripts/buffs/regression_demo_sleep_lock.lua` | register_buff | regression_demo_sleep_lock | sleepLock [M8a] |
| `scripts/spells/regression_demo_bolt.lua` | register_spell | regression_demo_bolt | targeting=cell mana + onUseAt(cellRay/zapEffect/damageChar) [M6d/M7c targeting+mana] |
| `scripts/spells/regression_demo_curse.lua` | register_spell | regression_demo_curse | targeting=self mana + onUse(randomBackpackItem/setItemCursed/itemName) [M11d curse stub] |
| `scripts/spells/regression_demo_possess.lua` | register_spell | regression_demo_possess | targeting=enemy mana + onUseAt(charAtCell/affectBuff MagicalSleep) [M11d possess stub] |
| `scripts/talents/regression_demo_tier2.lua` | register_talent | MOD_SECOND_TALENT | tier=2 class=MAGE on_upgrade(giveItem regression_demo_pickaxe + affectBuff regression_demo_combat) [tier2 class 路径] |
| `scripts/talents/regression_demo_tier34.lua` | register_talent | MOD_TIER3_TALENT + MOD_TIER4_TALENT | tier3 subclass=BERSERKER + tier4 armor_ability=HeroicLeap [tier3/4 路径] |
| `scripts/traps/regression_demo_trap.lua` | register_trap | regression_demo_trap | onActivate(cell,charId) [M10b trap] |
| `scripts/painters/regression_demo_painter.lua` | register_painter | ShopRoom | decorate(level,room) setTile [M10b painter] |
| `scripts/levels/regression_demo_level.lua` | register_level | regression_demo_level | {id} 注册 + levels/regression_demo_level.json 几何 [M4a] |
| `levels/regression_demo_level.json` | — | regression_demo_level | 32×32 SafeZone(镜像 test_safezone.json padding 规范,只留 1 个 entrance+floor 房间) |

> 共 15 个 mod asset 文件(mod.json + entry.lua + 13 scripts + 1 level.json)+ 1 测试文件。零上游 Java 改动,零与 M12b/c/d 的文件交集。

### 测试:`RegressionDemoModTest.java`(镜像 DemoM58LoadTest)

- **setup**:`@BeforeClass` HeadlessApplication + `Game.versionCode=896` + `Game.version="test"`(giveItem 路径命中 Document.<clinit> 要 version 非 null);`@Before` `FakePreferences` + `ModRegistry.resetForTests()` + `ModTestSupport.resetLuaState()`;`enableRegressionDemo()` = `scanDir(realModsHandle())` + `setEnabled("regression_demo",true)`。
- **test1 enabled_registersAll**:`LuaEngine.init()` 后逐个 `assertTrue(<Registry>.contains(id))`(item×2 / mob×1 / buff×3 / spell×3 / talent MOD_SECOND_TALENT via `isKnownModTalent` / trap via `LuaTrap` registry / painter via `LuaPainterRegistry` / level via `LuaLevelRegistry`)+ `assertFunction` 核对关键回调字段名无 typo(combat 7 槽、shield shieldAmount 是 int 非 fn、sleep_lock sleepLock、pickaxe execute+glowing、bolt onUseAt)+ talent 注入断言(`Talent.initClassTalents(MAGE,..)` 含 MOD_SECOND_TALENT)+ C3(test_mod/demo_m58 disabled → test_sword/combat_hook_demo 缺席)。
- **test2 disabled_registriesEmpty**:不 enable → 全 registry 不含 demo id + buff size==0。
- **test3 onUpgrade_firesGiveItemAndAffectBuff**(smoke):enable+init+new Hero(MAGE)+upgradeTalent(MOD_SECOND_TALENT) → 断言 backpack 含 LuaItem(regression_demo_pickaxe)+ hero 含 LuaBuff(regression_demo_combat)。镜像 DemoM58LoadTest 第 198-237 行。
- **trap/painter/level registry 类名待定**:实现时读 `LuaTrap`/`LuaPainterRegistry`/`LuaLevelRegistry` 的 contains/getTable 确认(test1 用)。

### 实施顺序(Phase 2)

1. mod.json + entry.lua(骨架,先让 mod 能被 scan)。
2. 13 个 scripts(按上表,逐个对照签名;spell/buff/pickaxe 回调对照确认表)。
3. level.json(32×32,镜像 test_safezone.json 结构,小 SafeZone)。
4. `./gradlew :core:test --tests '*RegressionDemoModTest*'` 先单测过。
5. `./gradlew :core:test` 全量绿(577 + 新增 3)。
6. codex exec 评审 diff(coverage matrix 落实 + lua 签名 + C3 守护)。
