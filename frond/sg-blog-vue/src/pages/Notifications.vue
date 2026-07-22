<template>
  <div>
    <sg-nav></sg-nav>
    <main class="container notification-page">
      <section v-loading="loading" class="notification-panel tcommonBox">
        <header class="notification-header">
          <div>
            <p class="notification-eyebrow">ACTIVITY INBOX</p>
            <h1>通知中心</h1>
            <p class="notification-subtitle">关注、评论、回复与新文章发布都会汇集在这里。</p>
          </div>
          <el-button :disabled="unreadCount === 0" size="small" plain @click="markAllRead">全部标为已读</el-button>
        </header>

        <div v-if="notifications.length" class="notification-list">
          <article
            v-for="notification in notifications"
            :key="notification.id"
            class="notification-item"
            :class="{ unread: notification.isRead === 0 }"
            @click="openNotification(notification)"
            @contextmenu.prevent.stop="showContextMenu(notification, $event)"
          >
            <a class="notification-avatar" href="javascript:void(0)" @click.stop="openActor(notification)">
              <img :src="notification.actorAvatar || defaultAvatar" :onerror="$store.state.errorImg" alt="">
            </a>
            <div class="notification-content">
              <div class="notification-topline">
                <a href="javascript:void(0)" @click.stop="openActor(notification)">{{ notification.actorNickName || '系统' }}</a>
                <span class="notification-kind">{{ typeLabel(notification.type) }}</span>
                <em v-if="notification.isRead === 0">未读</em>
                <time>{{ formatTime(notification.createTime) }}</time>
              </div>
              <p class="notification-message">{{ messageOf(notification) }}</p>
              <p v-if="notification.articleTitle" class="notification-article">《{{ notification.articleTitle }}》</p>
              <div v-if="isCommentNotification(notification)" class="notification-actions" @click.stop>
                <el-button type="text" size="mini" icon="el-icon-chat-dot-round" @click="toggleReply(notification)">直接回复</el-button>
                <el-button type="text" size="mini" @click="openNotification(notification)">查看评论</el-button>
              </div>
              <div v-if="replyingId === notification.id" class="inline-reply" @click.stop>
                <el-input v-model.trim="replyContent" type="textarea" :rows="2" maxlength="300" show-word-limit placeholder="回复这条评论…" />
                <div class="inline-reply-actions">
                  <el-button size="mini" @click="closeReply">取消</el-button>
                  <el-button type="primary" size="mini" :loading="replying" @click="sendInlineReply(notification)">发送回复</el-button>
                </div>
              </div>
            </div>
            <i v-if="notification.articleId || notification.type === 'FOLLOWED'" class="el-icon-arrow-right notification-arrow"></i>
          </article>
        </div>

        <div v-else-if="!loading" class="notification-empty">
          <i class="fa fa-bell-o"></i>
          <h2>暂时没有新通知</h2>
          <p>关注作者、发表评论或发布文章后，互动会在这里出现。</p>
        </div>

        <el-pagination
          v-if="total > pageSize"
          background
          layout="prev, pager, next"
          :current-page="pageNum"
          :page-size="pageSize"
          :total="total"
          @current-change="changePage"
        />
      </section>
    </main>

    <div v-if="contextMenu" class="notification-context-menu" :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }" @click.stop>
      <button type="button" @click="removeNotification(contextMenu.notification)"><i class="el-icon-delete"></i> 删除通知</button>
    </div>
  </div>
</template>

<script>
import header from '../components/header.vue'
import { sendComment } from '../api/comment'
import { deleteNotification, getNotifications, getUnreadCount, markAllNotificationsRead, markNotificationRead } from '../api/social'

