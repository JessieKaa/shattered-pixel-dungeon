# PLAN: M7b — combat hook rest(剩余 hook → buff 16/16)

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M7(M7b 条目)
> 前置:M7a 已合 master(`934d379e8`,LuaBuff 4 数值回调槽 attackProc/defenseProc/drRoll/speed + Char/Mob dispatch 框架 + callOptInt/callOptFloat 模式,11/16 buff 高保真)
> 本 feature 是 M7 第二批(**单 worker,不并行**——M7a/c/d/e 全合,无并行对象)
> D5' = (a) 禁 luajava(只传 id/int)

## Goal

补完 M7a 留下的剩余 combat hook → buff 完成度 **11/16 → 16/16**(除 `anesthesia` sleep-lock + `shields lib` 明确留 M8/永久降级)。复用 M7a 的 LuaBuff 回调槽 + Char/Mob 单点 dispatch 模式,边际递减。

## Context

### M7a 已交付(master `934d379e8`)

- LuaBuff 4 数值回调槽(attackProc/defenseProc/drRoll/speed)+ `callOptInt`/`callOptFloat` 模式
- Char 4 处单点 dispatch(attackProc/defenseProc/drRoll/speed 末尾 `for buff instanceof LuaBuff` loop)
- 9 buff 升级,body_armor/mana_shield/champion_of_fire/earth/air/die_hard/shield_left/chaos_shield_left/test_buff → 11/16 高保真

### M7a worker 的 M7b 预测(已回填 M7a PLAN)

剩余 8 类 hook + 5 degraded buff:

| hook | 工时 | 解锁的 buff |
|---|---|---|
| defenseSkill/attackSkill | ~2h(套 M7a 模式) | `champion_of_water`(defenseSkill) |
| charAct | ~0.5d(新机制,per-tick 主动) | `counter`(charAct 计时) |
| damage | ~1d | (备选,若有 buff 需要) |
| regenBonus | ~0.5d(依赖 charAct) | (备选) |
| HT setter | ~2h | (备选) |
| sprite tint | ~0.5d | (备选,glow) |
| belongings/yell | ~0.5d | `encumbrance`/`unsuitable_item`(belongings/yell) |
| shields lib | ~1-2d | (留 M8 或永久降级,工作量大) |

剩余 5 degraded buff:`champion_of_water`(defenseSkill)、`counter`(charAct)、`anesthesia`(sleep-lock)、`encumbrance`(belongings)、`unsuitable_item`(yell/belongings)

**M7b 优先级**(worker 建议):defenseSkill/attackSkill → charAct → belongings/yell,可推到 16/16(除 shields lib + sleep-lock)

### 关键 file:line(M7a 框架,worker 已核对)

- `core/.../modding/LuaBuff.java:220-260`(M7a 的 4 回调槽 + callOptInt/callOptFloat 模式,新 hook 套同样模式)
- `core/.../actors/Char.java:627-628`(`hit()` 静态方法读 `attacker.attackSkill()` / `defender.defenseSkill()`)
- `core/.../actors/Char.java:696-702`(`attackSkill`/`defenseSkill` 基类返回 0)
- `core/.../actors/hero/Hero.java:671/728`(Hero 重写 attackSkill/defenseSkill,**不调 super**)
- `core/.../actors/mobs/Mob.java:684`(Mob 重写 defenseSkill;attackSkill 在各 mob 子类重写,不在 Mob)
- `core/.../actors/mobs/Mob.java:225` `Mob.act()` 调 `super.act()`;`core/.../actors/hero/Hero.java:997` `Hero.act()` **不调 super.act()**
- M7a 升级的 buff scripts(`body_armor`/`champion_*` 等)作为新 hook 升级模板

### Worker 核对后的设计决定(覆盖原 PLAN 的方向)

1. **attackSkill/defenseSkill dispatch 放在 `Char.hit()` 调用点(line 627-628)**和**`Stone.proc`(line 46-47)**两处,不放基类方法末尾。
   - 原因:`Char.attackSkill/defenseSkill` 基类返回 0,Hero/Mob 重写都**不调 super**,且 `attackSkill` 在 ~20 个 mob 子类各自重写。在基类末尾 dispatch 不会触发;逐子类加 dispatch 是 20+ 点,不可接受。
   - 真实 combat 读点只有两个:`Char.hit()`(主命中判定)和 `Stone` glyph 的 what-if 计算(codex 评审 issue 1 指出)。`FloatingText` 也读但纯 UI(cosmetic,不接,且避开 `Stone.testingEvasion` flag 交互)。
   - 两处各加 `LuaBuff.dispatchAttackSkill(attacker, acuStat)` / `dispatchDefenseSkill(defender, defStat)`(float 入参出参,匹配局部变量类型)。语义与 M7a 一致("bearer 的 buff 修正自己的数值")。

