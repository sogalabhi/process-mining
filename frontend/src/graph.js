import { durationScale } from './colors.js'

export const START = '__start__'
export const END = '__end__'

export const edgeKey = (from, to) => `${from}→${to}`

export function processOrder(traces, activities) {
  const positions = new Map(activities.map((activity) => [activity, []]))
  for (const trace of traces) {
    const seen = new Set()
    trace.events.forEach((event, index) => {
      if (seen.has(event.activity)) return
      seen.add(event.activity)
      positions.get(event.activity)?.push(index / Math.max(1, trace.events.length - 1))
    })
  }
  const mean = (values) => (values.length ? values.reduce((a, b) => a + b, 0) / values.length : 1)
  return [...activities].sort((a, b) => mean(positions.get(a)) - mean(positions.get(b)) || a.localeCompare(b))
}

export function buildModel(data) {
  const order = processOrder(data.traces, data.dfg.activities)
  const rank = new Map(order.map((activity, index) => [activity, index]))
  const stats = new Map(data.transitions.map((t) => [edgeKey(t.from, t.to), t]))
  const activityStats = new Map(data.activities.map((a) => [a.activity, a]))
  const rework = new Map(data.rework.activities.map((a) => [a.activity, a]))

  const steps = data.dfg.transitions.map((t) => {
    const id = edgeKey(t.from, t.to)
    return {
      id,
      source: t.from,
      target: t.to,
      count: t.count,
      kind: 'step',
      duration: stats.get(id)?.duration ?? null,
      isRework: t.from === t.to || rank.get(t.to) < rank.get(t.from),
    }
  })

  const starts = Object.entries(data.dfg.startActivities).map(([activity, count]) => ({
    id: edgeKey(START, activity),
    source: START,
    target: activity,
    count,
    kind: 'start',
  }))

  const ends = Object.entries(data.dfg.endActivities).map(([activity, count]) => ({
    id: edgeKey(activity, END),
    source: activity,
    target: END,
    count,
    kind: 'end',
  }))

  const nodes = [
    { id: START, kind: 'start', count: data.traces.length },
    ...order.map((activity) => ({
      id: activity,
      kind: 'activity',
      stats: activityStats.get(activity),
      rework: rework.get(activity) ?? null,
    })),
    { id: END, kind: 'end', count: data.traces.length },
  ]

  const maxCount = Math.max(1, ...steps.map((s) => s.count))
  const level = durationScale(steps.map((s) => s.duration?.avgSeconds ?? 0))

  return { order, nodes, edges: [...starts, ...steps, ...ends], maxCount, level }
}
