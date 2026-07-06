# SPD × Remixed Lua Modding 平台 — 里程碑计划

> 上层路线图。基于 SPD fork 移植 Remixed Dungeon 的 Lua modding 基础设施,
> 在保留 SPD 深度内容的前提下叠加 Remixed 式广度。
>
> 本文档是所有后续 feature_worker 的上层指引;每个里程碑(M)拆出的具体 feature 各自写
> `docs/PLAN-modding-<m>-<slug>.md`,引用本文档作为依据。

**状态图例**:`[ ]` 待启动 / `[~]` 进行中 / `[x]` 已完成 / `[!]` 阻塞

---

## 1. 背景与目标

### 1.1 两项目定位

- **Shattered Pixel Dungeon(SPD,本仓)**:libgdx + 单 `core` 模块 + Java 硬编码。深度 roguelike,巨型天赋树(`Talent.java` 44K)、深度程序化生成、平衡调优、permadeath 美学。
- **Remixed Dungeon(NYRDS,姐妹仓 `../remixed-dungeon/`)**:双包(`com.watabou.*` 原版 + `com.nyrds.*` 新增)+ LuaJ 嵌入 VM + `@LuaInterface` 注解沙箱 + TeaVM 多平台。可扩展内容平台:10 职业、~220 物品、~120 mob、法术/mana、宠物/召唤、城镇枢纽。

### 1.2 杂糅目标

**既深又广**:保留 SPD 的深度内容(天赋/子职业/abilities/深度生成/平衡)不动,在其上铺一层 Remixed 式 Lua modding 基础设施,用数据驱动方式叠加广度内容(新职业/法术/宠物/城镇)。

### 1.3 选 SPD 为底座的理由

1. **广度=平台能力,可移植**;**深度=工程沉淀,不可移植**。Remixed 的 `luaj`/`annotation`/`processor` 是独立 Gradle 模块,可以搬到 SPD;SPD 的 10 年平衡调参搬不走。
2. SPD 单模块干净,加 modding 层是**加法**;Remixed 双包老底座做深度是**减法 + 重写**。
3. 两边 `Item`/`Hero`/`Char` 同名异实现,直接合并冲突地狱。

### 1.4 基本策略

- 保留 SPD 全部深度内容不动
- 外置铺一层 Lua modding 基础设施
- 广度内容(法术/宠物/城镇/新职业)作为**默认关闭的可选 mod 包**,保 SPD 原版体验

---

## 2. 贯穿性约束(每阶段都要守)

| ID | 约束 | 说明 |
|---|---|---|
| **C1** | 版本对齐 | modding 层尽量外置(独立 Gradle 模块 + assets 加载),对 `core` 的侵入收敛到几个单点 hook——沿用本 fork save slot 的"4 个上游单点 hook"范式(见 `CLAUDE.md`) |
| **C2** | 包结构隔离 | 所有 fork 加的 modding 代码进 `core/.../modding/` 子包,不散回上游根包/windows 子包 |
| **C3** | 回归基线 | 每阶段结束保 SPD 原版一周目可玩通 + Rankings/Badges/GamesInProgress 缓存不被污染 |
| **C4** | 上游 merge 友好 | 不搬动上游 `Hero.die()` / `WndGame` 等大方法体(参考 save slot 的递归 re-entry 设计) |
| **C5** | proguard 安全 | release 走 `minifyEnabled true`,新增反射入口(Lua 桥接、注解处理)必须配 `android/proguard-rules.pro` keep 规则(见 `CLAUDE.md` proguard 段) |

---

## 3. 关键决策点(必须在 M3 启动前定稿)

