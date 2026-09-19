export function Panel({ title, note, actions, className = '', bodyClassName = 'p-4', children }) {
  return (
    <section className={`rounded-md border border-border bg-surface ${className}`}>
      {(title || actions) && (
        <header className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-2.5">
          <div className="flex min-w-0 items-baseline gap-3">
            <h2 className="text-sm font-semibold text-ink">{title}</h2>
            {note && <p className="truncate text-xs text-muted">{note}</p>}
          </div>
          {actions && <div className="flex flex-wrap items-center gap-3">{actions}</div>}
        </header>
      )}
      <div className={bodyClassName}>{children}</div>
    </section>
  )
}

export function Segmented({ options, value, onChange, label }) {
  return (
    <div role="group" aria-label={label} className="flex rounded-md border border-border p-0.5">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          onClick={() => onChange(option.value)}
          aria-pressed={value === option.value}
          className={`rounded-sm px-2.5 py-1 text-xs font-medium transition-colors ${
            value === option.value ? 'bg-ink text-paper' : 'text-muted hover:text-ink'
          }`}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

export function Stat({ label, value, tone }) {
  return (
    <div className="min-w-0">
      <div className="label">{label}</div>
      <div className="num mt-0.5 truncate text-sm" style={{ color: tone ?? 'var(--ink)' }}>
        {value}
      </div>
    </div>
  )
}

export function Button({ children, className = '', ...props }) {
  return (
    <button
      type="button"
      className={`rounded-md border border-border px-3 py-1.5 text-xs font-medium text-ink transition-colors hover:border-ink disabled:opacity-50 ${className}`}
      {...props}
    >
      {children}
    </button>
  )
}
