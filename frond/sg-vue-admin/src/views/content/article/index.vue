<template>
  <div class="app-container">
    <el-row :gutter="20">
      <el-col :span="24" :xs="24">
        <el-form
          v-show="showSearch"
          ref="queryForm"
          :model="queryParams"
          :inline="true"
          label-width="68px"
        >
          <el-form-item label="博客标题" prop="title">
            <el-input
              v-model="queryParams.title"
              placeholder="请输入博客标题"
              clearable
              size="small"
              style="width: 240px"
              @keyup.enter.native="handleQuery"
            />
          </el-form-item>
          <el-form-item label="摘要" prop="summary">
            <el-input
              v-model="queryParams.summary"
              placeholder="请输入摘要"
              clearable
              size="small"
              style="width: 240px"
              @keyup.enter.native="handleQuery"
            />
          </el-form-item>
          <el-form-item label="分类" prop="categoryId">
            <el-select v-model="queryParams.categoryId" placeholder="全部分类" clearable size="small">
              <el-option
                v-for="category in categoryList"
                :key="category.id"
                :label="category.name"
                :value="category.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select v-model="queryParams.status" placeholder="全部状态" clearable size="small">
              <el-option label="已发布" value="0" />
              <el-option label="草稿" value="1" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              icon="el-icon-search"
              size="mini"
              @click="handleQuery"
            >搜索</el-button>
          </el-form-item>
        </el-form>

        <el-row :gutter="10" class="mb8">
          <el-col :span="1.5">
            <el-button
              type="primary"
              plain
              icon="el-icon-plus"
              size="mini"
              @click="handleAdd"
            >新增</el-button>
          </el-col>
          <el-col :span="1.5">
            <el-button
              type="danger"
              plain
              icon="el-icon-delete"
              size="mini"
              @click="handleDelete"
            >删除</el-button>
          </el-col>

          <!-- <right-toolbar
            :show-search.sync="showSearch"
            :columns="columns"
            @queryTable="getList"
          /> -->
        </el-row>

        <el-table :data="articleList" style="width: 100%" @selection-change="handleSelectionChange">
          <el-table-column type="selection" width="55" />
          <el-table-column prop="id" label="博文ID" align="center" />
          <el-table-column prop="title" label="标题" align="center" />
          <el-table-column prop="summary" label="摘要" align="center" />
          <el-table-column prop="categoryName" label="分类" align="center" />
          <el-table-column label="状态" align="center">
            <template slot-scope="scope">
              {{ scope.row.status === '0' ? '已发布' : '草稿' }}
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" align="center" />

          <el-table-column
            label="操作"
            align="center"
            width="200"
            class-name="small-padding fixed-width"
          >
            <template slot-scope="scope">
              <el-button
                size="mini"
                type="text"
                icon="el-icon-edit"
                @click="handleUpdate(scope.row)"
              >修改</el-button>
              <el-button
                size="mini"
                type="text"
                @click="handleStatus(scope.row, scope.row.status === '0' ? '1' : '0')"
              >{{ scope.row.status === '0' ? '下架' : '发布' }}</el-button>
              <el-button
                size="mini"
                type="text"
                icon="el-icon-delete"
                @click="handleDelete(scope.row)"
              >删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-col>
    </el-row>
    <el-pagination
      :page-size.sync="queryParams.pageSize"
      layout="total, sizes, prev, pager, next, jumper"
      :total="total"
      :page-sizes="[10, 20, 30, 40]"
      :current-page.sync="queryParams.pageNum"
      @current-change="getList"
      @size-change="getList"
    />

  </div>
</template>

<script>
// import { getToken } from '@/utils/auth'
import {
  listArticle,
  delArticle,
  updateArticleStatus
}
from '@/api/content/article'
import { listAllCategory } from '@/api/content/category'

export default {
  name: 'Article',
  data() {
    return {
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        title: undefined,
        summary: undefined,
        categoryId: undefined,
        status: undefined
      },
      title: '',
      // 是否显示弹出层
      open: false,
      // 总条数
      total: 0,
      articleList: [],
      categoryList: [],
      showSearch: true
    }
  },
  watch: {},
  created() {
    this.getList()
    this.getCategoryList()
  },
  methods: {
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.single = selection.length !== 1
      this.multiple = !selection.length
    },
    // 取消按钮
    cancel() {
      this.open = false
      this.reset()
    },
    /** 查询用户列表 */
    getList() {
      this.loading = true
      listArticle(this.queryParams).then((response) => {
        this.articleList = response.rows
        this.total = response.total
        this.loading = false
      })
    },
    getCategoryList() {
      listAllCategory().then(response => {
        this.categoryList = response
      })
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.$router.push('/write?id=' + row.id)
    },
    /** 新增用户 */
    handleAdd() {
      this.$router.push('/write')
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const ids = row.id || this.ids
      this.$modal.confirm('是否确认删除分类编号为"' + ids + '"的数据项？').then(function() {
        return delArticle(ids)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess('删除成功')
      }).catch(() => {})
    },
    handleStatus(row, status) {
      const action = status === '0' ? '发布' : '下架'
      this.$modal.confirm('是否确认' + action + '文章“' + row.title + '”？').then(() => {
        return updateArticleStatus(row.id, status)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess(action + '成功')
      }).catch(() => {})
    }
  }
}
</script>
