# PLAN: M10c — buff 回调补完(8 新回调 + 4 桥接)

## Goal
补完 LuaBuff 缺失的 8 个回调 + 4 个 `*Bonus` 桥接适配,让 9/16 Remished buff 脚本能完整工作(Champion×4 / DieHard / Anesthesia / Cloak / BodyArmor / Counter)。

## Context
M7b 接了部分 LuaBuff 回调(attackProc/defenseProc/defenseSkill/attackSkill/charAct/speed/drRoll),M8 加了 sleepLock/tint/shield。调研确认(M10c 报告)Remished buff 用了 M7b 没接的回调:

**8 全新回调**(SPD Buff 无对应方法,需新增 hook):
| 回调 | SPD 挂载点 | 涉及 buff |
|---|---|---|
| `regenerationBonus` | `Regeneration.act()` 前加成 | DieHard, ChampionOfEarth |
| `hasteLevel` | `Char.speed()` 或 Haste 乘算 | ChampionOfAir |
| `stealthBonus` | `Char.stealth()` 加成 | Cloak |
| `charSpriteStatus` | `Char.updateSpriteState()` 返回标签(如 "INVISIBLE") | Cloak |
| `setGlowing` | attachTo 后 `sprite.aura(color, rays)` | Champion×4 |
| `damage`(受击通告,不修正) | `Char.damage()` | DieHard, Anesthesia |
| `drBonus` | 物理减伤加成 | BodyArmor, ChampionOfEarth |
| `speedMultiplier` | 移速乘算 | BodyArmor |

**4 桥接适配**(`*Bonus` 增量语义 vs M7b 修正值语义):
- `attackSkillBonus`(ChampionOfFire)→ 桥接 `attackSkill`(增量叠加)
- `defenceSkillBonus`(ChampionOfWater)→ 桥接 `defenseSkill`
- `drBonus` → 桥接 `drRoll`
- `speedMultiplier` → 桥接 `speed`

**关键设计**:`*Bonus` 返回加成增量(如 +5),M7b 的 `attackSkill/defenseSkill/drRoll` 是"接受当前值返回修正值"。M10c 在 LuaBuff 内把 `*Bonus` 折算为增量叠加到当前值,复用 M7b 派发路径(不重写派发,只加增量折算层)。

## Files
- `core/.../modding/LuaBuff.java` — 8 新回调 dispatch + 4 桥接折算
- `core/.../actors/Char.java` — hook 点(`stealth()`/`updateSpriteState()`/`damage()` 等,单点)
- `core/.../actors/buffs/Regeneration.java` — `regenerationBonus` hook
- `core/.../actors/buffs/Haste.java`(或 `Char.speed()`)— `hasteLevel` hook
- `assets/mods/test_mod/scripts/buffs/*.lua` — 更新 Champion×4/DieHard/Anesthesia/Cloak/BodyArmor 用新回调
- 测试:`core/test/.../modding/LuaBuffCallbackTest.java`

## Steps
1. **regenerationBonus**:`Regeneration.act()` 前,遍历 hero LuaBuff 调 `regenerationBonus()`,加成 regen 量。
2. **hasteLevel**:`Char.speed()` 调 LuaBuff `hasteLevel()`,乘算 speed。
3. **stealthBonus**:`Char.stealth()` 调 LuaBuff `stealthBonus()`,加成 stealth。
4. **charSpriteStatus**:`Char.updateSpriteState()` 调 LuaBuff 返回状态标签(INVISIBLE 等)。
5. **setGlowing**:`LuaBuff.attachTo` 后调 `target.sprite.aura(color, rays)`(复用 M8c tint 的 aura 机制)。
6. **damage(通告)**:`Char.damage()` 调 LuaBuff `damage(buff, damage, src)`(通告,不修正;区别 defenseProc)。
7. **drBonus + speedMultiplier**:LuaBuff 内桥接(增量叠加到 drRoll/speed 派发)。
8. **attackSkillBonus + defenceSkillBonus**:桥接(增量叠加到 attackSkill/defenseSkill 派发)。
9. **更新 test_mod buff 脚本**:Champion×4 / DieHard / Anesthesia / Cloak / BodyArmor 用新回调(从 Remished `scripts/buffs/` 翻译)。
10. **测试**:每个回调 + 桥接 + buff 脚本工作。

## Acceptance
- [ ] 8 新回调在对应 SPD hook 点派发
- [ ] 4 桥接:`*Bonus` 增量正确叠加到 M7b 派发路径
- [ ] 9/16 Remished buff 用新回调工作(Champion×4 / DieHard / Anesthesia / Cloak / BodyArmor / Counter)
- [ ] M7b 已接回调不破(向后兼容)
- [ ] `./gradlew :core:test` 全绿(458 现有 + 新增)
- [ ] C3 不破

