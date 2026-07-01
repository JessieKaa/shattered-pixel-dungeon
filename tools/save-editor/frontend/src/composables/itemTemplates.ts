// 常用 Item 模板(对照 SPD 类层级)。
// 用户也可手填任意 __className。

const BASE = 'com.shatteredpixel.shatteredpixeldungeon.items'

export const ITEM_TEMPLATES: Record<string, () => Record<string, unknown>> = {
  Food: () => ({ __className: `${BASE}.food.Food`, quantity: 1 }),
  Waterskin: () => ({ __className: `${BASE}.waterskin`, quantity: 0 }),
  VelvetPouch: () => ({ __className: `${BASE}.bags.VelvetPouch`, items: [] }),
  ScrollHolder: () => ({ __className: `${BASE}.bags.ScrollHolder`, items: [] }),
  PotionBelt: () => ({ __className: `${BASE}.bags.PotionBelt`, items: [] }),
  ClothArmor: () => ({
    __className: `${BASE}.armor.ClothArmor`,
    tier: 1,
    level: 0,
    cursed: false,
    glyph: null,
  }),
  LeatherArmor: () => ({
    __className: `${BASE}.armor.LeatherArmor`,
    tier: 2,
    level: 0,
    cursed: false,
    glyph: null,
  }),
  MagesStaff: () => ({
    __className: `${BASE}.weapon.melee.MagesStaff`,
    tier: 1,
    level: 0,
    cursed: false,
    wand: null,
  }),
  Dagger: () => ({
    __className: `${BASE}.weapon.melee.Dagger`,
    tier: 1,
    level: 0,
    cursed: false,
    glyph: null,
  }),
  WoodRing: () => ({
    __className: `${BASE}.rings.WoodRing`,
    level: 0,
    cursed: false,
  }),
  Gold: () => ({ __className: `${BASE}.Gold`, quantity: 10 }),
}
