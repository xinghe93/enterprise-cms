import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteArticle, submitArticle } from '../../api/content.js'
import 'element-plus/es/components/message-box/style/css.mjs'

export function useArticleActions() {
  const busy = ref(false)

  async function changeArticle(article, action) {
    if (busy.value) return false
    const deleting = action === 'delete'
    busy.value = true
    try {
      await ElMessageBox.confirm(
        deleting ? `确定删除《${article.title}》？` : `确定将《${article.title}》提交审核？`,
        deleting ? '删除文章' : '提交审核',
        { type: 'warning', confirmButtonText: deleting ? '删除' : '提交', cancelButtonText: '取消' },
      )
      await (deleting ? deleteArticle : submitArticle)(article.id, article.version)
      ElMessage.success(deleting ? '文章已删除' : '文章已提交审核')
      return true
    } catch {
      // 取消确认时保持原数据；请求失败由公共客户端提示。
      return false
    } finally {
      busy.value = false
    }
  }

  return { busy, changeArticle }
}
