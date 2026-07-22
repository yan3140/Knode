import request from '@/utils/request'

export function searchArticles(query) {
  return request({
    url: '/search',
    method: 'get',
    headers: {
      isToken: false
    },
    params: query
  })
}
