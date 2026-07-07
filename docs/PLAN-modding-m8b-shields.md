# PLAN: M8b — shields lib(独立抽象层)

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M8(M8b 条目)+ §10.5 留项
> 前置:M7 全合 master(`5d1f0fd37`,331 tests);M7b shields lib 永久降级或留 M8(~1-2d)
> 并行:M8a(sleep-lock)/M8c(sprite tint)同时跑。**约定 LuaBuff/Char 分块**
> D5' = (a) 禁 luajava(只传 id/int)

## Goal

补完 M7b shields lib 降级:独立 `ShieldTracker` 抽象层,统一 `mana_shield`/`shield_left`/`chaos_shield_left` 三个 shield buff 的护盾点数管理(注册/消耗/恢复/显示),替代各 buff 各自硬编码。

## Context

M7b 评估:shields lib 是独立抽象层 ~1-2d。M7a/b 的 mana_shield(defenseProc 一次免伤自detach)、shield_left/chaos_shield_left(defenseProc 50%/30% block)各自硬编码护盾逻辑,无统一 tracker。M8b 抽象 `ShieldTracker` 让 shield buff 共享护盾池/消耗规则。

### 关键 file:line(worker 核对)

- `core/.../modding/LuaBuff.java`(M7a/b 的 defenseProc 回调,shield buff 走这)
- `core/src/main/assets/mods/test_mod/scripts/buffs/mana_shield.lua` / `shield_left.lua` / `chaos_shield_left.lua`(M7a/b 升级,各自硬编码)
- `core/.../actors/Char.java`(shield 可能挂 Char 字段或 buff)
- 参考 SPD 既有 `Barkskin`/`Barrier`(如果有的话)的护盾模式

## Files

- `core/src/main/java/.../modding/ShieldTracker.java`(**新增**):护盾抽象层,`WeakHashMap<Char,Integer>`(Char-identity 键,非 charId);`addShield(Char,amt)`/`absorb(Char,dmg)`→返吸收后剩余伤害/`getShield(Char)`/`clear(Char)`/`clearAll()`;fork-local 静态,ephemeral(不持久化 mid-combat 值)
- `core/src/main/java/.../modding/LuaBuff.java`:**`// M8b shield` 分块** shield buff metadata(`shieldAmount`/`shieldType`);defenseProc 调 `ShieldTracker.absorb`
- `core/src/main/java/.../modding/RpdApi.java`:**`// M8b shield API` 分块** `addShield(charId,amt)`/`charShield(charId)`/`absorbShield(charId,dmg)`
- `core/src/main/assets/mods/test_mod/scripts/buffs/mana_shield.lua` / `shield_left.lua` / `chaos_shield_left.lua`:改走 `RPD.addShield`/`ShieldTracker`,统一护盾逻辑
- `core/src/test/java/.../modding/RpdApiBuffTest.java` 或新 `ShieldTrackerTest`:扩 shield 单测

## Steps

