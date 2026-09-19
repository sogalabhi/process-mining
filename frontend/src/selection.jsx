import { createContext, useCallback, useContext, useMemo, useState } from 'react'

const SelectionContext = createContext(null)

export function SelectionProvider({ children }) {
  const [selection, setSelection] = useState(null)

  const select = useCallback((type, id) => {
    setSelection((current) => (current && current.type === type && current.id === id ? null : { type, id }))
  }, [])

  const clear = useCallback(() => setSelection(null), [])

  const value = useMemo(() => ({ selection, select, clear }), [selection, select, clear])

  return <SelectionContext.Provider value={value}>{children}</SelectionContext.Provider>
}

export function useSelection() {
  return useContext(SelectionContext)
}