export default {
  name: 'Notifications',
  components: { 'sg-nav': header },
  data() {
    return {
      notifications: [], unreadCount: 0, total: 0, pageNum: 1, pageSize: 10, loading: false,
      replyingId: null, replyContent: '', replying: false, contextMenu: null,
      defaultAvatar: 'static/img/tou.jpg'
    }
  },
  created() {
    this.fetchNotifications()
    document.addEventListener('click', this.closeContextMenu)
  },
  beforeDestroy() { document.removeEventListener('click', this.closeContextMenu) },
  methods: {
    fetchNotifications() {
      this.loading = true
      Promise.all([getNotifications({ pageNum: this.pageNum, pageSize: this.pageSize }), getUnreadCount()])
        .then(([response, unread]) => {
          this.notifications = response.rows || []
          this.total = response.total || 0
          this.unreadCount = unread.unreadCount || 0
        }).finally(() => { this.loading = false })
    },
    changePage(page) { this.pageNum = page; this.fetchNotifications() },
    markAllRead() {
      markAllNotificationsRead().then(() => {
        this.notifications.forEach(item => { item.isRead = 1 })
        this.unreadCount = 0
        window.dispatchEvent(new Event('notification-read'))
      })
    },
    markRead(notification, callback) {
      if (notification.isRead === 1) return callback()
      markNotificationRead(notification.id).then(() => {
        notification.isRead = 1
        this.unreadCount = Math.max(0, this.unreadCount - 1)
        window.dispatchEvent(new Event('notification-read'))
        callback()
      })
    },
    openNotification(notification) {
      this.closeContextMenu()
      this.markRead(notification, () => {
        if (notification.type === 'FOLLOWED') return this.openActor(notification)
        if (!notification.articleId) return
        const query = { aid: notification.articleId }
        if (notification.commentId) query.commentId = notification.commentId
        this.$router.push({ path: '/DetailArticle', query })
      })
    },
    openActor(notification) {
      if (notification.actorId) this.$router.push({ path: '/UserProfile', query: { userId: notification.actorId } })
    },
    isCommentNotification(notification) { return notification.type === 'COMMENT' || notification.type === 'COMMENT_REPLY' },
    toggleReply(notification) {
      this.replyingId = this.replyingId === notification.id ? null : notification.id
      this.replyContent = ''
    },
    closeReply() { this.replyingId = null; this.replyContent = '' },
    sendInlineReply(notification) {
      if (!this.replyContent) return this.$message.warning('请输入回复内容')
      if (!notification.articleId || !notification.commentId || !notification.actorId) return this.$message.error('这条通知缺少评论上下文，无法直接回复')
      const rootId = notification.rootCommentId && notification.rootCommentId !== -1 ? notification.rootCommentId : notification.commentId
      this.replying = true
      sendComment('0', notification.articleId, rootId, notification.commentId, notification.actorId, this.replyContent)
        .then(() => { this.$message.success('回复已发送'); this.closeReply() })
        .finally(() => { this.replying = false })
    },
    showContextMenu(notification, event) {
      this.contextMenu = { notification, x: Math.min(event.clientX, window.innerWidth - 150), y: Math.min(event.clientY, window.innerHeight - 48) }
    },
    closeContextMenu() { this.contextMenu = null },
    removeNotification(notification) {
      this.closeContextMenu()
      this.$confirm('删除后无法恢复，确定删除这条通知吗？', '删除通知', { type: 'warning' }).then(() => {
        return deleteNotification(notification.id).then(() => {
          const index = this.notifications.findIndex(item => item.id === notification.id)
          if (index !== -1) this.notifications.splice(index, 1)
          this.total = Math.max(0, this.total - 1)
          if (notification.isRead === 0) this.unreadCount = Math.max(0, this.unreadCount - 1)
          this.$message.success('通知已删除')
        })
      }).catch(() => {})
    },
    typeLabel(type) {
      return { FOLLOWED: '关注了你', COMMENT: '评论了文章', COMMENT_REPLY: '回复了你', ARTICLE_PUBLISHED: '发布了文章' }[type] || '发来通知'
    },
    messageOf(notification) {
      if (notification.type === 'FOLLOWED') return '成为了你的新粉丝，去看看他的主页吧。'
      if (notification.type === 'ARTICLE_PUBLISHED') return '发布了一篇新文章，点击即可阅读。'
      if (notification.type === 'COMMENT') return '评论内容：' + (notification.content || '')
      if (notification.type === 'COMMENT_REPLY') return '回复内容：' + (notification.content || '')
      return notification.content || '向你发送了一条新通知'
    },
    formatTime(value) {
      if (!value) return ''
      const date = new Date(value)
      if (Number.isNaN(date.getTime())) return value
      const pad = number => String(number).padStart(2, '0')
      return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes())
    }
  }
}
</script>

