import request from '@/utils/request'

// 图片上传：不手动覆盖无 boundary 的 Content-Type，由浏览器为 FormData 自动生成 multipart boundary
export function uploadImg(img) {
  const formData = new FormData()
  formData.append('img', img)
  return request({
    url: '/upload',
    method: 'post',
    headers: { 'Content-Type': undefined },
    data: formData,
    transformRequest: [function(data, headers) {
      if (headers) {
        headers['Content-Type'] = undefined
        delete headers['content-type']
      }
      return data
    }]
  })
}
