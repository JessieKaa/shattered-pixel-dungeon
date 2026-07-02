# PLAN: Save Editor — 字段中文注释

**Slug**: `field-labels-zh`
**Branch**: `feature/field-labels-zh` (based on `feature/local-save-slots`)
**Date**: 2026-07-02

---

## Goal

给表单编辑器和数值字段表的**字段名**增加中文显示 + 鼠标悬停描述,让用户不必猜测 `glyph_hardened` / `curse_infusion_bonus` / `partialCharge` 这些英文键的含义。

只做前端展示,不改后端 API / 不改数据保存格式。

## Context

- 现状(`feature/local-save-slots` HEAD `57c1d5c8`):
  - `FieldRow.vue` 直接显示 `keyName`(如 `cursed`、`quantity`、`levelKnown`)
  - `NumericFields.vue` 直接显示 `meta.name`、`hero.HP` 等英文标签
  - 字段名是 Java 类的实际字段名,含义对玩家不透明
- 约束:
  - 不能改 bundle 数据格式(`__className`/字段名等保持原样)
  - 不需要覆盖 100% 字段(上游新增字段默认英文,不崩)
  - 只做中文(后续要 i18n 时再扩展结构)

## Architecture

```
tools/save-editor/frontend/src/
├── composables/
│   └── useFieldLabels.ts        ★新增字段名 → 中文/描述 字典
├── components/
│   ├── FieldRow.vue             [改] label + tooltip
│   ├── NumericFields.vue        [改] el-form-item label + tooltip
│   └── NestedObject.vue         [改] 可选:__className 类名 tooltip
```

### 匹配策略

字段名可能带路径(如 `hero.armor.level` 或 `weapon.wand`)。匹配优先级:

1. **完整路径精确匹配**(`weapon.wand` / `inventory` 等少数)
2. **字段名(最后一段)匹配**(`level` / `quantity` / `cursed` 等通用字段)
3. **未命中**:显示原字段名,无 tooltip

函数签名:

```typescript
export interface FieldLabel {
  zh: string;
  desc: string;
}

export function getFieldLabel(path: string): FieldLabel | null;
// path 示例: "cursed", "hero.HP", "weapon.wand", "inventory"
```

### 字典内容(核心 40+ 字段)

