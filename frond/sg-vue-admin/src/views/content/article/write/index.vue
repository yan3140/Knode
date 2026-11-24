<template>
  <div class="app-container">

    <el-form ref="form" :model="form" label-width="90px">
      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item label="文章标题" prop="title">
            <el-input
              v-model="form.title"
              placeholder="请输入文章标题"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
        <el-col :span="6">
          <el-form-item label="分类">
            <el-select v-model="form.categoryId" placeholder="请选择">
              <el-option
                v-for="category in categoryList"
                :key="category.id"
                :label="category.name"
                :value="category.id"
              />
              <!-- <el-option :key="'1'" label="女" :value="'1'" /> -->
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="6">
          <el-form-item label="标签">
            <el-select v-model="form.tags" placeholder="请选择" multiple>
              <el-option
                v-for="tag in tagList"
                :key="tag.id"
                :label="tag.name"
                :value="tag.id"
              />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item label="文章摘要">
            <el-input v-model="form.summary" type="textarea" />
          </el-form-item>
        </el-col>
        <el-col :span="6">
          <el-form-item label="允许评论">
            <el-radio-group v-model="form.isComment">
              <el-radio :key="'1'" :label="'1'">正常</el-radio>
              <el-radio :key="'0'" :label="'0'">停用</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-col>
        <el-col :span="6">
          <el-form-item label="是否置顶">
            <el-radio-group v-model="form.isTop">
              <el-radio :key="'1'" :label="'1'">是</el-radio>
              <el-radio :key="'0'" :label="'0'">否</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-col>

      </el-row>
      <el-row :gutter="20" />

      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item label="缩略图">
            <el-upload
              :file-list="fileList"
              class="upload-demo"
              list-type="picture"
              drag
              name="img"
              action="upload"
              :on-remove="fileRemove"
              :limit="1"
              :http-request="handleUpload"
              :on-exceed="onExceed"
            >
              <i class="el-icon-upload" />
              <div class="el-upload__text">将文件拖到此处，或<em>点击上传</em></div>
              <div slot="tip" class="el-upload__tip">只能上传jpg/png文件，且不超过500kb</div>
            </el-upload>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <div v-if="loadError" class="article-load-error">
            <span>{{ loadError }}</span>
            <el-button type="text" size="mini" @click="loadArticleForCurrentId">重试加载</el-button>
          </div>
          <el-form-item>
            <el-button type="primary" size="medium" :disabled="submitting || (!!aId && !canSubmitEdit)" @click="handleSubmit">{{ aId?"更新":"发布" }}</el-button>
          </el-form-item>
          <el-form-item>
            <el-button v-if="!aId" type="info" :disabled="submitting" @click="handleSave">保存到草稿箱</el-button>
          </el-form-item>

        </el-col>
      </el-row>
      <el-row>
        <mavon-editor ref="md" v-model="form.content" @imgAdd="addImg" />
      </el-row>
    </el-form>
  </div>
</template>

<script>
import { listAllCategory } from '@/api/content/category'
import { uploadImg } from '@/api/content/upload'
import { addArticle, getArticle, updateArticle } from '@/api/content/article'
import { listAllTag } from '@/api/content/tag'

function createEmptyForm() {
  return {
    id: undefined,
    title: '',
    summary: '',
    thumbnail: '',
    isTop: '0',
    isComment: '1',
    content: '',
    categoryId: undefined,
    tags: []
  }
}

