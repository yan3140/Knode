<template>
  <div class="login-page">
    <section class="brand-panel">
      <a :href="blogUrl" class="brand-mark" aria-label="返回 YanBlog 首页">
        <span class="brand-symbol">Y</span>
        <span>YanBlog</span>
      </a>
      <div class="brand-copy">
        <p class="eyebrow">CONTENT WORKSPACE</p>
        <h1>让每一次创作，<br>都更专注一点。</h1>
        <p class="description">文章、分类、评论与站点配置，都在一个安静清晰的工作台里完成。</p>
        <ul class="feature-list">
          <li><i class="el-icon-edit-outline" /> 专注内容创作与发布</li>
          <li><i class="el-icon-data-analysis" /> 统一管理站点内容</li>
          <li><i class="el-icon-lock" /> 独立的管理员权限入口</li>
        </ul>
      </div>
      <p class="brand-footer">YanBlog · 管理工作台</p>
    </section>

    <main class="login-main">
      <div class="login-card">
        <a :href="blogUrl" class="back-link"><i class="el-icon-back" /> 返回博客首页</a>
        <div class="title-container">
          <p class="eyebrow">ADMIN SIGN IN</p>
          <h2>管理员登录</h2>
          <p>使用管理账号进入内容工作台</p>
        </div>

        <el-form
          ref="loginForm"
          :model="loginForm"
          :rules="loginRules"
          class="login-form"
          auto-complete="on"
          label-position="top"
        >
          <el-form-item label="账号" prop="userName">
            <el-input
              ref="userName"
              v-model="loginForm.userName"
              placeholder="请输入管理员账号"
              name="userName"
              type="text"
              tabindex="1"
              auto-complete="username"
              prefix-icon="el-icon-user"
            />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              ref="password"
              v-model="loginForm.password"
              :type="passwordType"
              placeholder="请输入密码"
              name="password"
              tabindex="2"
              auto-complete="current-password"
              prefix-icon="el-icon-lock"
              @keyup.enter.native="handleLogin"
            >
              <i
                slot="suffix"
                :class="passwordType === 'password' ? 'el-icon-view' : 'el-icon-minus'"
                class="password-toggle"
                @click="showPwd"
              />
            </el-input>
          </el-form-item>

          <el-button
            :loading="loading"
            type="primary"
            class="login-button"
            @click.native.prevent="handleLogin"
          >进入管理工作台</el-button>
        </el-form>

        <div class="identity-tip">
          <span>普通用户？</span>
          <a :href="userLoginUrl">前往用户登录</a>
        </div>
      </div>
    </main>
  </div>
</template>

<script>
import { validUsername } from '@/utils/validate'

export default {
  name: 'Login',
  data() {
    const validateUsername = (rule, value, callback) => {
      if (!validUsername(value)) {
        callback(new Error('请输入管理员账号'))
      } else {
        callback()
      }
    }
    const validatePassword = (rule, value, callback) => {
      if (!value) {
        callback(new Error('请输入密码'))
      } else {
        callback()
      }
    }
    return {
      loginForm: {
        userName: '',
        password: ''
      },
      loginRules: {
        userName: [{ required: true, trigger: 'blur', validator: validateUsername }],
        password: [{ required: true, trigger: 'blur', validator: validatePassword }]
      },
      loading: false,
      passwordType: 'password',
      redirect: undefined,
      blogUrl: process.env.VUE_APP_BLOG_URL || '/'
    }
  },
  computed: {
    userLoginUrl() {
      return `${this.blogUrl.replace(/\/$/, '')}/#/Login?login=1`
    }
  },
  watch: {
    $route: {
      handler(route) {
        this.redirect = route.query && route.query.redirect
      },
      immediate: true
    }
  },
  methods: {
    showPwd() {
      this.passwordType = this.passwordType === 'password' ? 'text' : 'password'
      this.$nextTick(() => this.$refs.password.focus())
    },
    handleLogin() {
      this.$refs.loginForm.validate((valid) => {
        if (!valid) return false
        this.loading = true
        this.$store.dispatch('Login', this.loginForm).then(() => {
          this.$router.push({ path: this.redirect || '/' })
          this.loading = false
        }).catch(() => {
          this.loading = false
        })
      })
    }
  }
}
</script>

<style lang="scss" scoped>
$ink: #1c2328;
$accent: #d97757;
$canvas: #f4f1eb;

