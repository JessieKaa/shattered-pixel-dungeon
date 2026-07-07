# PLAN: M5-M8 综合测试 mod(demo_m58)

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M5-M8
> 前置:master `f09c14a94`(M8a-d2 已合:combat hook/mana/talent-override/sleep-lock/shield/tint/talent-new/on_upgrade);397 tests
> 本 feature 新增一个**纯 Lua/资源** demo mod,系统性覆盖 M5-M8 所有 modding API,供真机/desktop 调试与回归验证
> **不改 production Java 代码**:只新增 `assets/mods/demo_m58/` 资源 + 一个加载测试;与 M8d3 worker(改 Talent/LuaTalentRegistry)无文件冲突,可并行

## Goal

新增 `demo_m58` mod(default_enabled=false),一个脚本对应一个 M5-M8 能力,玩家启用后用游戏内正常途径(找物品/战斗/升天赋)触发,验证各 API 端到端工作。每个脚本头部注释标明测哪个 M + 预期表现。

## Context

### 为什么需要这个 mod

- 现有 `test_mod` 是 M0-M4 内容(剑/NPC/商店/城镇),M5-M8 新 API(combat hook/mana/shield/tint/talent-new/on_upgrade)没有集中的 Lua 侧覆盖
- 单测(RpdApiBuffTest 等)验证 Java 侧,但 Lua 脚本→Java 回调→游戏表现的全链路缺一个可手动验证的 demo
- 真机/desktop 调试时需要一个"启用即测全部"的 mod

### API 速查(实测自 RpdApi.java + LuaEngine.java)

**Lua 全局**:`register_buff{...}` / `register_talent{...}` / `register_talent_override{...}` / `register_item{...}` / `register_spell{...}` / `register_mob{...}`

**RPD.xxx**:`affectBuff(charId,buffId,level)` / `buffLevel(selfId,buffId)` / `setBuffLevel` / `detachBuff` / `removeBuff` / `permanentBuff` / `giveItem(heroId,itemId,qty)` / `spawnMob` / `damageChar` / `healChar` / `charHP` / `charPos` / `addShield(charId,amount)` / `absorbShield` / `charShield` / `heroMana(heroId)` / `heroManaMax` / `spendMana` / `restoreMana` / `addImmunity` / `blink` / `teleportChar` / `GLog(msg)` / `GLogW(msg)` / `placeBlob` / `cellRay` / `yell` / `showDialog`

**buff 回调签名**(selfId 是 bearer charId):
- `attachTo(targetId, state)` → bool
- `attackProc(selfId, enemyId, damage)` → damage(amend)
- `defenseProc(selfId, enemyId, damage)` → damage(amend)
- `defenseSkill(selfId, def)` → def(amend)
- `speed(selfId, spd)` → spd(amend)
- `tintChar(selfId, state)` → nil / number(aura,color) / `{color=,rays=}`(aura) / `{r=,g=,b=[,a=0.5]}`(tint); `color` 优先于 `r/g/b`(M8c)
- `sleepLock(selfId)` → bool(M8a,锁眠不醒;fail-open false)
- `shieldAmount` — **声明式 int 字段**(或 `function(state)` 单参,readIntField 解析),**不是** `function(selfId,state)`。LuaBuff.attachTo 在 attach/restore 时 seed 进 ShieldTracker(M8b)。用法:`shieldAmount = 20`(参照 shield_left/mana_shield)
- `shieldType` — 声明式 string 字段(metadata,reserved)
- `charAct(selfId, targetId, state)` → 钩子,无返回值(M7b,每 Char tick)

## Files(全部新增,不改既有文件)

