import { getFieldLabel } from '@/composables/useFieldLabels'
import { inferType } from '@/composables/useFieldType'
import { itemLabel } from '@/composables/useItemLabel'

export function normalizeSearchText(value: unknown): string {
  return String(value ?? '').toLowerCase().replace(/\s+/g, '')
}

export function matchesQuery(query: string, ...parts: unknown[]): boolean {
  const normalizedQuery = normalizeSearchText(query)
  if (!normalizedQuery) return true
  return parts.some((part) => normalizeSearchText(part).includes(normalizedQuery))
}

export function fieldSearchParts(path: string, keyName: string, value?: unknown): string[] {
  const info = getFieldLabel(path)
  const parts = [path, keyName, info?.zh, info?.desc, inferType(value)]
  if (keyName === '__className') parts.push(...itemSearchParts(value))
  return parts.filter((part): part is string => typeof part === 'string' && part.length > 0)
}

export function itemSearchParts(className: unknown): string[] {
  if (typeof className !== 'string' || !className) return []
  const info = itemLabel(className)
  return [info.zh, info.fallback, className].filter(
    (part): part is string => typeof part === 'string' && part.length > 0
  )
}

export function summarySearchText(value: unknown): string {
  const type = inferType(value)
  if (type === 'dict') {
    const className = (value as Record<string, unknown>).__className
    if (!className) return 'dict'
    const info = itemLabel(className)
    return info.zh ? `${info.zh}(${info.fallback})` : info.fallback
  }

  if (type === 'list') {
    const items = Array.isArray(value) ? value : []
    const classes = items
      .map((item) => {
        const info = itemLabel((item as Record<string, unknown> | null)?.__className)
        return info.zh ?? info.fallback
      })
      .join(', ')
    return `list[${items.length}${classes ? ': ' + classes : ''}]`
  }

  return type
}

export function objectMatchesQuery(value: unknown, query: string, path = ''): boolean {
  if (!normalizeSearchText(query)) return true

  const type = inferType(value)
  if (type === 'list') {
    return (value as unknown[]).some((item, index) => listItemMatchesQuery(item, query, `${path}[${index}]`))
  }
  if (type !== 'dict') {
    return matchesQuery(query, value)
  }

  const objectValue = value as Record<string, unknown>
  if (matchesQuery(query, ...itemSearchParts(objectValue.__className))) return true

  for (const [key, child] of Object.entries(objectValue)) {
    const childPath = path ? `${path}.${key}` : key
    const childType = inferType(child)
    if (matchesQuery(query, ...fieldSearchParts(childPath, key, child))) return true
    if (childType === 'dict' && objectMatchesQuery(child, query, childPath)) return true
    if (childType === 'list' && listItemMatchesQuery(child, query, childPath)) return true
    if (childType !== 'dict' && childType !== 'list' && matchesQuery(query, child)) return true
  }

  return false
}

export function listItemMatchesQuery(value: unknown, query: string, path = ''): boolean {
  if (!normalizeSearchText(query)) return true

  if (Array.isArray(value)) {
    return value.some((item, index) => listItemMatchesQuery(item, query, `${path}[${index}]`))
  }

  if (value && typeof value === 'object') {
    return objectMatchesQuery(value, query, path)
  }

  return matchesQuery(query, value)
}
