import { useTheme } from '../theme.jsx'
import { Segmented } from './ui.jsx'

export function ThemeToggle() {
  const { theme, setTheme } = useTheme()
  return (
    <Segmented
      label="Color theme"
      value={theme}
      onChange={setTheme}
      options={[
        { value: 'light', label: 'Light' },
        { value: 'dark', label: 'Dark' },
      ]}
    />
  )
}
