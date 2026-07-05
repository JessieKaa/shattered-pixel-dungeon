export interface FieldLabel {
  zh: string
  desc: string
}

export const FIELD_LABELS: Record<string, FieldLabel> = {
  // 通用
  __className: { zh: '类名', desc: '决定游戏内物品类型,不可修改' },
  level: { zh: '等级', desc: '装备/武器/戒指等级,+1 相当于一次升级' },
  quantity: { zh: '数量', desc: '堆叠数量(食物/药水/卷轴等)' },
  cursed: { zh: '诅咒', desc: 'true 表示被诅咒,无法主动卸下/丢弃' },
  cursedKnown: { zh: '已知诅咒', desc: '玩家是否已识别诅咒状态' },
  levelKnown: { zh: '已知等级', desc: '玩家是否已识别等级' },
  kept_lost: { zh: '死亡保留', desc: 'true 表示死亡后该物品会保留' },
  id: { zh: 'ID', desc: '物品实例唯一标识' },
  pos: { zh: '位置', desc: '物品在地图/背包中的位置索引' },

  // 装备
  augment: { zh: '强化方向', desc: '护甲/武器强化方向(NONE/DAMAGE/DEFENSE)' },
  glyph_hardened: { zh: '雕文固化', desc: '护甲雕文是否已固化' },
  enchant_hardened: { zh: '附魔固化', desc: '武器附魔是否已固化' },
  curse_infusion_bonus: { zh: '诅咒灌注加成', desc: '来自诅咒灌注的加成数值' },
  mastery_potion_bonus: { zh: '精通药水加成', desc: '来自精通药水的加成数值' },
  available_uses: { zh: '可用次数', desc: '鉴定/使用类物品剩余可用次数' },
  uses_left_to_id: { zh: '剩余鉴定次数', desc: '还需使用多少次才能自动鉴定' },

  // 法杖/魔导器
  curCharges: { zh: '当前充能', desc: '法杖当前充能数' },
  curChargeKnown: { zh: '已知充能', desc: '玩家是否已知当前充能数' },
  partialCharge: { zh: '部分充能', desc: '法杖部分充能进度(0.0-1.0)' },
  resin_bonus: { zh: '树脂加成', desc: '来自魔法树脂的加成' },
  zapped: { zh: '已释放', desc: '本轮是否已经施放过' },

  // 容器/背包
  inventory: { zh: '容器内物品', desc: '背包/袋子内的物品列表' },
  volume: { zh: '水量', desc: '水袋当前水量' },
  quickslotpos: { zh: '快捷栏位置', desc: '绑定的快捷栏格子索引' },

  // Hero
  HP: { zh: '生命值', desc: '当前生命值' },
  HT: { zh: '生命上限', desc: '最大生命值' },
  STR: { zh: '力量', desc: '力量值,影响装备需求和近战伤害' },
  exp: { zh: '经验值', desc: '当前等级经验' },
  lvl: { zh: '英雄等级', desc: '英雄总等级' },
  class: { zh: '职业', desc: '英雄职业枚举' },
  buffs: { zh: 'Buffs', desc: '英雄身上的增益/减益效果列表' },

  // Meta / Game
  name: { zh: '存档名', desc: '槽位显示名称' },
  depth: { zh: '深度', desc: '当前地下城层数' },
  'meta.level': { zh: '等级', desc: '存档记录的英雄等级' },
  hero_class: { zh: '职业', desc: '英雄职业' },
  version: { zh: '版本', desc: '存档创建时的游戏版本号,必须匹配 896' },
  gold: { zh: '金币', desc: '持有金币数' },
  seed: { zh: '种子', desc: '随机种子,改后可能让世界状态不一致' },
  challenges: { zh: '挑战', desc: '开启的挑战模式位掩码' },
  duration: { zh: '时长', desc: '游戏时长(回合)' },
  'game.daily': { zh: '每日挑战', desc: 'true 会触发 SaveSlotService.isSaveAllowed 拒绝存读档' },

  // 嵌套路径特殊
  'weapon.wand': { zh: '内嵌法杖', desc: '法师杖内嵌的法杖' },
}

/**
 * 获取字段中文标签与描述。
 * 支持完整路径精确匹配(如 hero.weapon.wand)和路径后缀匹配:
 *   - 完整路径: hero.weapon.wand
 *   - 后 2 段: weapon.wand
 *   - 后 1 段(字段名): wand
 * 未命中时返回 null,调用方应保持原 key 显示。
 */
export function getFieldLabel(path: string): FieldLabel | null {
  if (!path) return null

  // 1. 完整路径精确匹配
  if (FIELD_LABELS[path]) return FIELD_LABELS[path]

  // 2. 从最长后缀开始尝试(weapon.wand → wand)
  const segments = path.split('.')
  for (let i = 1; i < segments.length; i++) {
    const suffix = segments.slice(i).join('.')
    if (FIELD_LABELS[suffix]) return FIELD_LABELS[suffix]
  }

  return null
}
