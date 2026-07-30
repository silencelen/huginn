// Image intake: clipboard pastes and dropped/picked images become ≤2048px
// JPEG q0.85 before upload — about model utility, not bandwidth (a 12MP
// screenshot costs tokens for pixels the model cannot use). Non-images are
// never read into the renderer at all; main streams them from disk by path.

const LONG_EDGE = 2048
const QUALITY = 0.85

export interface TranscodedImage {
  dataBase64: string
  contentType: 'image/jpeg'
  name: string
}

export async function transcodeImage(blob: Blob, name: string): Promise<TranscodedImage> {
  const bitmap = await createImageBitmap(blob)
  try {
    const scale = Math.min(1, LONG_EDGE / Math.max(bitmap.width, bitmap.height))
    const w = Math.max(1, Math.round(bitmap.width * scale))
    const h = Math.max(1, Math.round(bitmap.height * scale))
    const canvas = new OffscreenCanvas(w, h)
    const ctx = canvas.getContext('2d')
    if (ctx === null) throw new Error('no 2d context')
    ctx.drawImage(bitmap, 0, 0, w, h)
    const out = await canvas.convertToBlob({ type: 'image/jpeg', quality: QUALITY })
    const buf = new Uint8Array(await out.arrayBuffer())
    let bin = ''
    const CHUNK = 0x8000
    for (let i = 0; i < buf.length; i += CHUNK) {
      bin += String.fromCharCode(...buf.subarray(i, i + CHUNK))
    }
    return {
      dataBase64: btoa(bin),
      contentType: 'image/jpeg',
      name: name.replace(/\.[a-z0-9]+$/i, '') + '.jpg',
    }
  } finally {
    bitmap.close()
  }
}

export const isImageFile = (f: File): boolean => f.type.startsWith('image/')