export default {
  name: 'Write',
  data() {
    return {
      form: createEmptyForm(),
      categoryList: [],
      tagList: [],
      aId: '',
      loadedArticleId: '',
      loadingArticle: false,
      loadError: '',
      loadRequestSeq: 0,
      thumbnailUploadSeq: 0,
      submitting: false,
      fileList: []
    }
  },
  computed: {
    canSubmitEdit() {
      if (!this.aId) return true
      return !this.loadingArticle &&
        !this.loadError &&
        this.loadedArticleId === this.aId &&
        String(this.form.id || '') === this.aId
    }
  },
  watch: {
    $route(route) {
      this.syncRoute(route, false)
    }
  },
  created() {
    this.getCategoryAndTag()
    this.syncRoute(this.$route, true)
  },
  methods: {
    syncRoute(route, force) {
      const raw = route && route.query && route.query.id
      const nextId = raw != null && String(raw).trim() !== '' ? String(raw).trim() : ''
      if (!force && nextId === this.aId) {
        return
      }
      this.aId = nextId
      this.resetFormForRoute()
      if (this.aId) {
        this.loadArticleForCurrentId()
      }
    },
    resetFormForRoute() {
      this.loadRequestSeq++
      this.thumbnailUploadSeq++
      this.form = createEmptyForm()
      this.fileList = []
      this.loadedArticleId = ''
      this.loadingArticle = false
      this.loadError = ''
    },
    getArticle() {
      return this.loadArticleForCurrentId()
    },
    loadArticleForCurrentId() {
      const targetId = this.aId
      if (!targetId) return Promise.resolve()
      const seq = ++this.loadRequestSeq
      this.form = createEmptyForm()
      this.fileList = []
      this.loadedArticleId = ''
      this.loadError = ''
      this.loadingArticle = true
      return getArticle(targetId).then(response => {
        if (seq !== this.loadRequestSeq || targetId !== this.aId) {
          return
        }
        if (!response || typeof response !== 'object') {
          this.loadError = '文章数据为空，请重试'
          return
        }
        const resolvedId = response.id != null ? String(response.id).trim() : targetId
        if (resolvedId !== targetId) {
          this.loadError = '返回的文章标识与当前编辑目标不一致，已禁止保存'
          return
        }
        this.form = Object.assign(createEmptyForm(), response, {
          id: targetId,
          categoryId: response.categoryId != null ? String(response.categoryId) : undefined,
          tags: Array.isArray(response.tags) ? response.tags.map(t => String(t)) : []
        })
        this.fileList = response.thumbnail ? [{ name: '缩略图', url: response.thumbnail }] : []
        this.loadedArticleId = targetId
      }).catch(err => {
        if (seq !== this.loadRequestSeq || targetId !== this.aId) {
          return
        }
        this.form = createEmptyForm()
        this.fileList = []
        this.loadedArticleId = ''
        this.loadError = (err && (err.msg || err.message)) || '文章加载失败，请重试后再编辑'
      }).finally(() => {
        if (seq === this.loadRequestSeq) {
          this.loadingArticle = false
        }
      })
    },
    buildPayload(statusOverride) {
      const payload = Object.assign({}, this.form, {
        categoryId: this.form.categoryId != null ? String(this.form.categoryId) : undefined,
        tags: Array.isArray(this.form.tags) ? this.form.tags.map(t => String(t)) : []
      })
      if (this.aId) payload.id = String(this.aId)
      else delete payload.id
      if (statusOverride != null) payload.status = statusOverride
      return payload
    },
    handleSave() {
      if (this.submitting || this.aId) return
      this.submitting = true
      const payload = this.buildPayload('1')
      addArticle(payload).then(() => {
        this.$modal.msgSuccess('保存草稿成功')
      }).catch(() => {}).finally(() => {
        this.submitting = false
      })
    },
    handleSubmit() {
      if (this.submitting) return
      if (this.aId && !this.canSubmitEdit) {
        if (this.$message && this.$message.error) {
          this.$message.error('文章尚未加载完成或加载失败，禁止提交')
        }
        return
      }
      this.submitting = true
      if (!this.aId) {
        const payload = this.buildPayload('0')
        addArticle(payload).then(() => {
          this.$modal.msgSuccess('博客发布成功')
          this.$router.push({ path: '/content/article' })
        }).catch(() => {}).finally(() => {
          this.submitting = false
        })
      } else {
        const targetId = this.aId
        const payload = this.buildPayload(null)
        if (String(payload.id || '') !== targetId || this.loadedArticleId !== targetId) {
          this.submitting = false
          return
        }
        updateArticle(payload).then(() => {
          this.$modal.msgSuccess('博客更新成功')
          this.$router.push({ path: '/content/article' })
        }).catch(() => {}).finally(() => {
          this.submitting = false
        })
      }
    },
    onExceed() {
      this.$message.error('只能上传一个图片')
    },
    handleUpload(img) {
      const seq = this.loadRequestSeq
      const targetId = this.aId
      const uploadSeq = ++this.thumbnailUploadSeq
      let aborted = false
      const handle = {
        abort: () => {
          aborted = true
          if (uploadSeq === this.thumbnailUploadSeq) {
            this.thumbnailUploadSeq++
          }
        }
      }
      uploadImg(img.file).then(response => {
        if (aborted || uploadSeq !== this.thumbnailUploadSeq || seq !== this.loadRequestSeq || targetId !== this.aId) {
          return
        }
        this.form.thumbnail = response
        this.fileList = [{ name: img.file.name, url: response }]
        if (img && typeof img.onSuccess === 'function') {
          img.onSuccess(response)
        }
      }).catch(error => {
        if (aborted || uploadSeq !== this.thumbnailUploadSeq || seq !== this.loadRequestSeq || targetId !== this.aId) {
          return
        }
        if (img && typeof img.onError === 'function') {
          img.onError(error)
        }
        this.fileList = this.form.thumbnail ? [{ name: '缩略图', url: this.form.thumbnail }] : []
        const msg = (error && (error.msg || error.message)) || '图片上传失败'
        this.$message.error(msg)
      })
      return handle
    },
    fileRemove(file, fileList) {
      this.thumbnailUploadSeq++
      this.fileList = []
      this.form.thumbnail = ''
    },
    // 绑定@imgAdd event
    addImg(pos, file) {
      const seq = this.loadRequestSeq
      const targetId = this.aId
      uploadImg(file).then(response => {
        if (seq !== this.loadRequestSeq || targetId !== this.aId) return
        this.$refs.md.$img2Url(pos, response)
      }).catch(error => {
        const msg = (error && (error.msg || error.message)) || '图片上传失败'
        this.$message.error(msg)
      })
    },
    getCategoryAndTag() {
      listAllCategory().then((response) => {
        this.categoryList = response || []
      }).catch(() => {})
      listAllTag().then(response => {
        this.tagList = response || []
      }).catch(() => {})
    },
    beforeAvatarUpload(file) {
      const isJPG = file.type === 'image/jpeg'
      const isLt2M = file.size / 1024 / 1024 < 2

      if (!isJPG) {
        this.$message.error('上传头像图片只能是 JPG 格式!')
      }
      if (!isLt2M) {
        this.$message.error('上传头像图片大小不能超过 2MB!')
      }
      return isJPG && isLt2M
    }
  }
}
</script>
<style scoped>
div .upload-demo {
    /* padding-left: 80px; */
}
.el-col .el-upload {
    border: 1px dashed #d9d9d9;
    border-radius: 6px;
    cursor: pointer;
    position: relative;

    overflow: hidden;
  }
  .avatar-uploader .el-upload:hover {
    border-color: #409EFF;
  }
  .avatar-uploader-icon {
    font-size: 28px;
    color: #8c939d;
    width: 178px;
    height: 178px;
    line-height: 178px;
    text-align: center;
  }
  .avatar {
    width: 178px;
    height: 178px;
    display: block;
  }
</style>
