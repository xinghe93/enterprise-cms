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
