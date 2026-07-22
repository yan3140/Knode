<template>
  <div class="dashboard-container">
    <section class="welcome-panel">
      <div>
        <p class="eyebrow">YANBLOG WORKSPACE</p>
        <h1>{{ greeting }}，{{ name || '管理员' }}</h1>
        <p class="welcome-copy">从这里开始今天的内容维护。保持表达，持续记录。</p>
      </div>
      <button class="write-button" type="button" @click="$router.push('/write')">
        <i class="el-icon-edit-outline" />
        写一篇文章
      </button>
    </section>

    <section class="workspace-section">
      <div class="section-heading">
        <div>
          <p class="eyebrow">QUICK ACCESS</p>
          <h2>快捷入口</h2>
        </div>
        <span>常用操作集中在这里</span>
      </div>

      <div class="quick-grid">
        <button
          v-for="item in quickActions"
          :key="item.title"
          class="quick-card"
          type="button"
          @click="go(item)"
        >
          <span class="quick-icon" :class="item.tone"><i :class="item.icon" /></span>
          <span class="quick-content">
            <strong>{{ item.title }}</strong>
            <small>{{ item.description }}</small>
          </span>
          <i class="el-icon-right quick-arrow" />
        </button>
      </div>
    </section>

    <section class="bottom-grid">
      <div class="focus-card">
        <div class="section-heading compact">
          <div>
            <p class="eyebrow">TODAY'S FOCUS</p>
            <h2>内容工作流</h2>
          </div>
        </div>
        <ol class="workflow-list">
          <li><span>01</span><div><strong>整理选题</strong><small>明确这篇文章要解决的问题</small></div></li>
          <li><span>02</span><div><strong>完成创作</strong><small>补齐摘要、分类与封面信息</small></div></li>
          <li><span>03</span><div><strong>发布检查</strong><small>在博客前台检查最终阅读体验</small></div></li>
        </ol>
      </div>

      <div class="site-card">
        <span class="status"><i /> 站点工作台</span>
        <h2>去前台看看内容呈现</h2>
        <p>发布后及时检查标题、封面、排版和链接，让管理端与阅读端形成完整闭环。</p>
        <a :href="blogUrl">访问 YanBlog <i class="el-icon-right" /></a>
      </div>
    </section>
  </div>
</template>

<script>
import { mapGetters } from 'vuex'

export default {
  name: 'Dashboard',
  data() {
    return {
      blogUrl: process.env.VUE_APP_BLOG_URL || '/',
      quickActions: [
        { title: '开始写作', description: '创建并编辑一篇新文章', icon: 'el-icon-edit-outline', path: '/write', tone: 'coral' },
        { title: '文章管理', description: '维护文章与发布状态', icon: 'el-icon-document', path: '/content/article', tone: 'blue' },
        { title: '分类管理', description: '整理文章分类与展示结构', icon: 'el-icon-collection-tag', path: '/content/category', tone: 'green' },
        { title: '系统管理', description: '管理用户、角色与菜单', icon: 'el-icon-setting', path: '/system/user', tone: 'amber' }
      ]
    }
  },
  computed: {
    ...mapGetters(['name']),
    greeting() {
      const hour = new Date().getHours()
      if (hour < 11) return '早上好'
      if (hour < 14) return '中午好'
      if (hour < 18) return '下午好'
      return '晚上好'
    }
  },
  methods: {
    go(item) {
      const matched = this.$router.match(item.path).matched.length > 0
      if (item.path === '/write' || matched) {
        this.$router.push(item.path)
      } else {
        this.$message.info('当前账号暂无该入口或模块尚未加载')
      }
    }
  }
}
</script>

<style lang="scss" scoped>
$accent: #d97757;
$ink: #242723;
$muted: #7a7d76;
$border: #e5e0d8;
$surface: #fffdfa;

.dashboard-container { padding: 30px; }

.welcome-panel {
  position: relative;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 30px;
  min-height: 220px;
  padding: 42px 46px;
  overflow: hidden;
  border-radius: 14px;
  color: #f8f5ef;
  background: #1b2024;

  &::after {
    content: '';
    position: absolute;
    width: 250px;
    height: 250px;
    right: -70px;
    top: -105px;
    border: 1px solid rgba(217, 119, 87, 0.35);
    border-radius: 50%;
    box-shadow: 0 0 0 55px rgba(217, 119, 87, 0.04), 0 0 0 110px rgba(217, 119, 87, 0.025);
  }

  > * { position: relative; z-index: 1; }
  h1 { margin: 12px 0 10px; font-size: clamp(30px, 4vw, 44px); font-weight: 580; letter-spacing: -0.04em; }
}

