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

> **D2 是工作量杠杆最大的决策**。SPD `Talent.java`(44K)是硬编码 enum + 互锁逻辑,数据驱动化是独立大工程,建议第一版不碰。若选 (a),M3 拆成 M3a/M3b。

---

## 4. 里程碑

### M0 — 最小 Lua 闭环(可行性 PoC)

- **状态**: `[ ]` 待启动
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

- **状态**: `[ ]` 待启动
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

- **状态**: `[ ]` 待启动
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

- **状态**: `[ ]` 待启动(**前置:D1-D4 决策定稿**)
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

- **状态**: `[ ]` 待启动
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

- **状态**: `[ ]` 待启动
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

---

## 5. 里程碑依赖图

```
M0 → M1 → M2 → [D1-D4 决策门] → M3 → M4 → M5
                                ⭐
```

- M3 是关键门,**D1-D4 必须先答**。
- M3 若 D2 选 (a),拆 M3a(机制骨架)/ M3b(天赋数据驱动)。

---

## 6. 状态总表

| 里程碑 | 状态 | 依赖 | 难度 | 预估 feature 数 | 备注 |
|---|---|---|---|---|---|
| M0 最小 Lua 闭环 | `[ ]` | — | 中 | 2-3 | 可行性 PoC |
| M1 沙箱 + 注册管线 | `[ ]` | M0 | 中高 | 3-4 | |
| M2 核心 API 暴露 | `[ ]` | M1 | 高 | 4-6 | 工作量主峰 |
| M3 深度系统 + 机制骨架 | `[ ]` | M2 + D1-D4 | 极高 | 5-8 | 决策门,可能拆 a/b |
| M4 关卡 / 城镇 / 广度 | `[ ]` | M3 | 中 | 4-5 | |
| M5 mod 治理 + 平衡 | `[ ]` | M4 | 中 | 3-4 | |
| **合计** | | | | **21-30** | |

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