## 注意
- **绝不 `git add -A`**
- **codex 评审用 codex exec workaround**
- 新回调 hook 集中 `LuaBuff` + `Char` 单点(CLAUDE.md:fork 代码集中,但 Char.java hook 是必要的上游单点)
- **`*Bonus` 桥接语义:增量叠加,非替换**(避免破坏 M7b 修正值链)
- 参考 Remished `scripts/lib/buff.lua` + `scripts/buffs/ChampionOf*.lua` 回调签名

---

## Refined Design (worker, M10c 探索后细化)

### 现状勘误(探索确认)
- **test_mod 脚本路径**:`core/src/main/assets/mods/test_mod/scripts/buffs/`(非 `assets/...`)。
- **9 个目标脚本已存在但为 degraded/bridged 形态**(M6c/M7a/M7b/M8a/M8c 已移植),M10c 把它们升级到**canonical Remished 回调名**。M7b/M8 回调必须**向后兼容保留**(PLAN Acceptance: M7b 已接回调不破)。
- **现有测试 `RpdApiBuffTest` 已 pin 部分 degraded 行为**(`championOfWaterTintIsBlueAura` 测 tintChar、`championOfWaterDefenseSkillBonusApplies` 测 dispatchDefenseSkill +floor(lvl*1.25)、`anesthesiaAssetIsUpgraded` 测 sleepLock)。升级脚本时必须保持这些测试绿 → 见下方"测试稳定性"设计。
- **NYRDS 权威消费语义**(已核对 `remixed-dungeon/RemishedDungeon/src/main/java`):
  - `hasteLevel`:`Char.timeScale()` 里 `forEachBuff` **求和**后 `clamp(1.1^(-sum), 0.25, 4.0)`;Speed=+7.27(快),Slow=-7.27(慢)。正=haste。
  - `setGlowing(color, period)`:命令式 `target.setGlowing(color, period)` → `sprite.setGlowing(new Glowing(color, period))`。SPD 等价物 = `CharSprite.aura(color, rays)`(M8c fx 已用,ChampionEnemy.fx 同)。
  - `stealthBonus`:`Char` 里 `forEachBuff(b -> bonus += b.stealthBonus(this))` 求和加到 stealth。
  - `charSpriteStatus`:返回 `CharSprite.State` 枚举名(INVISIBLE 等)。

### 8 新回调 + 4 桥接 的精确签名与 hook 点

**A. `regenerationBonus`(新,加成 regen)** — NYRDS canon(`Regeneration.act` 权威实现已核对)
- 签名(Lua):`regenerationBonus(selfId, state)` → int(求和)。
- Java:`LuaBuff.dispatchRegenBonus(Char) → int`(求和,跨所有 attached LuaBuff)。
- hook:`Regeneration.act()`,NYRDS 公式 `healRate = Math.pow(1.2, bonus)` + `spend(10/healRate)`。SPD 适配(SPD 是 fractional 累积):`partialRegen += Math.pow(1.2f, dispatchRegenBonus((Hero)target)) / delay;`(**指数**,非线性 — codex review Issue 1)。hero-only。
- fail-open:无 fn/出错/非数 → 0 → `pow(1.2,0)=1` = 正常 regen 速率。

**B. `hasteLevel`(新,移速乘算)**
- 签名(Lua):`hasteLevel(selfId, state)` → number(求和)。
- Java:`LuaBuff.dispatchHasteMultiplier(Char) → float` = `clamp((float)Math.pow(1.1, sum), 0.25, 4.0)`(**正=haster**,对 SPD 移动 speed 取正指数,因 speed() 返回越高越快;NYRDS timeScale 用负指数因它乘的是 *动作耗时*)。
- hook:`Char.speed()`,M7a `speed` LuaBuff 循环**之后**:`speed *= dispatchHasteMultiplier(this)`。与 `speed`/`speedMultiplier` 叠乘。
- fail-open:无 fn → 0 求和项。

**C. `stealthBonus`(新,stealth 加成)**
- 签名(Lua):`stealthBonus(selfId, state)` → number(求和)。
- Java:`LuaBuff.dispatchStealthBonus(Char) → float`(求和)。
- hook:`Char.stealth()`,`return stealth` 前 `stealth += dispatchStealthBonus(this)`。

