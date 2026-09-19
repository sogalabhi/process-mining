import { useMemo, useState } from 'react'
import { formatCount, formatPercent } from '../format.js'
import { processOrder } from '../graph.js'
import { useSelection } from '../selection.jsx'
import { Button, Panel } from './ui.jsx'

function PathGlyph({ activities, colors }) {
  return (
    <div className="flex items-center">
      {activities.map((activity, index) => (
        <span key={index} className="flex items-center" title={activity}>
          {index > 0 && <span className="h-px w-2.5 bg-border" />}
          <span className="size-2.5 shrink-0 rounded-full" style={{ background: colors.get(activity) }} />
        </span>
      ))}
    </div>
  )
}

function Summary({ activities }) {
  const parts = []
  activities.forEach((activity) => {
    const last = parts[parts.length - 1]
    if (last && last.activity === activity) last.times++
    else parts.push({ activity, times: 1 })
  })
  return (
    <span>
      {parts.map((part, index) => (
        <span key={index}>
          {index > 0 && <span className="text-muted"> → </span>}
          {part.activity}
          {part.times > 1 && <span className="num text-rework"> ×{part.times}</span>}
        </span>
      ))}
    </span>
  )
}

export function VariantExplorer({ data, colors, className = '' }) {
  const { selection, select } = useSelection()
  const [showAll, setShowAll] = useState(false)
  const variants = data.variants
  const order = useMemo(() => processOrder(data.traces, data.dfg.activities), [data])
  const visible = showAll ? variants : variants.slice(0, 8)
  const maxShare = variants[0]?.percentage ?? 1
  const cumulative = variants.reduce((sums, variant) => [...sums, (sums[sums.length - 1] ?? 0) + variant.percentage], [])

  return (
    <Panel
      title="Variants"
      note={`${formatCount(variants.length)} distinct paths · click one to trace it on the map`}
      bodyClassName=""
      className={className}
    >
      <div className="flex flex-wrap gap-x-4 gap-y-1.5 border-b border-border px-4 py-2.5">
        {order.map((activity) => (
          <span key={activity} className="flex items-center gap-1.5 text-[11px] text-muted">
            <span className="size-2 rounded-full" style={{ background: colors.get(activity) }} />
            {activity}
          </span>
        ))}
      </div>
      <div className="label grid grid-cols-[28px_minmax(0,1fr)_120px_64px] gap-3 border-b border-border px-4 py-2">
        <span>#</span>
        <span>Path</span>
        <span>Cases</span>
        <span className="text-right">Cum.</span>
      </div>
      <ol className="max-h-[520px] overflow-y-auto">
        {visible.map((variant, index) => {
          const id = variant.activities.join(' → ')
          const selected = selection?.type === 'variant' && selection.id === id
          return (
            <li key={id}>
              <button
                type="button"
                onClick={() => select('variant', id)}
                className={`grid w-full grid-cols-[28px_minmax(0,1fr)_120px_64px] items-center gap-3 border-b border-border px-4 py-2.5 text-left transition-colors ${
                  selected ? 'bg-faint' : 'hover:bg-faint'
                }`}
              >
                <span className="num text-xs text-muted">{index + 1}</span>
                <span className="min-w-0">
                  <PathGlyph activities={variant.activities} colors={colors} />
                  <span className="mt-1 block truncate text-xs text-ink">
                    <Summary activities={variant.activities} />
                  </span>
                </span>
                <span>
                  <span className="num flex justify-between text-xs text-ink">
                    <span>{formatCount(variant.frequency)}</span>
                    <span className="text-muted">{formatPercent(variant.percentage)}</span>
                  </span>
                  <span className="mt-1 block h-1 rounded-sm bg-faint">
                    <span className="block h-1 rounded-sm bg-ink" style={{ width: `${(variant.percentage / maxShare) * 100}%` }} />
                  </span>
                </span>
                <span className="num text-right text-xs text-muted">{formatPercent(cumulative[index])}</span>
              </button>
            </li>
          )
        })}
      </ol>
      {variants.length > 8 && (
        <div className="px-4 py-2.5">
          <Button onClick={() => setShowAll((value) => !value)}>
            {showAll ? 'Show top 8' : `Show all ${variants.length}`}
          </Button>
        </div>
      )}
    </Panel>
  )
}