.login-page {
  min-height: 100%;
  display: grid;
  grid-template-columns: minmax(360px, 0.95fr) minmax(500px, 1.05fr);
  color: $ink;
  background: $canvas;
}

.brand-panel {
  min-height: 100vh;
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 44px clamp(44px, 6vw, 92px);
  overflow: hidden;
  color: #f8f5ef;
  background: #1b2024;

  &::after {
    content: '';
    position: absolute;
    width: 360px;
    height: 360px;
    right: -170px;
    bottom: -130px;
    border: 1px solid rgba(217, 119, 87, 0.26);
    border-radius: 50%;
    box-shadow: 0 0 0 70px rgba(217, 119, 87, 0.035), 0 0 0 140px rgba(217, 119, 87, 0.025);
  }
}

.brand-mark {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  gap: 12px;
  width: max-content;
  font: 600 20px/1 Arial, sans-serif;
  letter-spacing: 0.02em;
}

.brand-symbol {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 11px;
  background: $accent;
  color: #fff;
  font-family: Georgia, serif;
  font-size: 22px;
}

.brand-copy {
  position: relative;
  z-index: 1;
  margin: auto 0;
  max-width: 540px;

  h1 {
    margin: 16px 0 22px;
    font-family: Georgia, 'Noto Serif SC', serif;
    font-size: clamp(38px, 4.6vw, 64px);
    line-height: 1.16;
    font-weight: 500;
    letter-spacing: -0.04em;
  }
}

.eyebrow {
  margin: 0;
  color: $accent;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.18em;
}

.description {
  max-width: 440px;
  margin: 0;
  color: rgba(248, 245, 239, 0.64);
  font-size: 15px;
  line-height: 1.9;
}

.feature-list {
  display: grid;
  gap: 15px;
  margin: 38px 0 0;
  padding: 0;
  list-style: none;
  color: rgba(248, 245, 239, 0.82);
  font-size: 14px;

  i {
    width: 25px;
    color: $accent;
  }
}

.brand-footer {
  position: relative;
  z-index: 1;
  margin: 0;
  color: rgba(248, 245, 239, 0.38);
  font-size: 12px;
  letter-spacing: 0.08em;
}

.login-main {
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: 48px;
}

.login-card {
  width: min(100%, 430px);
  padding: 40px;
  border: 1px solid #e4ded4;
  border-radius: 16px;
  background: rgba(255, 253, 249, 0.92);
  box-shadow: 0 22px 70px rgba(59, 49, 38, 0.08);
}

.back-link {
  display: inline-flex;
  gap: 7px;
  align-items: center;
  margin-bottom: 48px;
  color: #7a7c76;
  font-size: 13px;

  &:hover { color: $accent; }
}

.title-container {
  margin-bottom: 32px;

  h2 {
    margin: 10px 0 8px;
    font-size: 30px;
    font-weight: 650;
    letter-spacing: -0.03em;
  }

  > p:last-child {
    margin: 0;
    color: #7b7e78;
    font-size: 14px;
  }
}

.login-form {
  ::v-deep .el-form-item { margin-bottom: 24px; }
  ::v-deep .el-form-item__label {
    padding: 0 0 8px;
    color: #3f433f;
    font-size: 13px;
    line-height: 1;
  }
  ::v-deep .el-input__inner {
    height: 46px;
    border-color: #ded8cf;
    border-radius: 9px;
    background: #fffefb;
  }
  ::v-deep .el-input__inner:focus { border-color: $accent; }
}

.password-toggle {
  cursor: pointer;
  line-height: 46px;
}

.login-button {
  width: 100%;
  height: 46px;
  margin-top: 6px;
  border-color: $accent;
  border-radius: 9px;
  background: $accent;
  font-weight: 600;
  letter-spacing: 0.04em;

  &:hover, &:focus { border-color: #c96849; background: #c96849; }
}

.identity-tip {
  margin-top: 26px;
  padding-top: 22px;
  border-top: 1px solid #ebe5dc;
  color: #8a8c86;
  text-align: center;
  font-size: 13px;

  a { margin-left: 6px; color: $accent; font-weight: 600; }
}

@media (max-width: 900px) {
  .login-page { grid-template-columns: 1fr; }
  .brand-panel { min-height: auto; padding: 28px 32px; }
  .brand-copy, .brand-footer { display: none; }
  .login-main { place-items: start center; min-height: calc(100vh - 94px); padding: 20px; }
  .login-card { padding: 32px 26px; }
  .back-link { margin-bottom: 34px; }
}
</style>
