# PLAN: M8a — sleep-lock hook(anesthesia 升级)

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M8(M8a 条目)+ §10.5 留项
> 前置:M7 全合 master(`5d1f0fd37`,331 tests);M7b anesthesia sleep-lock 留 M8
> 并行:M8b(shields)/M8c(sprite tint)同时跑。**约定 LuaBuff/Char 分块注释**
> D5' = (a) 禁 luajava(只传 id/bool)

## Goal

补完 M7b anesthesia sleep-lock 降级:`Char.damage` pre-hook + LuaBuff `sleepLock` 回调,让 anesthesia buff 抑制睡眠唤醒(受击不醒)。buff 行为降级清零(16/16 全高保真)。

## Context

M7b 评估:anesthesia sleep-lock 需 hook `Sleep`/`Drowsy` 唤醒路径,Remished 用自定义 Sleeping AI。SPD 的唤醒在 `Char.damage`/`Sleep.act` 内,无单点 buff hook。M8a 在 `Char.damage` 入口加 pre-hook dispatch 给 LuaBuff,anesthesia 返回 true 抑制唤醒。

### 关键 file:line(worker 已核对,2026-07-07)

- `core/.../actors/Char.java:918-920` —— **唤醒单点**:`if (this.buff(MagicalSleep.class) != null){ Buff.detach(this, MagicalSleep.class); }`。这是 `Char.damage` 内唯一"受击唤醒"路径(`Sleep` FlavourBuff 不在 damage 内 detach;`Drowsy` 仅在 act 时转 MagicalSleep)。hook 在此分支 guard,不在 damage 入口 return(HP 照扣,仅抑制 MagicalSleep detach)
- `core/.../actors/buffs/MagicalSleep.java` —— attachTo 对 ally 满血时 detach(toohealthy),测试需 HP<HT 才能挂上;detach 时 `paralysed--`
- `core/.../actors/buffs/Sleep.java` / `Drowsy.java` —— 已确认不在 damage 路径 detach(纯 FlavourBuff 计时)
- `core/.../modding/LuaBuff.java` —— M7a `attackProc`/`defenseProc`(callOptInt)与 M7b `dispatchAttackSkill`/`dispatchCharAct` 模式;`LuaItemCallbacks` 只有 callOpt/callOptInt/callOptFloat(无 bool),sleepLock 内联 bool 逻辑
- `core/src/main/assets/mods/test_mod/scripts/buffs/anesthesia.lua`(M7b 留 degraded=true)

## Files

- `core/src/main/java/.../actors/Char.java`:**`// M8a sleep-lock` 分块** —— line 918 改为 `if (this.buff(MagicalSleep.class) != null && !LuaBuff.dispatchSleepLock(this)){ Buff.detach(this, MagicalSleep.class); }`;**单点 hook,C1/C4**;HP 扣除(986 行)不动
- `core/src/main/java/.../modding/LuaBuff.java`:**`// M8a sleep-lock` 分块** 加 instance `boolean sleepLock(int selfId)`(内联 try/catch,默认 false:缺函数/出错/非 bool → 不锁,避免卡死)+ `static boolean dispatchSleepLock(Char)`(遍历 `buffs()` 任一返 true 则 true,null-safe);import 已齐(LuaValue 已 import)
- `core/src/main/assets/mods/test_mod/scripts/buffs/anesthesia.lua`:升级 `sleepLock=function(selfId) return true end`,移除 `degraded`/`degradation`,改 info 文案
- `core/src/test/java/.../modding/RpdApiBuffTest.java`:扩 sleepLock 单测(instance true/false/nil/dispatch/compose/null-safe)+ MagicalSleep 集成(headless damage 抑制唤醒)

## Steps

1. **核对 Char.damage 唤醒路径**(已做):唤醒单点 = line 918 MagicalSleep detach;Sleep/Drowsy 不在 damage 内 detach
2. **LuaBuff sleepLock 回调**:`sleepLock(selfId)` 内联:取 tbl → `tbl.get("sleepLock")` 非函数返 false → `fn.call(valueOf(selfId))` → `res.isboolean() && res.toboolean()`(非 bool/nil → false);catch → false + Gdx.app.error。`dispatchSleepLock(Char)`:null→false;遍历 `buffs()` 任一 LuaBuff.sleepLock 返 true 则 true
3. **Char.damage hook**:line 918 加 `&& !LuaBuff.dispatchSleepLock(this)` 条件,**HP 扣除链不动**
4. **anesthesia 升级**:`sleepLock=function(selfId) return true end`,移除 degraded 字段,info 改 "Anesthesia (M8a: suppresses waking on damage)"
5. **测试**:(a) sleepLock instance — true/nil-passthrough-false/false/non-bool-false;(b) dispatchSleepLock — 附加返 true/无附加 false/null-safe/多 buff compose;(c) MagicalSleep 集成 — Hero HP=40/HT=50 挂 MagicalSleep+anesthesia,damage(5,enemyChar) 后 MagicalSleep 仍在且 HP=35;无 anesthesia 则 MagicalSleep detach;既有 331 tests 不回归
6. **C3 回归**:test_mod disabled 无影响(enabledModLoadsAll16BuffScripts 仍 16,anesthesia 仍在内)

## Acceptance

- [ ] LuaBuff sleepLock 回调 + dispatchSleepLock
- [ ] Char.damage pre-hook(单点,不破上游 damage 链)
- [ ] anesthesia 升级 sleep-lock 行为(受击不醒),buff 16/16 全高保真
- [ ] 不开 luajava(只传 id/bool)
- [ ] `:core:test` 通过,既有 331 tests 不回归;C3 守住

## Risks

- Char.damage pre-hook 影响所有伤害路径,需 guard(只 LuaBuff 返 true 时抑制唤醒,不碰 HP 扣除)
- Sleep/Drowsy 唤醒语义复杂,worker 核对唤醒到底在 damage 哪段
- 守 fork 约束:Char.damage 单点 hook;LuaBuff 新代码进 modding/ 子包
- M8a/M8b/M8c 都可能碰 LuaBuff/Char:约定 `// M8a sleep-lock` 分块,合并取并集
