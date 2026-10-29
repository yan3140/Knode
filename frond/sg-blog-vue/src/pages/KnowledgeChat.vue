<template>
  <div class="knowledge-agent-page">
    <sg-nav :compact="true"></sg-nav>
    <main class="container agent-page-container">
      <section class="agent-shell tcommonBox">
        <aside class="conversation-rail" :class="{ open: railOpen }">
          <div class="rail-heading">
            <div>
              <p>KNOWLEDGE AGENT</p>
              <h1>知识问答</h1>
            </div>
            <el-tooltip content="新建会话" placement="bottom">
              <button class="icon-button new-chat-button" type="button" aria-label="新建会话" @click="newConversation">
                <i class="fa fa-plus"></i>
              </button>
            </el-tooltip>
          </div>

          <label class="field-label" for="knowledge-base-select">知识库</label>
          <el-select
            id="knowledge-base-select"
            v-model="selectedKnowledgeBaseId"
            class="knowledge-select"
            :loading="loadingKnowledgeBases"
            placeholder="选择知识库"
            @change="changeKnowledgeBase"
          >
            <el-option
              v-for="base in knowledgeBases"
              :key="base.id"
              :label="base.name"
              :value="String(base.id)"
            ></el-option>
          </el-select>

          <div class="session-heading">
            <span>最近会话</span>
            <span>{{ visibleSessions.length }}</span>
          </div>
          <div class="session-list">
            <button
              v-for="session in visibleSessions"
              :key="session.conversationId"
              class="session-item"
              :class="{ active: session.conversationId === conversationId }"
              type="button"
              @click="openSession(session)"
            >
              <i class="fa fa-comment-o"></i>
              <span>
                <strong>{{ session.title }}</strong>
                <small>
                  <span v-if="session.runStatus && session.runStatus !== 'IDLE'" class="session-run-state" :class="sessionStatusClass(session)">
                    <i v-if="isRunPending(session.runStatus)" class="el-icon-loading"></i>
                    {{ sessionStatusText(session.runStatus) }}
                  </span>
                  <time>{{ formatSessionTime(session.updatedAt) }}</time>
                </small>
              </span>
            </button>
            <div v-if="!visibleSessions.length" class="empty-sessions">当前知识库还没有会话</div>
          </div>
        </aside>

        <section class="chat-workspace">
          <header class="chat-header">
            <button class="icon-button rail-toggle" type="button" aria-label="切换会话列表" @click="railOpen = !railOpen">
              <i class="fa fa-bars"></i>
            </button>
            <div class="chat-title">
              <h2>{{ currentKnowledgeBaseName }}</h2>
              <p><span class="status-dot" :class="statusClass"></span>{{ statusText }}</p>
            </div>
            <el-radio-group v-model="retrievalMode" size="small" class="mode-switch" :disabled="busy">
              <el-radio-button label="AUTO">智能</el-radio-button>
              <el-radio-button label="EXACT">精确</el-radio-button>
              <el-radio-button label="HYBRID">混合</el-radio-button>
            </el-radio-group>
          </header>

          <div ref="messageViewport" class="message-viewport">
            <div v-if="loadingConversation" class="conversation-loading">
              <i class="el-icon-loading"></i><span>正在恢复会话上下文</span>
            </div>

            <div v-else-if="!messages.length" class="chat-empty">
              <span class="agent-mark"><i class="fa fa-database"></i></span>
              <h2>从知识库里找答案</h2>
              <p>回答会基于检索证据生成；证据不足时，Agent 会明确说明或请你补充问题。</p>
              <div class="prompt-list">
                <button v-for="prompt in suggestedPrompts" :key="prompt" type="button" @click="usePrompt(prompt)">
                  <i class="fa fa-arrow-right"></i><span>{{ prompt }}</span>
                </button>
              </div>
            </div>

            <div v-else class="message-list">
              <article v-for="message in messages" :key="message.messageId" class="message-row" :class="message.role.toLowerCase()">
                <span class="message-avatar">
                  <i :class="message.role === 'USER' ? 'fa fa-user' : 'fa fa-database'"></i>
                </span>
                <div class="message-body">
                  <div class="message-meta">
                    <strong>{{ message.role === 'USER' ? '我' : '知识库 Agent' }}</strong>
                    <time>{{ formatMessageTime(message.createdAt) }}</time>
                  </div>
                  <p>{{ message.content }}</p>
                  <button
                    v-if="message.citations && message.citations.length"
                    class="citation-trigger"
                    type="button"
                    @click="showCitations(message.citations)"
                  >
                    <i class="fa fa-book"></i> 查看 {{ message.citations.length }} 条引用证据
                  </button>
                </div>
              </article>

              <article v-if="busy" class="message-row assistant pending-message">
                <span class="message-avatar"><i class="fa fa-database"></i></span>
                <div class="message-body">
                  <div class="message-meta"><strong>知识库 Agent</strong></div>
                  <p><i class="el-icon-loading"></i> {{ progressText }}</p>
                </div>
              </article>
            </div>
          </div>

          <div v-if="waitingRun" class="clarification-banner">
            <i class="fa fa-info-circle"></i>
            <span>Agent 需要更多信息。你的下一条消息会作为补充说明继续当前流程。</span>
          </div>

          <footer class="composer">
            <el-input
              ref="questionInput"
              v-model="question"
              type="textarea"
              :rows="2"
              :maxlength="2000"
              :disabled="!selectedKnowledgeBaseId || busy"
              :placeholder="waitingRun ? '补充具体对象、范围或条件…' : '向当前知识库提问…'"
              @keydown.native="handleComposerKeydown"
            ></el-input>
            <div class="composer-bar">
              <span>{{ question.length }}/2000 · Enter 发送，Shift+Enter 换行</span>
              <el-button
                type="primary"
                icon="el-icon-upload2"
                :loading="busy"
                :disabled="!canSend"
                @click="sendQuestion"
              >发送</el-button>
            </div>
          </footer>
        </section>
      </section>
    </main>

    <el-dialog title="引用证据" :visible.sync="citationDialogVisible" size="small" class="citation-dialog">
      <div v-if="activeCitations.length" class="citation-list">
        <article v-for="(citation, index) in activeCitations" :key="citation.chunk_id || citation.chunkId || index" class="citation-item">
          <div class="citation-heading">
            <span>{{ index + 1 }}</span>
            <div>
              <h3>{{ citation.title || '未命名文档' }}</h3>
              <p>{{ citation.section || citation.source || '知识库文档' }}</p>
            </div>
            <em v-if="citationScore(citation) !== null">{{ citationScore(citation) }}</em>
          </div>
          <p class="citation-content">{{ citation.content || '该证据未返回正文摘要。' }}</p>
          <small v-if="citation.source">来源：{{ citation.source }}</small>
        </article>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import header from '../components/header.vue'