| ID | 决策 | 选项 | 建议(初版) | 影响范围 |
|---|---|---|---|---|
| **D1** | 法术系统 | (a) 引入 mana 系(Remixed 风格)<br>(b) 改造为消耗性 spell(SPD 风格) | **(b)** 先消耗性 spell;mana 作为后期可选 mod 包 | M3 全局平衡,M4 NPC 经济 |
| **D2** | 天赋树开放程度 | (a) Lua 职业自定义天赋树<br>(b) 复用现有 4 职业天赋<br>(c) 给简化天赋 | **(b)/(c)**,不开放天赋树编辑 | M3 工作量量级(可能翻倍) |
| **D3** | 宠物骨架 | 基于 SPD Ally / 友好 mob 做 `PetInventoryManager` 等价物 | 沿用 | M3,M4 |
| **D4** | 原版内容是否重构成 mod | (a) SPD 原版 Java 内容重构成"内置 mod"<br>(b) 保留硬编码 | **(b)** 保留硬编码,只让新增内容走 Lua | M1/M2 注册管线设计 |
| **D5** | Remished 玩法移植范围(M6b PoC 后定) | (a) B 全量(mob+buff+item+spell 全移植,~2-3 人月)<br>(b) B-mini 后止(只验证 5-6 mob,不铺量)<br>(c) 转 C 路径(纯数据皮) | **取决于 M6b PoC 实测边际成本**(平台 API 暴露工时 vs 脚本改写工时) | M6 走向,决定是否开 M6c/d/e |

> **D2 是工作量杠杆最大的决策**。SPD `Talent.java`(44K)是硬编码 enum + 互锁逻辑,数据驱动化是独立大工程,建议第一版不碰。若选 (a),M3 拆成 M3a/M3b。

---

## 4. 里程碑

### M0 — 最小 Lua 闭环(可行性 PoC)

- **状态**: `[x]` 已完成(commit a304d2e71)
- **目标**: 证明 luaj 能嵌进 SPD,能 new 出一个 Lua 定义的物品
- **范围**:
  - 引入 `luaj` 作为独立 Gradle 模块(与 `SPD-classes` 同级)
  - 最小 `LuaEngine`:从 assets 读 `.lua`,用 luaj 执行
  - 新建 `core/.../modding/` 子包(C2)
  - **不做沙箱、不做注解处理器**——只为打通链路
- **验收**: 游戏里能刷出一把 Lua 定义的"测试剑",有名字/贴图/攻击力;原版一周目不受影响(C3)
- **依赖**: 无
- **风险**: luaj 与 libgdx 1.14.0 / Java 11 / R8 minify 的兼容性(C5)
- **难度**: 中
- **预估 feature 数**: 2-3

### M1 — 沙箱 + 内容注册管线

- **状态**: `[x]` 已完成(commit 84f0477f7)
- **目标**: 把"Lua 定义 → 注册进 SPD 工厂"做扎实,加边界
- **范围**:
  - 移植 `annotation` + `processor` 模块
  - 设计 `@LuaInterface` 标注 SPD 暴露的类(`Item`/`Hero`/`Mob`/`Char`/`Buff`/`Level`)
  - 编译期生成可达闭包白名单(`LuaInterfaceProcessor`)
  - `LuaSandbox` 运行期校验
  - `Generator` / mob 工厂改造成"Java 注册 + Lua 注册"双源
- **验收**: mod 文件夹(`version.json` + `scripts/`)能加新物品和新怪;Lua 访问未暴露 Java 内部被拦截并报错
- **依赖**: M0
- **风险**: SPD 类层次比原版 PD 重,白名单闭包计算膨胀;proguard keep 规则要跟上(C5)
- **难度**: 中高
- **预估 feature 数**: 3-4

### M2 — 核心 API 暴露

- **状态**: `[x]` 已完成(commit a25105fa2;**仅 Item API,关卡 API Painter/Room/Trap 移至 M4**)
- **目标**: Lua 能写**完整行为**的内容(不止数值 stub)
- **范围**:
  - `scripts/lib/*.lua` 等价物:暴露 `actionsProc` / `Buff.onAttach` / `Char` 属性 / `Hero` 背包接口
  - `entities/*.txt` 风格名册(Lua 内容注册表)
  - 关卡侧:暴露 `Painter`/`Room`/`Trap` 给 Lua
- **验收**: Lua 实现一把有攻击 proc + 装备效果 + 完整描述的武器;一个有简单 AI 的怪;一个自定义陷阱房
- **依赖**: M1
- **风险**: API 定型后再改成本高——**这是项目工作量主峰**,设计要好
- **难度**: 高
- **预估 feature 数**: 4-6

### M3 — 深度系统边界 + 机制骨架(关键分叉) ⭐ 决策门

