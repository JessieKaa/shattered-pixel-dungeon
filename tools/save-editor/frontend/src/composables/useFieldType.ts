import type { FieldType } from '@/types'

export function inferType(v: unknown): FieldType {
  if (v === null) return 'null'
  if (typeof v === 'boolean') return 'bool'
  if (typeof v === 'number') return Number.isInteger(v) ? 'int' : 'float'
  if (typeof v === 'string') return 'str'
  if (Array.isArray(v)) return 'list'
  if (typeof v === 'object') return 'dict'
  return 'unknown'
}

export const FORM_TYPE_DEFAULTS: Record<string, unknown> = {
  int: 0,
  bool: false,
  str: '',
  float: 0.0,
  null: null,
  list: () => [] as unknown[],
  dict: () => ({}) as Record<string, unknown>,
}

export function defaultValueFor(type: FieldType): unknown {
  if (type === 'list') return []
  if (type === 'dict') return {}
  return FORM_TYPE_DEFAULTS[type] ?? null
}

export function formatClassName(cn: unknown): string {
  if (typeof cn !== 'string' || !cn) return '(no class)'
  // com.shatteredpixel.shatteredpixeldungeon.items.armor.ClothArmor -> ClothArmor
  const parts = cn.split('.')
  return parts[parts.length - 1] || cn
}

const KEY_RE = /^[a-zA-Z_][a-zA-Z0-9_]*$/

export function isValidFieldName(name: string): boolean {
  return KEY_RE.test(name)
}

export function isClassName(key: string): boolean {
  return key === '__className'
}