**D. `charSpriteStatus`(新,视觉 State 标签)**
- 签名(Lua):`charSpriteStatus(selfId, state)` → string(`CharSprite.State` 名,如 `"INVISIBLE"`)。
- Java:`LuaBuff.computeSpriteState() → CharSprite.State`(valueOf,异常/无 fn → null)。
- hook:**复用 M8c `fx(boolean on)`**(与 tint 对称)。fx(true):若 computeSpriteState() 非 null → `target.sprite.add(state)`,记 `appliedState`;fx(false)/re-apply:先 `sprite.remove(appliedState)`。**纯视觉,不碰 `Char.invisible` 计数器**(gameplay 隐身由 stealthBonus 负责)。last-wins/best-effort,与 tint 同 caveat。

**E. `setGlowing`(新,glow;复用 M8c aura)**
- 签名(Lua):`setGlowing(selfId, state)` → number(color)|`{color=,rays=}`(声明式 field/function)。
- Java:**在 `computeTint()` 里优先读 `setGlowing`**(number→`TintSpec.aura(color, DEFAULT_RAYS=6)`;`{color,rays}`→aura),再 fallback `tintChar`。**precedence: setGlowing > tintChar**。
- fx 应用路径不变(已是 aura)。这样 `championOfWaterTintIsBlueAura` 测试**仍绿**(computeTint 从 setGlowing 取 aura)。
- 声明式(非命令式),符合 sandbox "Lua 只拿 id" 原则;命令式 `RPD.setGlowing` 会被 fx re-fire 清掉,故不用。

**F. `damage`(新,受击,NYRDS canon return-consuming)** — codex review Issue 2 已采纳
- 签名(Lua):`damage(selfId, srcId, dmg)` → int(可修正 dmg,attach 顺序合成);`srcId`= src instanceof Char ? src.id() : nil。
- Java:`LuaBuff.dispatchDamage(Char self, int dmg, Object src) → int`(返回合成后 dmg,每 buff `damage(selfId,srcId,dmg)` 串行;无 fn/出错/非 int → 透传)。
- hook:`Char.damage(int dmg, Object src)`,**pre-shield/pre-HP**:在 `int shielded = dmg;`(`dmg = ShieldBuff.processDamage(...)` 之前)插入 `dmg = LuaBuff.dispatchDamage(this, dmg, src);`。时序 = 抵抗/Doom/DeathMark 之后,shield/HP 之前(NYRDS `charGotDamage` 同位)。
- 与 defenseProc 区别:defenseProc 在攻击方 `Char.attack` 解算时(pre-armor);damage 在受击方 `Char.damage`(post-resist, pre-shield)。两者均可修正,时序不同。
- DieHard:`damage` 内 `math.random()<X then RPD.detachBuff`,返回 dmg 透传(不修正,与 Remished 一致)。Anesthesia 保留 sleepLock(非唤醒);damage 在 NYRDS 是 no-op,本 feature **不给 anesthesia 加 damage**(dead code)。

**G. `drBonus`(桥接,增量→drRoll)** — 见桥接节。
**H. `speedMultiplier`(桥接,乘→speed)** — 见桥接节。

### 4 桥接(*Bonus 折算层,在 M7b 方法内部,不新增派发点)

设计核心(PLAN 关键设计):**在每个 M7b 方法体内,先算 M7b transform,再叠加 *Bonus 增量,返回合并值**。这样 `Char.drRoll()/speed()` 循环和 `dispatchAttackSkill/dispatchDefenseSkill` 自动拾取,无需改派发点。

| 桥接 | 所在 M7b 方法 | 折算 |
|---|---|---|
| `attackSkillBonus(selfId,state)→int` | `attackSkill(selfId,atk)` | `m7b=callOptInt("attackSkill",atk,…); bonus=callOptInt("attackSkillBonus",0,selfId); return m7b+bonus` |
| `defenceSkillBonus(selfId,state)→int` | `defenseSkill(selfId,def)` | `m7b=callOptInt("defenseSkill",def,…); bonus=callOptInt("defenceSkillBonus",0,selfId); return m7b+bonus`(注:Remished 拼法 `defenceSkillBonus`,保留) |
| `drBonus(selfId,state)→int` | `drRoll(selfId,dr)` | `m7b=callOptInt("drRoll",dr,…); bonus=callOptInt("drBonus",0,selfId); return m7b+bonus` |
| `speedMultiplier(selfId,state)→float` | `speed(selfId,spd)` | `m7b=callOptFloat("speed",spd,…); mult=callOptFloat("speedMultiplier",1f,selfId); return m7b*mult` |