- **状态**: `[x]` 已完成(D1-D4 定稿:D1 消耗性 spell / D2 复用 4 职业天赋 / D3 ally 宠物 / D4 保留硬编码。4 feature 合 master:m3a-mob 634da866d / m3b-pet 83f946057 / m3c-hero 015eb97ea / m3d-spell 5bb3d7ce9)
- **目标**: 新职业能玩通一周目;法术/宠物骨架定型
- **范围**:
  - 按 D1 实现法术(mana 或消耗性 spell)
  - 按 D2 处理新职业天赋
  - 按 D3 实现 `PetInventoryManager` 等价物
  - Lua 定义的完整新职业(子职业可暂略)
- **验收**: 一个 Lua 定义的完整新职业能从 1 层玩到通关;Rankings 正常提交(C3)
- **依赖**: M2 + D1-D4 决策
- **风险**: 最大风险集中点。若 D2 选 (a),拆成 M3a/M3b
- **难度**: 极高
- **预估 feature 数**: 5-8

### M4 — 关卡 / 城镇 / 广度内容

- **状态**: `[x]` 已完成(M4a Level `ac5fa76f6` / M4b NPC `68ff41f25` / M4c shop `873753f92` / M4d 主线可达+R4状态保留 `5db7b23ce`,4 feature 合 master `5db7b23ce`,185 tests 全绿)。**M4e SafeZone 稳定化(热修复,M5 后做)** `23e6c6f56`:进入 SafeZone 闪退链修复(`Actor.clear` before switchLevel + `test_safezone.json` 16×16→32×32 padding),218 tests 全绿。M4d:isDebug 守卫的主线 NPC 注入(`RegularLevel.createMobs` +1 hook)+ Option C 同步切 leaveLevel(保留 live hero/gold/quickslot/nextID,死亡分支仍走 CONTINUE)。M5 在此基础上做玩家开关 + mod 治理
- **目标**: 加广度容量(基础设施已铺好,本阶段主要是消费 API)
- **范围**:
  - `levelsDesc/*.json` 图结构(线性 + 枢纽 + 副本)
  - Tiled `.tmx` 加载(引入 Tiled map 解析库)
  - 城镇枢纽房 + NPC + 多店经济
- **验收**: 游戏里有可达城镇房,与 NPC 交互能买卖/储物
- **依赖**: M3
- **风险**: Tiled 库依赖体积;关卡图改动与上游 merge 的冲突面(C1/C4)
- **难度**: 中
- **预估 feature 数**: 4-5

### M5 — mod 治理 + 平衡收尾

- **状态**: `[x]` 已完成(M5a 清单+版本门 `51db427ba` / M5b UI+entry `05e700e04` / M5c 目录化+默认关闭+C3回归 `13b0d56a8`,3 feature 合 master `13b0d56a8`,216 tests 全绿)。test_mod `default_enabled=false` → 零 Lua 内容(C3 回归守住);`WndModManager` 开关(isDebug 守卫)+ LuaEngine entry 加载 + 脚本目录化(`mods/<id>/scripts/<type>/`)按 enabled mod 扫描
- **目标**: 体系对玩家可控、对平衡可守
- **范围**:
  - mod 目录扫描 + 版本兼容检查(`version.json` 的 `rpd_version` 等价物)
  - mod 切换 UI
  - 所有 Remixed 式内容作为默认关闭的可选包
  - 平衡回归:开/关扩展包各跑一轮徽章/挑战基线
- **验收**: 玩家在设置里可开关扩展内容包;原版体验不变
- **依赖**: M4
- **风险**: 平衡调参周期长
- **难度**: 中
- **预估 feature 数**: 3-4

### M6 — Remished 玩法移植(Lua API 面扩容 + 脚本改写)

