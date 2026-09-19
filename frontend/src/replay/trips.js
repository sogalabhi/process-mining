import { edgeKey } from '../graph.js'

export function buildTrips(traces) {
  const trips = []
  const caseEnds = []
  const reworkCases = new Set()

  for (const trace of traces) {
    const { caseId, events } = trace
    const seen = new Set()
    for (const event of events) {
      if (seen.has(event.activity)) reworkCases.add(caseId)
      seen.add(event.activity)
    }
    for (let i = 0; i < events.length - 1; i++) {
      const from = events[i]
      const to = events[i + 1]
      if (to.time <= from.time) continue
      trips.push({ caseId, edge: edgeKey(from.activity, to.activity), from: from.activity, start: from.time, end: to.time })
    }
    caseEnds.push(events[events.length - 1].time)
  }

  trips.sort((a, b) => a.start - b.start)
  caseEnds.sort((a, b) => a - b)
  return { trips, caseEnds, reworkCases }
}

export function countAtOrBefore(sorted, time) {
  let low = 0
  let high = sorted.length
  while (low < high) {
    const middle = (low + high) >> 1
    if (sorted[middle] <= time) low = middle + 1
    else high = middle
  }
  return low
}

export function createSweep(trips) {
  let pointer = 0
  let active = []
  let last = -Infinity

  return {
    at(now) {
      if (now < last) {
        pointer = 0
        active = []
      }
      last = now
      while (pointer < trips.length && trips[pointer].start <= now) {
        active.push(trips[pointer])
        pointer++
      }
      active = active.filter((trip) => trip.end > now)
      return active
    },
    nextStart() {
      return pointer < trips.length ? trips[pointer].start : null
    },
  }
}