2. **charAct dispatch 放 `Actor.process`(line 294 `acting.act()` 前),不放 Hero.act/Mob.act**。
   - 原因(codex 评审 issue 2):多个 Mob 子类(CrystalSpire/Pylon/YogDzewa/DwarfKing)重写 `act()` 不调 `super.act()`;`LuaMob.act()` 在 Lua 接管 tick 时也 `return true` 早于 `super.act()`。Hero.act/Mob.act 两点 dispatch 全漏这些路径。
   - `Actor.process` 是所有 Actor(含所有 Char 子类)的唯一调度点:在 `acting.act()` 前加 `if (acting instanceof Char) LuaBuff.dispatchCharAct((Char)acting);`,覆盖 Hero/Mob/mob 子类/LuaMob-Lua-mode 全部。一处 fork hook,真正单点。
   - charAct 是 advisory(建议性)回调,无返回值语义:charAct 是 Char 每 tick 主动行为(非 buff 自身 act 生命周期),返回值无对应 Java 控制流。counter buff 只递增 + showStatus,不需要返回值。buff 想 detach 调 `RPD.detachBuff`。用 `LuaItemCallbacks.callOpt(tbl,"charAct",selfId,targetId,state)`,与 `detach`/`onCommand` 同型。

3. **belongings/yell 用 id API,不暴露 Belongings 对象**。
   - `RPD.encumbranceItemName(heroId)` —— 遍历 hero 已装备 weapon/secondWep/armor,返回第一个 `STRReq() > hero.STR()` 的 item name,或 nil。faithful 移植 Remished `Belongings.encumbranceCheck()`(检查已装备 item 的 STR 超载)。Hero-only(mob 无 belongings)。
   - `RPD.yell(charId, text)` —— 任何 Char 都可:Mob 走 `Mob.yell`(GLog 引号行),Hero 走 `GLog.n` 同格式。补 npcYell 的 Hero 空白(npcYell 是 NPC-only,本 API 放开给 Hero)。
   - encumbrance/unsuitable_item 的 charAct:5% 概率取 encumbranceItemName,yell 警告句。unsuitable_item 原版 yell 注释掉,本端口补上(从 degraded stub 升级为实际行为)。
   - codex 确认此 API shape 守住 D5'-(a)(只传 id/int/string,无 Java 对象过边界)。

## Files

- `core/.../modding/LuaBuff.java`:加 `attackSkill`/`defenseSkill`/`charAct` 回调;前两者 callOptInt;`charAct` advisory(callOpt 无返回);加 3 个 static dispatch helper(`dispatchAttackSkill`/`dispatchDefenseSkill` float 重载 + `dispatchCharAct`)
- `core/.../actors/Char.java`:`hit()` line 627-628 后加 `dispatchAttackSkill`/`dispatchDefenseSkill`(float)
- `core/.../items/armor/glyphs/Stone.java`:`proc()` line 46-47 后加同样 dispatch(Stone 是第 2 个 combat 读点)
- `core/.../actors/Actor.java`:`process()` line 294 `acting.act()` 前加 `if (acting instanceof Char) LuaBuff.dispatchCharAct((Char)acting);` + import LuaBuff
- `core/.../modding/RpdApi.java`:`// M7b hook API` 分块:加 `encumbranceItemName(heroId)` + `yell(charId,text)`
- `core/.../assets/mods/test_mod/scripts/buffs/`:升级 `champion_of_water`(defenseSkill)/`counter`(charAct)/`encumbrance`(charAct+yell)/`unsuitable_item`(charAct+yell),移除 degraded
- `core/test/.../modding/RpdApiBuffTest.java`:扩 attackSkill/defenseSkill/charAct/encumbrance/yell hook 单测

## Steps

1. **attackSkill/defenseSkill hook**(call-site dispatch,~2h):
   - LuaBuff 加 `attackSkill(selfId,atk)`/`defenseSkill(selfId,def)` 回调(callOptInt)
   - LuaBuff 加 `static float dispatchAttackSkill(Char,float)` / `dispatchDefenseSkill` —— 遍历 `buffs()` callOptInt,int↔float 转换
   - `Char.hit()` line 627-628 + `Stone.proc()` line 46-47 后 dispatch(两处 combat 读点)
   - 升级 `champion_of_water`(`defenceSkillBonus = lvl*1.25` → `defenseSkill(selfId,def) return def + math.floor(lvl*1.25)`,lvl 取 `RPD.buffLevel`),移除 degraded
2. **charAct hook**(advisory,Actor.process 单点,~0.5d):
   - LuaBuff 加 `charAct(selfId,targetId,state)`(callOpt,无返回语义)
   - LuaBuff 加 `static void dispatchCharAct(Char c)` —— 遍历 `c.buffs()` 对每个 LuaBuff callOpt `charAct`
   - `Actor.process()` `acting.act()` 前单点 dispatch(覆盖 Hero/Mob/mob 子类/LuaMob-Lua-mode 全部)
   - 升级 `counter`(保留现有 act 计时/detach;新增 charAct:递增 state.counter + showStatus,对齐 Remished),移除 degraded