- **状态**: `[ ]` 待启动(2026-07-06 评估立项;B 路径可行性已论证,缺口量化 + 样本解剖见 §9 评估附录)
- **目标**: 把 Remished 的 Lua 定义玩法(mob/buff/item/spell)移植到我们的 Lua wrapper 平台,**验证"嵌 Remished 玩法"真实可行 + 量化边际成本**(D5 决策依据)
- **决策门 D5**(M6b PoC 后定):B 路径是否全量铺开
- **范围**:
  - **M6a 桥子集**(平台扩容):LuaMob 补 `act`/`spawn` 回调槽 + LuaSandbox 建 Blob/Buff 常量表层(`RPD.Blobs.*`/`RPD.Buffs.*` 等价)+ 暴露核心 API(`placeBlob`/`addImmunity`/`getPos`/`glog` 等 10-15 个)。**平台级设计工作,非逐脚本改**
  - **M6b mob 改写 PoC**:`RemishedFetidRat`/`ShamanElder`/`SpiderElite`/`Hydra`/`ScriptedThief` 5-6 个 → 走 LuaMob 回调改写,**实测单 mob 改写工时 + 倒逼的平台 API 数** → 触发 D5
  - **M6c buff 移植**(D5 通过后):补 buff 生命周期 API(`onAttach`/`detach`/`act`)+ 移植 16 个 Remished buff 脚本
  - **M6d item/spell 扩容**(D5 通过后):补 Item/Spell/Hero 背包 API 面 + 移植 Remished Lua item/spell
  - **M6e 平衡 + C3 回归**:所有移植内容 `default_enabled=false`,开/关各跑一轮徽章/挑战基线
  - **可选并行 M6-fast**(C 路径):数据驱动 item/mob 皮套现有 wrapper,快速铺量,不阻塞主线
- **验收**: 5-6 个 Remished mob 在 SPD 内生成 + 行为正确(放毒气/加 buff/特殊 AI);默认关闭,原版一周目不受影响(C3);M6b 产出实测成本数据驱动 D5
- **依赖**: M5
- **风险**:
  - 桥设计是平台级工作(常量表层),非逐脚本改 —— M6a 主工作量
  - **D5 决策门**:M6b 可能暴露桥根本性不够(per-tick `act` 性能 / 常量表膨胀 / 序列化兼容)→ 回评估降级到 C 路径
  - assets 版权(Remished 贴图/音效 license 需单独确认,非 MIT/类 MIT 部分要替换或重绘)
  - C5 proguard:每个新暴露 Java API 都要 keep 规则
- **难度**: 中高(平台扩容 + 批量改写)
- **预估 feature 数**: 4-5(主线 M6a/b/c/d/e)+ 1-2(可选 M6-fast)

#### 超出 M6 范围(需更大决策,单独立 M7+)

- **A 路径**(整套搬 Remished modding 层 + 713 标注铺到 SPD 原版类)—— 违背 C1/C4(侵入面爆炸),**不做**
- **核心机制层**(10 职业 / 天赋树差异 / mana 法术)—— 被 D1/D2 锁死,需先开:
  - **D6**(条件性):开放天赋树 Lua 编辑 —— `Talent.java` 44K 数据驱动化,工作量杠杆极大
  - **D7**(条件性):引入 mana 系 —— 反转 D1 决策
  - 任一启动 = M7 级独立大工程,**不在当前路线图内**

---

## 5. 里程碑依赖图

```
M0 → M1 → M2 → [D1-D4 决策门] → M3 → M4 → M5 → [D5 决策门] → M6
                                ⭐                       ⭐(M6b PoC 后)
```

- M3 是关键门,**D1-D4 必须先答**。
- M3 若 D2 选 (a),拆 M3a(机制骨架)/ M3b(天赋数据驱动)。
- M6 是 Remished 玩法移植门,**D5 在 M6b PoC 后答**(实测边际成本驱动)。

---

## 6. 状态总表

