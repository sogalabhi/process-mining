export function formatDuration(seconds) {
  if (seconds == null) return '—'
  const s = Math.round(seconds)
  if (s < 60) return `${s}s`
  const minutes = Math.floor(s / 60)
  if (minutes < 60) return `${minutes}m`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return minutes % 60 ? `${hours}h ${minutes % 60}m` : `${hours}h`
  const days = Math.floor(hours / 24)
  return hours % 24 ? `${days}d ${hours % 24}h` : `${days}d`
}

export function formatCount(value) {
  return value == null ? '—' : value.toLocaleString('en-US')
}

export function formatPercent(value, digits = 1) {
  return value == null ? '—' : `${value.toFixed(digits)}%`
}

const dateFormat = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' })
const dateTimeFormat = new Intl.DateTimeFormat('en-US', {
  month: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
  timeZone: 'UTC',
})

export function formatDate(time) {
  return dateFormat.format(new Date(time))
}

export function formatDateTime(time) {
  return `${dateTimeFormat.format(new Date(time))} UTC`
}
