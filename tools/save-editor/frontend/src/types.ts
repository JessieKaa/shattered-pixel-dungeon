// SaveSlotBundle 类型定义 — 与后端 app.py / spd_bundle.py 严格对齐

export interface SaveSlotBundle {
  meta: SlotMeta
  game: GameBundle
  /** key 是十进制 depth 数字字符串,如 "1" / "5" */
  depths: Record<string, BundleDict>
  warnings?: string[]
  /** 完整 files dict(opaque 透传给 /api/pack;含 depthN-branchM.dat 等不暴露字段)*/
  __raw_files: Record<string, BundleDict>
}

export interface SlotMeta {
  name?: string
  depth?: number
  level?: number
  version?: number
  hero_class?: string
  [k: string]: unknown
}

export interface GameBundle {
  version?: number
  hero: HeroBundle
  gold?: number
  depth?: number
  challenges?: number
  seed?: number | string
  daily?: boolean
  [k: string]: unknown
}

export interface HeroBundle {
  __className: string
  HP: number
  HT: number
  pos?: number
  lvl?: number
  STR?: number
  exp?: number
  attackSkill?: number
  defenseSkill?: number
  armor: Item | null
  weapon: Item | null
  inventory: Item[]
  [k: string]: unknown
}

export type Item = {
  __className: string
  [k: string]: unknown
}

export type BundleDict = Record<string, unknown>

export type FieldType =
  | 'bool'
  | 'int'
  | 'float'
  | 'str'
  | 'null'
  | 'list'
  | 'dict'
  | 'unknown'

export const FIELD_TYPES: FieldType[] = [
  'bool',
  'int',
  'float',
  'str',
  'null',
  'list',
  'dict',
]

export const HERO_CLASSES = [
  'WARRIOR',
  'MAGE',
  'ROGUE',
  'HUNTRESS',
  'CLERIC',
  'DUELIST',
] as const

export type HeroClass = (typeof HERO_CLASSES)[number]

export interface ParseResponse extends SaveSlotBundle {}

export interface PackRequest {
  meta: SlotMeta
  game: GameBundle
  depths: Record<string, BundleDict>
  __raw_files: Record<string, BundleDict>
  force_meta_version?: number | null | false
}