.eyebrow { margin: 0; color: $accent; font-size: 11px; font-weight: 700; letter-spacing: .17em; }
.welcome-copy { margin: 0; color: rgba(248, 245, 239, .6); font-size: 14px; }

.write-button {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 13px 18px;
  border: 0;
  border-radius: 9px;
  color: #fff;
  background: $accent;
  cursor: pointer;
  font-weight: 600;
  transition: transform .2s, background .2s;
  &:hover { transform: translateY(-2px); background: #c96849; }
}

.workspace-section { margin-top: 34px; }
.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 17px;
  h2 { margin: 6px 0 0; color: $ink; font-size: 21px; }
  > span { color: $muted; font-size: 13px; }
  &.compact { margin-bottom: 22px; }
}

.quick-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.quick-card {
  display: grid;
  grid-template-columns: 44px 1fr auto;
  align-items: center;
  gap: 13px;
  min-width: 0;
  padding: 20px 18px;
  border: 1px solid $border;
  border-radius: 12px;
  background: $surface;
  text-align: left;
  cursor: pointer;
  transition: border-color .2s, transform .2s, box-shadow .2s;
  &:hover { transform: translateY(-2px); border-color: #d7cfc4; box-shadow: 0 12px 28px rgba(63, 52, 42, .06); }
}
.quick-icon { display: grid; place-items: center; width: 44px; height: 44px; border-radius: 10px; font-size: 19px; }
.quick-icon.coral { color: #b95f43; background: #f8e9e3; }
.quick-icon.blue { color: #52758f; background: #e9f0f4; }
.quick-icon.green { color: #547b65; background: #e8f0ea; }
.quick-icon.amber { color: #96733d; background: #f4edde; }
.quick-content { min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.quick-content strong { color: $ink; font-size: 14px; }
.quick-content small { overflow: hidden; color: $muted; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.quick-arrow { color: #b0b1ad; }

.bottom-grid { display: grid; grid-template-columns: 1.35fr .65fr; gap: 16px; margin-top: 30px; }
.focus-card, .site-card { border: 1px solid $border; border-radius: 14px; background: $surface; }
.focus-card { padding: 28px; }
.workflow-list { display: grid; grid-template-columns: repeat(3, 1fr); gap: 0; margin: 0; padding: 0; list-style: none; }
.workflow-list li { display: flex; gap: 13px; padding: 4px 22px; border-right: 1px solid $border; &:first-child { padding-left: 0; } &:last-child { padding-right: 0; border: 0; } }
.workflow-list li > span { color: $accent; font: 600 12px/1.5 monospace; }
.workflow-list div { display: flex; flex-direction: column; gap: 8px; }
.workflow-list strong { color: $ink; font-size: 14px; }
.workflow-list small { color: $muted; font-size: 12px; line-height: 1.6; }

.site-card { padding: 30px; color: #f7f3ed; background: #30383b; }
.site-card .status { display: inline-flex; align-items: center; gap: 8px; color: #bfcbc4; font-size: 11px; letter-spacing: .08em; }
.site-card .status i { width: 7px; height: 7px; border-radius: 50%; background: #78a987; box-shadow: 0 0 0 4px rgba(120, 169, 135, .12); }
.site-card h2 { margin: 24px 0 12px; font-size: 21px; line-height: 1.45; }
.site-card p { margin: 0 0 26px; color: #aab3ae; font-size: 13px; line-height: 1.75; }
.site-card a { color: #ef9a7d; font-size: 13px; font-weight: 600; }

@media (max-width: 1200px) {
  .quick-grid { grid-template-columns: repeat(2, 1fr); }
  .bottom-grid { grid-template-columns: 1fr; }
}
@media (max-width: 700px) {
  .dashboard-container { padding: 18px; }
  .welcome-panel { align-items: flex-start; flex-direction: column; padding: 30px 24px; }
  .quick-grid { grid-template-columns: 1fr; }
  .section-heading > span { display: none; }
  .workflow-list { grid-template-columns: 1fr; gap: 20px; }
  .workflow-list li { padding: 0 0 20px; border-right: 0; border-bottom: 1px solid $border; }
  .workflow-list li:last-child { padding-bottom: 0; border-bottom: 0; }
}
</style>
