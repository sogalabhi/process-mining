import { Handle, Position } from '@xyflow/react'
import { formatCount } from '../format.js'

const hidden = { opacity: 0, width: 1, height: 1, minWidth: 0, minHeight: 0, border: 0 }

function Handles() {
  return (
    <>
      <Handle type="target" position={Position.Top} style={hidden} isConnectable={false} />
      <Handle type="source" position={Position.Bottom} style={hidden} isConnectable={false} />
    </>
  )
}

export function LoopIcon() {
  return (
    <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.3" aria-hidden="true">
      <path d="M8.5 5a3.5 3.5 0 1 1-1.1-2.55" />
      <path d="M8.2 1v2.1H6.1" />
    </svg>
  )
}

export function ActivityNode({ data }) {
  const { label, color, occurrences, rework, dimmed, selected } = data
  return (
    <div
      className="flex h-[54px] w-[176px] cursor-pointer flex-col justify-center rounded-md border bg-surface px-3 transition-opacity duration-300"
      style={{
        borderColor: selected ? 'var(--ink)' : 'var(--border)',
        boxShadow: `inset 3px 0 0 ${color}`,
        opacity: dimmed ? 0.3 : 1,
      }}
    >
      <Handles />
      <div className="flex items-center gap-2">
        <span className="size-2 shrink-0 rounded-full" style={{ background: color }} />
        <span className="truncate text-[14px] font-medium text-ink">{label}</span>
      </div>
      <div className="mt-1 flex items-center justify-between pl-4 text-[11px] text-muted">
        <span className="num">{formatCount(occurrences)}×</span>
        {rework && (
          <span className="num flex items-center gap-1 rounded-sm border border-dashed border-rework px-1 leading-4 text-rework">
            <LoopIcon />
            {formatCount(rework.casesWithRepeat)}
          </span>
        )}
      </div>
    </div>
  )
}

export function TerminalNode({ data }) {
  const filled = data.kind === 'start'
  return (
    <div
      className="label flex h-[28px] w-[72px] items-center justify-center rounded-full border transition-opacity duration-300"
      style={{
        background: filled ? 'var(--ink)' : 'var(--surface)',
        color: filled ? 'var(--paper)' : 'var(--ink)',
        borderColor: 'var(--ink)',
        opacity: data.dimmed ? 0.3 : 1,
      }}
    >
      <Handles />
      {data.label}
    </div>
  )
}

export const nodeTypes = { activity: ActivityNode, terminal: TerminalNode }