- **增量语义**:`attackSkillBonus/defenceSkillBonus/drBonus` 返回**加成增量**(+N),叠加;`speedMultiplier` 返回**乘数**(0.9),**乘法合成**(PLAN 写"增量叠加"对 multiplier 不适用 — BodyArmor 返回 0.9 是 factor 不是 delta,故乘法;已在 codex 评审项里标注此偏离)。
- 无对应 Lua fn → `callOptInt/Float` fail-open 默认(0 / 1.0)→ M7b 值透传,**向后兼容**。

### hook 集中清单(Char 单点 + Regeneration + LuaBuff)
- `Char.speed()`:末尾 `speed *= dispatchHasteMultiplier(this)`(现有 speed LuaBuff 循环后)。
- `Char.stealth()`:`stealth += dispatchStealthBonus(this)`(return 前)。
- `Char.damage(dmg, src)`:pre-shield/pre-HP `dmg = dispatchDamage(this, dmg, src)`(在 `int shielded = dmg;` 前,canon return-consuming)。
- `Regeneration.act()`:`partialRegen += Math.pow(1.2f, dispatchRegenBonus(hero)) / delay`(NYRDS canon 指数)。
- `LuaBuff.fx(on)`:加 charSpriteStatus 的 add/remove(与 tint 并列)。
- `LuaBuff.computeTint()`:优先 setGlowing。
- `LuaBuff` 的 `attackSkill/defenseSkill/drRoll/speed`:内嵌 *Bonus 折算。

### 9 脚本升级映射(degraded → canonical)
| 脚本 | 现 degraded | M10c canonical |
|---|---|---|
| champion_of_air | `speed×1.5` | `hasteLevel`(返 buff lvl)+ `setGlowing`(0xAAAABB) |
| champion_of_earth | `drRoll+5`+heal | `regenerationBonus` + `drBonus` + `setGlowing`(0x55AA55);保留 one-shot heal |
| champion_of_fire | `attackProc`(Burning) | `attackSkillBonus` + `setGlowing`(0xAA2222);保留 attackProc(Burning) |
| champion_of_water | `defenseSkill+floor(lvl*1.25)`+tintChar | `defenceSkillBonus`(floor(lvl*1.25))+ `setGlowing`(0x3399FF,rays5)** |
| die_hard | `defenseProc`(50% detach)+act(spend20) | `damage`(随机 detach)+ `regenerationBonus`(lvl);保留 act(spend20);**删 defenseProc** |
| anesthesia | `sleepLock` | **保留 sleepLock**(非唤醒由它负责);`damage` 通告是 no-op 死码 → **不加**(避免 dead code) |
| cloak | Invisibility 白名单 attach/detach | `stealthBonus` + `charSpriteStatus("INVISIBLE")`;保留 act(timed detach) |
| body_armor | `drRoll+3`+`speed×0.9` | `drBonus`(+3)+ `speedMultiplier`(0.9);保留 act(timed detach) |
| counter | act+charAct(已 M7b 完整) | **不动**(无 *Bonus/新回调可加) |

**champion_of_water 双测保持绿**:defenceSkillBonus 桥接 → `dispatchDefenseSkill` 仍 +floor(lvl*1.25);setGlowing 经 computeTint → `championOfWaterTintIsBlueAura` 仍返 blue aura。

### 测试计划(`core/src/test/.../modding/LuaBuffCallbackTest.java` 新建)
- 每个新回调:注册 probe buff → 断言 dispatch/helper 返回正确值(regenBonus/haste/stealth/damage 通告写 state/spriteStatus/setGlowing→computeTint)。
- 每个桥接:buff 同时定义 M7b + *Bonus → 断言两者叠加;只定义 *Bonus → M7b 透传 + bonus。
- nil passthrough(无 fn → 不改值)。
- Char 级集成:`h.stealth()`/`h.speed()` 带 haste buff 变化;`Regeneration` regen bonus(需 hero+Regeneration buff)。
- 9 脚本经 `LuaEngine.init()` 注册后,断言新回调 fn 存在 + 不 `degraded`。
- **不破坏** `RpdApiBuffTest` 现有断言(尤其 champion_of_water 两测、anesthesia sleepLock)。

## Pending Issues
- codex review R1(2 项 must-fix)已采纳:
  - regenBonus 改 NYRDS canon `pow(1.2, bonus)/delay`(原 `(1+bonus)/delay` 线性错)。
  - `damage` 改 canon return-consuming,pre-shield/pre-HP 插入(原 advisory 不符 canonical 回调兼容目标)。两目标脚本(DieHard/Anesthesia)返回 dmg 不变 → 行为零变化。
- **damage vs defenseProc 重叠**:两者都能修正 incoming damage,时序不同(defenseProc=attacker.attack pre-armor;damage=defender.damage post-resist pre-shield)。Remished 两者并存,保留。