| 里程碑 | 状态 | 依赖 | 难度 | 预估 feature 数 | 备注 |
|---|---|---|---|---|---|
| M0 最小 Lua 闭环 | `[x]` | — | 中 | 1(m0-lua-poc) | luaj 嵌入 + 测试剑 |
| M1 沙箱 + 注册管线 | `[x]` | M0 | 中高 | 1(m1-sandbox) | @LuaInterface + processor + Generator 双源 |
| M2 核心 API 暴露 | `[x]` | M1 | 高 | 1(m2-item-api) | 仅 Item API;关卡 API(Painter/Room)移到 M4 |
| M3 深度系统 + 机制骨架 | `[x]` | M2 + D1-D4 | 极高 | 4(m3a-mob/m3b-pet/m3c-hero/m3d-spell) | D1 消耗性/D2 复用天赋/D3 ally/D4 保留硬编码 |
| M4 关卡 / 城镇 / 广度 | `[x]` | M3 | 中 | 5(M4a Level/M4b NPC/M4c shop/M4d 主线可达/M4e SafeZone 稳定化) | 核心:数据驱动 Level + Lua NPC + 商店 + 主线注入(isDebug 守卫)+ R4 leaveLevel 状态保留 + SafeZone 进入崩溃修复 |
| M5 mod 治理 + 平衡 | `[x]` | M4 | 中 | 3(M5a 清单+版本门/M5b UI+entry/M5c 目录化+默认关闭) | 清单+版本门 / 开关UI+entry加载 / 脚本目录化+default_enabled=false → C3 守住 |
| M6 Remished 玩法移植 | `[ ]` | M5 + D5 | 中高 | 4-5(M6a 桥/M6b mob PoC/M6c buff/M6d item+spell/M6e 平衡) | B 路径:扩 Lua API 面 + 改写 Remished 脚本;D5 门控全量;核心机制层(职业/天赋/mana)出 M6 范围 |
| **合计(M0-M5 完成 + M4e,M6 待启动)** | | | | **15 + M6 进行中** | |

---

## 7. feature 拆分原则

- 每个 M 拆成 N 个独立 feature,各自写 `docs/PLAN-modding-<m>-<slug>.md`
- slug 命名建议:`modding-m0-luaj-embed` / `modding-m1-sandbox` / `modding-m2-item-api` / `modding-m3-spell-system` ...
- 每个 feature 走标准 Feature Dispatcher 流程(worktree + PLAN + feature_worker + codex_reviewer)
- 每个 feature 必须保 C3 回归基线
- 合并后 PLAN 文档跟随代码进 main,作为审计记录

---

## 8. 变更记录

| 日期 | 变更 | 作者 |
|---|---|---|
| 2026-07-05 | 初版路线图落盘 | dispatcher |
| 2026-07-05 | M0-M3 完成(7 feature 合 master);M2 关卡 API(Painter/Room/Trap)移至 M4;M3 按 D1-D4 拆 m3a-mob/m3b-pet/m3c-hero/m3d-spell;M4 启动(m4a-data-driven-level 进行中) | dispatcher |
| 2026-07-05 | M4 核心三件套完成(M4a Level `ac5fa76f6`/M4b NPC `68ff41f25`/M4c shop `873753f92`,3 feature 合 master `873753f92`,174 tests 全绿);**M4d 主线可达推迟到 M5**(玩家开关+接入一起做);M4 累计 10 feature(M0-M3 7 + M4 3) | dispatcher |
| 2026-07-06 | M4 全部完成(M4d 主线可达+R4状态保留 `5db7b23ce` 合 master,185 tests 全绿);M0-M4 累计 11 feature;启动 M5 mod 治理 + 平衡收尾 | dispatcher |
| 2026-07-06 | M5 全部完成(M5a `51db427ba`/M5b `05e700e04`/M5c `13b0d56a8` 合 master,216 tests 全绿);**M0-M5 全部完成,累计 14 feature**;SPD×Remixed Lua modding 平台闭环(清单+版本门+开关UI+entry+目录化+默认关闭+C3 回归) | dispatcher |
| 2026-07-06 | **M4e SafeZone 稳定化**(热修复,`23e6c6f56` 合 master,218 tests 全绿):进入 SafeZone 闪退链修复 — Bug 0 `Actor.clear` before switchLevel(主线 mob 泄漏到小地图 overflow)+ X1 `test_safezone.json` 16×16→32×32 padding(SPD observe/tilemap 假定 ≥32×32 + 边缘 padding);M0-M5 累计 **15 feature**;fork master 已 push 到 JessieKaa origin | dispatcher |
| 2026-07-06 | **M6 路线图立项**:Remished 玩法移植评估完成 —— 平台 API 面缺口量化(Remished 713 `@LuaInterface` 标注 / ~40 类 vs 我们 6 个 Lua wrapper,~10x);样本解剖(`RemishedFetidRat` 46 行)显示 B 路径真实成本在平台 API 暴露(80-120 API × 0.5 天 ≈ 2-3 人月 = M2-bis 级),Lua 脚本改写本身轻量(1-2 小时/mob);立项 M6(B 路径)+ D5 决策门(M6b PoC 后定);A 路径/核心机制层(职业/天赋/mana)出范围;评估依据见 §9 | dispatcher |