- `core/src/main/assets/mods/demo_m58/mod.json`:id=demo_m58, name="M5-M8 测试包", default_enabled=false, spd_version=896, entry=init.lua
- `core/src/main/assets/mods/demo_m58/init.lua`:mod 入口(可空或 GLog 通告)
- `core/src/main/assets/mods/demo_m58/scripts/buffs/`:
  - `combat_hook_demo.lua`(M7a/b):attackProc +10% 伤害 / defenseProc 减伤 / defenseSkill +闪避 / speed +速
  - `sleep_lock_demo.lua`(M8a):sleepLock=true 锁眠,attachTo 时 GLog
  - `shield_demo.lua`(M8b):shieldAmount 贡献护盾 + tintChar 金色光晕(兼测 M8c)
  - `tint_demo.lua`(M8c):tintChar 返回三种 spec(number/table-color/table-rgb)各一个变体,验证 computeTint
  - `mana_demo.lua`(M7d):attachTo 时 RPD.restoreMana + GLog heroMana/heroManaMax;可挂 spendMana 钩子
- `core/src/main/assets/mods/demo_m58/scripts/items/`:
  - `m58_test_weapon.lua`(M6d):register_item(LuaItem = MeleeWeapon 子类)。**LuaItem 只有 `attackProc`/`onEquip`/`onDeactivate` 三个回调,没有 `onUse`**(LuaItem.java 确认)。用 `onEquip(heroId)` 一次性 `RPD.affectBuff(heroId,"combat_hook_demo",1)` + `shield_demo` + `mana_demo`,装备即触发多 buff。tier=2, image 复用一个已有 frame
- `core/src/main/assets/mods/demo_m58/scripts/talents/`:
  - `new_talent_demo.lua`(M8d1/d2):register_talent id=MOD_EXAMPLE_TALENT? **注意**:test_mod 已用 MOD_EXAMPLE_TALENT。本 mod 用 `MOD_SECOND_TALENT`(M8d1 第二个预声明槽),tier=2, class=MAGE, on_upgrade 用 RPD.giveItem 送物 + RPD.affectBuff 挂 buff(验证 on_upgrade 多 API)
  - `override_demo.lua`(M7e):register_talent_override 覆写一个 vanilla 天赋(如 HEARTY_MEAL)maxPoints 下调 + desc
  - `tier34_placeholder.lua`(M8d3 预留):注释说明 tier3/4 需 M8d3 合后启用;当前 tier>2 会被 register_talent reject(不崩,log skip)。**M8d3 合后**改 tier=3 + subclass 字段即可激活
- `core/src/test/java/.../modding/DemoM58LoadTest.java`(新):启用 demo_m58 → LuaEngine.init 不抛异常 + 关键 register 都成功(buff/item/talent 进各自 registry)

## Steps

1. **mod.json + init.lua**:照 test_mod/mod.json 格式;init.lua 可 `RPD.GLog("[demo_m58] loaded")`
2. **buffs/combat_hook_demo.lua**:register_buff,系统性覆盖**全部 6 个 combat hook**:
   - `attackProc(selfId,enemyId,dmg)` → `dmg + math.floor(dmg*0.1)` + GLog
   - `defenseProc(selfId,enemyId,dmg)` → `math.floor(dmg*0.9)`(减伤)
   - `defenseSkill(selfId,def)` → `def + 2`
   - `attackSkill(selfId,atk)` → `atk + 2`
   - `drRoll(selfId,dr)` → `dr + 1`
   - `speed(selfId,spd)` → `spd * 1.2`
   - `charAct(selfId,targetId,state)` → `state.tick = (state.tick or 0)+1`(无返回值,M7b)
