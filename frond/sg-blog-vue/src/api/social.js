import request from '@/utils/request'

export function getUnreadCount() {
  return request({
    url: '/notification/unread-count',
    method: 'get'
  })
}

export function getNotifications(params) {
  return request({
    url: '/notification/list',
    method: 'get',
    params
  })
}

export function markNotificationRead(id) {
  return request({
    url: '/notification/' + id + '/read',
    method: 'put'
  })
}

export function markAllNotificationsRead() {
  return request({
    url: '/notification/read-all',
    method: 'put'
  })
}

export function deleteNotification(id) {
  return request({
    url: '/notification/' + id,
    method: 'delete'
  })
}

export function followUser(userId) {
  return request({ url: '/follow/' + userId, method: 'put' })
}

export function getFollowStatus(userId) {
  return request({ url: '/follow/status/' + userId, method: 'get' })
}

export function unfollowUser(userId) {
  return request({ url: '/follow/' + userId, method: 'delete' })
}

export function getUserProfile(userId, params) {
  return request({ url: '/user/profile/' + userId, method: 'get', params })
}

export function getUserFollowing(userId, params) {
  return request({ url: '/follow/' + userId + '/following', method: 'get', params })
}