1. **ShieldTracker 抽象**(`modding/ShieldTracker.java`,**新增**):fork-local 静态 `WeakHashMap<Char,Integer>`(**按 Char 对象 identity 而非 charId**)——避免 `Actor.nextID` 在新游戏重置(Actor.java:153,226)导致的 int-key 跨运行/槽位串点数泄漏;Char 未覆写 equals/hashCode(默认 identity),WeakHashMap 语义正确;Char 死亡/GC 后 entry 自动清。`addShield(Char,amt)`(累加,封顶 `MAX_AMOUNT=1000`、负数忽略、返新总额)/`absorb(Char,dmg)`(返剩余伤害:`shield>=dmg→shield-=dmg,return 0`;否则 `dmg-=shield,shield=0,return dmg`)/`getShield(Char)`/`clear(Char)`/`clearAll()`(test hook)。**ephemeral**:不持久化,save/load 重建 Char 对象→旧 entry 死,新 Char 经 attachTo seed 重建 declarative baseline(见 step 3)。与 SPD `ShieldBuff`/`Barrier` 完全隔离(不碰 `Char.shielding()`/`ShieldBuff.processDamage`)。
2. **RpdApi shield API**(`// M8b shield API` 分块,build() 末尾注册):`addShield(charId,amt)` TwoArgFunction(resolveChar→`ShieldTracker.addShield`)/`charShield(charId)` OneArgFunction(返 int 或 NIL)/`absorbShield(charId,dmg)` TwoArgFunction(resolveChar + 校验 dmg∈[1,MAX_AMOUNT] + 返剩余伤害 int 或 NIL)。resolveChar 已有,复用。只传 id/int 过 sandbox(D5'-(a)),内部 Char-key 不暴露给 Lua。
3. **LuaBuff `// M8b shield` 分块**:declarative metadata 读取(复用 `readIntField`/`readStringField` 模式,支持 value 或 function(state))——`shieldAmount()`(默认 0)/`shieldType()`(默认 "");**在 attachTo 两个分支都 seed**(fresh 非 restoring 分支 Lua attachTo 成功后;restoring 分支 super.attachTo 成功后)——`if (shieldAmount()>0) ShieldTracker.addShield(target, shieldAmount())`。restore 也 seed 的理由:fork 是 save-scum 向(频繁读档),shield buff 读档后清零会明显退化;declarative baseline 重建比「读档即死」更可接受;mid-combat 消耗值不持久化(documented + tested)。
4. **3 shield buff 改写**(defenseProc 用 `selfId` 调 RPD,**非 `self`**):
   - `mana_shield`:declarative `shieldAmount=<N>`(Java seed);defenseProc `local left=RPD.absorbShield(selfId, dmg); if RPD.charShield(selfId)<=0 then RPD.detachBuff(selfId, "mana_shield") end; return left`。语义从「免一次击」→「吸收 N 点护盾后自 detach」(M8b 统一为点数池,header 注明)。
   - `shield_left`:`shieldAmount=<N>` seed;defenseProc `return RPD.absorbShield(selfId, dmg)`;act 里 `RPD.addShield(targetId, N)` recharge。
   - `chaos_shield_left`:同 shield_left(chaos 随机效果表仍 deferred,header 注明)。
5. **测试**:新增 `ShieldTrackerTest`(add 累加/封顶、absorb 部分/完全、get、clear、clearAll、**多 buff 共享池**、**detach 后池仍存/reattach 重 seed**、**act recharge 封顶**)+ `RpdApiBuffTest` 扩 shield 用例(RPD.addShield/charShield/absorbShield、declarative shieldAmount Java seed、mana_shield defenseProc 吸收+池空自 detach、3 buff 注册数仍 16 不回归、**Bundle restore 重建 declarative baseline**)。`@Before` 调 `ShieldTracker.clearAll()`。
6. **C3 回归**:`luajavaStillUnreachable` 仍过;331 既有 tests 不回归。

## Decisions (worker 探索后细化,已吸收 codex round-1 must-fix)

- **Char-keyed WeakHashMap 而非 int-keyed 静态池**:Actor.nextID 在新游戏重置→int-key 会把上一局 charId=1 的护盾串到新角色;Char-identity 键彻底避免,且无需碰上游 `Actor.clear()` / `Dungeon` reset hook(fork-local 自洽)。
- **ShieldTracker 与 SPD `ShieldBuff` 正交**:SPD 走 `Char.shielding()`/`ShieldBuff.processDamage`(`Char.damage` 内);fork ShieldTracker 是 modding 包静态池,lua defenseProc 在 `Char.defenseProc`(Char.java:783-787)dispatch 阶段经 RPD 吸收、返剩余伤害,上游 `Char.damage` 应用到 HP。两套不互相干扰。
- **shieldAmount declarative + Java seed**(fresh + restore 两路):跟 `immunities()` declarative 模式一致(lua 声明、Java 处理);restore 也 seed 保证读档后 buff 不「死」。lua 脚本只管 drain(defenseProc)+ recharge(act)。
- **shieldType 暂为 metadata-only**(不参与逻辑):PLAN 列了,作为未来 damage-type 过滤钩子保留 getter + 单测覆盖。
- **ephemeral 不持久化 mid-combat 消耗值**:记入 `## Pending Issues`(持久化护盾点数需 hook Char Bundle,后续 feature)。

## Pending Issues

- 护盾点数不持久化 mid-combat 消耗值:save 时未 hook Char Bundle 存 `ShieldTracker` 池快照,读档只重建 declarative baseline(`shieldAmount`)。完整持久化需在 Char.save/restore 加 fork hook,留后续 feature。

## Acceptance

- [ ] ShieldTracker 抽象层(add/absorb/get/clear)
- [ ] RpdApi shield API
- [ ] 3 shield buff 统一走 ShieldTracker(mana_shield/shield_left/chaos_shield_left)
- [ ] 不开 luajava(只传 id/int)
- [ ] `:core:test` 通过,既有 331 tests 不回归;C3 守住

## Risks

- ~~ShieldTracker 与 SPD Barrier/Barkskin 冲突~~ → **已排除**:fork 静态池与 SPD `ShieldBuff` 系统正交(见 Decisions)。
- ~~shield buff defenseProc 改写影响 M7a dispatch~~ → **已排除**:`Char.defenseProc` LuaBuff dispatch(Char.java:783-787)只消费返回值,改写 lua defenseProc 返回值不影响 dispatch 机制。
- 守 fork 约束:ShieldTracker 进 `modding/` 子包 ✓。
- M8a/M8b/M8c LuaBuff 分块:约定 `// M8b shield`,合并取并集 ✓(本 feature 只在 attachTo 末尾 + iconTextDisplay 头部 + 新增 metadata getter 方法加分块,与 M8a sleep-lock / M8c tint 不重叠)。

## Semantic change (intentional)

M7a 三个 shield buff 的 **概率/一次性** 行为统一为 **点数池**:
- `mana_shield`:免一次击 → 吸收 N 点护盾后自 detach。
- `shield_left`:50% 概率格挡 → N 点护盾吸收 + act recharge。
- `chaos_shield_left`:30% 概率格挡 → 同 shield_left(chaos 效果表仍 deferred)。

这是 M8b「统一护盾管理」的目标(PLAN §Goal),非回归。lua header 已注明 M8b 语义。
