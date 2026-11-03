<template>
  <div class="navbar">
    <div class="nav-left">
      <hamburger
        :is-active="sidebar.opened"
        class="hamburger-container"
        @toggleClick="toggleSideBar"
      />
      <breadcrumb class="breadcrumb-container" />
    </div>

    <div class="right-menu">
      <a :href="blogUrl" class="visit-blog">
        <i class="el-icon-top-right" />
        <span>访问博客</span>
      </a>

      <span class="nav-divider" />

      <el-dropdown class="avatar-container" trigger="click">
        <div class="avatar-wrapper">
          <img v-if="avatar && !avatarFailed" :src="avatar" class="user-avatar" alt="用户头像" @error="avatarFailed = true">
          <span v-else class="avatar-fallback">{{ initials }}</span>
          <span class="user-copy">
            <strong>{{ name || '管理员' }}</strong>
            <small>内容管理员</small>
          </span>
          <i class="el-icon-arrow-down" />
        </div>
        <el-dropdown-menu slot="dropdown" class="user-dropdown">
          <router-link to="/">
            <el-dropdown-item><i class="el-icon-house" /> 工作台</el-dropdown-item>
          </router-link>
          <a :href="blogUrl">
            <el-dropdown-item><i class="el-icon-top-right" /> 访问博客</el-dropdown-item>
          </a>
          <el-dropdown-item divided @click.native="logout">
            <span><i class="el-icon-switch-button" /> 退出登录</span>
          </el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>
    </div>
  </div>
</template>

<script>
import { mapGetters } from 'vuex'
import Breadcrumb from '@/components/Breadcrumb'
import Hamburger from '@/components/Hamburger'

export default {
  components: { Breadcrumb, Hamburger },
  data() {
    return {
      blogUrl: process.env.VUE_APP_BLOG_URL || '/',
      avatarFailed: false
    }
  },
  computed: {
    ...mapGetters(['sidebar', 'avatar', 'name']),
    initials() {
      return (this.name || 'Y').slice(0, 1).toUpperCase()
    }
  },
  methods: {
    toggleSideBar() {
      this.$store.dispatch('app/toggleSideBar')
    },
    logout() {
      this.$confirm('确定注销并退出管理工作台吗？', '退出提示', {
        confirmButtonText: '确定退出',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        return this.$store.dispatch('LogOut').then(() => {
          this.$router.replace('/login')
        })
      }).catch(() => {})
    }
  }
}
</script>

<style lang="scss" scoped>
.navbar {
  height: 66px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 24px;
  overflow: hidden;
  background: rgba(255, 253, 250, .96);
  border-bottom: 1px solid #e8e2d9;
}
.nav-left, .right-menu { display: flex; align-items: center; height: 100%; }
.hamburger-container { display: flex; align-items: center; height: 100%; cursor: pointer; transition: background .2s; &:hover { background: #f5f1eb; } }
.breadcrumb-container { margin-left: 2px; }
.visit-blog { display: inline-flex; align-items: center; gap: 7px; padding: 8px 12px; border: 1px solid #dfd9d0; border-radius: 8px; color: #5f625d; font-size: 12px; transition: all .2s; &:hover { border-color: #d97757; color: #c86749; background: #fff8f5; } }
.nav-divider { width: 1px; height: 24px; margin: 0 18px; background: #e8e2d9; }
.avatar-wrapper { display: flex; align-items: center; gap: 10px; cursor: pointer; }
.user-avatar, .avatar-fallback { width: 36px; height: 36px; border-radius: 10px; object-fit: cover; }
.avatar-fallback { display: grid; place-items: center; color: #fff; background: #30383b; font-weight: 700; }
.user-copy { display: flex; flex-direction: column; gap: 3px; min-width: 72px; }
.user-copy strong { color: #2e312e; font-size: 12px; }
.user-copy small { color: #92948f; font-size: 10px; }
.avatar-wrapper > i { margin-left: 2px; color: #9a9c97; font-size: 11px; }
@media (max-width: 700px) {
  .navbar { padding-right: 12px; }
  .breadcrumb-container, .user-copy, .nav-divider { display: none; }
  .visit-blog span { display: none; }
  .visit-blog { margin-right: 12px; }
}
</style>
