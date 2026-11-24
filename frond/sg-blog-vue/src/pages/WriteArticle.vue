<template>
  <div>
    <sg-nav></sg-nav>
    <main class="container write-page">
      <section class="write-shell tcommonBox">
        <header class="write-header"><div><p>CREATOR STUDIO</p><h1>写一篇新文章</h1><span>草稿和发布均会保存到你的个人主页。</span></div><div class="write-header-actions"><el-button plain @click="submitAs('1')">保存草稿</el-button><el-button type="primary" :loading="saving" @click="submitAs('0')">发布文章</el-button><el-button plain icon="el-icon-back" @click="$router.back()">返回</el-button></div></header>
        <el-form ref="articleForm" :model="form" :rules="rules" label-position="top" class="write-form">
          <el-form-item label="文章标题" prop="title"><el-input v-model.trim="form.title" maxlength="80" show-word-limit placeholder="给文章一个清晰、有吸引力的标题" /></el-form-item>
          <el-row :gutter="18"><el-col :md="12" :sm="24"><el-form-item label="分类" prop="categoryId"><el-select :value="form.categoryId" placeholder="选择一个分类" style="width:100%" @input="onCategoryChange" @change="onCategoryChange"><el-option v-for="item in categories" :key="item.id" :label="item.name" :value="String(item.id)" /></el-select></el-form-item></el-col><el-col :md="12" :sm="24"><el-form-item label="标签（可选）"><el-select v-model="form.tags" multiple collapse-tags placeholder="选择标签" style="width:100%"><el-option v-for="item in tags" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item></el-col></el-row>
          <el-form-item label="文章摘要"><el-input v-model.trim="form.summary" type="textarea" :rows="2" maxlength="180" show-word-limit placeholder="简要说明文章想解决的问题；留空将自动截取正文。" /></el-form-item>
          <el-form-item label="正文" prop="content"><mavon-editor v-model="form.content" class="write-editor" :toolbars="editorTools" /></el-form-item>
          <div class="write-footer"><el-radio-group v-model="form.status"><el-radio label="0">直接发布</el-radio><el-radio label="1">保存草稿</el-radio></el-radio-group><div><el-button :loading="saving" @click="submitAs('1')">保存草稿</el-button><el-button type="primary" :loading="saving" @click="submitAs('0')">发布文章</el-button></div></div>
        </el-form>
      </section>
    </main>
  </div>
</template>

<script>
import header from '../components/header.vue'
import { mavonEditor } from 'mavon-editor'
import { getCategoryList } from '../api/category'
import { getAllTags } from '../api/tag'
import { publishArticle } from '../api/article'

export default {
  name: 'WriteArticle', components: { 'sg-nav': header, 'mavon-editor': mavonEditor },
  data() { return { categories: [], tags: [], saving: false, form: { title: '', categoryId: '', tags: [], summary: '', content: '', status: '0', isTop: '0', isComment: '1' }, rules: { title: [{ required: true, message: '请输入文章标题', trigger: 'blur' }], categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }], content: [{ required: true, message: '请写下正文内容', trigger: 'change' }] }, editorTools: { bold: true, italic: true, header: true, underline: true, strikethrough: true, mark: true, superscript: true, subscript: true, quote: true, ol: true, ul: true, link: true, code: true, table: true, fullscreen: true, readmodel: true, help: true, undo: true, redo: true, trash: true, preview: true } } },
  created() {
    getCategoryList().then(data => { this.categories = data || [] }).catch(() => { this.$message.error('分类加载失败，请刷新页面后重试') })
    getAllTags().then(data => { this.tags = data || [] }).catch(() => { this.$message.error('标签加载失败，但不影响不选标签发布') })
  },
  methods: {
    onCategoryChange(value) { this.form.categoryId = value; this.clearCategoryValidation() },
    clearCategoryValidation() { this.$nextTick(() => { if (this.$refs.articleForm) this.$refs.articleForm.clearValidate('categoryId') }) },
    submitAs(status) { if (this.saving) return; this.form.status = status; this.submit() },
    submit() {
      if (this.saving) return
      this.$refs.articleForm.validate(valid => {
        if (!valid) return
        const payload = Object.assign({}, this.form, {
          categoryId: String(this.form.categoryId),
          tags: (this.form.tags || []).map(tag => String(tag))
        })
        if (!payload.summary) payload.summary = payload.content.replace(/[#>*`_\-]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 150)
        this.saving = true
        publishArticle(payload).then(() => {
          this.$message.success(payload.status === '0' ? '文章已发布' : '草稿已保存')
          const user = JSON.parse(localStorage.getItem('userInfo') || 'null')
          this.$router.push({ path: '/UserProfile', query: { userId: user && user.id != null ? String(user.id) : '' } })
        }).catch(() => {}).finally(() => { this.saving = false })
      })
    }
  }
}
</script>

<style scoped>
.write-page{ margin-top:28px }.write-shell{ padding:30px; border:1px solid #e9ecf3; box-shadow:0 16px 36px rgba(49,52,78,.08) }.write-header{ display:flex;align-items:flex-start;justify-content:space-between;margin-bottom:25px;padding-bottom:19px;border-bottom:1px solid #eceff4 }.write-header p{ margin:0 0 7px;color:#8f97b2;font-size:11px;letter-spacing:1.6px }.write-header h1{ margin:0 0 7px;color:#2e3245;font-size:26px;font-weight:650 }.write-header span{ color:#77808f;font-size:13px }.write-header-actions{display:flex;align-items:center;gap:9px;flex-wrap:wrap;justify-content:flex-end}.write-header-actions .el-button--primary,.write-footer .el-button--primary{ border-color:#5f5a99;background:#5f5a99 }.write-form ::v-deep .el-form-item__label{ color:#505769;font-weight:650 }.write-editor{ position:relative;z-index:1;min-height:440px;box-shadow:none }.write-editor ::v-deep .v-note-wrapper{ min-height:440px;border:1px solid #dfe3ee;box-shadow:none }.write-editor ::v-deep .v-note-op{ display:flex!important;visibility:visible!important;min-height:46px;background:#f7f8fc;border-bottom:1px solid #dfe3ee }.write-editor ::v-deep .v-note-op .op-icon{ color:#4f5272!important }.write-editor ::v-deep .v-note-panel{ min-height:392px }.write-footer{ position:sticky;bottom:14px;z-index:4;display:flex;align-items:center;justify-content:space-between;margin-top:22px;padding:16px 0 0;border-top:1px solid #edf0f4;background:rgba(255,255,255,.96);box-shadow:0 -10px 18px rgba(255,255,255,.9) }@media screen and (max-width:700px){.write-shell{padding:19px}.write-header{gap:14px;flex-direction:column}.write-header h1{font-size:22px}.write-header-actions{justify-content:flex-start}.write-header .el-button{padding:8px}.write-footer{align-items:flex-start;flex-direction:column;gap:15px}.write-editor,.write-editor ::v-deep .v-note-wrapper{min-height:330px}.write-editor ::v-deep .v-note-panel{min-height:282px}}
</style>
