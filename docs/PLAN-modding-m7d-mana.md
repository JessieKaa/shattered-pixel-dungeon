# PLAN: M7d — mana 双轨(D7)

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M7(M7d 条目)+ §10.2 评估依据
> 前置:M7c 已合 master(`311961388`,LuaSpell `applySelf`/`applyAtCell` 消耗段已分离 L164-184)
> 并行:M7a(combat)/ M7e(talent)同时跑。本 feature 改 Hero/LuaSpell 消耗段/StatusPane/RpdApi mana 段,与 M7a(Char/Mob)、M7e(Talent) 文件域不重叠,**RpdApi 约定 `// M7d mana API` 分块**
> **D7 = 双轨(`useMode`),不单轨反转 D1**;D5' = 禁 luajava(只传 heroId/int)

## Goal

引入 mana 系统(双轨):Hero `MP`/`MPMax` 字段 + Bundle + regen + StatusPane MP 条 + LuaSpell `useMode`("consumable"|"mana")+ RpdApi mana API。新 spell 可走 mana(不消耗背包),旧 spell 保留消耗。**不破坏消耗经济**(双轨,旧 spell 0 改动)。

## Context

### 评估结论(§10.2,worker 自包含)

- **M3d LuaSpell 已预留 `spellCost` 字段**(L84,只 store 不 query,注释"future UI")— D7 反转的既定预留点
- **M7c 刚分离 `applySelf`(L164-172)/ `applyAtCell`(L175-184) 消耗段**,M7d 把 `detach(backpack)` 换成 mana 检查即可(callOpt 名 onUse/onUseAt 不变)
- **SPD 无现成 mana 槽**(`Char` 只有 HP;资源都是 per-item:artifact charge / wand charge)→ 必须新加 Hero MP 字段 + Bundle + UI
- **Remished 实为 `skillPoints(SP)` 非 mana**(`Hero.getSkillPoints/spendSkillPoints`,战斗驱动 regen),无直接抄;"真 mana" = 自研
- **D7=双轨**:`useMode` 字段,新 spell 走 mana / 旧保留消耗,0 重构 M6d;单轨反转破坏消耗经济 + 与 save-scum 叠加放大,**不做**

### 关键 file:line(worker Phase-1 已核对)

- `core/.../modding/LuaSpell.java`
  - 字段声明 L58-63(`luaSpellId/nameStr/descStr/castTime/spellCost/targeting`)→ 加 `useMode`
  - `hydrate` L80-98(已读 `spellCost` L90,只缺 `useMode`)
  - `applySelf` L164-172 / `applyAtCell` L175-184(M7c 分离的消耗段,两处都先 `detach` → M7d 改成调 `consume(hero)`)
  - `storeInBundle` L227-230 / `restoreFromBundle` L233-244(useMode 是 Lua 派生,不进 bundle —— 同 castTime/spellCost)
- `core/.../actors/hero/Hero.java`
  - 字段 L257-264(`STR/lvl/exp/HTBoost`)→ 加 `public int MP, MPMax`
  - 构造器 L272-281(`HP=HT=20`)→ 加 `MP=MPMax=10`
  - `updateHT` L283-298(HT = `20 + 5*(lvl-1)`)→ 末尾加 `updateMPMax()` 调用;新方法 `MPMax = 10 + 2*(lvl-1)`, `MP = min(MP, MPMax)`
  - `storeInBundle` L334-359 / `restoreFromBundle` L362-393(参考 HTBOOST L331/367)→ 加 `MP`/`MPMax` key(restore 缺 MP key → `MP = MPMax`,旧档兼容)
  - `live()` L565-571(attach Regeneration/Hunger)→ 加 `Buff.affect(this, ManaRegen.class)`
- `core/.../actors/buffs/Regeneration.java`(L34-137,ManaRegen 模板:`act()` spend TICK、partialRegen 持久化、`Regeneration.regenOn()` 门控 LockedFloor/Vault)
- `core/.../ui/StatusPane.java`
  - 字段 L60-63(`shieldHP/hp/hpText`)→ 加 `mpText`(简化:BitmapText,不做资产条)
  - 创建 L144-146(`hpText`)→ 加 `mpText = new BitmapText(PixelScene.pixelFont)`
  - `layout()` L203-278(hp 定位 large/small)→ mpText 跟在 hpText 下方(large: y+30 行;small: hp 行下方,可能需微调 — 简化放 large-only,small 隐藏)
  - `update()` L289-331(hp.scale/hpText)→ 加 `mpText.text("MP:"+mp+"/"+mpmax)`(cache `oldMP/oldMPMax`)
  - `alpha()` L383-398 → 加 `mpText.alpha(...)`