```typescript
export const FIELD_LABELS: Record<string, FieldLabel> = {
  // 通用
  __className: { zh: '类名', desc: '决定游戏内物品类型,不可修改' },
  level:       { zh: '等级', desc: '装备/武器/戒指等级,+1 相当于一次升级' },
  quantity:    { zh: '数量', desc: '堆叠数量(食物/药水/卷轴等)' },
  cursed:      { zh: '诅咒', desc: 'true 表示被诅咒,无法主动卸下/丢弃' },
  cursedKnown: { zh: '已知诅咒', desc: '玩家是否已识别诅咒状态' },
  levelKnown:  { zh: '已知等级', desc: '玩家是否已识别等级' },
  kept_lost:   { zh: '死亡保留', desc: 'true 表示死亡后该物品会保留' },
  id:          { zh: 'ID', desc: '物品实例唯一标识' },
  pos:         { zh: '位置', desc: '物品在地图/背包中的位置索引' },

  // 装备
  augment:            { zh: '强化方向', desc: '护甲/武器强化方向(NONE/DAMAGE/DEFENSE)' },
  glyph_hardened:     { zh: '雕文固化', desc: '护甲雕文是否已固化' },
  enchant_hardened:   { zh: '附魔固化', desc: '武器附魔是否已固化' },
  curse_infusion_bonus:{ zh: '诅咒灌注加成', desc: '来自诅咒灌注的加成数值' },
  mastery_potion_bonus:{ zh: '精通药水加成', desc: '来自精通药水的加成数值' },
  available_uses:     { zh: '可用次数', desc: '鉴定/使用类物品剩余可用次数' },
  uses_left_to_id:    { zh: '剩余鉴定次数', desc: '还需使用多少次才能自动鉴定' },

  // 法杖/魔导器
  curCharges:    { zh: '当前充能', desc: '法杖当前充能数' },
  curChargeKnown:{ zh: '已知充能', desc: '玩家是否已知当前充能数' },
  partialCharge: { zh: '部分充能', desc: '法杖部分充能进度(0.0-1.0)' },
  resin_bonus:   { zh: '树脂加成', desc: '来自魔法树脂的加成' },
  zapped:        { zh: '已释放', desc: '本轮是否已经施放过' },

  // 容器/背包
  inventory:     { zh: '容器内物品', desc: '背包/袋子内的物品列表' },
  volume:        { zh: '水量', desc: '水袋当前水量' },
  quickslotpos:  { zh: '快捷栏位置', desc: '绑定的快捷栏格子索引' },

  // Hero
  HP:   { zh: '生命值', desc: '当前生命值' },
  HT:   { zh: '生命上限', desc: '最大生命值' },
  STR:  { zh: '力量', desc: '力量值,影响装备需求和近战伤害' },
  exp:  { zh: '经验值', desc: '当前等级经验' },
  lvl:  { zh: '英雄等级', desc: '英雄总等级' },
  class:{ zh: '职业', desc: '英雄职业枚举' },
  buffs:{ zh: 'Buffs', desc: '英雄身上的增益/减益效果列表' },

  // Meta / Game
  name:       { zh: '存档名', desc: '槽位显示名称' },
  depth:      { zh: '深度', desc: '当前地下城层数' },
  level:      { zh: '等级', desc: '存档记录的英雄等级' },
  hero_class: { zh: '职业', desc: '英雄职业' },
  version:    { zh: '版本', desc: '存档创建时的游戏版本号,必须匹配 896' },
  gold:       { zh: '金币', desc: '持有金币数' },
  seed:       { zh: '种子', desc: '随机种子,改后可能让世界状态不一致' },
  challenges: { zh: '挑战', desc: '开启的挑战模式位掩码' },
  duration:   { zh: '时长', desc: '游戏时长(回合)' },

  // 嵌套路径特殊
  'weapon.wand': { zh: '内嵌法杖', desc: '法师杖内嵌的法杖' },
};
```

字典只覆盖常见字段,**未命中字段保持原 key,不影响功能**。

## Files

### 新增 `tools/save-editor/frontend/src/composables/useFieldLabels.ts`

- `FieldLabel` 接口
- `FIELD_LABELS` 字典(40+ 条目)
- `getFieldLabel(path: string): FieldLabel | null` 函数

### 改 `tools/save-editor/frontend/src/components/FieldRow.vue`

- 引入 `getFieldLabel`
- label 改为:
  - 有中文:显示 `中文名(原始 key)`
  - 无中文:显示原 `keyName`
  - 鼠标悬停 `el-tooltip` 显示描述
- 保持现有控件和布局不变

### 改 `tools/save-editor/frontend/src/components/NumericFields.vue`

- 引入 `getFieldLabel`
- 每个 `el-form-item` 的 `label` 属性仍传原 key(用于表单校验/调试)
- 在 `label` 插槽里显示中文名,tooltip 显示描述
- 代码变动小,保持现有结构

### 可选:改 `tools/save-editor/frontend/src/components/NestedObject.vue`

- `__className` 值显示类简称,鼠标悬停显示完整类名
- 可选加"中文类名"(如 `ClothArmor` → `布甲`),但类名太多,建议只做通用几个或只做完整类名 tooltip
- **本 PR 不强制要求,可在 Step 5 作为 nice-to-have**

## Steps

### Step 1: 字段字典

- 创建 `frontend/src/composables/useFieldLabels.ts`
- 实现 `getFieldLabel`,支持路径最后一段匹配
- 写 40 个核心字段条目(从 Context 里列出的真实存档字段出发)

### Step 2: FieldRow 中文标签

- 改 `FieldRow.vue`:
  ```vue
  <el-tooltip v-if="labelInfo" placement="top">
    <template #content>
      <div>{{ labelInfo.zh }}</div>
      <div v-if="labelInfo.desc" style="font-size: 12px; opacity: 0.8">{{ labelInfo.desc }}</div>
    </template>
    <span class="field-label">{{ labelInfo.zh }}<span class="raw-key">({{ keyName }})</span></span>
  </el-tooltip>
  <span v-else class="field-label">{{ keyName }}</span>
  ```
