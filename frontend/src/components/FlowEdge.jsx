import { EdgeLabelRenderer } from '@xyflow/react'
import { arrowHead, polyline, roundedPath } from '../geometry.js'

export function FlowEdge({ id, data }) {
  const { points, stroke, width, dash, opacity, label, labelTone, hidden } = data
  if (!points || points.length < 2) return null

  const d = roundedPath(points)
  const middle = polyline(points).pointAt(0.5)
  const style = {
    transition: 'stroke 350ms ease, stroke-width 350ms ease, opacity 300ms ease',
    opacity: hidden ? 0 : opacity,
  }

  return (
    <g style={{ pointerEvents: hidden ? 'none' : 'auto' }}>
      <path d={d} fill="none" stroke="transparent" strokeWidth={Math.max(14, width + 8)} className="cursor-pointer" />
      <path
        id={id}
        d={d}
        fill="none"
        stroke={stroke}
        strokeWidth={width}
        strokeDasharray={dash}
        strokeLinecap="round"
        strokeLinejoin="round"
        style={style}
      />
      <path d={arrowHead(points, 6 + width * 0.6)} fill={stroke} style={style} />
      {label && (
        <EdgeLabelRenderer>
          <div
            className="num pointer-events-none absolute rounded-sm bg-paper px-1 text-[11px] leading-4"
            style={{
              transform: `translate(-50%, -50%) translate(${middle.x}px, ${middle.y}px)`,
              color: labelTone,
              opacity: hidden ? 0 : opacity,
              transition: 'opacity 300ms ease, color 350ms ease',
            }}
          >
            {label}
          </div>
        </EdgeLabelRenderer>
      )}
    </g>
  )
}

export const edgeTypes = { flow: FlowEdge }
