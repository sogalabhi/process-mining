import { useMemo, useState } from 'react'
import { durationScale } from '../colors.js'
import { formatDateTime, formatDuration } from '../format.js'
import { processOrder } from '../graph.js'
import { useSelection } from '../selection.jsx'
import { useWidth } from '../useWidth.js'
import { Panel, Segmented } from './ui.jsx'

const ROWS = 24
const ROW_HEIGHT = 22
const LABEL_WIDTH = 92
const TICK_STEPS = [900, 1800, 3600, 7200, 10800, 21600, 43200, 86400, 172800, 604800]

function hasRework(trace) {
  const seen = new Set()
  for (const event of trace.events) {
    if (seen.has(event.activity)) return true
    seen.add(event.activity)
  }
  return false
}

const duration = (trace) => (trace.events[trace.events.length - 1].time - trace.events[0].time) / 1000

export function CaseTimeline({ data, colors }) {
  const { selection, select } = useSelection()
  const [mode, setMode] = useState('longest')
  const [ref, width] = useWidth()
  const finalActivity = useMemo(() => {
    const order = processOrder(data.traces, data.dfg.activities)
    return order[order.length - 1]
  }, [data])
  const level = useMemo(() => durationScale(data.transitions.map((t) => t.duration.avgSeconds)), [data])

  const rows = useMemo(() => {
    let pool = data.traces
    if (mode === 'rework') pool = pool.filter(hasRework)
    if (mode === 'stopped') pool = pool.filter((t) => t.events[t.events.length - 1].activity !== finalActivity)
    return [...pool].sort((a, b) => duration(b) - duration(a) || a.caseId.localeCompare(b.caseId)).slice(0, ROWS)
  }, [data, mode, finalActivity])

  const maxSeconds = Math.max(1, ...rows.map(duration))
  const plotWidth = Math.max(0, width - LABEL_WIDTH - 70)
  const x = (seconds) => LABEL_WIDTH + (seconds / maxSeconds) * plotWidth
  const tickStep = TICK_STEPS.find((step) => maxSeconds / step <= 6) ?? TICK_STEPS[TICK_STEPS.length - 1]
  const ticks = Array.from({ length: Math.floor(maxSeconds / tickStep) + 1 }, (_, i) => i * tickStep)

  return (
    <Panel
      title="Cases"
      note="each row is one case, time since it started · click a row to trace it"
      actions={
        <Segmented
          label="Which cases"
          value={mode}
          onChange={setMode}
          options={[
            { value: 'longest', label: 'Longest' },
            { value: 'rework', label: 'With rework' },
            { value: 'stopped', label: `Not ${finalActivity}` },
          ]}
        />
      }
    >
      <div ref={ref} className="w-full">
        {width > 0 && (
          <svg width={width} height={rows.length * ROW_HEIGHT + 28} className="block">
            {ticks.map((tick) => (
              <g key={tick}>
                <line x1={x(tick)} x2={x(tick)} y1={0} y2={rows.length * ROW_HEIGHT} stroke="var(--border)" />
                <text x={x(tick)} y={rows.length * ROW_HEIGHT + 16} textAnchor="middle" className="num" fontSize="10" fill="var(--muted)">
                  {formatDuration(tick)}
                </text>
              </g>
            ))}
            {rows.map((trace, row) => {
              const start = trace.events[0].time
              const y = row * ROW_HEIGHT + ROW_HEIGHT / 2
              const selected = selection?.type === 'case' && selection.id === trace.caseId
              return (
                <g key={trace.caseId} onClick={() => select('case', trace.caseId)} className="cursor-pointer">
                  <rect x={0} y={row * ROW_HEIGHT} width={width} height={ROW_HEIGHT} fill={selected ? 'var(--faint)' : 'transparent'} />
                  <text x={4} y={y} dominantBaseline="middle" className="num" fontSize="11" fill={selected ? 'var(--ink)' : 'var(--muted)'}>
                    {trace.caseId}
                  </text>
                  {trace.events.slice(1).map((event, index) => {
                    const previous = trace.events[index]
                    const gap = (event.time - previous.time) / 1000
                    return (
                      <line
                        key={index}
                        x1={x((previous.time - start) / 1000)}
                        x2={x((event.time - start) / 1000)}
                        y1={y}
                        y2={y}
                        stroke={`var(--dur-${level(gap)})`}
                        strokeWidth={4}
                      >
                        <title>
                          {previous.activity} → {event.activity}: {formatDuration(gap)}
                        </title>
                      </line>
                    )
                  })}
                  {trace.events.map((event, index) => (
                    <circle
                      key={index}
                      cx={x((event.time - start) / 1000)}
                      cy={y}
                      r={4}
                      fill={colors.get(event.activity)}
                      stroke="var(--surface)"
                      strokeWidth={1.5}
                    >
                      <title>
                        {event.activity} · {formatDateTime(event.time)}
                      </title>
                    </circle>
                  ))}
                  <text x={x(duration(trace)) + 8} y={y} dominantBaseline="middle" className="num" fontSize="10" fill="var(--muted)">
                    {formatDuration(duration(trace))}
                  </text>
                </g>
              )
            })}
          </svg>
        )}
      </div>
    </Panel>
  )
}