3. **belongings/yell id API**(~0.5d):
   - RpdApi 加 `encumbranceItemName(heroId)`:resolveHero → 遍历 weapon/secondWep/armor,返回首个 `STRReq()>STR()` 的 name 或 NIL
   - RpdApi 加 `yell(charId,text)`:Mob→`Mob.yell`;Hero/其他→`GLog.n` 同格式
   - 升级 `encumbrance`(charAct 5% → encumbranceItemName → yell)/`unsuitable_item`(同,原版 yell 注释掉,端口补上),移除 degraded
4. **anesthesia sleep-lock 评估**:sleep-lock 需 hook `Sleep`/`Drowsy` + `Char.damage` 唤醒路径,Remished 用自定义 Sleeping AI。SPD 无对应单点 hook,**明确留 M8**
5. **shields lib 评估**:工作量 ~1-2d(独立 shields 抽象层),**明确永久降级或留 M8**
6. **测试**:RpdApiBuffTest 扩 attackSkill/defenseSkill/charAct/encumbrance/yell;既有 tests 不回归
7. **C3 回归**:test_mod disabled 8 registry 全空
8. **回填 PLAN 末尾**:剩余降级项清单(anesthesia/shields lib)+ M8 建议

## Acceptance

- [ ] defenseSkill/attackSkill hook 实现(LuaBuff 回调 + Char dispatch)
- [ ] charAct hook 实现(per-tick 主动,LuaBuff 回调 + Mob/Char dispatch)
- [ ] belongings/yell hook 或 id API 实现
- [ ] 4 buff 升级(champion_of_water/counter/encumbrance/unsuitable_item),buff **16/16**(或明确剩余降级)
- [ ] anesthesia sleep-lock + shields lib 明确处置(留 M8 / 永久降级,PLAN 记录)
- [ ] 不开 luajava(只传 id/int/string)
- [ ] `:core:test` 通过,既有 322 tests 不回归;C3 守住
- [ ] 回填剩余降级项 + M8 建议

## 剩余降级项(完成后回填)

- anesthesia sleep-lock:**留 M8**。Remished 用自定义 `Sleeping` AI + `damage()` 回调抑制睡眠唤醒;SPD 的 `Sleep`/`Drowsy` 唤醒路径在 `Char.damage`/`Sleep.act` 内,无单点 buff hook,且需改 AI 状态机。M8 评估 `Char.damage` pre-hook 或 `Sleep` awakening flag。
- shields lib:**永久降级 / 留 M8**。Remished 的 shields 是独立抽象层(类 `Shield` 库,多 shield 类型组合),SPD 用 `Shield` buff(int 字段)。完整移植需新 shield 抽象 + 组合规则,~1-2d,工作量大且 gameplay 收益低(SPD `Shield` buff 已覆盖主要用例)。建议 M8 评估是否值得,否则永久降级。
- champion_of_water glow(sprite tint):**留 M8**。`setGlowing` 是 sprite 着色,需 sprite-tint hook,本 feature 无。gameplay 半(defenseSkill)已桥接,glow 是 cosmetic。
- 其他未接 hook:`damage`(备选,无 buff 需要)、`regenBonus`(无 buff 需要)、`HT setter`(无 buff 需要)、`sprite tint`(见上)—— M7b 无 buff 依赖这些,未接。
- buff 完成度:**16/16**(5 degraded 中升级 4:champion_of_water/counter/encumbrance/unsuitable_item;anesthesia 留 M8)。原 PLAN 说"除 anesthesia + shields lib",其中 shields lib 不是 buff 而是 lib,不影响 buff 计数;实际 buff 16/16 中 anesthesia 是唯一行为降级(metadata + icon 保留)。

## M8 建议

1. **anesthesia sleep-lock**:加 `Char.damage` pre-hook(buff 标记 damage 为 non-waking),或 hook `Sleep.awaken` 路径。
2. **shields lib**:评估是否移植 Remished shield 抽象层,否则永久降级(SPD `Shield` buff 够用)。
3. **sprite tint / glow**:加 `Char.sprite` tint hook 解锁 champion_of_water glow 及潜在 visual buff。
4. **charAct 性能**:若 M8+ Lua buff 数量增长,考虑 charAct 限频或 guard(目前每 Char turn 每 buff 一次 Lua call,vanilla 无 LuaBuff 时空遍历)。

## Risks

- charAct 是新机制(per-tick 主动),性能:多 LuaBuff 每 tick 调 Lua,需 guard 或限频
- belongings/yell 可能需新 id API(背包遍历),若复杂简化或留 M8
- sleep-lock / shields lib 若评估为 M8 级,明确降级不硬做
- 守 fork 约束:Char/Mob 单点 dispatch(套 M7a 模式),新代码进 modding/ 子包
- C5 proguard:无反射
