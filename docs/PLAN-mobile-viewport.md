# PLAN: Save Editor — 移动端响应式适配

**Slug**: `mobile-viewport`
**Branch**: `feature/mobile-viewport` (based on `feature/local-save-slots`)
**Date**: 2026-07-02

---

## Goal

让 Vue SPA 在手机/平板(≤768px)上表单值可见、可编辑、布局合理。当前桌面布局在窄屏上 label 强制 220px 把 value 控件挤到 0 宽度,**用户看不到也无法编辑数值**。

只改前端 CSS + 少量 prop,**后端零改动**。

## Context

- 现状(`feature/local-save-slots` HEAD `4f32a6f8`):
  - `viewport` meta 已在 `index.html`(`width=device-width, initial-scale=1.0`)
  - **无任何 `@media` 查询**(全项目仅 3 处固定尺寸约束)
  - 桌面布局:`.app-container { max-width: 1280px; padding: 16px 24px 64px }`
- 问题根因:
  - `FieldRow.vue`:`label { min-width: 220px }` + `flex` 水平排列
  - `NumericFields.vue`:`<el-form label-width="160px">`
  - 360px 屏:label 220px + padding 48px = 268px,剩 92px 给 input + type-tag(60px)+ 删除按钮 → input 宽度归零
- 用户报告:**移动端表单值不显示**

## Architecture

```
单断点:768px(标准 mobile breakpoint,Bootstrap/ Tailwind 默认)

≤768px 时:
- FieldRow 改垂直布局(label 上,value 下)
- el-form label-position 切到 top
- app-container padding 减小
- ActionBar 按钮组允许换行
- Monaco 高度减小
- el-tabs 用 scrollable 模式

>768px:保持当前桌面布局不变
```

### 策略:**纯 CSS @media + 极少 JS**

- 不引入 `useWindowSize` 或 `vueuse` 的 `useMediaQuery`(增加依赖,且 SSR 不友好)
- 组件内 `<style scoped>` 加 `@media (max-width: 768px)` 块
- 仅 `el-form label-position` 是 Vue prop,CSS 改不了 → 用 computed + matchMedia(原生浏览器 API,零依赖)

## Files

### 改 `tools/save-editor/frontend/src/styles.css`

```css
/* 全局响应式 */
@media (max-width: 768px) {
  .app-container {
    padding: 8px 8px 32px;
  }
  .app-header h1 {
    font-size: 1.2rem;
  }
  .app-header .subtle {
    font-size: 0.75rem;
  }
}
```

### 改 `tools/save-editor/frontend/src/components/FieldRow.vue`

```vue
<style scoped>
/* 现有桌面布局不动 */

@media (max-width: 768px) {
  .field-row {
    flex-direction: column;
    align-items: stretch;
    gap: 4px;
  }
  label,
  label.readonly-label {
    min-width: 0;
    width: 100%;
    font-size: 0.85em;
  }
  .type-tag {
    min-width: 0;
    font-size: 0.7em;
  }
  /* value 控件强制 100% 宽 */
  .value-input,
  .el-input,
  .el-input-number {
    width: 100%;
  }
}
</style>
```

### 改 `tools/save-editor/frontend/src/components/NumericFields.vue`

`el-form` 用 `:label-position="labelPosition"` + `:label-width="labelWidth"`:

```typescript
// script setup
import { computed, ref, onMounted, onUnmounted } from 'vue'

const isMobile = ref(false)
const labelPosition = computed(() => (isMobile.value ? 'top' : 'right'))
const labelWidth = computed(() => (isMobile.value ? 'auto' : '160px'))

let mql: MediaQueryList | null = null
function updateMobile() {
  isMobile.value = !!mql?.matches
}
onMounted(() => {
  mql = window.matchMedia('(max-width: 768px)')
  updateMobile()
  // 优先 addEventListener;老 Safari fallback 到 addListener
  if (typeof mql.addEventListener === 'function') {
    mql.addEventListener('change', updateMobile)
  } else if (typeof (mql as any).addListener === 'function') {
    ;(mql as any).addListener(updateMobile)
  }
})
onUnmounted(() => {
  if (!mql) return
  if (typeof mql.removeEventListener === 'function') {
    mql.removeEventListener('change', updateMobile)
  } else if (typeof (mql as any).removeListener === 'function') {
    ;(mql as any).removeListener(updateMobile)
  }
  mql = null
})
```

3 个 `<el-form>` 都换成 `:label-position="labelPosition" :label-width="labelWidth"`。

> **注意**: `mql` 必须保存同一引用,mounted/unmounted 共用,否则 removeEventListener 解不掉监听器。`computed` 要从 vue 显式 import,漏掉会 vue-tsc 失败。

### 改 `tools/save-editor/frontend/src/components/ActionBar.vue`

```css
@media (max-width: 768px) {
  .action-bar {
    flex-wrap: wrap;
    gap: 8px;
  }
  .action-bar .hint {
    display: none; /* 窄屏隐藏 undo 栈文字提示,省空间 */
  }
  .action-bar .spacer {
    display: none;
  }
}
```