<style scoped>
.notification-page { margin-top: 28px; }.notification-panel { min-height: 410px; padding: 30px; }.notification-header { display:flex;align-items:flex-start;justify-content:space-between;gap:20px;padding-bottom:22px;border-bottom:1px solid #edf0f4; }.notification-eyebrow { margin:0 0 7px;color:#9aa1ae;font-size:12px;letter-spacing:1.5px; }.notification-header h1 { margin:0;color:#2d3142;font-size:28px;font-weight:600; }.notification-subtitle { margin:9px 0 0;color:#7c8391;font-size:14px; }.notification-list { margin-top:4px; }.notification-item { display:flex;width:100%;align-items:flex-start;gap:14px;padding:18px 6px;border-bottom:1px solid #edf0f4;cursor:pointer;transition:background .18s ease,transform .18s ease; }.notification-item:hover { background:#f7f7fb;transform:translateX(2px); }.notification-item.unread { background:linear-gradient(90deg,#f4f3fb 0%,rgba(244,243,251,0) 75%); }.notification-avatar { flex:0 0 42px;width:42px;height:42px;overflow:hidden;border-radius:50%;background:#eef0f5; }.notification-avatar img { width:100%;height:100%;object-fit:cover; }.notification-content { display:flex;min-width:0;flex:1;flex-direction:column;gap:5px; }.notification-topline { display:flex;align-items:center;gap:8px;min-width:0; }.notification-topline a { overflow:hidden;color:#3c4050;font-size:14px;font-weight:700;text-overflow:ellipsis;white-space:nowrap; }.notification-kind { color:#7c8391;font-size:13px; }.notification-topline em { padding:2px 6px;border-radius:9px;color:#64609e;background:#e9e7f5;font-size:11px;font-style:normal; }.notification-topline time { margin-left:auto;color:#a8aeba;font-size:12px;white-space:nowrap; }.notification-message,.notification-article { margin:0;font-size:14px;line-height:1.6; }.notification-message { display:-webkit-box;overflow:hidden;color:#626976;-webkit-box-orient:vertical;-webkit-line-clamp:2; }.notification-article { overflow:hidden;color:#64609e;font-weight:600;text-overflow:ellipsis;white-space:nowrap; }.notification-actions { display:flex;gap:8px;margin-top:2px; }.notification-actions .el-button { padding:0;color:#64609e; }.inline-reply { margin-top:8px;padding:10px;border:1px solid #e3e6f0;border-radius:8px;background:#fafaff; }.inline-reply-actions { display:flex;justify-content:flex-end;gap:8px;margin-top:8px; }.notification-arrow { align-self:center;color:#b0b5c0; }.notification-context-menu { position:fixed;z-index:3000;width:144px;padding:5px;border:1px solid #e3e5ed;border-radius:8px;background:#fff;box-shadow:0 12px 30px rgba(40,43,65,.18); }.notification-context-menu button { width:100%;padding:8px;border:0;border-radius:5px;color:#cf4d57;background:transparent;text-align:left;cursor:pointer; }.notification-context-menu button:hover { background:#fff1f2; }.notification-empty { padding:92px 10px;color:#9aa1ae;text-align:center; }.notification-empty i { color:#bab7d9;font-size:42px; }.notification-empty h2 { margin:16px 0 8px;color:#5b6070;font-size:18px; }.notification-empty p { margin:0;font-size:14px; }.el-pagination { margin-top:22px;text-align:center; }@media screen and (max-width:700px){.notification-panel{padding:20px}.notification-header{align-items:stretch;flex-direction:column}.notification-header .el-button{align-self:flex-start}.notification-topline{flex-wrap:wrap}.notification-topline time{margin-left:0}.notification-item{gap:10px}.notification-avatar{flex-basis:36px;width:36px;height:36px}}
</style>
