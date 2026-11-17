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

const articles = '/admin/articles'
const portalArticles = '/portal/articles'

export const listArticles = (params, config) => api.get(articles, { ...config, params })
export const getArticle = (id, config) => api.get(`${articles}/${id}`, config)
export const createArticle = (data) => api.post(articles, data)
export const updateArticle = (id, data) => api.put(`${articles}/${id}`, data)
export const deleteArticle = (id, version) => api.delete(`${articles}/${id}`, { params: { version } })
export const submitArticle = (id, version) => api.post(`${articles}/${id}/submit`, { version })

export const listPortalArticles = (params, config) => api.get(portalArticles, { ...config, params })
export const getPortalArticle = (id, config) => api.get(`${portalArticles}/${id}`, config)
export const listPortalCategories = (config) => api.get('/portal/categories', config)
export const listPortalTags = (params, config) => api.get('/portal/tags', { ...config, params })
