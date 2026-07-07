# PLAN: M7a — combat hook core + stolen loot + Charm/Terror

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M7(M7a 条目)+ §10.3 评估依据
> 前置:M6 全部已合 master(`9a802f91e`,273 tests 绿)
> 并行:M7c(targeting)/ M7e(talent)同时开发。**约定 RpdApi 分块 + 独立测试类,降低冲突**
> D5' = (a) 禁 luajava 全程守住(本 feature 只传 char id/cell id,不暴露 Java 句柄)

## Goal

补完 M6c 留下的 combat hook 降级:**LuaBuff 加 4 个数值回调槽**(attackProc/defenseProc/drRoll/speed)+ Char/Mob proc 点**单点 dispatch** + M6d stolen loot 持久化 + Charm/Terror whitelist。解锁 12 个降级 buff 中的 8 个(buff 完成度 4/16 → 12/16)。

## Context

### 评估结论(§10.3,worker 自包含)

M6c 的 12/16 buff 降级,根因是 LuaBuff 只挂了 metadata + lifecycle,没接 SPD 的 combat 强度 hook。评估发现:

- **combat hook 接入是单点 dispatch,非"source-aware 全局 hook"**:Char/Mob 的 `attackProc`/`defenseProc`/`drRoll`/`speed` 都是 public,末尾 `for (LuaBuff b : buffs(LuaBuff.class)) dispatch` 是单点 hook,**完全符合 fork 约束 C1/C4**(单点 hook、不大改上游方法体)
- **不需要 source-aware Char/Mob 全局 hook**:只需把 `target.id()`/`enemy.id()` 传给 Lua,LuaBuff 已是 id-only 沙箱约定(D5' 守住)
- LuaBuff 加 4 回调槽直接套 `LuaItemCallbacks.callOptInt` 模式,边际成本 ~20 行/buff
- stolen loot 持久化廉价:LuaMob.storeInBundle 已 override,加 `STOLEN_LOOT` bundle key 即可(SPD Bundle 支持 Item 序列化)
- Charm/Terror 最廉价 0.5 项:`BuffWhitelist` 已有 `putFlavour` helper,加 2 行(`Charm`/`Terror` 都是 FlavourBuff 子类)

### 关键 file:line(worker 实测核对,2026-07-07)

- `core/.../actors/Char.java:706`(`drRoll`,returns int,Barkskin base)、`:721`(`attackProc`,已有 `for(ChampionEnemy)` 模板可镜像)、`:728`(`defenseProc`)、`:775`(`speed`,**returns float**)
- `core/.../actors/hero/Hero.java:756/827/1592/1657` —— Hero **4 个方法全部 override 且全部 `super.` 先调**(drRoll/speed/attackProc/defenseProc 均确认),故 Char 级单点 dispatch 经 super 链覆盖 Hero
- `core/.../actors/mobs/Mob.java:708`(`defenseProc` override,末尾 `super.defenseProc`)、`:767`(`speed` override,`super.speed()*mod`)、`:989`(`protected Object loot`)、`:993`(`createLoot`,`loot instanceof Item` → 直接返回)、`:155`(`storeInBundle` **不持久化 loot/lootChance** —— 这是 stolen loot gap 根因)
- `core/.../modding/LuaMob.java:214/225`(attackProc/defenseProc override —— **这是 LuaMob 自身 Lua 表的 dispatch,与 LuaBuff 系统无关**;`stolenLoot(Item)`/`stolenLoot()` 已在 `:330/:335`,注释 `:326` 已标注 M6e persistence gap)、`:342/352`(storeInBundle/restoreFromBundle override)
- `core/.../modding/LuaBuff.java`:`act():167`(callOptInt 风格模板)、`state`(LuaTable)、`target`(Char)、`luaTable()`(返注册表)、`buffs(LuaBuff.class)`(Char 侧迭代)
- `core/.../modding/LuaItemCallbacks.java:49`(`callOptInt(table,fn,fallback,args...)`—— **int only**;speed 需 float,新增 `callOptFloat`)、`:32`(`callOpt`)
- `core/.../modding/RpdApi.java:998/1061`(`BuffWhitelist` + `putFlavour(name, Class<? extends FlavourBuff>)`)、`:134/181/184`(`affectBuff`/`removeBuff`/`detachBuff`/`setBuffLevel`/`buffLevel` 已存在,升级后的脚本可直接用)
- `core/src/test/java/.../modding/RpdApiBuffTest.java`(459 行,`freshHero()`/`findLuaBuff()`/`stateInt()` harness 齐备,combat hook 测试直接 `h.attackProc(enemy,dmg)` 断言)
- 16 buff 脚本:`core/src/main/assets/mods/test_mod/scripts/buffs/*.lua`(2 已 non-degraded:`cloak`/`gases_immunity`;14 degraded)

## Files(worker 核对,精确到插入点)

- `core/src/main/java/.../modding/LuaItemCallbacks.java`:**新增 `static float callOptFloat(LuaTable,String,float,LuaValue...)`**(镜像 `callOptInt`,用 `todouble()`;speed 槽需要)。约 15 行。
- `core/src/main/java/.../modding/LuaBuff.java`:加 4 个 public 数值回调方法 —— `int attackProc(int selfId,int enemyId,int dmg)` / `int defenseProc(int selfId,int enemyId,int dmg)` / `int drRoll(int selfId,int dr)` / `float speed(int selfId,float spd)`。每个 ~6 行,内部走 `LuaItemCallbacks.callOptInt`/`callOptFloat`,nil/非数/无函数 → 透传 fallback。回调槽 Lua 签名同名,参数全 int/float(D5'-(a) 守住:只传 id+数值,不传 Char 句柄)。
- `core/src/main/java/.../actors/Char.java`:**4 处单点 dispatch(每个方法 return 前 1 行 loop)**,镜像已有的 `for(ChampionEnemy buff : buffs(ChampionEnemy.class))` 写法:
  - `attackProc(:721)`:末尾 `for (LuaBuff b : buffs(LuaBuff.class)) damage = b.attackProc(id(), enemy.id(), damage);`
  - `defenseProc(:728)`:`return damage;` 前同模式 `b.defenseProc(...)`
  - `drRoll(:706)`:Barkskin 后 `for(...) dr = b.drRoll(id(), dr);`
  - `speed(:775)`:return 前 `for(...) speed = b.speed(id(), speed);`
  - **import `com.shatteredpixel.shatteredpixeldungeon.modding.LuaBuff`**(Char 在 actors 包,modding 跨包,需显式 import;`buffs(LuaBuff.class)` 已是 public Char 方法)
- `core/src/main/java/.../actors/mobs/Mob.java`:**不动**(Mob.defenseProc/speed 已 `super.` 链回 Char;attackProc/drRoll 无 override,继承 Char。Char 级 dispatch 自动覆盖 Mob/LuaMob)。**重要**:`LuaMob.attackProc/defenseProc`(自身 Lua 表 dispatch)与 LuaBuff 系统正交,不冲突。
- `core/src/main/java/.../modding/LuaMob.java`:`storeInBundle(:342)` 加 `if (loot instanceof Item) bundle.put(STOLEN_LOOT, (Item) loot);`;`restoreFromBundle(:352)` 加 `if (bundle.contains(STOLEN_LOOT)) { loot = bundle.get(STOLEN_LOOT); lootChance = 1f; }`。新增常量 `STOLEN_LOOT = "lua_stolen_loot"`。**注意**:`loot`/`lootChance` 是 `Mob` protected 字段,跨包子类 `this.loot` 合法(同 `stolenLoot()` 既有模式)。Bundle 单 Item 序列化用 `bundle.put(key, (Bundlable)item)` 模式(参考 `Thief.java:66`、`Bones.java:76`)。
- `core/src/main/java/.../modding/RpdApi.java`:`BuffWhitelist` static block(:1011 Haste 后或 :1025 Chill 后)加 `putFlavour("Charm", Charm.class);` + `putFlavour("Terror", Terror.class);`;import 补 `actors.buffs.Charm`/`Terror`(均为 FlavourBuff 子类,已核对)。
- `core/src/main/assets/mods/test_mod/scripts/buffs/`:9 个 degraded buff 升级(见下方 buff 升级表),移除 `degraded=true`、更新 `info`/`degradation` 注释为"已接 hook"。剩余 5 个保留 degraded(需 M7b)。
- `core/src/test/java/.../modding/RpdApiBuffTest.java`:扩 combat hook 单测(见 Steps 步骤 6 测试矩阵)。**RpdApi 用既有公共测试类**(本 feature 是 M7a combat,无并行冲突;新增测试方法即可,不动既有 16-buff 计数断言)。

## Steps(worker 细化到可执行粒度)

1. **`LuaItemCallbacks.callOptFloat`**(先做,LuaBuff.speed 依赖):复制 `callOptInt` 签名,把 `int fallback`/`toint()` 换成 `float fallback`/`(float) todouble()`,错误/nil/非数分支返回 fallback。
2. **LuaBuff 4 数值回调槽**:加 4 public 方法,内部 `LuaTable tbl = luaTable(); if (tbl==null) return fallback; return LuaItemCallbacks.callOptInt(tbl, "attackProc", damage, LuaValue.valueOf(selfId), LuaValue.valueOf(enemyId), LuaValue.valueOf(damage));`(speed 用 callOptFloat)。参数顺序:proc=(selfId,enemyId,damage);drRoll=(selfId,dr);speed=(selfId,spd)。**nil/非数/无函数 → 透传**(callOptInt/Float 保证)。D5'-(a) 守住:只传 id+数值。
3. **Char 4 处单点 dispatch**(C1/C4:每方法 1 行 loop,不动方法体,镜像 `for(ChampionEnemy buff : buffs(ChampionEnemy.class))`):见 Files 的 4 个插入点。import `modding.LuaBuff`。**dispatch 顺序** = `buffs(LuaBuff.class)` 迭代序(attach 序,LinkedHashSet),确定性、无随机;在 LuaBuff 类 javadoc 文档化。
4. **stolen loot 持久化**(LuaMob):常量 `STOLEN_LOOT="lua_stolen_loot"`;storeInBundle 末尾 `if (loot instanceof Item) bundle.put(STOLEN_LOOT, (Item) loot);`;restoreFromBundle 末尾 `if (bundle.contains(STOLEN_LOOT)) { loot = bundle.get(STOLEN_LOOT); lootChance = 1f; }`;改注释 `:326` "gap (M6e)" → "fixed in M7a"。
5. **Charm/Terror whitelist**(RpdApi.BuffWhitelist):static block 加 2 行 `putFlavour`;import `actors.buffs.Charm`/`Terror`(均 FlavourBuff 子类,已核对)。
6. **9 个 degraded buff 升级**(逐个接 hook,移除 `degraded=true`,更新 info):见下方 **buff 升级表**。secondary effect(regen/glow/chaos-random/HT-scaling)无对应 hook 的,info 注明留 M7b,但仍移除 degraded(主效果已活)。
7. **测试**(RpdApiBuffTest 加方法,既有 16-buff 计数断言不动):
   - `luaBuffAttackProcModifiesDamage`:`attackProc=function(self,enemy,dmg) return dmg+5 end` → `h.attackProc(enemy,10)` ≥15(无武器 hero,weapon proc=0)
   - `luaBuffDefenseProcReducesDamage`:`defenseProc=function(...) return math.floor(dmg/2) end` → 断言减半
   - `luaBuffDrRollAddsArmor`:`drRoll=function(self,dr) return dr+3 end` → `h.drRoll()` ≥3
   - `luaBuffSpeedMultiplier`:`speed=function(self,spd) return spd*1.5 end` → `h.speed()` ≈ baseSpeed*1.5
   - `multipleLuaBuffsComposeInAttachOrder`:×2 与 +10 两 buff,断言组合值随 attach 序确定
   - `stolenLootSurvivesBundleRoundTrip`:LuaMob→`stolenLoot(new Gold(50))`→store→new LuaMob restore→`createLoot()` 返 Gold qty 50
   - `charmAndTerrorInWhitelist`:`RPD.affectBuff(id,'Charm',5)`/`'Terror',5` → `h.buff(Charm.class)`/`Terror.class` 非 null
8. **C3 回归**:`ModToggleRegressionTest` 既有绿;disabled mod → 0 LuaBuff → combat loop 空 iterator,无影响。
9. **回填 M7b 预测**:见文末 **M7b 预测**。

### buff 升级表(worker 实测 16 脚本后制定)

| 脚本 | Remished intent | 本 feature 接的 hook | 升级后状态 |
|---|---|---|---|
| body_armor | drBonus + speedMultiplier + timed detach | `drRoll`(+3) + `speed`(*0.9)| **high** |
| mana_shield | defenseProc 一次免伤后 detach | `defenseProc` 返 0 + `RPD.detachBuff` | **high** |
| champion_of_fire | attackProc 对敌 Burning | `attackProc` + `RPD.affectBuff(enemyId,'Burning',4)` | **high** |
| champion_of_earth | drBonus + regenBonus + HT×4 | `drRoll`(+5)| **high**(regen/HT 注明 M7b)|
| champion_of_air | hasteLevel + glow | `speed`(*1.5)| **high**(glow 注明 M7b)|
| die_hard | on-hit random detach + regenBonus | `defenseProc` 命中 50% detach | **high**(regen 注明 M7b)|
| shield_left | defenseProc 格挡概率 | `defenseProc` 50% 返 0 | **high** |
| chaos_shield_left | defenseProc 混沌效果 | `defenseProc` 30% 格挡 | **high**(full chaos 注明 M7b)|
| test_buff | attackProc/defenseProc 日志 | `attackProc`+`defenseProc` `RPD.glog` | **high** |
| champion_of_water | defenceSkillBonus + glow | —(需 defenseSkill hook)| degraded(M7b)|
| counter | charAct 每 Char 回合计数 | —(需 per-Char-turn hook)| degraded(M7b;act 行为已活)|
| anesthesia | sleep-lock | —(需 damage/Sleeping AI hook)| degraded(M7b)|
| encumbrance | belongings + yell | —(需 belongings/yell API)| degraded(M7b)|
| unsuitable_item | belongings + yell | —(同上)| degraded(M7b)|

**翻转 9 个 degraded→high**(≥8 ✓)。剩余 5 degraded 留 M7b。最终 high = 2(cloak/gases_immunity)+ 9 = **11/16**。

## Acceptance

- [x] LuaBuff 4 数值回调槽(attackProc/defenseProc/drRoll/speed)实现,Lua 可改战斗数值
- [x] Char/Mob proc 单点 dispatch(LuaBuff only),不破上游 proc 链(C1/C4)
- [x] stolen loot 持久化(round-trip 测试绿)
- [x] Charm/Terror 进 BuffWhitelist,可 affect/remove
- [x] 9 个降级 buff 升级到高保真(可接 hook 的),buff 完成度 → 11/16(超 PLAN 投射因 counter charAct 计时不属本 hook 集)
- [x] 不开 luajava,不暴露 Java 句柄(只传 id/int/float;luajava 仍 stripped,回归测试绿)
- [x] `:core:test` 通过(285 tests,0 失败,既有不回归);C3 守住
- [x] 回填 M7b 预测

## M7b 预测(完成后回填 — M7a 完成于 2026-07-07)

M7a 实际交付:4 combat hook(attackProc/defenseProc/drRoll/speed)+ stolen loot 持久化 + Charm/Terror whitelist + 9 个 degraded buff 升级到 high(11/16 high)。剩余 5 degraded + 若干 secondary effect 需 M7b:

- **剩余 hook 数(需 M7b):8 类**
  1. `defenseSkill`/`attackSkill` 数值回调(champion_of_water / champion_of_fire secondary)
  2. `charAct` per-Char-turn 回调(counter 计时)
  3. `damage` 受击回调(anesthesia sleep-lock;die_hard secondary 可复用)
  4. `regenerationBonus` 每 tick 回血(champion_of_earth / die_hard secondary)
  5. `HT` 安全 setter(champion_of_earth x4 HT)
  6. sprite/glow tint 回调(champion_of_air glow)
  7. belongings + yell API(encumbrance / unsuitable_item)
  8. scripts/lib/shields 混沌效果库(chaos_shield_left full chaos)

- **哪些有 SPD 等价 hook 点(廉价,M7b 可直接套 M7a 单点 dispatch 模式):**
  - `defenseSkill(Char)`/`attackSkill(Char)` —— public,镜像 M7a 的 4 hook,末尾 1 行 dispatch。**~2h each**
  - sprite tint —— `CharSprite` 有 tint/flare public 方法,需在 `Char.sprite()` 或 `updateSpriteState()` 单点 dispatch。**~0.5d**

- **哪些需 M7b 新机制(不是单点 dispatch,要新 API/AI 注入):**
  - `charAct` —— SPD 无 per-Char-turn 通用回调,需在 `Char.act()` 末尾加 `for(LuaBuff b) b.charAct()` 单点 dispatch(类似 M7a,但 act 路径更敏感)。**~0.5d**
  - `damage` —— `Char.damage()` 是 200+ 行大方法,override 风险高;可在 `damage` 末尾单点 dispatch(参考 die() 递归 re-entry 教训,避免搬方法体)。**~1d**
  - `regenerationBonus` —— SPD 无通用 regen hook(Barkskin/Hunger 各自实现);建议复用 `charAct` hook,每 tick 调 Lua 回血。**~0.5d(依赖 charAct)**
  - sleep-lock —— Sleeping AI 是内部状态机,无 hook;需 AI 注入或 `isImmune(Sleep.class)` workaround。**~1d,设计风险高**
  - belongings/yell —— 新 `RPD.belongings(id)` + `RPD.yell(id,msg)` bridge,暴露 encumbrance 检查。**~0.5d**
  - scripts/lib/shields —— 移植 Remished 盾库(chaos 效果表),工作量较大。**~1-2d**
  - `HT` 安全 setter —— `RPD.setMaxHp(id,val)` 加在 RpdApi,guarded。**~2h**

- **单 hook 平均工时**:
  - 单点数值 hook(defenseSkill/attackSkill/HT-setter):**2-4h**(套 M7a 模式,边际成本最低)
  - 新机制 hook(charAct/damage/regen/belongings/yell):**0.5-1d each**
  - 大件(sleep-lock / shields lib):**1-2d each,需设计评审**

- **M7b 建议**:优先做 defenseSkill/attackSkill(廉价,解锁 champion_of_water + champion_of_fire secondary,推到 13/16)→ charAct(解锁 counter,14/16)→ belongings/yell(解锁 encumbrance + unsuitable_item,16/16 除 chaos lib)。sleep-lock + shields lib 留 M7c 或单独 epic。


## Risks

- Char 级 dispatch 若过宽(影响所有 Char 子类),改为 LuaBuff 类型精确过滤;dispatch 顺序需文档化(多 buff 叠加)
- stolen loot Bundle 序列化 Item list 的兼容(save 跨版本)
- 守 fork 约束:Char/Mob 改动是单点 hook 末尾 dispatch,不动 proc 方法体(C4);新代码进 modding/ 子包(Char/Mob 本身是上游类,只加最小 dispatch 行)
- C5 proguard:回调槽无反射,不需 keep
