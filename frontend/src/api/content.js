import { api } from './index.js'

const categories = '/admin/categories'
const tags = '/admin/tags'

export const listCategories = (params, config) => api.get(categories, { ...config, params })
export const createCategory = (data) => api.post(categories, data)
export const updateCategory = (id, data) => api.put(`${categories}/${id}`, data)
export const deleteCategory = (id) => api.delete(`${categories}/${id}`)
export const setCategoryStatus = (id, status) => api.put(`${categories}/${id}/status`, { status })

export const listTags = (params, config) => api.get(tags, { ...config, params })
export const createTag = (data) => api.post(tags, data)
export const updateTag = (id, data) => api.put(`${tags}/${id}`, data)
export const deleteTag = (id) => api.delete(`${tags}/${id}`)
export const setTagStatus = (id, status) => api.put(`${tags}/${id}/status`, { status })