### 改 `tools/save-editor/frontend/src/components/RawJsonEditor.vue`

```css
@media (max-width: 768px) {
  .monaco-wrapper {
    height: 240px;  /* 桌面 360,手机 240 给键盘留空间 */
  }
  .toolbar {
    flex-wrap: wrap;
    gap: 4px;
  }
}
```

### 改 `tools/save-editor/frontend/src/App.vue`(el-tabs scrollable)

**关键**: 项目里有 **3 层 el-tabs** — `App.vue` 顶层(数值字段/表单 Schema/Raw JSON)、`FormSchemaEditor.vue` 内层(护甲/武器/背包)、`RawJsonEditor.vue` 内层(armor/weapon/inventory)。窄屏最容易溢出的是内层较长 label(如 `背包 (hero.inventory)`),所以 **三层 tabs 都要覆盖**。

`<el-tabs>` 默认 tab 多了会换行。由于 `App.vue <style scoped>` 里直接选 `.el-tabs__nav-wrap` 选不到 EP 内部 DOM,统一用 **全局 `styles.css` 覆盖**:

```css
/* styles.css 全局,移动端所有 tabs 横向滚动 */
@media (max-width: 768px) {
  .el-tabs__nav-wrap,
  .el-tabs__nav-scroll {
    overflow-x: auto;
    overflow-y: hidden;
  }
  .el-tabs__nav {
    white-space: nowrap;
  }
  .el-tabs__nav-wrap::after {
    display: none;
  }
}
```

**不**加 `type="card"` —— 会改变视觉样式,而滚动能力来自 CSS overflow 不是 type prop。

### 改 `tools/save-editor/frontend/src/components/SlotMetaPanel.vue`(必做)

`el-descriptions :column="3" border` 在窄屏 (375px) 必然横向溢出 —— 这是上传后首屏,溢出会让"整体适配"验收失败。

**方案**: 用同一 matchMedia 模式(从 NumericFields 抽出 composable 复用),窄屏切 `:column="1"`:

```vue
<el-descriptions :column="descColumn" border>
  ...
</el-descriptions>
```

```typescript
// script setup
import { ref, onMounted, onUnmounted } from 'vue'
const isMobile = ref(false)
const descColumn = computed(() => (isMobile.value ? 1 : 3))
// 同 NumericFields 的 mql 模式
```

**或** 用全局 CSS 让 `.el-descriptions__body` 单列(更简单但 EP 内部 class 易变,弱优先):

```css
@media (max-width: 768px) {
  .el-descriptions__table {
    display: block;
  }
  .el-descriptions__cell {
    display: block;
    width: 100%;
  }
}
```

**决策**: 用 composable + matchMedia 切 prop(与 NumericFields 一致,行为可预期),CSS 兜底次要。

### 改 `tools/save-editor/frontend/src/components/NestedObject.vue` / `NestedList.vue`

```css
@media (max-width: 768px) {
  .item-card,
  .nested-dict {
    margin: 4px 0;
    padding: 4px;
  }
  .item-row {
    flex-wrap: wrap;
  }
}
```

## Steps

### Step 1: 全局 CSS 媒体查询 + 三层 tabs 滚动

- 改 `styles.css`,加移动端 padding/字体缩减
- 加 `app-container` padding 8px
- 加 **三层 el-tabs 全局横向滚动 CSS**(`.el-tabs__nav-wrap` 等,EP 内部 DOM 必须全局选)

### Step 2: 抽 `useMobileBreakpoint` composable

- 新建 `tools/save-editor/frontend/src/composables/useMobileBreakpoint.ts`
- 暴露 `isMobile: Ref<boolean>`,内部 mql 同一引用 + addEventListener/removeEventListener(含老 Safari fallback)
- 复用者:NumericFields、SlotMetaPanel

### Step 3: FieldRow 垂直布局

- 改 `FieldRow.vue <style>`,加 @media 块
- 验证:窄屏 label 在上、value 控件 100% 宽

### Step 4: NumericFields label-position 切换

- 用 `useMobileBreakpoint` 替换 inline matchMedia
- 3 个 `<el-form>` 切换 `label-position="top"` + `label-width="auto"`

### Step 5: ActionBar 换行

- 改 `ActionBar.vue <style>`,加 flex-wrap + spacer/hint 隐藏

### Step 6: RawJsonEditor Monaco 高度 + 内层 tabs

- 改 `RawJsonEditor.vue <style>`,Monaco 240px
- 内层 tabs 横向滚动由 Step 1 全局 CSS 兜底

### Step 7: App.vue(无需 type=card)

- 顶层 tabs 横向滚动由 Step 1 全局 CSS 兜底
- 不改 `<el-tabs>` prop

### Step 8: SlotMetaPanel 列数切换(必做)

- 用 `useMobileBreakpoint`,`descColumn = isMobile ? 1 : 3`

### Step 9: NestedObject / NestedList 边距

- 内嵌卡片窄屏 margin/padding 缩小

