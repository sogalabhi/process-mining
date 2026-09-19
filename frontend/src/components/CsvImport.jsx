import { useEffect, useRef, useState } from 'react'
import { api } from '../api.js'
import { Button } from './ui.jsx'

export function CsvImport({ onImported }) {
  const inputRef = useRef(null)
  const [dragging, setDragging] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState(null)

  async function upload(file) {
    if (!file) return
    setBusy(true)
    setMessage(null)
    try {
      const result = await api.importCsv(file)
      setMessage({ tone: 'ok', text: `${file.name}: ${result}` })
      await onImported()
    } catch (error) {
      setMessage({ tone: 'error', text: `${file.name}: ${error.message}` })
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    let depth = 0
    const hasFiles = (event) => event.dataTransfer?.types?.includes('Files')
    const enter = (event) => {
      if (!hasFiles(event)) return
      depth++
      setDragging(true)
    }
    const leave = () => {
      depth = Math.max(0, depth - 1)
      if (depth === 0) setDragging(false)
    }
    const over = (event) => {
      if (hasFiles(event)) event.preventDefault()
    }
    const drop = (event) => {
      if (!hasFiles(event)) return
      event.preventDefault()
      depth = 0
      setDragging(false)
      upload(event.dataTransfer.files[0])
    }
    window.addEventListener('dragenter', enter)
    window.addEventListener('dragleave', leave)
    window.addEventListener('dragover', over)
    window.addEventListener('drop', drop)
    return () => {
      window.removeEventListener('dragenter', enter)
      window.removeEventListener('dragleave', leave)
      window.removeEventListener('dragover', over)
      window.removeEventListener('drop', drop)
    }
  })

  return (
    <>
      <Button onClick={() => inputRef.current?.click()} disabled={busy}>
        {busy ? 'Importing…' : 'Import CSV'}
      </Button>
      <input
        ref={inputRef}
        type="file"
        accept=".csv,text/csv"
        className="hidden"
        onChange={(event) => {
          upload(event.target.files[0])
          event.target.value = ''
        }}
      />
      {message && (
        <div
          role="status"
          className="fixed top-16 right-6 z-50 flex max-w-sm items-start gap-3 rounded-md border bg-surface px-4 py-3 text-xs"
          style={{ borderColor: message.tone === 'error' ? 'var(--signal)' : 'var(--border)' }}
        >
          <span className={message.tone === 'error' ? 'text-signal' : 'text-ink'}>{message.text}</span>
          <button type="button" onClick={() => setMessage(null)} className="text-muted hover:text-ink" aria-label="Dismiss">
            ✕
          </button>
        </div>
      )}
      {dragging && (
        <div className="pointer-events-none fixed inset-0 z-40 flex items-center justify-center bg-paper/85">
          <div className="rounded-md border-2 border-dashed border-ink px-10 py-8 text-center">
            <div className="text-sm font-semibold text-ink">Drop a CSV to import it</div>
            <div className="num mt-1 text-xs text-muted">case_id, activity, timestamp</div>
          </div>
        </div>
      )}
    </>
  )
}
