const HOUR = 3_600_000

export const SPEEDS = [
  { value: 0.5, label: '½×' },
  { value: 1, label: '1×' },
  { value: 4, label: '4×' },
]

export function createClock(start, end) {
  const listeners = new Set()
  const clock = {
    start,
    end,
    now: start,
    playing: false,
    started: false,
    speed: 1,
    logMsPerSecond: 12 * HOUR,
    stats: { inProgress: 0, completed: 0 },
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    notify() {
      listeners.forEach((listener) => listener())
    },
    play() {
      if (clock.now >= clock.end) clock.now = clock.start
      clock.playing = true
      clock.started = true
      clock.notify()
    },
    pause() {
      clock.playing = false
      clock.notify()
    },
    stop() {
      clock.playing = false
      clock.started = false
      clock.now = clock.start
      clock.notify()
    },
    seek(time) {
      clock.now = Math.min(clock.end, Math.max(clock.start, time))
      clock.started = true
      clock.notify()
    },
    setSpeed(speed) {
      clock.speed = speed
      clock.notify()
    },
    advance(seconds) {
      if (!clock.playing) return
      clock.now += seconds * clock.logMsPerSecond * clock.speed
      if (clock.now >= clock.end) {
        clock.now = clock.end
        clock.playing = false
      }
    },
  }
  return clock
}