---

## 9. 附录:M6 评估依据(2026-07-06)

> Remished 玩法移植可行性评估的数据沉淀。M6a PLAN 在此基础上展开桥子集设计。

### 9.1 平台 API 面缺口量化

| 维度 | Remished | 我们(SPD fork) | 差距 |
|---|---|---|---|
| `@LuaInterface` 标注 | 713 处 / ~40 类 | 6 个 Lua wrapper | ~10x |
| Lua 脚本 | 162(scripts/)+ 20 lib/*.lua | test_mod 几个示例 | 全缺 |
| 暴露 Java 包 | items/ levels/objects/ mechanics/{buffs,spells} mobs/npc/ + 平台层 | 仅 `modding/Lua*.java` | 全缺 |
| Lua 实现 mob / buff | 18 mob(scripts/mobs/)/ 16 buff(scripts/buffs/) | 0(LuaMob 框架空载) | 全缺 |

> 注:Remished `~120 mob / ~220 item` 大头是 **Java 硬编码**(`entities/mobs.txt` 名册),Lua 只覆盖 18 mob + 16 buff + 部分 item/spell。真正"可移植的 Remished Lua 玩法"是这几十个脚本,不是整个 220/120。

### 9.2 架构模式差异(根因)

- **Remished** = `@LuaInterface` 标在**原版 + 扩展 Java 类**,Lua 通过 `scripts/lib/commonClasses.lua`(576 行 RPD 桥)**主动调 Java API**。工厂注册(ItemFactory/SpellFactory/BuffFactory)让 Lua 实体进 SPD 工厂链
- **我们** = `@LuaInterface` 只标在 **6 个 Lua wrapper**,Lua table 仅作**行为回调注入**(proc/damageRoll/activate...)。Lua **不能主动调 SPD Java API**,只能在 wrapper 预设回调槽返回数值

### 9.3 样本解剖:RemishedFetidRat(46 行)

| Remished 用到的 API | 我们 LuaMob | 改写处理 |
|---|---|---|
| `attackProc`/`defenseProc`/`die` | ✅ 有 | 直接映射 |
| `storeData/restoreData`(serpent) | ✅ storeInBundle/restoreInBundle | Bundle 代 serpent |
| `stats` 回调(选 kind + 加免疫) | ❌ 无初始化槽 | 扩 LuaMob 加 `spawn` |
| `act` 回调(每 tick `placeBlob`) | ❌ **关键缺口** | LuaMob 没暴露 `act()`,要扩 |
| `self:addImmunity(Buff)` / `getPos()` | ❌ 未暴露 | LuaMob 加方法 |
| `RPD.placeBlob(blob,pos,amt)` | ❌ 完全无 | 桥层新增 |
| `RPD.Blobs.*` / `RPD.Buffs.*` 常量 | ❌ 完全无 | 建常量表(平台级) |
| `RPD.glog` / `RPD.Sfx.Speck.*` | ❌ 无 | 日志可代,Speck 可丢 |

**单 mob 倒逼 6-8 个平台 API**。

### 9.4 三路径成本

| 路径 | 做法 | 工作量 | 决策 |
|---|---|---|---|
| **A** | 整套搬 Remished modding 层 + 713 标注铺 SPD 原版类 | M7 级+(≈ 重做 M1+M2 且更难) | **不做**(违 C1/C4) |
| **B** | 改写 Remished 脚本到我们 wrapper 回调风格 | 80-120 API × 0.5 天 ≈ **2-3 人月**(M2-bis 级) | **M6 采纳,B-mini 先行** |
| **C** | 只搬数据(数值/贴图),行为套现有 wrapper | 几 feature | **M6-fast 可选并行** |

### 9.5 B-mini 量化(单 mob 边际成本)

- Lua 侧改写:**1-2 小时/mob**
- 倒逼平台 API:6-8 个 × 0.5 天(边际递减:前几个建常量表框架,后续 mob 复用)
- 18 mob + 16 buff 全量 B ≈ **40-60 工作日**