### Step 10: 验证

- 桌面浏览器 F12 → 设备工具栏 → iPhone SE(375x667) / Pixel 5(393x851) / iPad mini(768x1024) / 360x640(手测极窄)
- 上传 `/tmp/123.zip`,在每个 tab(三层)检查:
  - 数值字段表:label 在上,input 100% 宽可见可编辑
  - 表单 Schema:FieldRow 垂直,所有 value 控件可见,内层 tabs(护甲/武器/背包)横向滚动
  - Raw JSON:Monaco 高度 240px,工具栏不溢出,内层 tabs 横向滚动
  - 存档信息(SlotMetaPanel):el-descriptions 单列,无横向溢出
- 自动断言 `document.documentElement.scrollWidth <= window.innerWidth`(关键页面上传后切到三层 tabs 各跑一次)
- 改 `hero.HP` 和 `armor.quantity`,下载 zip,Java 闭环通过

## Acceptance

每个 viewport (375x667, 393x851, 768x1024) 都跑核心流程,自动断言 `document.documentElement.scrollWidth <= window.innerWidth` 和关键输入 `boundingBox.width > 0`:

| # | 验收点 | 验证方法 |
|---|---|---|
| 1 | `app-container` 在 ≤768px padding 8px | DevTools computed style |
| 2 | FieldRow 在 ≤768px 垂直布局,label 在上 value 在下 | DevTools 模拟 iPhone SE |
| 3 | NumericFields 在 ≤768px label-position=top | DevTools |
| 4 | el-input-number 在 ≤768px 完整显示(+/- 按钮 + 数字 + 步进) | boundingBox.width > 0 自动断言 |
| 5 | el-switch / el-input 同样可见可点 | boundingBox.width > 0 自动断言 |
| 6 | ActionBar 在 ≤768px 按钮自动换行,不溢出 | DevTools |
| 7 | RawJsonEditor Monaco 在 ≤768px 高度 ≤280px | DevTools |
| 8 | **三层** el-tabs(App / FormSchemaEditor / RawJsonEditor)在 ≤768px 横向滚动,不换行 | 各切到最长的 tab label 验证 |
| 9 | NestedObject/NestedList 嵌套卡片在窄屏不溢出 | DevTools |
| 10 | SlotMetaPanel el-descriptions 在 ≤768px 单列,无横向溢出 | boundingBox.width > 0 + scrollWidth 断言 |
| 11 | 桌面(>768px)布局完全不变,无回归 | 桌面浏览器对照 |
| 12 | `npm run build` 通过,dist 增长 < 10KB(纯 CSS) | `npm run build` |
| 13 | pytest 40/40 不回归(后端不变) | `pytest -q` |
| 14 | Java 闭环通过(SPD 数据格式不变) | `SPD_ZIP_PATH=/tmp/... ./gradlew :core:test` |
| 15 | Playwright 模拟 **3 个 viewport** 跑一遍核心流程,自动断言 scrollWidth ≤ innerWidth + 关键 input 可见可编辑 | `setViewportSize` 各跑 |
| 16 | 改 armor.quantity + hero.HP 后下载 zip 字段值正确 | round-trip |

## 风险与备选

- **`label-position` prop 切换需要 JS**(el-form prop,CSS 改不了):
  - 用 `window.matchMedia` 监听,零依赖
  - 备选:用 CSS 强制覆盖 `.el-form-item__label { display: block }`(更纯但需 !important,容易和 EP 内部样式打架)
  - **决策:用 matchMedia**
- **Element Plus 内部样式**(EP 内部 DOM 如 `.el-tabs__nav-wrap`,scoped 选不到):
  - 用**全局 `styles.css`** 覆盖(决策)
  - 备选: scoped + `:deep()`(组件级,但三层 tabs 要重复写)
  - 决策理由: tabs 滚动要覆盖三层组件,全局一处改完,维护成本最低
- **Monaco 在窄屏性能**:启动慢,可考虑 lazy load 进一步优化(本 PR 不做)
- **iPad 768px 边界**:768px 时正好切换,可能闪烁。可加 `(max-width: 767px)` 避免边界,但 EP 默认断点用 768,统一即可
- **桌面回归**:每个 @media 块都用 `max-width: 768px` 包裹,桌面默认不受影响
- **iPhone SE 320px 极小屏**:可能仍有问题,不在 MVP 范围
- **横屏**:手机横屏宽度可能 >768px,按桌面布局渲染,可接受

## Out of scope

- iPad 横屏专项优化
- 折叠抽屉式侧边栏(改为 hamburger menu)
- 触摸手势优化(swipe 切 tab)
- PWA / 离线支持
- iPhone notch 安全区
- 主题切换适配(只暗色)
- SSR(单用户工具不需要)
- 多语言 i18n
- 极小屏(<360px)专项优化

## 后续可加(不在本 PR)

- `useWindowSize` composable 统一断点管理
- iPhone notch safe-area-inset
- 折叠侧边栏
- PWA manifest(可加到主屏幕)
