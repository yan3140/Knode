import request from '@/utils/request'

// 发送文章评论
export function sendComment(type, articleId, rootId, toCommentId, toCommentUserId, content) {
  const payload = {
    type: String(type),
    rootId: rootId != null ? String(rootId) : '-1',
    toCommentId: toCommentId != null ? String(toCommentId) : '-1',
    toCommentUserId: toCommentUserId != null ? String(toCommentUserId) : '-1',
    content: content
  }
  if (String(type) !== '1' && articleId != null && articleId !== '') {
    payload.articleId = String(articleId)
  }
  return request({
    url: '/comment',
    method: 'post',
    data: payload
  })
}

export function getArticleComment(query) {
  return request({
    url: '/comment/commentList',
    method: 'get',
    headers: {
      isToken: false
    },
    params: query
  })
}

export function getLinkComment(query) {
  const params = {}
  if (query) {
    if (query.pageNum != null) params.pageNum = query.pageNum
    if (query.pageSize != null) params.pageSize = query.pageSize
  }
  return request({
    url: '/comment/linkCommentList',
    method: 'get',
    headers: {
      isToken: false
    },
    params: params
  })
}