- `core/.../modding/RpdApi.java`
  - `build()` L132-203,`return rpd;` L202 前 → 加 `// M7d mana API` 分块(4 个 set)
  - `resolveChar` L206-217(复用;mana 函数里 `instanceof Hero` 再窄化,非 Hero → NIL)
  - `CharHP` L445-450 / `HealChar` L421-437 / `DamageChar` L402-418(`heroMana`/`heroManaMax` 仿 CharHP;`spendMana`/`restoreMana` 仿 HealChar 的 clamp 写法)
- `core/.../modding/LuaEngine.java` L436-454(`RegisterSpellFunction` 校验 id/name 后把**整张表**交给 `LuaSpellRegistry.register`)→ **无需改动**(useMode 由 LuaSpell.hydrate 消费,LuaEngine 透传)
- `core/src/main/assets/mods/test_mod/scripts/spells/heal.lua`(已有 `spellCost=5`,缺 `useMode`)→ 加 `useMode = "mana"`;`test_spell.lua` 保持无 useMode(默认 consumable)
- `core/src/test/java/.../modding/HeroManaTest.java`(新增):仿 `LuaHeroTest` L193-214 的 `new Hero()` + Bundle round-trip;`LuaSpellTest` 的 headless harness

## Files

- `core/src/main/java/.../actors/hero/Hero.java`:
  - 加 `public int MP, MPMax` 字段(L257-264 区,挨着 `HTBoost`)
  - 构造器 L272-281:`MP = MPMax = 10`(同 `HP=HT=20`)
  - 新 `private void updateMPMax()`:`MPMax = 10 + 2*(lvl-1)`;`MP = Math.min(MP, MPMax)`。由 `updateHT` 末尾(L297 后)调用,保证升级同步
  - `storeInBundle` L334-359 加 `bundle.put(MP, MP)` / `bundle.put(MPMAX, MPMax)`
  - `restoreFromBundle` L362-393(**codex round-1 must-fix #1**):`MPMax = bundle.contains(MPMAX) ? bundle.getInt(MPMAX) : (10 + 2*(lvl-1))`(旧档缺 key 用公式,不是 getInt 的 0 默认);`MP = bundle.contains(MP) ? Math.max(0, Math.min(bundle.getInt(MP), MPMax)) : MPMax`(旧档默认满;clamp 到 [0,MPMax] 防异常存档)
  - **codex round-1 must-fix #2**:`restoreFromBundle` 末尾加迁移 `if (buff(ManaRegen.class) == null) Buff.affect(this, ManaRegen.class);` —— `Dungeon.loadGame` 走 Bundle 还原不调 `live()`,`Char.restoreFromBundle` 只还原存档里已有的 buff,旧档(无 ManaRegen)加载后不会自动回蓝。新游戏走 `live()` 也挂(双保险)
  - `live()` L565-571:加 `Buff.affect(this, ManaRegen.class)`(新游戏入口)
- `core/src/main/java/.../actors/buffs/ManaRegen.java`(**新增**,放 `actors/buffs/`,仿 `Regeneration`):
  - `extends Buff`;`actPriority = HERO_PRIO - 1`(同 Regeneration,hero 之后回蓝)
  - `act()`:`if (!Regeneration.regenOn()) { spend(TICK); return true; }`(locked floor/vault 不回蓝,同 HP regen 门控);否则每 10 tick `((Hero)target).MP = min(MP+1, MPMax)`,partialRegen 累加(同 Regeneration 的浮点累加写法),`spend(TICK)`
  - 不抄 chalice/salt/energy 配件(那些是 HP 专属);纯慢回蓝
- `core/src/main/java/.../modding/LuaSpell.java`:
  - 字段加 `private String useMode = "consumable";`(默认 consumable = 旧行为)
  - `hydrate` L80-98 加:`String m = tbl.get("useMode").optjstring("consumable"); useMode = "mana".equals(m) ? "mana" : "consumable";`(坏值/缺省 → consumable,zero regression)
  - **codex round-1 must-fix #3**:`spellCost = Math.max(0, tbl.get("spellCost").optint(0));`(spellCost 现在是真实消耗,负值会让 `MP -= spellCost` 反向加蓝,破坏经济;clamp ≥0)
  - 新 `private boolean consume(Hero hero)`:
    - `"consumable".equals(useMode)` → `detach(hero.belongings.backpack); return true;`
    - `"mana".equals(useMode)` → `if (hero.MP < spellCost) return false; hero.MP -= spellCost; return true;`(spellCost 已 ≥0)
  - `applySelf` L164-172:`if (!consume(hero)) { GLog.w("Not enough mana"); return; }` 然后原 callOpt/spend/busy 不变
  - `applyAtCell` L175-184:同上(把首行 `detach(...)` 换成 `if (!consume(hero)) { GLog.w("Not enough mana"); return; }`)
  - 加 `public String useMode()` getter(测试用)
- `core/src/main/java/.../modding/LuaEngine.java`:**不改**(`RegisterSpellFunction` L436-454 已透传整张表,useMode 由 hydrate 消费)
- `core/src/main/java/.../modding/RpdApi.java`:`build()` L202 `return rpd;` 前加 `// M7d mana API` 分块:
  - `heroMana(heroId)`→int(resolveChar→`Hero`→`MP`,非 Hero/NIL → NIL)
  - `heroManaMax(heroId)`→int
  - `spendMana(heroId,amt)`→bool(Hero 且 MP≥amt → `MP-=amt` 返 true;否则 false)
  - `restoreMana(heroId,amt)`→bool(`MP = min(MPMax, MP+amt)`,返 true;非 Hero 返 false)
  - **codex round-1 must-fix #4**:mana 金额**不复用** `validAmount`(它放过 0.5 这种小数,`(int)0.5=0` → spendMana 返 true 扣 0)。新增 `private static boolean validManaAmount(LuaValue v) { return v.isint() && v.toint() >= 1 && v.toint() <= (int)MAX_AMOUNT; }`,spendMana/restoreMana 用它校验(要求整数 ≥1)。MP 是 int,小数无意义
  - 4 个 private static inner class(`OneArgFunction`/`TwoArgFunction`),仿 `CharHP`/`HealChar`
- `core/.../modding/LuaSpellRegistry.java`(round-2):加 `private static boolean hasManaSpell` + `register()` 里检测 useMode=mana 置位 + `clear()` 重置 + `public static boolean hasManaSpell()` getter(StatusPane 显示守卫用)
- `core/.../ui/StatusPane.java`:加 `mpText`(BitmapText,简化文字条,无新资产):
  - 字段 L60-63 区加 `private BitmapText mpText;` + cache `private int oldMP = 0, oldMPMax = 0;`
  - 创建(L144 hpText 后):`mpText = new BitmapText(PixelScene.pixelFont); mpText.alpha(0.6f); add(mpText);`
  - **codex round-1 must-fix #5(布局)**:large 布局 HP 条在 `y+19`(高 9 → 到 y+28),exp 条在 `y+30`。原方案 `mpText.y=y+28` 会压到 exp 行。改为 **HP 条同行右对齐**:large 分支里 `mpText.scale.set(PixelScene.align(0.5f)); mpText.x = hp.x + 128 - mpText.width(); mpText.y = hp.y + 1;`(与 hpText 同一行,hpText 居中、mpText 靠右,128px 宽条两端通常有空白;不碰 exp 行)。small 分支 `mpText.visible = false`(空间不够,资产留后)
  - `update()`(L322 hpText 更新块后):`if (oldMP != mp || oldMPMax != mpmax) { mpText.text(mp+"/"+mpmax); mpText.measure(); mpText.x = hp.x + 128 - mpText.width(); oldMP=mp; oldMPMax=mpmax; }` + **`mpText.visible = large && LuaSpellRegistry.hasManaSpell()`**(round-2 守卫,vanilla 隐藏)
  - `alpha()` L383-398:加 `mpText.alpha(0.6f*value)`
- `core/src/main/assets/mods/test_mod/scripts/spells/heal.lua`:加 `useMode = "mana"`(已有 `spellCost = 5`);`test_spell.lua` **不动**(默认 consumable,验证双轨)
- `core/src/test/java/.../modding/HeroManaTest.java`(新增):
  - MP/MPMax 默认值 + Bundle round-trip(旧档无 MP key → 默认满)
  - LuaSpell mana 模式:hydrate `useMode="mana"`;MP 够 → consume 返 true + MP 扣 + 不 detach(quantity 不变);MP 不足 → consume 返 false(applySelf 早返,GLog.w — 测 consume 返 false 即可,applySelf 需 GameScene 见 PLAN risk)
  - LuaSpell consumable 模式:test_spell quantity 3 → consume 调 detach → quantity 2
  - RpdApi spendMana/restoreMana/heroMana/heroManaMax(需 Hero 在 Actor 注册表 — 见下 risk)
  - useMode 坏值 → consumable fallback

## Worker Decisions (Phase-1 细化)

1. **MPMax 公式**:`10 + 2*(lvl-1)`(lvl1=10,lvl10=28,lvl26=60)。形状对齐 HT 的 `20 + 5*(lvl-1)`。heal spellCost=5 → lvl1 恰好 2 发
2. **ManaRegen 位置**:`actors/buffs/ManaRegen.java`(不放 modding/ 子包)。理由:它是 hero 常驻 buff(非 Lua 脚本驱动,挂在 `Hero.live()`),包归属对齐 `Regeneration`。vanilla 也常驻(MP 字段始终在),只是 vanilla spell 全 consumable → MP 不被消耗,等若无物(C3 不污染)。新文件 = 0 上游合并冲突
3. **ManaRegen 门控**:复用 `Regeneration.regenOn()`(LockedFloor/VaultLevel 不回蓝),与 HP regen 一致;不抄 chalice/salt/RingOfEnergy(HP 专属,避免把回蓝绑到那些配件上)
4. **ManaRegen 不直接调 RpdApi**:`((Hero)target).MP = min(MP+1, MPMax)` 直写字段(同 Regeneration 直写 `target.HP`);RpdApi.restoreMana 是给 Lua spell 用的 public face,内部 buff 不绕路
5. **LuaEngine 无改动**:`RegisterSpellFunction` 已透传整张 Lua 表,useMode 由 `LuaSpell.hydrate` 消费(PLAN 原 step 3 是 no-op)
6. **consume 失败早返**:`applySelf`/`applyAtCell` 首件事就是 `consume(hero)`;mana 不足返 false → GLog.w + return,**不 spend(castTime)、不 busy()**(玩家可立即重试,不白等 1f)
7. **StatusPane 简化**:只加 `mpText`(BitmapText "MP:x/y"),large 布局显示;small 布局 `visible=false`(空间挤,美术资产留后)。不做资产条(资产要改 `interfaces.png`,超 M7d 范围)
8. **测试边界**(risk 记录):`ManaRegen.act()`、`applySelf` 全路径依赖 GameScene/Actor tick,LuaSpellTest 已有先例"selectCell 路径只 code-review,不 headless"。HeroManaTest 测得到的部分:MP 字段 round-trip、`consume(hero)` 两种 useMode 分支(直接调 private via package)、RpdApi mana 4 函数(需 `Actor` 注册 hero —— 用 `Actor.add`/`Actor.process` 之外的 `nextID` + 手动 `id`,或参考 RpdApiBuffTest 的 hero 装配)。测不到的:`applySelf` 整链、`ManaRegen.act()` 整链(留 code-review + desktop run)
9. **save-scum 缓解**:死亡读档时 `WndResurrect.instance` 占位抑制 Rankings,save slot 满血满蓝重载是设计行为(fork 立场)。M7d 不额外加 mana cooldown —— 平衡靠 MPMax/regen 常数 + spellCost。回填建议:后续可在 `SaveSlotService.loadFromSlot` 里给 MP 打折(如重载后 MP=MPMax/2),但属 save-slot feature 决策,不在 M7d scope
10. **codex round-1 must-fix 全收纳**(5 条):
    - #1 MPMax restore 不能用 `getInt`(缺 key 返 0),用 `contains` 三元 + 公式 fallback
    - #2 `Hero.restoreFromBundle` 必须迁移补挂 ManaRegen(`loadGame` 不走 `live()`,旧档 buff 列表无 ManaRegen)
    - #3 spellCost clamp ≥0(防负值反向加蓝)
    - #4 mana API 用 int-only 校验(防 0.5 小数 → 扣 0 返 true)
    - #5 StatusPane mpText 改 HP 条同行右对齐(原 y+28 压 exp 行 y+30)
11. **codex round-2 must-fix #1(C3 可见性污染)**:StatusPane 不能在所有 vanilla 局显示 MP。加**显示守卫**:
    - `LuaSpellRegistry` 加 `private static boolean hasManaSpell`(在 `register(id,tbl)` 里,若 `tbl.get("useMode").optjstring("consumable").equals("mana")` 则置 true;`clear()` 重置)+ `public static boolean hasManaSpell()` getter
    - StatusPane `mpText.visible = large && LuaSpellRegistry.hasManaSpell()`(vanilla 无 mod → registry 空 → false;有 mod 注册了 mana spell → true)
    - C3 现在真零污染(机制 + UI 都不显)。HeroManaTest 补:mod disabled → `hasManaSpell()==false`;test_mod init(heal.lua useMode=mana)→ true

## Steps(高层 checklist;细节见上方 Files + Worker Decisions)

1. **Hero MP 字段 + Bundle**:
   - `int MP, MPMax`;`storeInBundle` put `MP`/`MPMax`;`restoreFromBundle` 若 `!bundle.contains("MP")` 默认 `MP=MPMax`(旧档兼容)
   - `updateHT` 末尾调 `updateMPMax()`:`MPMax = 10 + 2*(lvl-1)`(基数可调),`MP = min(MP, MPMax)`
2. **ManaRegen buff**(`actors/buffs/`):
   - 新 buff `ManaRegen extends Buff`,`act()` 门控 `Regeneration.regenOn()`,每 10 tick `hero.MP = min(MP+1, MPMax); spend(TICK)`;`Hero.live()` 里 attach
   - 平衡:慢 regen(不 flood);modder 可调 MPMax/regen rate(系统常数,非 per-mod)
3. **LuaSpell useMode 激活**(LuaEngine 无需改):
   - hydrate `useMode = tbl.get("useMode").optjstring("consumable")`;校验 "consumable"|"mana",坏值 → consumable
   - `spellCost` 既有(L90),激活读取(默认 0)
4. **applySelf/applyAtCell 消耗段分支**(M7c 分离的段,L164-184):
   - 提取消耗成 `consume(Hero hero)`:if useMode=="mana" → 检查 MP;else detach
   - `applySelf/applyAtCell`:`if (!consume(hero)) { GLog.w("Not enough mana"); return; }` 然后 callOpt/spend/busy 不变
   - **MP 不足**:返 false → GLog.w + return(不 spend,不 cast,玩家可重试)
5. **RpdApi mana API**(`// M7d mana API` 分块,`return rpd` 前):
   - `heroMana(heroId)`→int(resolveChar → Hero → MP,非 Hero → NIL)
   - `heroManaMax(heroId)`→int
   - `spendMana(heroId,amt)`→bool(够则减,返 true;不够返 false)
   - `restoreMana(heroId,amt)`→bool(给 regen/heal spell 用)
6. **StatusPane MP 文字条**:`mpText` BitmapText("MP:x/y"),large 显示,small 隐藏;资产留后
7. **示例 spell**:heal.lua 加 `useMode="mana"`(已有 spellCost=5);test_spell 保持 consumable;验证双轨
8. **测试**(HeroManaTest):
   - MP round-trip(store/restore,旧档默认满 —— 用 contains 公式 fallback,不是 getInt 0)
   - **旧档迁移**(round-1 #2):restore 一个无 ManaRegen 的 bundle → hero 上有 ManaRegen buff
   - LuaSpell mana 模式:MP 够 → consume 返 true + MP 扣 + 不 detach;MP 不足 → consume 返 false
   - LuaSpell consumable 模式:test_spell → consume detach
   - spellCost 负值 clamp ≥0(round-1 #3)
   - RpdApi spendMana/restoreMana/heroMana/heroManaMax;spendMana 拒绝小数/0/非 int(round-1 #4)
   - useMode 坏值 → consumable fallback
   - 既有 277 tests 不回归
9. **C3 回归**:test_mod disabled 时 vanilla 无 mana(MP 字段在 Hero 但 vanilla spell 全 consumable,MP 不消耗,等若无物;**且 StatusPane 不显示 MP** —— `LuaSpellRegistry.hasManaSpell()==false` 时 mpText.visible=false,round-2 守卫);原版一周目零污染(机制+UI)。ModToggleRegressionTest 绿
10. **回填 PLAN 末尾平衡风险**:MPMax/regen 系数、save-scum 叠加缓解建议、modder 调参点

## Acceptance

- [x] Hero MP/MPMax 字段 + Bundle round-trip(旧档无 key 默认满,兼容)
- [x] ManaRegen buff(慢 regen)
- [x] LuaSpell useMode 双轨:mana 模式 spend MP(不足不消耗);consumable 模式 detach 不变
- [x] RpdApi mana API(heroMana/heroManaMax/spendMana/restoreMana)
- [x] StatusPane MP 条(简化,large-only,vanilla 隐藏)
- [x] heal.lua 走 mana;test_spell 保持 consumable
- [x] 不破坏消耗经济(双轨,旧 spell 不变)
- [x] 不开 luajava(只传 heroId/int)
- [x] `:core:test` 通过(295/0/0,277 基线 + 18 新);C3 守住(vanilla 机制 + UI 零污染)
- [x] 回填平衡风险 + modder 调参点

## 平衡风险(完成后回填)

- **MPMax/regen 系数**:MPMax = `10 + 2*(lvl-1)`(lvl1=10 → 2 发 heal,lvl10=28,lvl26=60);ManaRegen = 1 MP / 10 tick(同 HP regen 节奏),LockedFloor/Vault 不回蓝(门控 `Regeneration.regenOn()`)。系统级常数(非 per-mod),装在 Hero.updateMPMax / ManaRegen.MANA_DELAY。lvl1 heal(spellCost=5)≈ 2 发,regen 回满需 100 turn(约 50 步探索),对前期节奏偏紧、后期宽裕,符合"慢回蓝不 flood"意图。
- **save-scum 叠加缓解**:fork 的死亡读档(save slot)本就满血重载,mana 满重载是同构设计行为(非 bug)。M7d **不加**额外 mana cooldown —— 平衡靠 MPMax/regen/spellCost 三常数。**建议(留后)**:若发现 mana spell 滥用,在 `SaveSlotService.loadFromSlot` 末尾给 MP 打折(如 `hero.MP = hero.MPMax / 2`),属 save-slot feature 决策,不在 M7d scope。死亡读档 + Rankings 抑制链路(WndResurrect.instance)未受 M7d 影响(MP 字段透明)。
- **modder 调参点(MPMax/regen/useMode/spellCost)**:
  - per-spell:`useMode`("consumable"|"mana")+ `spellCost`(≥0,lua 表里设)。这是 modder 唯一可直接调的 mana 旋钮
  - 系统级(改源码,非 lua):MPMax 公式(`Hero.updateMPMax`)、regen 速率(`ManaRegen.MANA_DELAY`)、regen 门控(`Regeneration.regenOn()`)
  - **局限**:M7d 不提供 per-mod/per-hero 的 MPMax/regen 覆写 API(无 RpdApi.setManaMax)。modder 只能在 spellCost 上调;想要自定义 mana 池大小的 mod 需改 Hero 源码。per-hero MPMax override 是未来 talent(M7e)或 hero-def(lua hero `mp` 字段)的扩展点,本 feature 不做
- **消耗经济影响**:双轨保证旧 spell(useMode 默认 consumable)100% 走 detach,0 改动;新 spell 走 mana 不消耗背包 → 两套经济并行,互不污染。test_spell(consumable)与 heal(mana)同时存在于 test_mod,验证双轨

## Risks

- Hero MP 字段影响 save schema(Bundle 加 key,旧档默认满,兼容;但需测跨版本)
- mana 平衡:save-scum 叠加(死亡读档 MP 满)→ 需 cooldown/regen 调参;modder 可调
- StatusPane UI 改动影响所有 hero 类/分辨率(资产:先简化文字条,美术留后)
- 守 fork 约束:Hero/StatusPane 改动是加字段 + 加 UI 元素,不动上游方法体(C4);新 buff ManaRegen 进 buffs 或 modding 子包
- M7d/M7a/M7e RpdApi 冲突:约定 `// M7d mana API` / `// M7a combat API` / `// M7e talent API` 分块,合并取并集
- C5 proguard:MP 字段无反射,不需 keep;ManaRegen 是新 Buff 类,检查 keep 规则