import {
  createAgentRun,
  getAgentConversation,
  getAgentRun,
  getMyKnowledgeBases,
  getSystemKnowledgeBase,
  resumeAgentRun
} from '../api/knowledgeAgent'

const TERMINAL_STATUSES = ['SUCCEEDED', 'INSUFFICIENT_EVIDENCE', 'FAILED', 'CANCELLED', 'WAITING_INPUT']
const SESSION_STORAGE_PREFIX = 'yanblog.agent.sessions.v1.'
const LAST_KNOWLEDGE_BASE_PREFIX = 'yanblog.agent.lastKnowledgeBase.v1.'

export default {
  name: 'KnowledgeChat',
  components: { 'sg-nav': header },
  data() {
    return {
      knowledgeBases: [],
      selectedKnowledgeBaseId: '',
      loadingKnowledgeBases: false,
      loadingConversation: false,
      sessions: [],
      conversationId: '',
      messages: [],
      question: '',
      retrievalMode: 'AUTO',
      runStatus: 'IDLE',
      currentRunId: '',
      waitingRun: null,
      pollTimers: {},
      pollCounts: {},
      pollErrors: {},
      railOpen: false,
      citationDialogVisible: false,
      activeCitations: [],
      suggestedPrompts: [
        'MySQL 联合索引的最左匹配原则是什么？',
        '什么情况下索引会失效？',
        '解释一下 MVCC 和事务隔离级别的关系'
      ]
    }
  },
  computed: {
    busy() {
      return this.runStatus === 'QUEUED' || this.runStatus === 'RUNNING'
    },
    canSend() {
      return Boolean(this.selectedKnowledgeBaseId && this.question.trim() && !this.busy)
    },
    currentKnowledgeBaseName() {
      const base = this.knowledgeBases.find(item => String(item.id) === String(this.selectedKnowledgeBaseId))
      return base ? base.name : '知识库'
    },
    visibleSessions() {
      return this.sessions
        .filter(item => String(item.knowledgeBaseId) === String(this.selectedKnowledgeBaseId))
        .sort((left, right) => right.updatedAt - left.updatedAt)
    },
    statusText() {
      return {
        IDLE: '就绪', QUEUED: '已进入任务队列', RUNNING: '正在检索并生成回答',
        WAITING_INPUT: '等待补充信息', SUCCEEDED: '回答完成',
        INSUFFICIENT_EVIDENCE: '证据不足', FAILED: '执行失败', CANCELLED: '任务已取消'
      }[this.runStatus] || '就绪'
    },
    statusClass() {
      if (this.busy) return 'working'
      if (this.runStatus === 'FAILED' || this.runStatus === 'CANCELLED') return 'error'
      if (this.runStatus === 'WAITING_INPUT' || this.runStatus === 'INSUFFICIENT_EVIDENCE') return 'warning'
      return 'ready'
    },
    progressText() {
      return this.runStatus === 'QUEUED' ? '问题已提交，正在等待执行…' : '正在检索证据并校验回答…'
    }
  },
  created() {
    this.sessions = this.readSessions()
    this.loadKnowledgeBases()
  },
  beforeDestroy() {
    this.stopAllPolling()
  },
  methods: {
    loadKnowledgeBases() {
      this.loadingKnowledgeBases = true
      Promise.all([getSystemKnowledgeBase(), getMyKnowledgeBases()])
        .then(([system, mine]) => {
          this.knowledgeBases = [system].concat(mine || [])
          const requested = this.$route.query.knowledgeBaseId || localStorage.getItem(this.lastKnowledgeBaseKey())
          const exists = this.knowledgeBases.some(item => String(item.id) === String(requested))
          this.selectedKnowledgeBaseId = exists ? String(requested) : String(system.id)
          this.persistSelectedKnowledgeBase()
          this.updateKnowledgeBaseRoute()
          const latest = this.visibleSessions[0]
          if (latest) this.openSession(latest)
          else this.newConversation(false)
          this.resumePendingRuns()
        })
        .catch(() => { this.$message.error('知识库加载失败，请稍后重试') })
        .finally(() => { this.loadingKnowledgeBases = false })
    },
    changeKnowledgeBase() {
      this.messages = []
      this.persistSelectedKnowledgeBase()
      this.updateKnowledgeBaseRoute()
      const latest = this.visibleSessions[0]
      if (latest) this.openSession(latest)
      else this.newConversation(false)
      this.railOpen = false
    },
    newConversation(focusInput) {
      this.conversationId = this.newId()
      this.messages = []
      this.syncActiveRunState(null)
      this.question = ''
      this.railOpen = false
      if (focusInput !== false) this.$nextTick(() => this.focusComposer())
    },
    openSession(session) {
      const currentSession = this.sessionFor(session.conversationId) || session
      this.selectedKnowledgeBaseId = String(currentSession.knowledgeBaseId)
      this.persistSelectedKnowledgeBase()
      this.updateKnowledgeBaseRoute()
      this.conversationId = currentSession.conversationId
      this.syncActiveRunState(currentSession)
      this.ensureSessionPolling(currentSession)
      this.railOpen = false
      this.loadConversation()
    },
    loadConversation() {
      if (!this.conversationId) return
      const conversationId = this.conversationId
      const knowledgeBaseId = this.selectedKnowledgeBaseId
      this.loadingConversation = true
      getAgentConversation(knowledgeBaseId, conversationId)
        .then(context => {
          if (!this.isActiveConversation(conversationId, knowledgeBaseId)) return
          this.messages = (context.messages || []).map(this.normalizeMessage)
          this.scrollToBottom()
        })
        .catch(() => {
          if (this.isActiveConversation(conversationId, knowledgeBaseId)) {
            this.$message.error('会话恢复失败，该记录可能已失效')
          }
        })
        .finally(() => {
          if (this.isActiveConversation(conversationId, knowledgeBaseId)) this.loadingConversation = false
        })
    },
    sendQuestion() {
      const content = this.question.trim()
      if (!content || !this.canSend) return
      const conversationId = this.conversationId
      const knowledgeBaseId = this.selectedKnowledgeBaseId
      const waitingRunId = this.waitingRun && this.waitingRun.runId
      this.question = ''
      this.messages.push({
        messageId: 'local-' + this.newId(), role: 'USER', content,
        citations: [], createdAt: new Date().toISOString()
      })
      this.touchSession(content, {
        runStatus: 'QUEUED',
        waitingRunId: null,
        pollCount: 0
      })
      this.scrollToBottom()
      this.$set(this.pollCounts, conversationId, 0)
      this.$set(this.pollErrors, conversationId, 0)
      const action = waitingRunId
        ? resumeAgentRun(knowledgeBaseId, waitingRunId, content)
        : createAgentRun(knowledgeBaseId, {
          clientRequestId: this.newId(),
          conversationId,
          question: content,
          requestedMode: this.retrievalMode
        })
      action.then(run => {
        this.handleRunForConversation(conversationId, knowledgeBaseId, run)
      }).catch(() => {
        this.finishTracking(conversationId)
        this.updateSessionRuntime(conversationId, knowledgeBaseId, {
          runStatus: 'FAILED',
          waitingRunId: null,
          updatedAt: Date.now()
        })
        if (this.isActiveConversation(conversationId, knowledgeBaseId)) {
          this.appendLocalAssistant('请求未能提交，请检查服务状态后重试。', [])
        }
      })
    },
    handleRunForConversation(conversationId, knowledgeBaseId, run) {
      this.$set(this.pollErrors, conversationId, 0)
      this.updateSessionRuntime(conversationId, knowledgeBaseId, {
        runId: run.runId,
        runStatus: run.status,
        waitingRunId: run.status === 'WAITING_INPUT' ? run.runId : null,
        updatedAt: TERMINAL_STATUSES.indexOf(run.status) === -1 ? undefined : Date.now()
      })
      if (TERMINAL_STATUSES.indexOf(run.status) === -1) {
        this.schedulePoll(conversationId, knowledgeBaseId, run.runId)
        return
      }
      this.finishTracking(conversationId)
      this.refreshAfterRun(run, conversationId, knowledgeBaseId)
    },
    schedulePoll(conversationId, knowledgeBaseId, runId, delay) {
      this.clearPollTimer(conversationId)
      const timer = window.setTimeout(() => {
        const pollCount = (this.pollCounts[conversationId] || 0) + 1
        this.$set(this.pollCounts, conversationId, pollCount)
        if (pollCount > 180) {
          this.finishTracking(conversationId)
          this.updateSessionRuntime(conversationId, knowledgeBaseId, {
            runStatus: 'FAILED',
            waitingRunId: null,
            updatedAt: Date.now()
          })
          if (this.isActiveConversation(conversationId, knowledgeBaseId)) {
            this.appendLocalAssistant('任务执行超时，请稍后重新提问。', [])
          }
          return
        }
        getAgentRun(knowledgeBaseId, runId)
          .then(run => this.handleRunForConversation(conversationId, knowledgeBaseId, run))
          .catch(() => {
            const errorCount = (this.pollErrors[conversationId] || 0) + 1
            this.$set(this.pollErrors, conversationId, errorCount)
            if (errorCount < 5) {
              this.schedulePoll(conversationId, knowledgeBaseId, runId, Math.min(errorCount * 1000, 5000))
              return
            }
            this.finishTracking(conversationId)
            this.updateSessionRuntime(conversationId, knowledgeBaseId, {
              runStatus: 'FAILED',
              waitingRunId: null,
              updatedAt: Date.now()
            })
            if (this.isActiveConversation(conversationId, knowledgeBaseId)) {
              this.appendLocalAssistant('无法获取任务状态，请稍后重试。', [])
            }
          })
      }, delay || 1000)
      this.$set(this.pollTimers, conversationId, timer)
    },
    refreshAfterRun(run, conversationId, knowledgeBaseId) {
      if (!this.isActiveConversation(conversationId, knowledgeBaseId)) return
      getAgentConversation(knowledgeBaseId, conversationId)
        .then(context => {
          if (!this.isActiveConversation(conversationId, knowledgeBaseId)) return
          this.messages = (context.messages || []).map(this.normalizeMessage)
          if (!this.messages.length && run.result && run.result.answer) {
            this.appendLocalAssistant(run.result.answer, run.result.citations || [])
          }
          this.scrollToBottom()
        })
        .catch(() => {
          if (!this.isActiveConversation(conversationId, knowledgeBaseId)) return
          const answer = run.result && run.result.answer
          if (answer) this.appendLocalAssistant(answer, run.result.citations || [])
          else if (run.status === 'FAILED') this.appendLocalAssistant('Agent 执行失败，请换一种问法后重试。', [])
        })
    },
    clearPollTimer(conversationId) {
      const timer = this.pollTimers[conversationId]
      if (timer) window.clearTimeout(timer)
      this.$delete(this.pollTimers, conversationId)
    },
    finishTracking(conversationId) {
      this.clearPollTimer(conversationId)
      this.$delete(this.pollCounts, conversationId)
      this.$delete(this.pollErrors, conversationId)
    },
    stopAllPolling() {
      Object.keys(this.pollTimers).forEach(this.clearPollTimer)
    },
    resumePendingRuns() {
      this.sessions.forEach(this.ensureSessionPolling)
    },
    ensureSessionPolling(session) {
      if (!session || !session.runId || !this.isRunPending(session.runStatus) || this.pollTimers[session.conversationId]) return
      this.schedulePoll(session.conversationId, String(session.knowledgeBaseId), session.runId)
    },
    isRunPending(status) {
      return status === 'QUEUED' || status === 'RUNNING'
    },
    isActiveConversation(conversationId, knowledgeBaseId) {
      return this.conversationId === conversationId
        && String(this.selectedKnowledgeBaseId) === String(knowledgeBaseId)
    },
    sessionFor(conversationId) {
      return this.sessions.find(item => item.conversationId === conversationId)
    },
    syncActiveRunState(session) {
      this.runStatus = session && session.runStatus ? session.runStatus : 'IDLE'
      this.currentRunId = session && session.runId ? session.runId : ''
      this.waitingRun = session && session.waitingRunId ? { runId: session.waitingRunId } : null
    },
    updateSessionRuntime(conversationId, knowledgeBaseId, updates) {
      const index = this.sessions.findIndex(item => item.conversationId === conversationId)
      const current = index === -1 ? {
        conversationId,
        knowledgeBaseId: String(knowledgeBaseId),
        title: '新会话',
        updatedAt: Date.now()
      } : this.sessions[index]
      const runtimeUpdates = Object.assign({}, updates)
      if (runtimeUpdates.updatedAt === undefined) delete runtimeUpdates.updatedAt
      const session = Object.assign({}, current, runtimeUpdates)
      if (index === -1) this.sessions.unshift(session)
      else this.sessions.splice(index, 1, session)
      this.sessions = this.sessions.slice(0, 30)
      this.writeSessions()
      if (this.isActiveConversation(conversationId, knowledgeBaseId)) this.syncActiveRunState(session)
      return session
    },
    normalizeMessage(message) {
      return {
        messageId: message.messageId,
        runId: message.runId,
        role: message.role,
        content: message.content,
        citations: message.citations || [],
        createdAt: message.createdAt
      }
    },
    appendLocalAssistant(content, citations) {
      const duplicate = this.messages.some(item => item.role === 'ASSISTANT' && item.content === content)
      if (!duplicate) {
        this.messages.push({
          messageId: 'local-' + this.newId(), role: 'ASSISTANT', content,
          citations: citations || [], createdAt: new Date().toISOString()
        })
      }
      this.scrollToBottom()
    },
    touchSession(question, updates) {
      const index = this.sessions.findIndex(item => item.conversationId === this.conversationId)
      const current = index === -1 ? null : this.sessions[index]
      const title = current ? current.title : (question || '新会话').slice(0, 28)
      const session = Object.assign({}, current || {}, {
        conversationId: this.conversationId,
        knowledgeBaseId: this.selectedKnowledgeBaseId,
        title,
        updatedAt: Date.now()
      }, updates || {})
      if (index !== -1) this.sessions.splice(index, 1)
      this.sessions.unshift(session)
      this.sessions = this.sessions.slice(0, 30)
      this.writeSessions()
      this.syncActiveRunState(session)
    },
    readSessions() {
      try {
        const value = JSON.parse(localStorage.getItem(this.sessionStorageKey()) || '[]')
        return Array.isArray(value) ? value : []
      } catch (error) {
        return []
      }
    },
    writeSessions() {
      localStorage.setItem(this.sessionStorageKey(), JSON.stringify(this.sessions))
    },
    sessionStorageKey() {
      const user = JSON.parse(localStorage.getItem('userInfo') || 'null')
      return SESSION_STORAGE_PREFIX + (user && user.id ? user.id : 'user')
    },
    lastKnowledgeBaseKey() {
      const user = JSON.parse(localStorage.getItem('userInfo') || 'null')
      return LAST_KNOWLEDGE_BASE_PREFIX + (user && user.id ? user.id : 'user')
    },
    persistSelectedKnowledgeBase() {
      if (this.selectedKnowledgeBaseId) {
        localStorage.setItem(this.lastKnowledgeBaseKey(), this.selectedKnowledgeBaseId)
      }
    },
    updateKnowledgeBaseRoute() {
      if (!this.selectedKnowledgeBaseId
          || String(this.$route.query.knowledgeBaseId) === String(this.selectedKnowledgeBaseId)) return
      this.$router.replace({
        path: '/KnowledgeChat',
        query: { knowledgeBaseId: this.selectedKnowledgeBaseId }
      })
    },
    newId() {
      const alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
      let timestamp = Date.now()
      let value = ''
      for (let index = 0; index < 10; index += 1) {
        value = alphabet[timestamp % 32] + value
        timestamp = Math.floor(timestamp / 32)
      }
      let randomValues
      if (window.crypto && window.crypto.getRandomValues) {
        randomValues = new Uint8Array(16)
        window.crypto.getRandomValues(randomValues)
      } else {
        randomValues = Array.from({ length: 16 }, () => Math.floor(Math.random() * 256))
      }
      for (let index = 0; index < 16; index += 1) value += alphabet[randomValues[index] % 32]
      return value
    },
    usePrompt(prompt) {
      this.question = prompt
      this.$nextTick(() => this.focusComposer())
    },
    focusComposer() {
      if (this.$refs.questionInput && this.$refs.questionInput.$refs.textarea) {
        this.$refs.questionInput.$refs.textarea.focus()
      }
    },
    handleComposerKeydown(event) {
      if (event.keyCode === 13 && !event.shiftKey) {
        event.preventDefault()
        this.sendQuestion()
      }
    },
    scrollToBottom() {
      this.$nextTick(() => {
        const viewport = this.$refs.messageViewport
        if (viewport) viewport.scrollTop = viewport.scrollHeight
      })
    },
    showCitations(citations) {
      this.activeCitations = citations
      this.citationDialogVisible = true
    },
    citationScore(citation) {
      const score = citation.final_score !== undefined ? citation.final_score : citation.finalScore
      return typeof score === 'number' ? score.toFixed(3) : null
    },
    formatSessionTime(value) {
      if (!value) return ''
      const date = new Date(value)
      const today = new Date()
      if (date.toDateString() === today.toDateString()) {
        return String(date.getHours()).padStart(2, '0') + ':' + String(date.getMinutes()).padStart(2, '0')
      }
      return (date.getMonth() + 1) + '-' + date.getDate()
    },
    sessionStatusText(status) {
      return {
        QUEUED: '排队中', RUNNING: '处理中', WAITING_INPUT: '待补充',
        SUCCEEDED: '已完成', INSUFFICIENT_EVIDENCE: '证据不足',
        FAILED: '失败', CANCELLED: '已取消'
      }[status] || ''
    },
    sessionStatusClass(session) {
      if (this.isRunPending(session.runStatus)) return 'working'
      if (session.runStatus === 'FAILED' || session.runStatus === 'CANCELLED') return 'error'
      if (session.runStatus === 'WAITING_INPUT' || session.runStatus === 'INSUFFICIENT_EVIDENCE') return 'warning'
      return 'complete'
    },
    formatMessageTime(value) {
      if (!value) return ''
      const date = new Date(value)
      return String(date.getHours()).padStart(2, '0') + ':' + String(date.getMinutes()).padStart(2, '0')
    }
  }
}
</script>

