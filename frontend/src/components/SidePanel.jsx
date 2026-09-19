import { formatCount, formatDuration, formatPercent } from '../format.js'
import { END, START, edgeKey } from '../graph.js'
import { Stat } from './ui.jsx'

function DistributionBar({ duration }) {
  const { minSeconds, maxSeconds, medianSeconds, avgSeconds, p90Seconds } = duration
  const low = Math.log(Math.max(1, minSeconds))
  const high = Math.log(Math.max(minSeconds + 1, maxSeconds))
  const at = (seconds) => `${((Math.log(Math.max(1, seconds)) - low) / (high - low || 1)) * 100}%`
  const marks = [
    { key: 'median', seconds: medianSeconds, tall: true, row: 0 },
    { key: 'avg', seconds: avgSeconds, row: 1 },
    { key: 'P90', seconds: p90Seconds, row: 0 },
  ]

  return (
    <div className="mt-4">
      <div className="relative h-8">
        <div className="absolute top-3 right-0 left-0 h-1.5 rounded-sm bg-faint" />
        <div
          className="absolute top-3 h-1.5 rounded-sm bg-border"
          style={{ left: at(medianSeconds), width: `calc(${at(p90Seconds)} - ${at(medianSeconds)})` }}
        />
        {marks.map((mark) => (
          <div key={mark.key} className="absolute top-0 flex -translate-x-1/2 flex-col items-center" style={{ left: at(mark.seconds) }}>
            <div className={`w-px bg-ink ${mark.tall ? 'h-7' : 'h-5 mt-1'}`} />
          </div>
        ))}
      </div>
      <div className="relative h-7 text-[10px]">
        {marks.map((mark) => (
          <span key={mark.key} className="label absolute -translate-x-1/2 !text-[10px]" style={{ left: at(mark.seconds), top: mark.row * 13 }}>
            {mark.key}
          </span>
        ))}
      </div>
      <div className="num mt-1 flex justify-between text-[11px] text-muted">
        <span>{formatDuration(minSeconds)}</span>
        <span>log scale</span>
        <span>{formatDuration(maxSeconds)}</span>
      </div>
    </div>
  )
}

function EdgeDetails({ edge, bottleneckRank, totalCases }) {
  const from = edge.source === START ? 'Start' : edge.source
  const to = edge.target === END ? 'End' : edge.target
  return (
    <div>
      <div className="label">{edge.kind === 'step' ? 'Step' : edge.kind === 'start' ? 'Case start' : 'Case end'}</div>
      <h3 className="mt-1 text-[15px] font-semibold leading-snug text-ink">
        {from} <span className="text-muted">→</span> {to}
      </h3>
      <div className="mt-2 flex flex-wrap gap-1.5">
        {bottleneckRank && (
          <span className="num rounded-sm border border-signal px-1.5 text-[11px] leading-5 text-signal">
            slowest step #{bottleneckRank}
          </span>
        )}
        {edge.isRework && (
          <span className="num rounded-sm border border-dashed border-rework px-1.5 text-[11px] leading-5 text-rework">
            goes back · rework
          </span>
        )}
      </div>
      <div className="mt-4 grid grid-cols-2 gap-x-4 gap-y-3">
        <Stat label="Times" value={formatCount(edge.count)} />
        <Stat label="Share of cases" value={edge.kind === 'step' ? '—' : formatPercent((edge.count / totalCases) * 100)} />
      </div>
      {edge.duration && (
        <>
          <div className="mt-5 grid grid-cols-3 gap-x-4 gap-y-3">
            <Stat label="Min" value={formatDuration(edge.duration.minSeconds)} />
            <Stat label="Median" value={formatDuration(edge.duration.medianSeconds)} />
            <Stat label="Average" value={formatDuration(edge.duration.avgSeconds)} />
            <Stat label="P90" value={formatDuration(edge.duration.p90Seconds)} />
            <Stat label="P95" value={formatDuration(edge.duration.p95Seconds)} />
            <Stat label="Max" value={formatDuration(edge.duration.maxSeconds)} />
          </div>
          <DistributionBar duration={edge.duration} />
          <p className="mt-3 text-xs leading-relaxed text-muted">
            Half of these waits took {formatDuration(edge.duration.medianSeconds)} or less; one in ten took longer than{' '}
            {formatDuration(edge.duration.p90Seconds)}.
          </p>
        </>
      )}
    </div>
  )
}

