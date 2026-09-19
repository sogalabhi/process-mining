export function roundedPath(points, radius = 10) {
  if (points.length < 2) return ''
  let d = `M ${points[0].x} ${points[0].y}`
  for (let i = 1; i < points.length - 1; i++) {
    const previous = points[i - 1]
    const current = points[i]
    const next = points[i + 1]
    const inLength = Math.hypot(current.x - previous.x, current.y - previous.y)
    const outLength = Math.hypot(next.x - current.x, next.y - current.y)
    const r = Math.min(radius, inLength / 2, outLength / 2)
    const before = {
      x: current.x - ((current.x - previous.x) / inLength) * r,
      y: current.y - ((current.y - previous.y) / inLength) * r,
    }
    const after = {
      x: current.x + ((next.x - current.x) / outLength) * r,
      y: current.y + ((next.y - current.y) / outLength) * r,
    }
    d += ` L ${before.x} ${before.y} Q ${current.x} ${current.y} ${after.x} ${after.y}`
  }
  const last = points[points.length - 1]
  return `${d} L ${last.x} ${last.y}`
}

export function polyline(points) {
  const lengths = [0]
  for (let i = 1; i < points.length; i++) {
    lengths.push(lengths[i - 1] + Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y))
  }
  const total = lengths[lengths.length - 1]

  function pointAt(fraction) {
    const target = Math.max(0, Math.min(1, fraction)) * total
    let i = 1
    while (i < lengths.length - 1 && lengths[i] < target) i++
    const segment = lengths[i] - lengths[i - 1] || 1
    const t = (target - lengths[i - 1]) / segment
    const a = points[i - 1]
    const b = points[i]
    return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t, angle: Math.atan2(b.y - a.y, b.x - a.x) }
  }

  return { total, pointAt }
}

export function arrowHead(points, size = 7) {
  const tip = points[points.length - 1]
  const from = points[points.length - 2]
  const angle = Math.atan2(tip.y - from.y, tip.x - from.x)
  const left = { x: tip.x - size * Math.cos(angle - 0.45), y: tip.y - size * Math.sin(angle - 0.45) }
  const right = { x: tip.x - size * Math.cos(angle + 0.45), y: tip.y - size * Math.sin(angle + 0.45) }
  return `M ${tip.x} ${tip.y} L ${left.x} ${left.y} L ${right.x} ${right.y} Z`
}