<style scoped>
.agent-page-container{max-width:1180px;margin-top:0}.agent-shell{display:flex;height:calc(100vh - 230px);min-height:590px;padding:0;overflow:hidden;border:1px solid #e3e6ee;box-shadow:0 18px 45px rgba(42,45,68,.09)}.conversation-rail{display:flex;flex:0 0 250px;width:250px;box-sizing:border-box;flex-direction:column;padding:22px 16px;border-right:1px solid #e5e8ef;background:#f7f8fb}.rail-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:22px}.rail-heading p{margin:0 0 5px;color:#9298a8;font-size:10px;letter-spacing:1.4px}.rail-heading h1{margin:0;color:#303447;font-size:21px;font-weight:650}.icon-button{display:inline-flex;width:34px;height:34px;align-items:center;justify-content:center;padding:0;border:1px solid #dfe2ea;border-radius:5px;color:#575b70;background:#fff;cursor:pointer;transition:border-color .18s,color .18s,background .18s}.icon-button:hover{border-color:#64609e;color:#64609e;background:#f6f5fb}.field-label{display:block;margin:0 0 7px;color:#656b79;font-size:12px;font-weight:600}.knowledge-select{width:100%}.session-heading{display:flex;align-items:center;justify-content:space-between;margin:25px 3px 8px;color:#8b91a0;font-size:11px}.session-list{min-height:0;overflow-y:auto}.session-item{display:flex;width:100%;align-items:flex-start;gap:10px;padding:11px 9px;border:0;border-bottom:1px solid #e8eaf0;color:#555b6c;background:transparent;text-align:left;cursor:pointer}.session-item>i{margin-top:3px;color:#989dad}.session-item>span{display:flex;min-width:0;flex:1;flex-direction:column;gap:4px}.session-item strong{overflow:hidden;font-size:13px;font-weight:500;text-overflow:ellipsis;white-space:nowrap}.session-item small{display:flex;min-height:14px;align-items:center;justify-content:space-between;gap:8px;color:#a0a6b2;font-size:10px}.session-item small time{margin-left:auto}.session-run-state{display:inline-flex;min-width:0;align-items:center;gap:4px;font-weight:600}.session-run-state.working{color:#4f7faf}.session-run-state.warning{color:#a8732d}.session-run-state.error{color:#b84f58}.session-run-state.complete{color:#4d886e}.session-item:hover,.session-item.active{color:#49456f;background:#eeedf6}.session-item.active>i{color:#64609e}.empty-sessions{padding:24px 8px;color:#a1a6b1;font-size:12px;text-align:center}.chat-workspace{display:flex;min-width:0;flex:1;flex-direction:column;background:#fff}.chat-header{display:flex;height:69px;box-sizing:border-box;align-items:center;gap:13px;padding:12px 22px;border-bottom:1px solid #e8eaf0}.rail-toggle{display:none}.chat-title{min-width:0;flex:1}.chat-title h2{overflow:hidden;margin:0 0 4px;color:#2f3345;font-size:17px;font-weight:650;text-overflow:ellipsis;white-space:nowrap}.chat-title p{display:flex;align-items:center;gap:6px;margin:0;color:#8e94a1;font-size:11px}.status-dot{width:7px;height:7px;border-radius:50%;background:#6fa98c}.status-dot.working{background:#5c8fc5;animation:status-pulse 1.2s infinite}.status-dot.warning{background:#d49a45}.status-dot.error{background:#cc5d65}@keyframes status-pulse{50%{opacity:.35}}.mode-switch{white-space:nowrap}.mode-switch /deep/ .el-radio-button__inner{padding:8px 12px;color:#636879;border-color:#dfe2e9;box-shadow:none}.mode-switch /deep/ .el-radio-button__orig-radio:checked+.el-radio-button__inner{border-color:#64609e;color:#fff;background:#64609e;box-shadow:-1px 0 0 0 #64609e}.message-viewport{min-height:0;flex:1;overflow-y:auto;padding:24px 32px;scroll-behavior:smooth}.chat-empty{max-width:620px;margin:78px auto 0;text-align:center}.agent-mark{display:inline-flex;width:50px;height:50px;align-items:center;justify-content:center;border:1px solid #d9dce7;border-radius:50%;color:#64609e;background:#f7f6fb;font-size:20px}.chat-empty h2{margin:17px 0 8px;color:#35394c;font-size:20px;font-weight:600}.chat-empty>p{margin:0 auto;color:#7f8694;font-size:13px;line-height:1.7}.prompt-list{display:grid;grid-template-columns:1fr;margin:24px auto 0;border-top:1px solid #e8eaf0}.prompt-list button{display:flex;align-items:center;gap:10px;padding:12px 5px;border:0;border-bottom:1px solid #e8eaf0;color:#565c6c;background:transparent;text-align:left;cursor:pointer}.prompt-list button:hover{color:#64609e;background:#fafafe}.prompt-list i{color:#a1a6b2;font-size:11px}.conversation-loading{display:flex;height:100%;align-items:center;justify-content:center;gap:8px;color:#8b91a0}.message-list{max-width:820px;margin:0 auto}.message-row{display:flex;align-items:flex-start;gap:13px;margin-bottom:24px}.message-row.user{flex-direction:row-reverse}.message-avatar{display:inline-flex;width:32px;height:32px;flex:0 0 32px;align-items:center;justify-content:center;border-radius:50%;color:#fff;background:#64609e}.message-row.user .message-avatar{background:#698b81}.message-body{min-width:0;max-width:78%}.message-meta{display:flex;align-items:center;gap:9px;margin:0 0 7px;color:#555b69;font-size:12px}.message-row.user .message-meta{justify-content:flex-end}.message-meta strong{font-weight:650}.message-meta time{color:#a1a6b0;font-size:10px}.message-body>p{margin:0;padding:12px 15px;border:1px solid #e4e7ed;border-radius:6px;color:#454b5a;background:#f8f9fb;font-size:14px;line-height:1.75;white-space:pre-wrap;word-break:break-word}.message-row.user .message-body>p{border-color:#dce9e4;background:#eef6f3}.pending-message .message-body>p{color:#747b89}.citation-trigger{margin-top:8px;padding:0;border:0;color:#64609e;background:transparent;font-size:12px;cursor:pointer}.citation-trigger:hover{text-decoration:underline}.clarification-banner{display:flex;align-items:center;gap:9px;padding:10px 24px;border-top:1px solid #efe3c9;color:#8b682f;background:#fff9ec;font-size:12px}.composer{padding:15px 22px 17px;border-top:1px solid #e7e9ef;background:#fff}.composer /deep/ textarea{resize:none;border-color:#dfe2e9;border-radius:5px;line-height:1.6}.composer /deep/ textarea:focus{border-color:#7772a9}.composer-bar{display:flex;align-items:center;justify-content:space-between;margin-top:9px}.composer-bar>span{color:#9aa0ac;font-size:10px}.composer-bar .el-button--primary{border-color:#5f5a99;background:#5f5a99}.citation-list{max-height:56vh;overflow-y:auto}.citation-item{padding:16px 0;border-bottom:1px solid #e8eaf0}.citation-item:first-child{padding-top:0}.citation-heading{display:flex;align-items:flex-start;gap:10px}.citation-heading>span{display:inline-flex;width:24px;height:24px;flex:0 0 24px;align-items:center;justify-content:center;border-radius:50%;color:#fff;background:#64609e;font-size:11px}.citation-heading>div{min-width:0;flex:1}.citation-heading h3{margin:0 0 3px;color:#343849;font-size:14px;font-weight:650}.citation-heading p{margin:0;color:#9197a4;font-size:11px}.citation-heading em{padding:3px 6px;border-radius:3px;color:#3e7965;background:#eaf4ef;font-size:10px;font-style:normal}.citation-content{margin:11px 0 7px;color:#59606e;font-size:13px;line-height:1.65;white-space:pre-wrap}.citation-item small{color:#9aa0aa;font-size:10px}
@media screen and (max-width:900px){.agent-page-container{padding:0 12px}.agent-shell{height:calc(100vh - 150px);min-height:520px;margin-bottom:16px}.conversation-rail{position:absolute;z-index:20;top:0;bottom:0;left:0;width:250px;transform:translateX(-102%);box-shadow:12px 0 28px rgba(44,47,69,.16);transition:transform .2s ease}.conversation-rail.open{transform:translateX(0)}.rail-toggle{display:inline-flex}.chat-header{padding:11px 14px}.message-viewport{padding:20px 16px}.composer{padding:12px 14px 14px}.message-body{max-width:86%}}
@media screen and (max-width:560px){.agent-page-container{padding:0 7px}.agent-shell{height:calc(100vh - 130px);min-height:500px;border-radius:4px}.chat-header{height:auto;min-height:66px;flex-wrap:wrap}.chat-title{width:calc(100% - 52px)}.mode-switch{width:100%;margin-left:47px}.mode-switch /deep/ .el-radio-button{width:33.333%}.mode-switch /deep/ .el-radio-button__inner{width:100%;padding:7px 5px}.message-viewport{padding:17px 11px}.chat-empty{margin-top:34px}.chat-empty>p{max-width:92%}.message-row{gap:8px;margin-bottom:18px}.message-avatar{width:28px;height:28px;flex-basis:28px;font-size:12px}.message-body{max-width:88%}.message-body>p{padding:10px 12px;font-size:13px}.composer-bar>span{display:none}.composer-bar{justify-content:flex-end}.clarification-banner{padding:9px 13px;line-height:1.5}.citation-dialog /deep/ .el-dialog{width:92%!important}}
</style>
