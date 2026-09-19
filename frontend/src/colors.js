const ACTIVITY_SLOTS = 8

export function activityColorMap(activities) {
  const map = new Map()
  activities.forEach((activity, index) => {
    map.set(activity, `var(--act-${(index % ACTIVITY_SLOTS) + 1})`)
  })
  return map
}

export function durationScale(values) {
  const positive = values.filter((value) => value > 0)
  if (positive.length === 0) return () => 1
  const low = Math.log(Math.min(...positive))
  const high = Math.log(Math.max(...positive))
  return (seconds) => {
    if (!(seconds > 0) || high === low) return 1
    const t = (Math.log(seconds) - low) / (high - low)
    return Math.min(5, 1 + Math.floor(t * 5))
  }
}
