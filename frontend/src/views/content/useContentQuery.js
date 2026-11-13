import { onScopeDispose, ref, shallowRef } from 'vue'

export function useContentQuery(request) {
  const data = shallowRef(null)
  const error = shallowRef(null)
  const loading = ref(false)
  let pending
  let disposed = false

  function clear() {
    pending?.abort()
    pending = undefined
    data.value = null
    error.value = null
    loading.value = false
  }

  async function load() {
    if (disposed) return
    pending?.abort()
    const controller = new AbortController()
    pending = controller
    loading.value = true
    error.value = null
    try {
      const result = await request({ signal: controller.signal, silent: true })
      if (!controller.signal.aborted) data.value = result
    } catch (failure) {
      if (!controller.signal.aborted) error.value = failure
    } finally {
      if (pending === controller) {
        loading.value = false
        pending = undefined
      }
    }
  }

  onScopeDispose(() => {
    disposed = true
    clear()
  })
  return { data, error, loading, load, clear }
}
