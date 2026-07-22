<template>
  <div>
    <sg-nav></sg-nav>
    <main v-loading="loading" class="container profile-page">
      <section v-if="profile.id" class="profile-hero">
        <div class="profile-orbit orbit-one"></div><div class="profile-orbit orbit-two"></div>
        <img class="profile-avatar" :src="profile.avatar || defaultAvatar" :onerror="$store.state.errorImg" alt="用户头像">
        <div class="profile-identity">
          <p>CREATOR PROFILE</p>
          <h1>{{ profile.nickName }}</h1>
          <span>加入 YanBlog · {{ formatDate(profile.createTime) }}</span>
        </div>
        <div class="profile-actions">
          <el-button v-if="isSelf" type="primary" icon="el-icon-edit" @click="$router.push('/WriteArticle')">写博客</el-button>
          <el-button v-else-if="hasLogin" :type="following ? 'default' : 'primary'" :icon="following ? 'el-icon-check' : 'el-icon-plus'" @click="toggleFollow">{{ following ? '取消关注' : '关注' }}</el-button>
          <el-button v-else plain @click="goLogin">登录后关注</el-button>
        </div>
        <div class="profile-stats"><span><strong>{{ profile.followerCount || 0 }}</strong> 粉丝</span><span><strong>{{ profile.followingCount || 0 }}</strong> 关注</span><span><strong>{{ articleTotal }}</strong> 文章</span></div>
      </section>

      <el-row :gutter="24">
        <el-col :md="16" :sm="24">
          <section class="profile-section tcommonBox">
            <div class="section-title"><div><p>PUBLICATIONS</p><h2>{{ isSelf ? '我发布的文章' : 'TA 发布的文章' }}</h2></div><span>{{ articleTotal }} 篇</span></div>
            <article v-for="article in articles" :key="article.id" class="profile-article" @click="openArticle(article.id)">
              <img v-if="article.thumbnail" :src="article.thumbnail" :onerror="$store.state.errorImg" alt="">
              <div><h3>{{ article.title }}</h3><p>{{ article.summary || '作者暂未填写摘要。' }}</p><small>{{ article.categoryName || '未分类' }} · {{ article.viewCount || 0 }} 次阅读 · {{ formatDate(article.createTime) }}</small></div>
              <i class="el-icon-arrow-right"></i>
            </article>
            <div v-if="!articles.length && !loading" class="profile-empty"><i class="fa fa-file-text-o"></i><p>还没有发布文章</p><el-button v-if="isSelf" type="primary" size="small" @click="$router.push('/WriteArticle')">开始写第一篇</el-button></div>
            <el-pagination v-if="articleTotal > articlePageSize" background layout="prev, pager, next" :current-page="articlePage" :page-size="articlePageSize" :total="articleTotal" @current-change="changeArticlePage" />
          </section>
        </el-col>
        <el-col :md="8" :sm="24">
          <section class="profile-section tcommonBox following-card">
            <div class="section-title"><div><p>FOLLOWING</p><h2>{{ isSelf ? '我的关注' : 'TA 的关注' }}</h2></div><span>{{ followingTotal }}</span></div>
            <a v-for="user in followingUsers" :key="user.userId" href="javascript:void(0);" class="following-user" @click="openProfile(user.userId)">
              <img :src="user.avatar || defaultAvatar" :onerror="$store.state.errorImg" alt=""><span>{{ user.nickName }}</span>
              <el-button v-if="isSelf" size="mini" type="text" @click.stop="cancelFollow(user.userId)">取消关注</el-button>
            </a>
            <div v-if="!followingUsers.length && !loading" class="small-empty">暂无关注，去发现值得关注的作者吧。</div>
            <el-pagination v-if="followingTotal > followingPageSize" small layout="prev, next" :current-page="followingPage" :page-size="followingPageSize" :total="followingTotal" @current-change="changeFollowingPage" />
          </section>
        </el-col>
      </el-row>
    </main>
  </div>
</template>

<script>
import header from '../components/header.vue'
import { getUserProfile, getUserFollowing, followUser, getFollowStatus, unfollowUser } from '../api/social'