3. **buffs/sleep_lock_demo.lua**:`sleepLock = function(selfId) return true end` + attachTo GLog(锁眠;敌人/英雄通用,fail-open)
4. **buffs/shield_demo.lua**:声明式 `shieldAmount = 20` + `shieldType = "physical"`(seed 进 ShieldTracker);defenseProc `return RPD.absorbShield(selfId, damage)`;tintChar 金色 `{color=16753920, rays=4}`(0xFFD700=16753920 十进制)
5. **buffs/tint_demo.lua**:tintChar 按 `RPD.buffLevel(selfId,"tint_demo")` 切三变体:lvl1→number(3381759 即 0x3399FF,aura 默认 rays=6)、lvl2→`{color=16753920, rays=4}`(aura)、lvl3→`{r=1.0,g=0.84,b=0.0,a=0.5}`(tint),一次性覆盖 computeTint 三个分支
6. **buffs/mana_demo.lua**:attachTo 覆盖**全部 4 个 mana API**:`RPD.restoreMana(heroId,50)` → `RPD.spendMana(heroId,10)` → GLog `tostring(RPD.heroMana(heroId)).."/"..tostring(RPD.heroManaMax(heroId))`(heroMana/Max 对非 Hero 返回 NIL,tostring 兜底)
7. **items/m58_test_weapon.lua**:register_item tier=2 image=99。**每个 buff 都有触发路径 + 覆盖 LuaItem 全部 3 回调**:
   - `onEquip(heroId)`:affectBuff 挂 4 个 hero-side buff(combat_hook_demo/shield_demo/mana_demo/tint_demo)+ GLog
   - `attackProc(attackerId,defenderId,dmg)`:affectBuff 给敌人挂 sleep_lock_demo(锁眠) + GLog + `return dmg+1`(覆盖 enemy-targeted buff 触发路径)
   - `onDeactivate(heroId)`:GLog("[demo_m58] weapon unequipped")
8. **talents/new_talent_demo.lua**:`register_talent{ id="MOD_SECOND_TALENT", tier=2, class="MAGE", name="M58 二号天赋", maxPoints=2, desc="...", on_upgrade = function(heroId, points) RPD.giveItem(heroId,"m58_test_weapon",1); RPD.affectBuff(heroId,"combat_hook_demo",points) end }`。**name 必填**(MOD_ enum 无 .title key,缺 name 会被 reject)。on_upgrade 签名固定 `function(heroId, points)`,heroId 是 int,points 是升级后点数。giveItem 用 demo_m58 自己注册的 m58_test_weapon(test_mod 在本测试 disabled,test_sword 不可用)
9. **talents/override_demo.lua**:`register_talent_override{ id="HEARTY_MEAL", maxPoints=1, desc="M58 override: 饱餐恢复降低" }`(baseMaxPoints=2,1 合法)
10. **talents/tier34_placeholder.lua**:注释说明 tier>2 需 M8d3;写一个 `register_talent{ id="MOD_EXAMPLE_TALENT", tier=3, class="MAGE", name="t34" }`(**id 必须是已声明的 MOD_ 枚举**;tier=3 触发 tier-guard reject,验证不崩。用 MOD_EXAMPLE_TALENT 是因为 test_mod 在本测试里 disabled,不冲突;new_talent_demo 用 MOD_SECOND_TALENT 也不冲突)
11. **DemoM58LoadTest.java**:照 `LuaModEntryTest` 模式(HeadlessApplication + `Game.versionCode=896` + `Game.version="test"` + FakePreferences)。`@Before`: `ModRegistry.scanDir(realModsHandle())` + `setEnabled("demo_m58",true)`(**不**启用 test_mod)+ `ModTestSupport.resetLuaState()`。两个 `@Test`:
    - `loadEnabled_registersAllScripts`:`LuaEngine.init()` 不抛异常,断言:
      - `LuaBuffRegistry.contains` 5 个 buff(combat_hook_demo/sleep_lock_demo/shield_demo/tint_demo/mana_demo)
      - **回调字段存在性**(防拼写错误,register_buff 不校验字段名):combat_hook_demo 的 attackProc/defenseProc/defenseSkill/attackSkill/drRoll/speed/charAct 用 `.isfunction()`;shield_demo 的 `shieldAmount` 用 `.optint(0)==20`(声明式 int,不是 function);sleep_lock_demo 的 sleepLock、shield_demo/tint_demo 的 tintChar 用 `.isfunction()`
      - `LuaItemRegistry.contains("m58_test_weapon")` + 其 getTable 有 onEquip/attackProc/onDeactivate 三个函数
      - `LuaTalentRegistry.isKnownModTalent("MOD_SECOND_TALENT")` + on_upgrade 是 function
      - `LuaTalentOverride.get(Talent.HEARTY_MEAL)` 非 null,maxPoints()=1
      - **C3**:test_mod 未启用 → `LuaItemRegistry.contains("test_sword")==false` + `LuaTalentRegistry.isKnownModTalent("MOD_EXAMPLE_TALENT")==false`(placeholder 被 tier-guard reject,不在 registry)
    - `loadDisabled_registriesEmpty`:`setEnabled("demo_m58",false)` → init 后 5 buff/weapon/talent/override 全不存在