function ActivityDetails({ node, model, startEnd, totalCases, onSelectEdge }) {
  const starts = startEnd.startActivities.find((a) => a.activity === node.id)
  const ends = startEnd.endActivities.find((a) => a.activity === node.id)
  const incoming = model.edges.filter((e) => e.kind === 'step' && e.target === node.id).sort((a, b) => b.count - a.count)
  const outgoing = model.edges.filter((e) => e.kind === 'step' && e.source === node.id).sort((a, b) => b.count - a.count)

  const list = (title, edges, pick) =>
    edges.length > 0 && (
      <div className="mt-4">
        <div className="label">{title}</div>
        <ul className="mt-1.5 divide-y divide-border border-y border-border">
          {edges.map((edge) => (
            <li key={edge.id}>
              <button
                type="button"
                onClick={() => onSelectEdge(edge.id)}
                className="flex w-full items-center justify-between gap-2 py-1.5 text-left text-xs hover:text-ink"
              >
                <span className="truncate text-ink">{pick(edge)}</span>
                <span className="num shrink-0 text-muted">
                  {formatCount(edge.count)} · {formatDuration(edge.duration?.medianSeconds)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </div>
    )

  return (
    <div>
      <div className="label">Activity</div>
      <h3 className="mt-1 text-[15px] font-semibold text-ink">{node.id}</h3>
      <div className="mt-4 grid grid-cols-2 gap-x-4 gap-y-3">
        <Stat label="Occurrences" value={formatCount(node.stats?.occurrences)} />
        <Stat label="In cases" value={`${formatCount(node.stats?.cases)} · ${formatPercent(node.stats?.casePercentage)}`} />
        <Stat label="Cases start here" value={formatCount(starts?.count ?? 0)} />
        <Stat label="Cases end here" value={formatCount(ends?.count ?? 0)} />
        {node.rework && (
          <>
            <Stat label="Repeated in" value={`${formatCount(node.rework.casesWithRepeat)} cases`} tone="var(--rework)" />
            <Stat label="Extra runs" value={formatCount(node.rework.extraOccurrences)} tone="var(--rework)" />
          </>
        )}
      </div>
      {ends && node.id !== model.order[model.order.length - 1] && (
        <p className="mt-3 text-xs leading-relaxed text-muted">
          {formatCount(ends.count)} of {formatCount(totalCases)} cases stop here instead of finishing.
        </p>
      )}
      {list('Comes from', incoming, (edge) => edge.source)}
      {list('Goes to', outgoing, (edge) => edge.target)}
    </div>
  )
}

function SlowestSteps({ bottlenecks, top, setTop, minFrequency, setMinFrequency, onSelectEdge }) {
  return (
    <div>
      <div className="flex items-baseline justify-between">
        <h3 className="text-sm font-semibold text-ink">Slowest steps</h3>
        <span className="label">by average wait</span>
      </div>
      <div className="mt-3 flex gap-3">
        <label className="flex flex-1 flex-col gap-1">
          <span className="label">Show top</span>
          <input
            type="number"
            min={1}
            max={10}
            value={top}
            onChange={(event) => setTop(event.target.value)}
            className="num rounded-md border border-border bg-paper px-2 py-1 text-sm text-ink outline-none focus:border-ink"
          />
        </label>
        <label className="flex flex-1 flex-col gap-1">
          <span className="label">Seen at least</span>
          <input
            type="number"
            min={1}
            value={minFrequency}
            onChange={(event) => setMinFrequency(event.target.value)}
            className="num rounded-md border border-border bg-paper px-2 py-1 text-sm text-ink outline-none focus:border-ink"
          />
        </label>
      </div>
      <ol className="mt-3 divide-y divide-border border-y border-border">
        {bottlenecks.map((step, index) => (
          <li key={edgeKey(step.from, step.to)}>
            <button
              type="button"
              onClick={() => onSelectEdge(edgeKey(step.from, step.to))}
              className="flex w-full items-start gap-3 py-2 text-left"
            >
              <span className="num w-4 shrink-0 text-xs text-signal">{index + 1}</span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-xs text-ink">
                  {step.from} → {step.to}
                </span>
                <span className="num block text-[11px] text-muted">
                  {formatCount(step.frequency)} times · P90 {formatDuration(step.duration.p90Seconds)}
                </span>
              </span>
              <span className="num shrink-0 text-xs text-ink">{formatDuration(step.duration.avgSeconds)}</span>
            </button>
          </li>
        ))}
        {bottlenecks.length === 0 && <li className="py-2 text-xs text-muted">No step is seen that often.</li>}
      </ol>
      <p className="mt-2 text-[11px] leading-relaxed text-muted">
        Steps seen fewer times are left out, so one unusual case can't top the list.
      </p>
    </div>
  )
}

function Legend({ view }) {
  return (
    <div className="space-y-2 text-[11px] text-muted">
      <div className="label">Legend</div>
      {view === 'frequency' ? (
        <div className="flex items-center gap-2">
          <svg width="44" height="10">
            <line x1="2" y1="5" x2="20" y2="5" stroke="var(--muted)" strokeWidth="1.5" />
            <line x1="24" y1="5" x2="42" y2="5" stroke="var(--muted)" strokeWidth="6" />
          </svg>
          line width = how often
        </div>
      ) : (
        <>
          <div className="flex items-center gap-2">
            <span className="flex">
              {[1, 2, 3, 4, 5].map((level) => (
                <span key={level} className="h-2 w-2.5" style={{ background: `var(--dur-${level})` }} />
              ))}
            </span>
            fast → slow (average wait)
          </div>
          <div className="flex items-center gap-2">
            <span className="h-[3px] w-[44px] bg-signal" />
            slowest steps
          </div>
        </>
      )}
      <div className="flex items-center gap-2">
        <svg width="44" height="10">
          <line x1="2" y1="5" x2="42" y2="5" stroke="var(--rework)" strokeWidth="2" strokeDasharray="6 4" />
        </svg>
        goes back (rework)
      </div>
      <div className="flex items-center gap-2">
        <svg width="44" height="10">
          <line x1="2" y1="5" x2="42" y2="5" stroke="var(--muted)" strokeWidth="1.5" strokeDasharray="2 5" strokeLinecap="round" />
        </svg>
        case starts / ends
      </div>
    </div>
  )
}

export function SidePanel(props) {
  const { selection, model, view, bottleneckRanks, startEnd, onSelectEdge } = props
  const totalCases = model.nodes[0].count
  const edge = selection?.type === 'edge' ? model.edges.find((e) => e.id === selection.id) : null
  const node = selection?.type === 'activity' ? model.nodes.find((n) => n.id === selection.id) : null

  return (
    <div className="flex h-full flex-col gap-6 overflow-y-auto p-4">
      {edge && <EdgeDetails edge={edge} bottleneckRank={bottleneckRanks.get(edge.id)} totalCases={totalCases} />}
      {node && <ActivityDetails node={node} model={model} startEnd={startEnd} totalCases={totalCases} onSelectEdge={onSelectEdge} />}
      {!edge && !node && <SlowestSteps {...props} />}
      <div className="mt-auto border-t border-border pt-4">
        <Legend view={view} />
      </div>
    </div>
  )
}
