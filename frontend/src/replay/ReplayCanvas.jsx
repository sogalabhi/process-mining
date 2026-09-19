import { useStoreApi } from '@xyflow/react'
import { useEffect, useMemo, useRef } from 'react'
import { polyline } from '../geometry.js'
import { cssVar, useTheme } from '../theme.jsx'
import { countAtOrBefore, createSweep } from './trips.js'

const HOUR = 3_600_000

function jitter(caseId) {
  let hash = 0
  for (let i = 0; i < caseId.length; i++) hash = (hash * 31 + caseId.charCodeAt(i)) | 0
  return ((hash % 1000) / 1000) * 5
}

export function ReplayCanvas({ clock, layout, replay, visibleCases }) {
  const canvasRef = useRef(null)
  const colorsRef = useRef(null)
  const store = useStoreApi()
  const { theme } = useTheme()

  useEffect(() => {
    colorsRef.current = { ink: cssVar('ink'), rework: cssVar('rework'), muted: cssVar('muted'), surface: cssVar('surface') }
  }, [theme])

  const routes = useMemo(
    () => new Map([...layout.routes].map(([id, points]) => [id, polyline(points)])),
    [layout],
  )

  useEffect(() => {
    const canvas = canvasRef.current
    const context = canvas.getContext('2d')
    const sweep = createSweep(replay.trips)
    const jitters = new Map()
    let frame
    let previous = performance.now()
    let lastNotify = 0

    const resize = () => {
      const rect = canvas.parentElement.getBoundingClientRect()
      const ratio = window.devicePixelRatio || 1
      canvas.width = rect.width * ratio
      canvas.height = rect.height * ratio
      canvas.style.width = `${rect.width}px`
      canvas.style.height = `${rect.height}px`
    }
    const observer = new ResizeObserver(resize)
    observer.observe(canvas.parentElement)
    resize()

    const draw = (time) => {
      const elapsed = Math.min(0.1, (time - previous) / 1000)
      previous = time
      clock.advance(elapsed)

      const ratio = window.devicePixelRatio || 1
      context.setTransform(1, 0, 0, 1, 0, 0)
      context.clearRect(0, 0, canvas.width, canvas.height)

      if (clock.started) {
        let active = sweep.at(clock.now)
        const next = sweep.nextStart()
        if (clock.playing && active.length === 0 && next && next - clock.now > 6 * HOUR) {
          clock.now = next - HOUR / 2
          active = sweep.at(clock.now)
        }

        const [tx, ty, zoom] = store.getState().transform
        context.setTransform(ratio * zoom, 0, 0, ratio * zoom, ratio * tx, ratio * ty)

        const colors = colorsRef.current
        const queues = new Map()
        const inProgress = new Set()
        const plain = new Path2D()
        const rework = new Path2D()
        const radius = 3.2

        for (const trip of active) {
          inProgress.add(trip.caseId)
          if (visibleCases && !visibleCases.has(trip.caseId)) continue
          const route = routes.get(trip.edge)
          if (!route) continue
          queues.set(trip.from, (queues.get(trip.from) ?? 0) + 1)
          const point = route.pointAt((clock.now - trip.start) / (trip.end - trip.start))
          let offset = jitters.get(trip.caseId)
          if (offset === undefined) {
            offset = jitter(trip.caseId)
            jitters.set(trip.caseId, offset)
          }
          const x = point.x - Math.sin(point.angle) * offset
          const y = point.y + Math.cos(point.angle) * offset
          const path = replay.reworkCases.has(trip.caseId) ? rework : plain
          path.moveTo(x + radius, y)
          path.arc(x, y, radius, 0, Math.PI * 2)
        }

        context.globalAlpha = 0.78
        context.fillStyle = colors.ink
        context.fill(plain)
        context.fillStyle = colors.rework
        context.fill(rework)
        context.globalAlpha = 1

        const maxQueue = Math.max(1, ...queues.values())
        context.font = '500 10px "IBM Plex Mono", monospace'
        context.textBaseline = 'middle'
        for (const [activity, count] of queues) {
          const position = layout.positions.get(activity)
          if (!position) continue
          const x = position.x + position.width + 8
          const y = position.y + 10
          const width = 4 + (count / maxQueue) * 56
          context.fillStyle = colors.surface
          context.fillRect(x - 2, y - 7, width + 34, 14)
          context.fillStyle = colors.muted
          context.fillRect(x, y - 2, width, 4)
          context.fillStyle = colors.ink
          context.fillText(String(count), x + width + 4, y)
        }

        clock.stats = { inProgress: inProgress.size, completed: countAtOrBefore(replay.caseEnds, clock.now) }
      }

      if (clock.playing && time - lastNotify > 100) {
        lastNotify = time
        clock.notify()
      }
      frame = requestAnimationFrame(draw)
    }

    frame = requestAnimationFrame(draw)
    return () => {
      cancelAnimationFrame(frame)
      observer.disconnect()
    }
  }, [clock, replay, routes, visibleCases, store, layout])

  return <canvas ref={canvasRef} className="pointer-events-none absolute inset-0 z-[5]" />
}
