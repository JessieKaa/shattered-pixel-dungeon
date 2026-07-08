# PLAN: M10a-mob — 10 个 Remished mob 脚本改写

## Goal
改写剩余 10 个 Remished mob 脚本到 SPD fork Lua API(M6b 移了 7,`remixed-dungeon/scripts/mobs/` 共 17)。

## Context
M6b 移植 7 个 mob 样本(shaman_elder/spider_elite/deep_snail/hydra/maze_shadow/buffer 等),建立 mob 改写模式(`LuaMob` + `register_mob` + AI primitive)。M10a-mob 补剩余 10 个。

**第一件事**:列剩余 10 个 mob —— 读 `../remixed-dungeon/scripts/mobs/*.lua`(17 个)对比 M6b 已移的(见 `assets/mods/test_mod/scripts/mobs/` + `core/.../modding/LuaMob*.java`),列未移的 10 个。

**约束**:
- 基于 M6-M8 API(`LuaMob` + `register_mob` + M6b primitive:`setMobAi`/`enemyOf`/`cellDistance`/`emptyCellNextTo`/`blink` + M7b combat hook)
- 遇到需 M10c 回调的 mob(用 `regenerationBonus`/`hasteLevel` 等),**标注降级**(M10c 并行在做,合并后回调就位)。**不 [BLOCKED]**。
- 资产依赖(sprite/icon):优先复用 SPD 现有;缺失则降级(相近 sprite 或默认)+ 标注
- 默认不进生成池(M6b 模式,C3 守住)

## Files
- `core/src/main/assets/mods/test_mod/scripts/mobs/*.lua`(新 10 个,见下表)
- `core/src/test/.../ModToggleRegressionTest.java:146`(mob 计数 8 → 18)
- 无需改 `init.lua`/`mod.json`:`scripts/mobs/*.lua` 由 `LuaEngine.init()` 平铺扫描自动注册(已被 `shippedTestMobRegistersViaEngineInit` 证明)

## 剩余 10 mob(已核对)
`../remixed-dungeon/scripts/mobs/` 共 17。M6b 已移 6(buffer/deep_snail/hydra/maze_shadow/shaman_elder/spider_elite)+ M6a PoC `test_blob_rat`(=RemishedFetidRat)。剩余 10:

| # | 文件(snake) | remished 源 | 行为 | API 映射 | sprite | 降级 |
|---|---|---|---|---|---|---|
| 1 | `assasin.lua` | Assasin.lua | 永久隐身,首次攻击破隐 | `spawn`→`affectBuff(self,"Invisibility",1000)`;`attackProc`→`removeBuff(self,"Invisibility")` | gnoll | 永久隐身:`permanentBuff` 拒 Java 白名单 buff,改 1000 回合长持续 |
| 2 | `bee_spawner.lua` | BeeSpawner.lua | 被动蜂巢,死亡/受击释放蜂群 | `spawn`→`setMobAi(self,"passive")`;`die`→循环 `spawnMobNear("test_mob",selfPos)` ×N(3-8);`defenseProc`→`spawnMobNear` ×1 | bat | 蜂群代理:无 vanilla "Bee" 注册进 LuaMobRegistry,改 spawn 现有小怪 `test_mob`;`damage`→`defenseProc`(无 damage 回调) |
| 3 | `dummy.lua` | Dummy.lua | 纯默认(训练假人) | 仅 `register_mob` 默认属性 | skeleton | — |
| 4 | `hero.lua` | Hero.lua | 纯默认(damage hook 源码全注释 = no-op) | 仅 `register_mob` 默认属性 | brute | — |
| 5 | `mirror.lua` | Mirror.lua | 伤害反射(`damage(src,dmg)`) | `defenseProc`→`damageChar(enemy,baseDamage)`;return baseDamage | brute | `damage`→`defenseProc`(无 damage 回调) |
| 6 | `nature_aura.lua` | NatureAura.lua | 根系免疫 + 行走播撒 Regrowth | `spawn`→`addImmunity(self,"Roots")`;`act`→`placeBlob("Regrowth",selfPos,10)`;return false | slime | `stats`→`spawn`;`move`→`act`(无 move 回调) |
| 7 | `rat.lua` | Rat.lua | 纯默认(defenceProc 源码已注释) | 仅 `register_mob` 默认属性 | rat | — |
| 8 | `scripted_thief.lua` | ScriptedThief.lua | 攻击偷随机物品后逃跑 | `attackProc`→若 `stolenLootName(self)==nil` 则 `stealRandomItem(self,enemy)`;成功则 `GLog(msg)` + `setMobAi(self,"fleeing")`。**msg 用 `..` 拼接**(`RPD.GLog` 仅 1 参,无格式化):`RPD.charName(self).." stole "..name.." from "..RPD.charName(enemy)` | gnoll | — (M6d item API 齐全,完整移植) |
| 9 | `stinger.lua` | Stinger.lua | 攻击 75% 附毒 | `attackProc`→`if math.random()>0.25 then affectBuff(enemy,"Poison",2)` | crab | — |
| 10 | `talkie.lua` | Talkie.lua | 出场打招呼 | `spawn`→`yell(self,"Hello!")` | skeleton | `interact`→`spawn`(interact 是 NPC-only,M4b;hostile mob 改 spawn 时 yell) |

**回调可用性(LuaMob,已核对)**:`spawn(selfId)` / `act(selfId)→bool` / `attackProc(selfId,enemyId,dmg)→int` / `defenseProc(selfId,enemyId,dmg)→int` / `die(selfId)`。**无** `move`/`damage`/`interact`/`stats` → 上表相应降级。

