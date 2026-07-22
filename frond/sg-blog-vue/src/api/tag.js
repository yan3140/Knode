import request from '@/utils/request'

export function getAllTags() {
  return request({
    url: '/tag/listAllTag',
    method: 'get',
    headers: { isToken: false }
  })
}
