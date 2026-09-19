import { useEffect, useMemo, useState } from 'react'
import { api } from '../api.js'
import { formatCount, formatDate } from '../format.js'
import { processOrder } from '../graph.js'
import { Panel } from './ui.jsx'

const DAY = 86_400_000
const CELL = 15
const GAP = 3
const WEEKDAYS = ['Mon', '', 'Wed', '', 'Fri', '', 'Sun']

function mondayOf(time) {
  const date = new Date(time)
  const day = (date.getUTCDay() + 6) % 7
  return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()) - day * DAY
}

export function ThroughputCalendar({ data }) {
  const order = useMemo(() => processOrder(data.traces, data.dfg.activities), [data])
  const [endActivity, setEndActivity] = useState(order[order.length - 1])
  const [throughput, setThroughput] = useState(null)

  useEffect(() => {
    let cancelled = false
    api.throughput(endActivity).then((result) => {
      if (!cancelled) setThroughput(result)
    })
    return () => {
      cancelled = true
    }
  }, [endActivity, data])

  const view = useMemo(() => {
    if (!throughput || throughput.perDay.length === 0) return null
    const counts = new Map(throughput.perDay.map((d) => [Date.parse(`${d.date}T00:00:00Z`), d.completed]))
    const days = [...counts.keys()].sort((a, b) => a - b)
    const first = mondayOf(days[0])
    const last = days[days.length - 1]
    const weeks = Math.floor((last - first) / DAY / 7) + 1
    const values = [...counts.values()].sort((a, b) => a - b)
    const quantile = (q) => values[Math.min(values.length - 1, Math.floor(q * values.length))]
    const cuts = [quantile(0.25), quantile(0.5), quantile(0.75)]
    const level = (count) => (count ? 1 + cuts.filter((cut) => count > cut).length : 0)

    const cells = []
    for (let w = 0; w < weeks; w++) {
      for (let d = 0; d < 7; d++) {
        const time = first + (w * 7 + d) * DAY
        if (time > last) continue
        cells.push({ time, w, d, count: counts.get(time) ?? 0 })
      }
    }

    let weekday = 0
    let weekdayDays = 0
    let weekend = 0
    let weekendDays = 0
    for (const cell of cells) {
      if (cell.d >= 5) {
        weekend += cell.count
        weekendDays++
      } else {
        weekday += cell.count
        weekdayDays++
      }
    }
    const busiest = cells.reduce((best, cell) => (cell.count > best.count ? cell : best), cells[0])

    return {
      cells,
      weeks,
      level,
      busiest,
      weekdayAverage: weekdayDays ? weekday / weekdayDays : 0,
      weekendAverage: weekendDays ? weekend / weekendDays : 0,
    }
  }, [throughput])

  const shades = [0, 0.2, 0.4, 0.62, 0.9]

  return (
    <Panel
      title="Throughput"
      note="cases completed per day (UTC)"
      actions={
        <label className="flex items-center gap-2 text-xs text-muted">
          ending at
          <select
            value={endActivity}
            onChange={(event) => setEndActivity(event.target.value)}
            className="rounded-md border border-border bg-paper px-2 py-1 text-xs text-ink outline-none focus:border-ink"
          >
            {order.map((activity) => (
              <option key={activity} value={activity}>
                {activity}
              </option>
            ))}
          </select>
        </label>
      }
    >
      {!view && <div className="label py-8 text-center">No cases end at {endActivity}</div>}
      {view && (
        <>
          <div className="overflow-x-auto">
            <svg width={32 + view.weeks * (CELL + GAP)} height={7 * (CELL + GAP) + 18} className="block">
              {WEEKDAYS.map((label, d) => (
                <text key={d} x={0} y={18 + d * (CELL + GAP) + CELL / 2} dominantBaseline="middle" className="num" fontSize="10" fill="var(--muted)">
                  {label}
                </text>
              ))}
              {view.cells
                .filter((cell) => cell.d === 0 && (cell.w === 0 || new Date(cell.time).getUTCDate() <= 7))
                .map((cell) => (
                  <text key={cell.time} x={32 + cell.w * (CELL + GAP)} y={10} className="num" fontSize="10" fill="var(--muted)">
                    {formatDate(cell.time)}
                  </text>
                ))}
              {view.cells.map((cell) => (
                <rect
                  key={cell.time}
                  x={32 + cell.w * (CELL + GAP)}
                  y={18 + cell.d * (CELL + GAP)}
                  width={CELL}
                  height={CELL}
                  rx={2}
                  fill={cell.count ? 'var(--ink)' : 'var(--faint)'}
                  fillOpacity={cell.count ? shades[view.level(cell.count)] : 1}
                >
                  <title>
                    {formatDate(cell.time)}: {formatCount(cell.count)} completed
                  </title>
                </rect>
              ))}
            </svg>
          </div>
          <div className="mt-4 grid grid-cols-3 gap-4">
            <div>
              <div className="label">Completed</div>
              <div className="num mt-0.5 text-sm text-ink">
                {formatCount(throughput.completedCases)} / {formatCount(throughput.totalCases)}
              </div>
            </div>
            <div>
              <div className="label">Weekday avg</div>
              <div className="num mt-0.5 text-sm text-ink">{formatCount(Math.round(view.weekdayAverage))}/day</div>
            </div>
            <div>
              <div className="label">Weekend avg</div>
              <div className="num mt-0.5 text-sm text-ink">{formatCount(Math.round(view.weekendAverage))}/day</div>
            </div>
          </div>
          <p className="mt-3 text-xs text-muted">
            Busiest day: {formatDate(view.busiest.time)} with {formatCount(view.busiest.count)} cases.
          </p>
        </>
      )}
    </Panel>
  )
}
