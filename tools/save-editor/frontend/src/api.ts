import type { PackRequest, ParseResponse, SaveSlotBundle } from '@/types'

export class ApiError extends Error {
  status: number
  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export async function parseZip(file: File): Promise<ParseResponse> {
  const form = new FormData()
  form.append('file', file)
  const res = await fetch('/api/parse', { method: 'POST', body: form })
  const text = await res.text()
  let data: any
  try {
    data = text ? JSON.parse(text) : {}
  } catch {
    throw new ApiError(`server returned non-json: ${text.slice(0, 200)}`, res.status)
  }
  if (!res.ok) {
    throw new ApiError(data?.error || `parse failed (${res.status})`, res.status)
  }
  return data as ParseResponse
}

export async function packZip(req: PackRequest): Promise<Blob> {
  const res = await fetch('/api/pack', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  })
  if (!res.ok) {
    let msg = `pack failed (${res.status})`
    try {
      const data = await res.json()
      msg = data?.error || msg
    } catch {
      // ignore
    }
    throw new ApiError(msg, res.status)
  }
  return await res.blob()
}

export function buildPackRequest(bundle: SaveSlotBundle, forceVersion: number | null | false = 896): PackRequest {
  return {
    meta: bundle.meta,
    game: bundle.game,
    depths: bundle.depths,
    __raw_files: bundle.__raw_files,
    force_meta_version: forceVersion,
  }
}
