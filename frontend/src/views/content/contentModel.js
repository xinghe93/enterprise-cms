export function categoryOptions(tree, { activeOnly = false, currentId, excludeId } = {}) {
  const options = []

  function visit(nodes, parentLabel, parentActive) {
    for (const node of nodes) {
      if (node.id === excludeId) continue
      const label = parentLabel ? `${parentLabel} / ${node.categoryName}` : node.categoryName
      const active = parentActive && node.status === 1
      if (!activeOnly || active || node.id === currentId) {
        options.push({ id: node.id, label: active ? label : `${label}（停用分支）`, active })
      }
      visit(node.children, label, active)
    }
  }

  visit(tree, '', true)
  return options
}

export function displayTime(value) {
  return value ? value.slice(0, 16).replace('T', ' ') : '—'
}

export const articleStatuses = {
  DRAFT: { label: '草稿', type: 'info' },
  PENDING_REVIEW: { label: '待审核', type: 'warning' },
  APPROVED: { label: '审核通过', type: 'primary' },
  REJECTED: { label: '已驳回', type: 'danger' },
  PUBLISHED: { label: '已发布', type: 'success' },
  OFFLINE: { label: '已下架', type: 'info' },
}

export function isEditableArticle(status) {
  return ['DRAFT', 'REJECTED', 'OFFLINE'].includes(status)
}

export function isContentId(id) {
  return typeof id === 'string' && /^[1-9][0-9]{0,18}$/.test(id)
    && (id.length < 19 || id <= '9223372036854775807')
}

export function articlePayload(form, version) {
  return {
    title: form.title.trim(),
    summary: form.summary || null,
    content: form.content,
    categoryId: form.categoryId,
    tagIds: [...form.tagIds],
    ...(version === undefined ? {} : { version }),
  }
}
