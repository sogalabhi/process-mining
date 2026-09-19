import { formatCount, formatDate, formatDuration, formatPercent } from '../format.js'

function Kpi({ label, value, detail }) {
  return (
    <div className="min-w-0 px-5 py-4">
      <div className="label">{label}</div>
      <div className="num mt-1.5 text-2xl leading-none text-ink">{value}</div>
      <div className="mt-1.5 truncate text-xs text-muted">{detail}</div>
    </div>
  )
}

export function KpiStrip({ data }) {
  const { durations, rework, variants, span, traces } = data
  const events = traces.reduce((sum, trace) => sum + trace.events.length, 0)
  const topThree = variants.slice(0, 3).reduce((sum, variant) => sum + variant.percentage, 0)
  const days = Math.max(1, Math.round((span.end - span.start) / 86_400_000))

  return (
    <div className="grid grid-cols-2 divide-border rounded-md border border-border bg-surface sm:grid-cols-3 lg:grid-cols-5 lg:divide-x">
      <Kpi label="Cases" value={formatCount(durations.caseCount)} detail={`${formatCount(events)} events`} />
      <Kpi
        label="Median case"
        value={formatDuration(durations.stats?.medianSeconds)}
        detail={`P90 ${formatDuration(durations.stats?.p90Seconds)} · max ${formatDuration(durations.stats?.maxSeconds)}`}
      />
      <Kpi
        label="Rework"
        value={formatPercent(rework.reworkPercentage)}
        detail={`${formatCount(rework.casesWithRework)} cases repeat a step`}
      />
      <Kpi label="Variants" value={formatCount(variants.length)} detail={`top 3 cover ${formatPercent(topThree)}`} />
      <Kpi label="Log span" value={`${days}d`} detail={`${formatDate(span.start)} – ${formatDate(span.end)}`} />
    </div>
  )
}
