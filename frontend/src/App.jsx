import { useMemo } from 'react'
import { activityColorMap } from './colors.js'
import { CaseTimeline } from './components/CaseTimeline.jsx'
import { CsvImport } from './components/CsvImport.jsx'
import { DurationHistogram } from './components/DurationHistogram.jsx'
import { ThroughputCalendar } from './components/ThroughputCalendar.jsx'
import { KpiStrip } from './components/KpiStrip.jsx'
import { MapSection } from './components/MapSection.jsx'
import { VariantExplorer } from './components/VariantExplorer.jsx'
import { ThemeToggle } from './components/ThemeToggle.jsx'
import { formatCount } from './format.js'
import { SelectionProvider } from './selection.jsx'
import { useDashboardData } from './useDashboardData.js'

function Header({ data, actions }) {
  return (
    <header className="border-b border-border">
      <div className="mx-auto flex max-w-[1440px] flex-wrap items-center justify-between gap-4 px-4 py-3 sm:px-6">
        <div className="flex items-center gap-3">
          <img src="/favicon.svg" alt="" className="size-7" />
          <div>
            <h1 className="text-[15px] font-semibold leading-tight text-ink">Process mining</h1>
            <p className="text-xs text-muted">
              Order fulfilment event log{data ? ` · ${formatCount(data.traces.length)} cases` : ''}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-3">{actions}</div>
      </div>
    </header>
  )
}

function Status({ error, loading }) {
  if (error) {
    return (
      <div className="rounded-md border border-border bg-surface p-6">
        <div className="label">Backend not reachable</div>
        <p className="mt-2 text-sm text-ink">{error.message}</p>
        <p className="mt-2 text-xs text-muted">
          Start it with <code className="num">./mvnw spring-boot:run</code>; the dev server proxies <code className="num">/api</code> to port 8080.
        </p>
      </div>
    )
  }
  if (loading) return <div className="label py-24 text-center">Loading event log…</div>
  return null
}

export default function App() {
  const { data, error, loading, reload } = useDashboardData()
  const colors = useMemo(() => (data ? activityColorMap(data.dfg.activities) : new Map()), [data])

  return (
    <SelectionProvider>
      <div className="min-h-screen bg-paper">
        <Header
          data={data}
          actions={
            <>
              <CsvImport onImported={reload} />
              <ThemeToggle />
            </>
          }
        />
        <main className="mx-auto flex max-w-[1440px] flex-col gap-4 px-4 py-5 sm:px-6">
          {!data && <Status error={error} loading={loading} />}
          {data && (
            <>
              <KpiStrip data={data} />
              <MapSection data={data} colors={colors} />
              <div className="grid items-start gap-4 lg:grid-cols-12">
                <VariantExplorer data={data} colors={colors} className="lg:col-span-7" />
                <div className="flex flex-col gap-4 lg:col-span-5">
                  <DurationHistogram data={data} />
                  <ThroughputCalendar data={data} />
                </div>
              </div>
              <CaseTimeline data={data} colors={colors} />
            </>
          )}
        </main>
        <footer className="mx-auto max-w-[1440px] px-4 pb-8 text-[11px] text-muted sm:px-6">
          Spring Boot + PostgreSQL backend · directly-follows graph, analytics and conformance computed server-side
        </footer>
      </div>
    </SelectionProvider>
  )
}
