# PLAN: M8c — sprite tint/glow(cosmetic hook)

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M8(M8c 条目)+ §10.5 留项
> 前置:M7 全合 master(`5d1f0fd37`,331 tests);M7b champion_of_water glow 留 M8(cosmetic)
> 并行:M8a(sleep-lock)/M8b(shields)同时跑。**约定 LuaBuff/Char 分块**
> D5' = (a) 禁 luajava(只传 id/int)

## Goal

补完 M7b sprite tint 降级:LuaBuff `tintChar` 回调 + Char sprite tint dispatch,让 champion_of_water 等 buff 给 bearer 上色/发光(cosmetic)。补完 cosmetic 降级。

## Context

M7b 评估:sprite tint/glow 是 cosmetic ~0.5d。SPD 的 `Char.sprite` 有 `tint`/`removeTint` API(参考 `Charm`/`Frost` 等既有 buff 如何 tint bearer)。M8c 给 LuaBuff 加 tintChar 回调,buff 可声明颜色 + 发光。

### 关键 file:line(worker 已核对,2026-07-07)

- `core/.../actors/Char.java:1267` `updateSpriteState()` 遍历 buffs 调 `buff.fx(true)`(sprite 链接/刷新时)
- `core/.../actors/buffs/Buff.java:77` `attachTo` 成功且 `target.sprite!=null` → `fx(true)`;`:85` `detach` 同理调 `fx(false)`(**sprite null 自动跳过**)
- `core/.../actors/buffs/Buff.java:114` `fx(boolean on)` 默认 no-op —— 子类 override 即可
- `core/.../actors/buffs/ChampionEnemy.java:64` **直接先例**:`fx(on){ if(on) target.sprite.aura(color,rays); else target.sprite.clearAura(); }`(SPD champion buff 就是这么上色 bearer)
- `core/.../actors/buffs/Frost.java:129` `fx` 另一例(`sprite.add(State.FROZEN)`)
- `core/.../sprites/CharSprite.java:380` `aura(int color,int nRays)` / `:482` `clearAura()`(Flare 光环);`Visual.java:214` `tint(r,g,b,strength)` / `:229` `tint(int)` / `:255` `resetColor()`(乘性 tint)
- `core/.../modding/LuaBuff.java` M7a/b 回调槽模式 + `attachTo`/`detach` 已有 `restoring` 短路
- `core/src/main/assets/mods/test_mod/scripts/buffs/champion_of_water.lua`(M7b 留 glow degraded)
- `core/src/test/java/.../modding/RpdApiBuffTest.java` M7a/b 单测模式:**直接调实例方法**(如 `lb.attackProc(h.id(),999,10)`),headless sprite 为 null

## 设计决定(worker 细化,经代码核对)

**用 SPD 原生 `fx(boolean on)` hook,不用 PLAN 初稿的 "attach/detach dispatch"。** 原因:
1. **sprite null 自动安全**:`Buff.attachTo`/`detach` 已经只在 `target.sprite!=null` 时调 `fx`(Buff.java:77,85)—— headless 测试和 sprite 未链接时天然跳过,无需手写 null 检查。
2. **save/restore 自动重应用**:`Char.updateSpriteState()`(Char.java:1267)在 sprite 链接时对全部 buffs 重放 `fx(true)`,读档后 tint 自动恢复,无需碰 `restoring` 短路。
3. **不改 Char.java**:`fx` 由 `Buff.attachTo`/`detach` 逐实例调用,不需要静态 dispatcher(区别于 M7a 的 attackSkill 等,那些因 Hero/Mob 覆写不调 super 才需要静态 dispatch)。tint 是纯视觉,每 buff 独立,无跨 buff 合成需求。
4. **ChampionEnemy 先例**:SPD 自己的 champion buff 就是 `fx` + `aura(color,rays)`,与 champion_of_water 同类,照搬该模式最贴上游。

**Lua 回调 `tintChar(selfId, state)` 返回值支持三种(覆盖 "tint" 与 "glow" 两义):**
- `nil`/无函数/无返回 → 不上色
- **number**(int 颜色,如 `0x3399FF`)→ `sprite.aura(color, DEFAULT_RAYS=6)`(glow,ChampionEnemy 风格)
- **table `{color=0x3399FF, rays=5}`** → `sprite.aura(color, rays)`(glow,自定 rays)
- **table `{r=.., g=.., b=..[, a=0.5]}`** → `sprite.tint(r,g,b,a)`(乘性 tint,float 0-1)

**优先级**:同一 table 同时含 `color` 和 `r`/`g`/`b` 时,**`color` 优先**(aura 优先于 tint,单测覆盖)。

**fx(true) 自清理 + 配对(codex issue 1+2):**
- `fx(true)` 开头先按当前 `appliedTint` 清旧效果(aura→`clearAura()`;tint→`resetColor()`),再据新 spec 应用并更新 `appliedTint`;新 spec 为 nil 也要清旧(置 `appliedTint=NONE`)。解决 spec 类型切换(aura↔tint↔nil)残留 + fx(true) 重入累积。
- **`attachTo` 时序**:LuaBuff.attachTo 现有结构是 `super.attachTo(target)`(此处 Buff 基类触发 `fx(true)`)→ 再调 Lua `attachTo` 回调。若 `tintChar` 依赖 Lua `attachTo` 初始化的 `state`,首次 `fx(true)` 会读到未初始化 state。修法:Lua `attachTo` 成功且 `target.sprite != null` 时,再重放一次 `fx(true)`(此时 state 已就绪,且 fx(true) 自清理保证不重复)。`restoring` 短路路径不重放(restore 由 `updateSpriteState` 统一重放)。

