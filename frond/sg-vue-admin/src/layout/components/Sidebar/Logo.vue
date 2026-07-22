<template>
  <div class="sidebar-logo-container" :class="{ collapse }">
    <transition name="sidebarLogoFade">
      <router-link :key="collapse ? 'collapse' : 'expand'" class="sidebar-logo-link" to="/">
        <span class="sidebar-logo">Y</span>
        <span v-if="!collapse" class="sidebar-copy">
          <strong>YanBlog</strong>
          <small>CONTENT STUDIO</small>
        </span>
      </router-link>
    </transition>
  </div>
</template>

<script>
export default {
  name: 'SidebarLogo',
  props: { collapse: { type: Boolean, required: true } }
}
</script>

<style lang="scss" scoped>
.sidebarLogoFade-enter-active { transition: opacity .4s; }
.sidebarLogoFade-enter, .sidebarLogoFade-leave-to { opacity: 0; }
.sidebar-logo-container { height: 66px; overflow: hidden; background: #171b1f; }
.sidebar-logo-link { display: flex !important; align-items: center; gap: 12px; height: 100%; padding: 0 20px; }
.sidebar-logo { flex: 0 0 auto; display: grid; place-items: center; width: 34px; height: 34px; border-radius: 10px; color: #fff; background: #d97757; font: 600 20px/1 Georgia, serif; }
.sidebar-copy { display: flex; flex-direction: column; gap: 4px; min-width: 0; color: #f6f3ee; }
.sidebar-copy strong { font-size: 15px; letter-spacing: .02em; }
.sidebar-copy small { color: #777f83; font-size: 8px; letter-spacing: .18em; }
.collapse .sidebar-logo-link { justify-content: center; padding: 0; }
</style>
