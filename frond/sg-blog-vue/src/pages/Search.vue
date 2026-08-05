<template>
  <div>
    <sg-nav></sg-nav>
    <div class="container search-page">
      <el-row :gutter="30">
        <el-col :sm="24" :md="16">
          <section class="search-panel tcommonBox">
            <p class="search-eyebrow">ARTICLE SEARCH</p>
            <h1>搜索文章</h1>
            <el-input
              v-model.trim="keyword"
              class="search-input"
              placeholder="输入标题、摘要或正文关键词"
              clearable
              @keyup.enter.native="submitSearch"
              @clear="clearSearch"
            >
              <el-button slot="append" icon="el-icon-search" @click="submitSearch">搜索</el-button>
            </el-input>

            <div v-if="searched" class="search-summary">
              <span>“{{ keyword }}” 的搜索结果</span>
              <span v-if="!loading">共 {{ total }} 篇</span>
            </div>

            <div v-loading="loading" class="search-results">
              <article v-for="article in articles" :key="article.id" class="search-result">
                <h2>
                  <router-link :to="{ path: '/DetailArticle', query: { aid: article.id } }">
                    {{ article.title }}
                  </router-link>
                </h2>
                <p class="search-meta">
                  {{ article.categoryName || '未分类' }} · {{ article.viewCount || 0 }} 次围观
                </p>
                <p class="search-summary-text">{{ article.summary || '暂无摘要' }}</p>
                <div v-if="article.tagNames && article.tagNames.length" class="search-tags">
                  <span v-for="tag in article.tagNames" :key="tag"># {{ tag }}</span>
                </div>
              </article>
              <el-empty v-if="searched && !loading && articles.length === 0" description="没有找到相关文章" />
            </div>

            <div v-if="searched && articles.length" class="search-load-more">
              <el-button
                v-if="hasMore"
                :loading="loadingMore"
                round
                size="small"
                @click="loadMore"
              >
                {{ loadingMore ? '加载中...' : '加载更多' }}
              </el-button>
              <span v-else>已经到底啦</span>
            </div>
          </section>
        </el-col>
        <el-col :sm="24" :md="8">
          <sg-rightlist></sg-rightlist>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script>
import header from '../components/header.vue'
import rightlist from '../components/rightlist.vue'
import { searchArticles } from '../api/search'

export default {
  name: 'Search',
  components: {
    'sg-nav': header,
    'sg-rightlist': rightlist
  },
  data() {
    return {
      keyword: '',
      articles: [],
      total: 0,
      pageNum: 1,
      pageSize: 5,
      loading: false,
      loadingMore: false,
      hasMore: false,
      searched: false
    }
  },
  watch: {
    '$route.query.keyword': 'loadFromRoute'
  },
  created() {
    this.loadFromRoute()
  },
  mounted() {
    window.addEventListener('scroll', this.handleScroll)
  },
  beforeDestroy() {
    window.removeEventListener('scroll', this.handleScroll)
  },
  methods: {
    loadFromRoute() {
      this.keyword = (this.$route.query.keyword || '').trim()
      this.pageNum = 1
      if (this.keyword) {
        this.fetchResults(false)
      } else {
        this.articles = []
        this.total = 0
        this.hasMore = false
        this.searched = false
      }
    },
    submitSearch() {
      if (!this.keyword) {
        this.$message.info('请输入要搜索的关键词')
        return
      }
      this.$router.push({ path: '/Search', query: { keyword: this.keyword } })
    },
    clearSearch() {
      this.$router.push({ path: '/Search' })
    },
    loadMore() {
      if (!this.hasMore || this.loading || this.loadingMore) {
        return
      }
      this.pageNum += 1
      this.fetchResults(true)
    },
    handleScroll() {
      if (!this.searched || !this.hasMore || this.loading || this.loadingMore) {
        return
      }
      const nearBottom = window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 240
      if (nearBottom) {
        this.loadMore()
      }
    },
    fetchResults(append) {
      if (append) {
        this.loadingMore = true
      } else {
        this.loading = true
        this.articles = []
      }
      this.searched = true
      searchArticles({
        keyword: this.keyword,
        pageNum: this.pageNum,
        pageSize: this.pageSize,
        sort: 'relevance'
      }).then(response => {
        const rows = response.rows || []
        this.articles = append ? this.articles.concat(rows) : rows
        this.total = response.total || 0
        this.hasMore = this.articles.length < this.total
      }).finally(() => {
        this.loading = false
        this.loadingMore = false
      })
    }
  }
}
</script>

<style scoped>
.search-page { margin-top: 28px; }
.search-panel { padding: 30px; min-height: 420px; }
.search-eyebrow { margin: 0 0 8px; color: #a0a8b8; font-size: 12px; letter-spacing: 1.5px; }
.search-panel h1 { margin: 0 0 22px; font-size: 28px; color: #2d3142; }
.search-input { max-width: 640px; }
.search-summary { display: flex; justify-content: space-between; margin: 24px 0 8px; color: #6c7280; font-size: 14px; }
.search-result { padding: 22px 0; border-bottom: 1px solid #edf0f4; }
.search-result h2 { margin: 0 0 8px; font-size: 20px; }
.search-result h2 a { color: #353b4d; }
.search-result h2 a:hover { color: #64609e; }
.search-meta { margin: 0 0 10px; color: #9aa1ae; font-size: 13px; }
.search-summary-text { margin: 0; color: #5f6572; line-height: 1.75; }
.search-tags { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.search-tags span { padding: 3px 8px; color: #64609e; font-size: 12px; background: #f1f0f8; border-radius: 12px; }
.search-load-more { margin: 22px 0 4px; text-align: center; color: #9aa1ae; }
@media screen and (max-width: 700px) { .search-panel { padding: 20px; } .search-summary { display: block; } .search-summary span { display: block; margin-bottom: 5px; } }
</style>
