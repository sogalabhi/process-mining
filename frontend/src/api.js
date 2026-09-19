async function request(path, options) {
  const response = await fetch(path, options)
  const text = await response.text()
  let body = text
  try {
    body = JSON.parse(text)
  } catch {
    body = text
  }
  if (!response.ok) {
    const message = typeof body === 'object' && body ? body.message ?? body.detail ?? body.error : body
    throw new Error(message || `${response.status} ${response.statusText}`)
  }
  return body
}

const get = (path) => request(path)

export const api = {
  dfg: () => get('/api/discovery/dfg'),
  traces: () => get('/api/discovery/traces'),
  variants: () => get('/api/analytics/variants'),
  durations: () => get('/api/analytics/durations'),
  activities: () => get('/api/analytics/activities'),
  transitions: () => get('/api/analytics/transitions'),
  bottlenecks: (top, minFrequency) =>
    get(`/api/analytics/bottlenecks?top=${top}&minFrequency=${minFrequency}`),
  rework: () => get('/api/analytics/rework'),
  startEnd: () => get('/api/analytics/start-end'),
  throughput: (endActivity) =>
    get(`/api/analytics/throughput${endActivity ? `?endActivity=${encodeURIComponent(endActivity)}` : ''}`),
  importCsv: (file) => {
    const form = new FormData()
    form.append('file', file)
    return request('/api/import', { method: 'POST', body: form })
  },
  conformance: (expected) =>
    request('/api/conformance', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ expected }),
    }),
}