export default {
  name: 'UserProfile',
  components: { 'sg-nav': header },
  data() {
    return { profile: {}, articles: [], articleTotal: 0, articlePage: 1, articlePageSize: 8, followingUsers: [], followingTotal: 0, followingPage: 1, followingPageSize: 8, loading: false, following: false, defaultAvatar: 'static/img/tou.jpg' }
  },
  computed: {
    profileId() { return Number(this.$route.query.userId) || 0 },
    currentUser() { return JSON.parse(localStorage.getItem('userInfo') || 'null') },
    hasLogin() { return !!this.currentUser },
    isSelf() { return this.hasLogin && Number(this.currentUser.id) === this.profileId }
  },
  watch: { '$route.query.userId': 'loadProfile' },
  created() { this.loadProfile() },
  methods: {
    loadProfile() {
      if (!this.profileId) { this.$router.replace('/'); return }
      this.loading = true
      getUserProfile(this.profileId, { pageNum: this.articlePage, pageSize: this.articlePageSize }).then(response => {
        this.profile = response
        this.articles = (response.articles && response.articles.rows) || []
        this.articleTotal = (response.articles && response.articles.total) || 0
        this.loadFollowing()
        if (this.hasLogin && !this.isSelf) this.loadFollowStatus()
      }).finally(() => { this.loading = false })
    },
    loadFollowing() {
      getUserFollowing(this.profileId, { pageNum: this.followingPage, pageSize: this.followingPageSize }).then(response => {
        this.followingUsers = response.rows || []; this.followingTotal = response.total || 0
      })
    },
    loadFollowStatus() { getFollowStatus(this.profileId).then(response => { this.following = response.following }).catch(() => { this.following = false }) },
    toggleFollow() { (this.following ? unfollowUser(this.profileId) : followUser(this.profileId)).then(() => { this.following = !this.following; this.loadProfile() }) },
    cancelFollow(userId) { unfollowUser(userId).then(() => { this.$message.success('已取消关注'); this.loadFollowing(); this.loadProfile() }) },
    changeArticlePage(page) { this.articlePage = page; this.loadProfile() },
    changeFollowingPage(page) { this.followingPage = page; this.loadFollowing() },
    openProfile(userId) { this.$router.push({ path: '/UserProfile', query: { userId } }) },
    openArticle(id) { this.$router.push({ path: '/DetailArticle', query: { aid: id } }) },
    goLogin() { this.$router.push({ name: 'Login', query: { login: '1', redirect: this.$route.fullPath } }) },
    formatDate(value) { return value ? String(value).slice(0, 10) : '—' }
  }
}
</script>

<style scoped>
.profile-page{ margin-top:28px; }.profile-hero{ position:relative; display:grid; grid-template-columns:86px 1fr auto; align-items:center; gap:20px; overflow:hidden; margin-bottom:24px; padding:28px 34px; border-radius:14px; color:#eef4ff; background:radial-gradient(circle at 85% 10%,rgba(125,221,255,.25),transparent 28%),linear-gradient(135deg,#171b35,#4c477e); box-shadow:0 18px 38px rgba(37,36,75,.24); }.profile-orbit{ position:absolute; border:1px solid rgba(170,225,255,.18); border-radius:50%; pointer-events:none }.orbit-one{ width:310px;height:310px;right:-130px;top:-170px }.orbit-two{ width:210px;height:210px;right:110px;bottom:-155px }.profile-avatar{ z-index:1;width:82px;height:82px;border:3px solid rgba(218,246,255,.84);border-radius:50%;object-fit:cover;box-shadow:0 8px 22px rgba(0,0,0,.25) }.profile-identity,.profile-actions,.profile-stats{ z-index:1 }.profile-identity p,.section-title p{ margin:0 0 7px; color:#aeb6dc; font-size:11px; letter-spacing:1.5px }.profile-identity h1{ margin:0 0 7px;font-size:28px;font-weight:650 }.profile-identity span{ color:#d0d9ed;font-size:13px }.profile-actions .el-button{ border:0;font-weight:700 }.profile-stats{ grid-column:2/4;display:flex;gap:30px;margin-top:-8px;color:#bac6e2;font-size:13px }.profile-stats strong{ display:block;color:#fff;font-size:19px }.profile-section{ padding:24px;min-height:260px;border:1px solid #ebedf4;box-shadow:0 10px 28px rgba(51,54,79,.06) }.section-title{ display:flex;align-items:flex-start;justify-content:space-between;padding-bottom:16px;border-bottom:1px solid #edf0f4 }.section-title h2{ margin:0;color:#303547;font-size:20px;font-weight:650 }.section-title>span{ padding:4px 9px;border-radius:11px;color:#65609b;background:#f0eff9;font-size:12px }.profile-article{ display:flex;align-items:center;gap:15px;padding:18px 2px;border-bottom:1px solid #edf0f4;cursor:pointer;transition:.2s }.profile-article:hover{ transform:translateX(4px);background:#fafaff }.profile-article img{ flex:0 0 80px;width:80px;height:60px;border-radius:7px;object-fit:cover }.profile-article>div{ min-width:0;flex:1 }.profile-article h3{ overflow:hidden;margin:0 0 7px;color:#35394b;font-size:16px;font-weight:650;text-overflow:ellipsis;white-space:nowrap }.profile-article p{ display:-webkit-box;overflow:hidden;margin:0 0 8px;color:#777e8d;font-size:13px;line-height:1.5;-webkit-line-clamp:2;-webkit-box-orient:vertical }.profile-article small{ color:#a0a6b2 }.profile-article>i{ color:#b2b7c3 }.following-user{ display:flex;align-items:center;gap:10px;padding:13px 0;border-bottom:1px solid #edf0f4;color:#4c5162 }.following-user img{ width:36px;height:36px;border-radius:50%;object-fit:cover }.following-user span{ flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:600 }.profile-empty,.small-empty{ padding:56px 8px;color:#9ba2af;text-align:center }.profile-empty i{ font-size:32px;color:#c2bee1 }.profile-empty p{ margin:12px 0 }.small-empty{ padding:34px 8px;font-size:13px;line-height:1.8 }.el-pagination{ margin-top:20px;text-align:center }@media screen and (max-width:700px){.profile-hero{grid-template-columns:62px 1fr;padding:22px 18px;gap:14px}.profile-avatar{width:60px;height:60px}.profile-identity h1{font-size:22px}.profile-actions{grid-column:1/3}.profile-stats{grid-column:1/3;gap:20px;margin-top:0}.profile-section{margin-bottom:18px;padding:19px}.profile-article img{display:none}.profile-article{padding:15px 0}}
</style>
