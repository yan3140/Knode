jest.mock('../../src/components/header.vue', () => ({ name: 'sg-nav' }))

jest.mock('../../src/api/knowledgeAgent', () => ({
  createAgentRun: jest.fn(),
  getAgentConversation: jest.fn(),
  getAgentRun: jest.fn(),
  getMyKnowledgeBases: jest.fn(),
  getSystemKnowledgeBase: jest.fn(),
  resumeAgentRun: jest.fn()
}))

const KnowledgeChat = require('../../src/pages/KnowledgeChat.vue').default

function createHarness(overrides) {
  const context = Object.assign({
    sessions: [],
    conversationId: 'conversation-b',
    selectedKnowledgeBaseId: '3',
    runStatus: 'IDLE',
    currentRunId: '',
    waitingRun: null,
    pollTimers: {},
    pollCounts: {},
    pollErrors: {},
    railOpen: false,
    $set(target, key, value) { target[key] = value },
    $delete(target, key) { delete target[key] }
  }, overrides || {})

  Object.keys(KnowledgeChat.methods).forEach(name => {
    context[name] = KnowledgeChat.methods[name].bind(context)
  })
  context.writeSessions = jest.fn()
  return context
}

describe('KnowledgeChat conversation run tracking', () => {
  beforeEach(() => {
    jest.useFakeTimers()
  })

  afterEach(() => {
    jest.clearAllTimers()
    jest.useRealTimers()
  })

  it('keeps one polling timer for each running conversation', () => {
    const context = createHarness()

    context.schedulePoll('conversation-a', '3', 'run-a')
    const conversationATimer = context.pollTimers['conversation-a']
    context.schedulePoll('conversation-b', '3', 'run-b')

    expect(context.pollTimers['conversation-a']).toBe(conversationATimer)
    expect(context.pollTimers['conversation-b']).toBeDefined()
    expect(Object.keys(context.pollTimers)).toHaveLength(2)
  })

  it('does not apply a background conversation callback to the active conversation', () => {
    const context = createHarness({
      sessions: [
        { conversationId: 'conversation-a', knowledgeBaseId: '3', title: 'A', updatedAt: 1 },
        { conversationId: 'conversation-b', knowledgeBaseId: '3', title: 'B', updatedAt: 2 }
      ]
    })

    context.handleRunForConversation('conversation-a', '3', {
      runId: 'run-a',
      status: 'RUNNING'
    })

    expect(context.sessionFor('conversation-a').runStatus).toBe('RUNNING')
    expect(context.runStatus).toBe('IDLE')
    expect(context.currentRunId).toBe('')

    context.handleRunForConversation('conversation-a', '3', {
      runId: 'run-a',
      status: 'SUCCEEDED',
      result: { answer: 'answer-a' }
    })

    expect(context.sessionFor('conversation-a').runStatus).toBe('SUCCEEDED')
    expect(context.runStatus).toBe('IDLE')
    expect(context.currentRunId).toBe('')
  })

  it('does not clear another conversation timer when switching sessions', () => {
    const context = createHarness({
      sessions: [
        {
          conversationId: 'conversation-a', knowledgeBaseId: '3', title: 'A',
          updatedAt: 1, runId: 'run-a', runStatus: 'RUNNING'
        },
        {
          conversationId: 'conversation-b', knowledgeBaseId: '3', title: 'B',
          updatedAt: 2, runId: 'run-b', runStatus: 'SUCCEEDED'
        }
      ],
      conversationId: 'conversation-a',
      runStatus: 'RUNNING',
      currentRunId: 'run-a',
      pollTimers: { 'conversation-a': 123 }
    })
    context.persistSelectedKnowledgeBase = jest.fn()
    context.updateKnowledgeBaseRoute = jest.fn()
    context.ensureSessionPolling = jest.fn()
    context.loadConversation = jest.fn()

    context.openSession(context.sessionFor('conversation-b'))

    expect(context.pollTimers['conversation-a']).toBe(123)
    expect(context.conversationId).toBe('conversation-b')
    expect(context.runStatus).toBe('SUCCEEDED')
    expect(context.currentRunId).toBe('run-b')
  })
})
