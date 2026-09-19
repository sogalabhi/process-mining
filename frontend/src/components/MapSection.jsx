import { ReactFlowProvider } from '@xyflow/react'
import { useEffect, useMemo, useState } from 'react'
import { api } from '../api.js'
import { formatCount } from '../format.js'
import { END, START, buildModel, edgeKey } from '../graph.js'
import { layoutGraph } from '../layout.js'
import { createClock } from '../replay/clock.js'
import { ReplayCanvas } from '../replay/ReplayCanvas.jsx'
import { ReplayControls } from '../replay/ReplayControls.jsx'
import { buildTrips } from '../replay/trips.js'
import { useSelection } from '../selection.jsx'
import { ProcessMap } from './ProcessMap.jsx'
import { SidePanel } from './SidePanel.jsx'
import { Panel, Segmented } from './ui.jsx'

function pathHighlight(activities) {
  const nodes = new Set([START, END, ...activities])
  const edges = new Set([edgeKey(START, activities[0]), edgeKey(activities[activities.length - 1], END)])
  for (let i = 0; i < activities.length - 1; i++) edges.add(edgeKey(activities[i], activities[i + 1]))
  return { nodes, edges }
}

function clampInt(value, min, max) {
  const number = Math.round(Number(value))
  if (!Number.isFinite(number)) return min
  return Math.min(max, Math.max(min, number))
}

export function MapSection({ data, colors }) {
  const { selection, select, clear } = useSelection()
  const model = useMemo(() => buildModel(data), [data])
  const [layout, setLayout] = useState(null)
  const [view, setView] = useState('performance')
  const [thresholdIndex, setThresholdIndex] = useState(0)
  const [top, setTop] = useState(3)
  const [minFrequency, setMinFrequency] = useState(20)
  const [bottlenecks, setBottlenecks] = useState([])
  const clock = useMemo(() => createClock(data.span.start, data.span.end), [data])
  const replay = useMemo(() => buildTrips(data.traces), [data])

  useEffect(() => {
    let cancelled = false
    layoutGraph(model.nodes, model.edges).then((result) => {
      if (!cancelled) setLayout(result)
    })
    return () => {
      cancelled = true
    }
  }, [model])

  const safeTop = clampInt(top, 1, 10)
  const safeMinFrequency = clampInt(minFrequency, 1, 1_000_000)

  useEffect(() => {
    let cancelled = false
    api.bottlenecks(safeTop, safeMinFrequency).then((result) => {
      if (!cancelled) setBottlenecks(result)
    }).catch(() => {
      if (!cancelled) setBottlenecks([])
    })
    return () => {
      cancelled = true
    }
  }, [safeTop, safeMinFrequency, data])

  const bottleneckRanks = useMemo(
    () => new Map(bottlenecks.map((step, index) => [edgeKey(step.from, step.to), index + 1])),
    [bottlenecks],
  )

  const counts = useMemo(
    () => [...new Set(model.edges.filter((e) => e.kind === 'step').map((e) => e.count))].sort((a, b) => a - b),
    [model],
  )
  const threshold = counts[Math.min(thresholdIndex, counts.length - 1)] ?? 0
  const steps = model.edges.filter((e) => e.kind === 'step')
  const shown = steps.filter((e) => e.count >= threshold).length

  const highlight = useMemo(() => {
    if (selection?.type === 'variant') return pathHighlight(selection.id.split(' → '))
    if (selection?.type === 'case') {
      const trace = data.traces.find((t) => t.caseId === selection.id)
      return trace ? pathHighlight(trace.events.map((e) => e.activity)) : null
    }
    return null
  }, [selection, data])

  const visibleCases = useMemo(() => {
    if (selection?.type === 'case') return new Set([selection.id])
    if (selection?.type === 'variant') {
      return new Set(data.traces.filter((t) => t.events.map((e) => e.activity).join(' → ') === selection.id).map((t) => t.caseId))
    }
    return null
  }, [selection, data])

  const toolbar = (
    <>
      <Segmented
        label="Map view"
        value={view}
        onChange={setView}
        options={[
          { value: 'frequency', label: 'Frequency' },
          { value: 'performance', label: 'Performance' },
        ]}
      />
      <label className="flex items-center gap-2 text-xs text-muted">
        <span className="whitespace-nowrap">Hide steps seen &lt;</span>
        <input
          type="range"
          min={0}
          max={Math.max(0, counts.length - 1)}
          value={Math.min(thresholdIndex, counts.length - 1)}
          onChange={(event) => setThresholdIndex(Number(event.target.value))}
          className="w-28"
          aria-label="Minimum step frequency"
        />
        <span className="num w-24 text-ink">
          {formatCount(threshold)}× · {shown}/{steps.length}
        </span>
      </label>
    </>
  )

  return (
    <Panel
      title="Process map"
      note={view === 'frequency' ? 'How often each step happens' : 'How long cases wait between steps'}
      actions={toolbar}
      bodyClassName="grid lg:grid-cols-[minmax(0,1fr)_320px]"
    >
      <div className="relative h-[800px] border-b border-border lg:border-r lg:border-b-0">
        {layout ? (
          <ReactFlowProvider>
            <ProcessMap
              model={model}
              layout={layout}
              colors={colors}
              view={view}
              threshold={threshold}
              bottlenecks={view === 'performance' ? bottleneckRanks : new Map()}
              highlight={highlight}
              selectedEdge={selection?.type === 'edge' ? selection.id : null}
              selectedNode={selection?.type === 'activity' ? selection.id : null}
              onEdgeClick={(id) => select('edge', id)}
              onNodeClick={(id) => (id === START || id === END ? null : select('activity', id))}
              onPaneClick={clear}
            >
              <ReplayCanvas clock={clock} layout={layout} replay={replay} visibleCases={visibleCases} />
            </ProcessMap>
            <ReplayControls clock={clock} totalCases={data.traces.length} />
          </ReactFlowProvider>
        ) : (
          <div className="label flex h-full items-center justify-center">Laying out graph…</div>
        )}
      </div>
      <div className="max-h-[800px]">
        <SidePanel
          selection={selection}
          model={model}
          view={view}
          bottlenecks={bottlenecks}
          bottleneckRanks={bottleneckRanks}
          startEnd={data.startEnd}
          top={top}
          setTop={setTop}
          minFrequency={minFrequency}
          setMinFrequency={setMinFrequency}
          onSelectEdge={(id) => select('edge', id)}
        />
      </div>
    </Panel>
  )
}
