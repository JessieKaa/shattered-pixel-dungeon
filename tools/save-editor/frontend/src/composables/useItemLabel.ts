import labels from '@/data/item-labels-zh.json'
import { formatClassName } from '@/composables/useFieldType'

const PREFIX = 'com.shatteredpixel.shatteredpixeldungeon.'

function toPropKey(className: string): string | null {
  if (!className.startsWith(PREFIX)) return null
  return className.slice(PREFIX.length).toLowerCase()
}

export interface ItemLabelInfo {
  zh: string | null
  fallback: string
}

export function itemLabel(cn: unknown): ItemLabelInfo {
  const fallback = formatClassName(cn)
  if (typeof cn !== 'string' || !cn) return { zh: null, fallback }
  const key = toPropKey(cn)
  if (!key) return { zh: null, fallback }
  const zh = (labels as Record<string, string>)[key]
  return { zh: zh ?? null, fallback }
}