**RPD 原语(已核对 RpdApi.build)**:`affectBuff`/`removeBuff`/`permanentBuff`(仅 Lua buff)/`damageChar`/`healChar`/`GLog`/`GLogW`/`charPos`/`charName`/`spawnMob`/`spawnMobNear`/`setMobAi`(sleeping/hunting/wandering/fleeing/passive)/`enemyOf`/`cellDistance`/`emptyCellNextTo`/`blink`/`placeBlob`/`addImmunity`/`stealRandomItem`/`stolenLootName`/`yell`(任意 Char)。`RPD.Buffs.{Invisibility,Poison,Roots}` / `RPD.Blobs.{Regrowth}` 均在白名单。

**sprite 白名单**:crab/rat/slime/gnoll/brute/skeleton/bat(未知 → 蟹兜底)。

## 属性(hp/ht/attack/defense)
register_mob 必填 `id,name,hp,attack,defense`(`ht`/`sprite` 可选,缺 ht=hp)。remished 源不带数值(继承基类),SPD 需显式。按下表(M6b 量级 hp 8-50 / atk 4-12 / def 0-8):

| mob | hp | ht | atk | def |
|---|---|---|---|---|
| assasin | 25 | 25 | 12 | 4 |
| bee_spawner | 30 | 30 | 6 | 4 |
| dummy | 80 | 80 | 3 | 0 |
| hero | 35 | 35 | 10 | 8 |
| mirror | 20 | 20 | 8 | 6 |
| nature_aura | 28 | 28 | 7 | 6 |
| rat | 12 | 12 | 5 | 2 |
| scripted_thief | 22 | 22 | 8 | 4 |
| stinger | 16 | 16 | 8 | 3 |
| talkie | 15 | 15 | 5 | 3 |

## Steps
1. ~~列剩余 10 mob~~(已列,见上表)
2. **逐个改写**:按上表 10 行,参考 M6b 模板(`register_mob{}` + 回调字段)落 `core/src/main/assets/mods/test_mod/scripts/mobs/<file>.lua`。
3. **资产**:sprite 全部命中白名单,无缺失。
4. **测试**:`ModToggleRegressionTest:146` mob 计数 8→18(消息更新)。`./gradlew :core:test` 全绿。
5. **默认不进生成池**:`register_mob` 仅写 LuaMobRegistry,`spawnMob`/`Level.createMob` 不涉及(C3/C4,已在 LuaMobRegistry Javadoc 确认)。

## Acceptance
- [ ] 10 mob 脚本注册成功(`register_mob`,被 LuaEngine.init 扫描)
- [ ] 基本 AI 工作(M6b primitive;dummy/hero/rat/mirror-default 走上游默认 AI;其中 mirror 叠 defenseProc 反射)
- [ ] combat hook 工作(attackProc/defenseProc/die/spawn;scripted_thief/stinger/mirror/nature_aura/assasin/bee_spawner/talkie)
- [ ] 默认不进生成池(C3)
- [ ] `./gradlew :core:test` 全绿(含 ModToggleRegressionTest 计数更新)
- [ ] **降级清单**记录在文末 §Degradation

## 注意
- **绝不 `git add -A`**:`.claude/` 不进 commit(选择性 `git add` 具体文件)
- **codex 评审用 codex exec workaround**(不 assign codex_reviewer,必超时)
- **遇 M10c 回调缺:标注降级,不 [BLOCKED]** —— 实际核对后本次 10 mob 无 M10c 依赖(降级均为本 API 已有回调形态差异:move/damage/stats/interact 缺失 + permanentBuff 仅 Lua buff)
- 新 mob 集中 `test_mod/scripts/mobs/`(参考 M6b 模板)

## §Degradation(降级清单)
1. **assasin** — 永久隐身不可用(`RPD.permanentBuff` 仅接受 Lua buff,拒绝 Java 白名单 `Invisibility`)。降级:`spawn` 时 `affectBuff(self,"Invisibility",1000)`(1000 回合≈整局)。合并 M10c 后若开放 Java buff 永久化可改回。
2. **bee_spawner** — 无 vanilla "Bee" 注册进 LuaMobRegistry(`spawnMob`/`spawnMobNear` 只能 spawn 已注册 Lua mob)。降级:蜂群改 spawn 现有小怪 `test_mob`(crab sprite)作代理。`damage` 回调不存在 → 改 `defenseProc`(受击释放 1 只)。后续可加独立 `bee.lua` 作为第 11 个 mob 还原。
3. **mirror** — 无 `damage` 回调。降级:反射改 `defenseProc`(`damageChar(enemy,baseDamage)`)。(注:remished `Hero.lua` 的 damage hook 源码全注释 = no-op,故 hero 走默认,反射行为属 mirror。)
4. **nature_aura** — 无 `move`/`stats` 回调。降级:`stats`→`spawn`(免疫);`move`→`act`(`placeBlob Regrowth`,return false 让上游 AI 仍跑)。播撒节奏从"每次移动"变"每 tick"。
5. **talkie** — `interact` 是 NPC-only(M4b `LuaNpc`),hostile `LuaMob` 无此回调。降级:`spawn` 时 `yell(self,"Hello!")`(出场打招呼)。若需真正对话交互,应改为 `LuaNpc`(M4b)而非 mob。
6. **资产** — sprite 全部命中现有白名单(crab/rat/slime/gnoll/brute/skeleton/bat),无新美术,无资产降级。
