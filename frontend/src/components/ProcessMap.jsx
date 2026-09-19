import { Background, Controls, ReactFlow } from '@xyflow/react'
import { useMemo } from 'react'
import { formatCount, formatDuration } from '../format.js'
import { END, START } from '../graph.js'
import { edgeTypes } from './FlowEdge.jsx'
import { nodeTypes } from './MapNodes.jsx'

function edgeStyle(edge, { view, maxCount, totalCases, level, bottlenecks, threshold, highlight, selectedEdge }) {
  const isStep = edge.kind === 'step'
  const hidden = isStep && edge.count < threshold
  const bottleneckRank = bottlenecks.get(edge.id)
  const avg = edge.duration?.avgSeconds

  let stroke = 'var(--muted)'
  let width
  let dash
  let label
  let labelTone = 'var(--muted)'

  if (!isStep) {
    width = 1 + 2.5 * Math.sqrt(edge.count / totalCases)
    dash = '2 5'
    label = formatCount(edge.count)
  } else if (view === 'frequency') {
    width = 1.25 + 6.5 * Math.sqrt(edge.count / maxCount)
    label = formatCount(edge.count)
    labelTone = 'var(--ink)'
  } else {
    width = 1.75 + 3.5 * Math.sqrt(edge.count / maxCount)
    stroke = `var(--dur-${level(avg)})`
    label = formatDuration(avg)
    labelTone = level(avg) >= 4 ? 'var(--ink)' : 'var(--muted)'
    if (bottleneckRank) {
      stroke = 'var(--signal)'
      width += 1.5
      label = `${bottleneckRank}. ${formatDuration(avg)}`
      labelTone = 'var(--signal)'
    }
  }

  if (isStep && edge.isRework) {
    stroke = 'var(--rework)'
    dash = '6 4'
    labelTone = 'var(--rework)'
  }

  if (selectedEdge === edge.id) {
    stroke = 'var(--ink)'
    labelTone = 'var(--ink)'
  }

  const opacity = highlight && !highlight.edges.has(edge.id) ? 0.12 : 1
  return { stroke, width, dash, label, labelTone, hidden, opacity }
}

export function ProcessMap({ model, layout, colors, view, threshold, bottlenecks, highlight, selectedEdge, selectedNode, onEdgeClick, onNodeClick, onPaneClick, children }) {
  const totalCases = model.nodes[0].count

  const nodes = useMemo(
    () =>
      model.nodes.map((node) => {
        const position = layout.positions.get(node.id)
        const dimmed = highlight ? !highlight.nodes.has(node.id) : false
        if (node.id === START || node.id === END) {
          return {
            id: node.id,
            type: 'terminal',
            position: { x: position.x, y: position.y },
            data: { kind: node.kind, label: node.kind === 'start' ? 'Start' : 'End', dimmed },
            selectable: false,
          }
        }
        return {
          id: node.id,
          type: 'activity',
          position: { x: position.x, y: position.y },
          data: {
            label: node.id,
            color: colors.get(node.id),
            occurrences: node.stats?.occurrences,
            rework: node.rework,
            dimmed,
            selected: selectedNode === node.id,
          },
        }
      }),
    [model, layout, colors, highlight, selectedNode],
  )

  const edges = useMemo(
    () =>
      model.edges.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        type: 'flow',
        data: {
          points: layout.routes.get(edge.id),
          ...edgeStyle(edge, {
            view,
            maxCount: model.maxCount,
            totalCases,
            level: model.level,
            bottlenecks,
            threshold,
            highlight,
            selectedEdge,
          }),
        },
      })),
    [model, layout, view, threshold, bottlenecks, highlight, selectedEdge, totalCases],
  )

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      edgeTypes={edgeTypes}
      nodesDraggable={false}
      nodesConnectable={false}
      elementsSelectable={false}
      fitView
      fitViewOptions={{ padding: 0.04 }}
      minZoom={0.25}
      maxZoom={2.5}
      onEdgeClick={(_, edge) => onEdgeClick(edge.id)}
      onNodeClick={(_, node) => onNodeClick(node.id)}
      onPaneClick={onPaneClick}
      proOptions={{ hideAttribution: false }}
    >
      <Background gap={24} size={1} color="var(--border)" />
      <Controls showInteractive={false} position="bottom-right" />
      {children}
    </ReactFlow>
  )
}
