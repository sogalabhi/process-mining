import { useSyncExternalStore } from 'react'
import { formatCount, formatDateTime } from '../format.js'
import { Segmented } from '../components/ui.jsx'
import { SPEEDS } from './clock.js'

function useClock(clock) {
  return useSyncExternalStore(
    clock.subscribe,
    () => `${clock.now}|${clock.playing}|${clock.started}|${clock.speed}|${clock.stats.inProgress}|${clock.stats.completed}`,
  )
}

export function ReplayControls({ clock, totalCases }) {
  useClock(clock)
  const { playing, started, now, start, end, speed, stats } = clock

  return (
    <div className="absolute right-14 bottom-3 left-3 z-10 flex flex-wrap items-center gap-3 rounded-md border border-border bg-surface px-3 py-2">
      <button
        type="button"
        onClick={() => (playing ? clock.pause() : clock.play())}
        className="flex h-7 items-center gap-1.5 rounded-md bg-ink px-2.5 text-xs font-medium text-paper"
      >
        {playing ? (
          <svg width="10" height="10" viewBox="0 0 10 10" fill="currentColor" aria-hidden="true">
            <rect x="1.5" y="1" width="2.5" height="8" />
            <rect x="6" y="1" width="2.5" height="8" />
          </svg>
        ) : (
          <svg width="10" height="10" viewBox="0 0 10 10" fill="currentColor" aria-hidden="true">
            <path d="M2 1 9 5 2 9Z" />
          </svg>
        )}
        {playing ? 'Pause' : started ? 'Resume' : 'Replay log'}
      </button>
      {started && (
        <button type="button" onClick={() => clock.stop()} className="text-xs text-muted hover:text-ink">
          Reset
        </button>
      )}
      <Segmented label="Replay speed" value={speed} onChange={(value) => clock.setSpeed(value)} options={SPEEDS} />
      <input
        type="range"
        min={start}
        max={end}
        step={60_000}
        value={now}
        onChange={(event) => clock.seek(Number(event.target.value))}
        className="min-w-32 flex-1"
        aria-label="Replay time"
      />
      <span className="num w-[132px] text-xs text-ink">{formatDateTime(now)}</span>
      <span className="num text-xs text-muted">
        {started ? `${formatCount(stats.inProgress)} open · ${formatCount(stats.completed)}/${formatCount(totalCases)} done` : `${formatCount(totalCases)} cases`}
      </span>
    </div>
  )
}
