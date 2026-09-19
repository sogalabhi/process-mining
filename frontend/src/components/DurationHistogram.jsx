import { useMemo } from 'react'
import { formatCount, formatDuration } from '../format.js'
import { useWidth } from '../useWidth.js'
import { Panel } from './ui.jsx'

const HOUR = 3600
const STEPS = [HOUR, 2 * HOUR, 3 * HOUR, 4 * HOUR, 6 * HOUR, 12 * HOUR, 24 * HOUR, 48 * HOUR]

function buildBins(cases) {
  const sorted = cases.map((c) => c.durationSeconds).sort((a, b) => a - b)
  const p99 = sorted[Math.floor(sorted.length * 0.99)] ?? 0
  const step = STEPS.find((s) => p99 / s <= 26) ?? STEPS[STEPS.length - 1]
  const count = Math.max(1, Math.ceil(p99 / step))
  const bins = Array.from({ length: count + 1 }, (_, i) => ({ from: i * step, count: 0, overflow: i === count }))
  for (const seconds of sorted) bins[Math.min(count, Math.floor(seconds / step))].count++
  return { bins, step, domain: (count + 1) * step }
}

export function DurationHistogram({ data }) {
  const [ref, width] = useWidth()
  const { stats, cases } = data.durations
  const { bins, step, domain } = useMemo(() => buildBins(cases), [cases])
  const height = 220
  const margin = { top: 34, right: 12, bottom: 26, left: 36 }
  const innerWidth = Math.max(0, width - margin.left - margin.right)
  const innerHeight = height - margin.top - margin.bottom
  const maxCount = Math.max(1, ...bins.map((b) => b.count))
  const x = (seconds) => margin.left + (Math.min(seconds, domain) / domain) * innerWidth
  const y = (count) => margin.top + innerHeight - (count / maxCount) * innerHeight
  const barWidth = innerWidth / bins.length
  const labelEvery = Math.ceil(bins.length / Math.max(1, Math.floor(innerWidth / 64)))

  const markers = [
    { label: 'median', seconds: stats.medianSeconds, dash: undefined, row: 0 },
    { label: 'avg', seconds: stats.avgSeconds, dash: '1 3', row: 1 },
    { label: 'P90', seconds: stats.p90Seconds, dash: '4 3', row: 0 },
    { label: 'P95', seconds: stats.p95Seconds, dash: '4 3', row: 1 },
  ]

  return (
    <Panel title="Case duration" note={`first to last event · ${formatDuration(step)} buckets`}>
      <div ref={ref} className="w-full">
        {width > 0 && (
          <svg width={width} height={height} className="block">
            {[0.5, 1].map((t) => (
              <g key={t}>
                <line x1={margin.left} x2={width - margin.right} y1={y(maxCount * t)} y2={y(maxCount * t)} stroke="var(--border)" />
                <text x={margin.left - 6} y={y(maxCount * t)} textAnchor="end" dominantBaseline="middle" className="num" fontSize="10" fill="var(--muted)">
                  {formatCount(Math.round(maxCount * t))}
                </text>
              </g>
            ))}
            {bins.map((bin, index) => (
              <rect
                key={index}
                x={margin.left + index * barWidth + 1}
                y={y(bin.count)}
                width={Math.max(1, barWidth - 2)}
                height={margin.top + innerHeight - y(bin.count)}
                fill="var(--muted)"
                opacity={bin.overflow ? 0.3 : 0.5}
              >
                <title>
                  {bin.overflow ? `≥ ${formatDuration(bin.from)}` : `${formatDuration(bin.from)} – ${formatDuration(bin.from + step)}`}: {formatCount(bin.count)} cases
                </title>
              </rect>
            ))}
            <line x1={margin.left} x2={width - margin.right} y1={margin.top + innerHeight} y2={margin.top + innerHeight} stroke="var(--ink)" />
            {bins.map(
              (bin, index) =>
                index % labelEvery === 0 && (
                  <text key={index} x={margin.left + index * barWidth} y={height - 8} className="num" fontSize="10" fill="var(--muted)">
                    {formatDuration(bin.from)}
                  </text>
                ),
            )}
            {markers.map((marker) => (
              <g key={marker.label}>
                <line
                  x1={x(marker.seconds)}
                  x2={x(marker.seconds)}
                  y1={margin.top - 6 + marker.row * 12}
                  y2={margin.top + innerHeight}
                  stroke="var(--ink)"
                  strokeDasharray={marker.dash}
                />
                <text x={x(marker.seconds) + 4} y={margin.top - 22 + marker.row * 12} className="num" fontSize="10" fill="var(--ink)">
                  {marker.label} {formatDuration(marker.seconds)}
                </text>
              </g>
            ))}
          </svg>
        )}
      </div>
      <p className="mt-2 text-xs leading-relaxed text-muted">
        The long right tail pulls the average ({formatDuration(stats.avgSeconds)}) above the median ({formatDuration(stats.medianSeconds)}),
        which is why the median is the headline number.
      </p>
    </Panel>
  )
}
