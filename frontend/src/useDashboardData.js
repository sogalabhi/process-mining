import { useCallback, useEffect, useState } from 'react'
import { api } from './api.js'

async function loadAll() {
  const [dfg, traces, variants, durations, activities, transitions, rework, startEnd] = await Promise.all([
    api.dfg(),
    api.traces(),
    api.variants(),
    api.durations(),
    api.activities(),
    api.transitions(),
    api.rework(),
    api.startEnd(),
  ])

  const parsedTraces = traces.map((trace) => ({
    caseId: trace.caseId,
    events: trace.events.map((event) => ({ activity: event.activity, time: Date.parse(event.timestamp) })),
  }))

  let start = Infinity
  let end = -Infinity
  for (const trace of parsedTraces) {
    start = Math.min(start, trace.events[0].time)
    end = Math.max(end, trace.events[trace.events.length - 1].time)
  }

  return {
    dfg,
    traces: parsedTraces,
    variants,
    durations,
    activities,
    transitions,
    rework,
    startEnd,
    span: { start, end },
  }
}

export function useDashboardData() {
  const [state, setState] = useState({ data: null, error: null, loading: true })

  const reload = useCallback(async () => {
    setState((previous) => ({ ...previous, loading: true, error: null }))
    try {
      const data = await loadAll()
      setState({ data, error: null, loading: false })
    } catch (error) {
      setState((previous) => ({ ...previous, error, loading: false }))
    }
  }, [])

  useEffect(() => {
    reload()
  }, [reload])

  return { ...state, reload }
}