- 加 CSS `.raw-key { color: var(--el-text-color-secondary); font-size: 0.85em; margin-left: 4px; }`
- 保持 `min-width: 220px`

### Step 3: NumericFields 中文标签

- 改 `NumericFields.vue`:
  - 每个 `el-form-item` 的 `label` 仍用原 key
  - 用 `#label` 插槽覆盖显示:
    ```vue
    <template #label>
      <el-tooltip v-if="getFieldLabel('meta.name')" placement="top">
        <template #content>{{ getFieldLabel('meta.name')?.desc }}</template>
        <span>存档名<span class="raw-key">(meta.name)</span></span>
      </el-tooltip>
    </template>
    ```
- 为简化,写一个小函数 `labeled(key, fallback)` 返回插槽内容
- 覆盖 12 个字段

### Step 4: 构建 + 手测

- `cd frontend && npm run build` 通过
- 启动 prod Flask,打开 SPA,上传 `/tmp/123.zip`
- 检查:
  - `level` 显示"等级(level)"
  - `quantity` 显示"数量(quantity)"
  - `cursed` 显示"诅咒(cursed)"
  - `__className` 显示"类名(__className)"
  - 鼠标悬停有 tooltip 描述
  - 无字典字段(如 `kept_lost`)正常显示原 key,不崩

### Step 5(可选): 类名 tooltip

- 在 `NestedObject.vue` 的 item title 处,给 `__className` 值加 tooltip 显示完整类名
- 不做中文类名映射(类名太多)
- 如果工期紧,跳过

### Step 6: 验证

- pytest 40/40 全绿(后端不变)
- Java 闭环通过(数据格式不变)
- npm run build 通过
- 主仓合并后不再引入新文件到 spd_bundle / tests / SaveSlotIO

## Acceptance

| # | 验收点 | 验证方法 |
|---|---|---|
| 1 | 40 个核心字段字典落到 `useFieldLabels.ts` | 文件检查 |
| 2 | `FieldRow.vue` 显示中文名+原 key,未命中字段显示原 key | 手测 |
| 3 | `FieldRow.vue` 鼠标悬停显示描述 tooltip | 手测 |
| 4 | `NumericFields.vue` 12 个字段显示中文名 | 手测 |
| 5 | `__className` 显示"类名(__className)" + 只读提示 | 手测 |
| 6 | 无字典字段(如不常见子类字段)正常显示,不崩 | 手测 |
| 7 | 表单 Schema + Raw JSON 切换后标签仍正确 | 手测 |
| 8 | `npm run build` 通过,dist 大小增长 < 50KB(字典很小) | `npm run build` |
| 9 | pytest 40/40 不回归 | `pytest -q` |
| 10 | Java 闭环通过(SPD 数据格式不变) | `SPD_ZIP_PATH=/tmp/... ./gradlew :core:test --tests '*SaveSlotIOPythonZipTest'` |

## 风险与备选

- **字段名歧义**:同一个 key 在不同上下文含义不同(如 `level` 在装备是等级、在法杖也是等级、在英雄也是等级)。字典按字段名匹配,恰好这些含义一致,无需路径级歧义处理
- **类名太多**:只做通用字段,不做类名中文映射,避免维护爆炸
- **上游新增字段**:字典未覆盖时自动回退英文,不崩,后续再补
- **tooltip 覆盖密集**:问题不大,Element Plus tooltip 交互标准
- **label 宽度变宽**:中文名+原 key 可能超过 220px,测试后如果截断,适当调宽或把原 key 缩小字号

## Out of scope

- 多语言 i18n(只中文)
- 字段值校验规则(如 level 范围)
- 类名中文映射(ClothArmor → 布甲 等)
- 后端 API 改动
- 自动从 Java 源码提取字段注释(人工字典 MVP)
- 表单 schema 字段重命名/类型转换