**fx(false) 清理语义(codex issue 3,诚实声明):**
`clearAura()` / `resetColor()` 是 **sprite 全局单槽清理**,不是 per-buff —— `appliedTint` 只能记"本 buff 上次应用了哪种",不能证明槽位仍归本 buff。因此本 feature 是 **best-effort last-wins cosmetic**,与 SPD 单槽限制一致(`ChampionEnemy`/`Invulnerability` 同样共享 AURA 槽;`Invulnerability.fx` 甚至 `if(!buffs(ChampionEnemy.class).isEmpty()) return` 来避让)。**不做**"fx(false) 后重放剩余 buff"的复杂恢复(cosmetic 不值得,SPD 自己也没做)。`appliedTint` 的唯一作用:让 fx(false) 清对类型 + fx(true) 自清理,不是"避免误清别的 buff"。

**可测性**:抽 `TintSpec computeTint()`(package-private)纯调 Lua 解析返回,无 sprite 依赖 —— headless 直接断言返回值(沿用 M7a `lb.attackProc(...)` 直接调实例方法的模式)。`fx` 只负责 spec→sprite API 接线,不需在单测里验证(桌面验证实际渲染)。

## Files

- `core/src/main/java/.../modding/LuaBuff.java`:**`// M8c tint` 分块**
  - `static final class TintSpec`(package-private):`boolean aura; int color; int rays; float r,g,b,a;`
  - `TintSpec computeTint()`:`LuaValue fn=tbl.get("tintChar")`;非函数→null;`fn.call(LuaValue.valueOf(id()), state)` 取返回值(2 参用 `call` 不用 `invoke`,对齐现有 `attachTo` 回调 LuaBuff.java:151);number→aura(toint, DEFAULT_RAYS);table 有 `color` 键→aura(color, rays or DEFAULT);table 有 `r`/`g`/`b`→tint(r,g,b, a or 0.5f);其余→null。catch Exception →null(记 error)
  - `transient int appliedTint`(0/1/2,默认 0,不进 Bundle)
  - `@Override public void fx(boolean on)`:`target==null||target.sprite==null`→return;on=true 先按 `appliedTint` 清旧(aura→clearAura,tint→resetColor)再据 computeTint() 应用(nil 则只清不应用),更新 `appliedTint`;on=false 据 `appliedTint` 调 `clearAura()`/`resetColor()` 并复位
  - `attachTo` 末尾(Lua `attachTo` 成功分支后,`restoring` 路径除外):若 `target.sprite!=null` 重放 `fx(true)`(state 已就绪,自清理保证不重复)
- `core/src/main/assets/mods/test_mod/scripts/buffs/champion_of_water.lua`:加 `tintChar = function(selfId, state) return {color=0x3399FF, rays=5} end`(蓝色光环 glow),info/注释移除 "glow M8" degraded 字样
- `core/src/test/java/.../modding/RpdApiBuffTest.java`:加 5 个 tint 单测(computeTint 返回 aura/int/rgb/nil + champion_of_water 蓝色 aura);attachTo(freshHero sprite=null)顺带验证 fx(null sprite)不崩

## Steps

1. **核对 SPD tint 模式**(已done):ChampionEnemy.fx + Buff.fx 接线 + CharSprite.aura/clearAura + Visual.tint/resetColor
2. **LuaBuff tintChar 回调 + TintSpec**:`computeTint()` 解析 Lua 返回(number/table)→ TintSpec;color 优先于 r/g/b;失败/nil→null
3. **fx(boolean on) override**:fx(true) 先自清旧再应用(nil 也清);fx(false) 据 appliedTint 清理。null sprite 早退。attachTo 末尾 sprite 非空时重放 fx(true)
4. **champion_of_water 升级**:`tintChar` 返蓝色 aura `{color=0x3399FF, rays=5}`,清 degraded 注释
5. **测试**:5 个 computeTint 单测(headless,直接断言 TintSpec 字段);既有 331 tests 不回归
6. **C3 回归**

## Acceptance

- [ ] LuaBuff `tintChar` 回调 + `TintSpec computeTint()`(number→aura;{color,rays}→aura;{r,g,b[,a]}→tint;nil→null)
- [ ] `fx(boolean on)` override:fx(true) 自清旧再应用 / fx(false) 据 `appliedTint` 清理;null sprite 早退;attachTo 末尾 sprite 非空重放 fx(true)
- [ ] champion_of_water 升级蓝色 aura glow(cosmetic),degraded 清除
- [ ] 不开 luajava(只传 id/number);不改 Char.java 方法体(fx 由 Buff 基类接线)
- [ ] 单槽 best-effort last-wins 语义明确(同 ChampionEnemy/Invulnerability,不做重放恢复);color 优先于 r 单测覆盖
- [ ] `:core:test` 通过,既有 331 tests 不回归 + 5 个新 tint 单测;C3 守住

## Risks

- ~~tint 颜色表示(int vs float[r,g,b,a])~~ → 已定:number/{color,rays}→aura;{r,g,b[,a]}→tint;color 优先于 r
- **单槽 last-wins(已接受)**:`clearAura`/`resetColor` 是全局清槽,多 buff 叠加时 last-wins,与 SPD ChampionEnemy/Invulnerability 同限;不做重放恢复(cosmetic)
- fx(true) 重入/spec 切换:fx(true) 开头自清旧 + nil 也清,保证不残留(codex issue 2)
- attachTo 时序:super.attachTo 先于 Lua attachTo 触发 fx(true) → attachTo 末尾 sprite 非空时重放 fx(true) 读到就绪 state(codex issue 1)
- 守 fork 约束:LuaBuff 新代码进 modding/ 子包(已在该包内)
- M8a/M8b/M8c LuaBuff 分块:约定 `// M8c tint`,合并取并集
