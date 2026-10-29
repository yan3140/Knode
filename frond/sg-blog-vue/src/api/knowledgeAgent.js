import request from '@/utils/request'

export function getSystemKnowledgeBase() {
  return request({ url: '/knowledge/system', method: 'get' })
}

export function getMyKnowledgeBases() {
  return request({ url: '/knowledge/mine', method: 'get' })
}

export function createAgentRun(knowledgeBaseId, data) {
  return request({
    url: '/knowledge/' + knowledgeBaseId + '/agent/runs',
    method: 'post',
    data
  })
}

export function getAgentRun(knowledgeBaseId, runId) {
  return request({
    url: '/knowledge/' + knowledgeBaseId + '/agent/runs/' + runId,
    method: 'get'
  })
}

export function resumeAgentRun(knowledgeBaseId, runId, answer) {
  return request({
    url: '/knowledge/' + knowledgeBaseId + '/agent/runs/' + runId + '/resume',
    method: 'post',
    data: { answer }
  })
}

export function getAgentConversation(knowledgeBaseId, conversationId) {
  return request({
    url: '/knowledge/' + knowledgeBaseId + '/agent/conversations/' + conversationId,
    method: 'get'
  })
}