12. **:core:test** 通过(既有 397 + 本测试 +2,不回归)

## Acceptance

- [ ] 5 个 buff 脚本注册成功(LuaBuffRegistry 含 combat_hook_demo/sleep_lock_demo/shield_demo/tint_demo/mana_demo),且关键回调字段存在(attackProc/defenseProc/defenseSkill/attackSkill/drRoll/speed/charAct/sleepLock/tintChar 为 function;shieldAmount 为声明式 int==20)— register_buff 不校验字段名,靠测试把拼写关
- [ ] m58_test_weapon 注册成功,覆盖 LuaItem 全部 3 回调(onEquip 挂 4 hero buff / attackProc 给敌人挂 sleep_lock / onDeactivate GLog)
- [ ] combat_hook_demo 覆盖全部 6 个 combat hook(attackProc/defenseProc/defenseSkill/attackSkill/drRoll/speed)+ charAct
- [ ] mana_demo 覆盖全部 4 个 mana API(restoreMana/spendMana/heroMana/heroManaMax)
- [ ] MOD_SECOND_TALENT 注册到 MAGE tier2(name 字段齐),on_upgrade(heroId,points) 升级时 giveItem + affectBuff
- [ ] HEARTY_MEAL override 下调 maxPoints=1
- [ ] tier3 placeholder(id=MOD_EXAMPLE_TALENT tier=3)被 tier-guard reject 但不崩(Gdx.app.error log,无异常)
- [ ] DemoM58LoadTest 通过(2 个 @Test);既有 397 tests 不回归
- [ ] C3:mod disabled 时所有 registry 空,vanilla 不受影响
- [ ] 不开 luajava(只传 id/int)
- [ ] 不改 production Java 代码(纯资源 + 1 个 test 类)

## Risks

- **MOD_SECOND_TALENT 占用冲突**:test_mod 已用 MOD_EXAMPLE_TALENT;本 mod 用 MOD_SECOND_TALENT。若两个 mod 同时启用且都用同一槽会 upsert(last wins)——demo_m58 用 SECOND 槽避让
- **tier3/4 placeholder**:M8d3 合前 tier>2 被 reject(预期);M8d3 合后需回头把 placeholder 改成真 tier3/4 注册(留 TODO 注释)
- **luaj 无 hex literal**:所有颜色用十进制(0x3399FF=3381759,0xFFD700=16753920),参照 champion_of_water.lua
- **buff 回调签名**:以 test_mod/scripts/buffs/*.lua 现有脚本为准(attackProc/defenseProc/defenseSkill/speed/tintChar/sleepLock/shieldAmount),不确定的签名先 grep LuaBuff.java 确认
- **真机触发**:m58_test_weapon 经 register_item 进 `LUA_ITEM` pool,但 `Generator.Category.LUA_ITEM(0,0,...)` 概率为 0,**不会自然掉落**(Generator.java:261 注释:"Reached only via Generator.random(LUA_ITEM)")。触发路径:debug 控制台 `Generator.random(Category.LUA_ITEM)`、或升级 new_talent_demo(MOD_SECOND_TALENT)由 on_upgrade giveItem(m58_test_weapon)送入背包。load test 只验证注册;真机手动测试用 debug 刷武器装备
