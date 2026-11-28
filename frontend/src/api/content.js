import { api } from './index.js'
import { isContentId } from '../views/content/contentModel.js'

const categories = '/admin/categories'
const tags = '/admin/tags'

function pathId(id) {
  if (!isContentId(id)) throw new TypeError(`业务 ID 必须是十进制字符串：${String(id)}`)
  return encodeURIComponent(id)
}

export const listCategories = (params, config) => api.get(categories, { ...config, params })
export const createCategory = (data) => api.post(categories, data)
export const updateCategory = (id, data) => api.put(`${categories}/${pathId(id)}`, data)
export const deleteCategory = (id) => api.delete(`${categories}/${pathId(id)}`)
export const setCategoryStatus = (id, status) => api.put(`${categories}/${pathId(id)}/status`, { status })

export const listTags = (params, config) => api.get(tags, { ...config, params })
export const createTag = (data) => api.post(tags, data)
export const updateTag = (id, data) => api.put(`${tags}/${pathId(id)}`, data)
export const deleteTag = (id) => api.delete(`${tags}/${pathId(id)}`)
export const setTagStatus = (id, status) => api.put(`${tags}/${pathId(id)}/status`, { status })

const articles = '/admin/articles'
const portalArticles = '/portal/articles'

export const listArticles = (params, config) => api.get(articles, { ...config, params })
export const getArticle = (id, config) => api.get(`${articles}/${pathId(id)}`, config)
export const createArticle = (data) => api.post(articles, data)
export const updateArticle = (id, data) => api.put(`${articles}/${pathId(id)}`, data)
export const deleteArticle = (id, version) => api.delete(`${articles}/${pathId(id)}`, { params: { version } })
export const submitArticle = (id, version) => api.post(`${articles}/${pathId(id)}/submit`, { version })

export const listPortalArticles = (params, config) => api.get(portalArticles, { ...config, params })
export const getPortalArticle = (id, config) => api.get(`${portalArticles}/${pathId(id)}`, config)
export const listPortalCategories = (config) => api.get('/portal/categories', config)
export const listPortalTags = (params, config) => api.get('/portal/tags', { ...config, params })
