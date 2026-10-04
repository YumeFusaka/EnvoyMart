import request from '@/utils/axios'

/**
 * 图片上传。
 * <p>
 * 走 `multipart/form-data`，不手动设 `Content-Type` —— 浏览器需要在其中带上
 * 自动生成的 `boundary`，手写一个 `multipart/form-data` 反而会让服务端解析失败。
 */
export async function uploadProductImage(file: File) {
  const form = new FormData()
  form.append('file', file)
  const response = await request.post('/products/admin/media/images', form)
  return response.data.data.url as string
}
