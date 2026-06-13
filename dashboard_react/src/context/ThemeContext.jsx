import { createContext, useContext, useState, useCallback } from 'react';

const ThemeContext = createContext();

export function ThemeProvider({ children }) {
  const [isDark, setIsDark] = useState(true);
  const toggle = useCallback(() => setIsDark(d => !d), []);
  return (
    <ThemeContext.Provider value={{ isDark, toggle }}>
      <div className={isDark ? 'theme-dark' : 'theme-light'}>{children}</div>
    </ThemeContext.Provider>
  );
}

export function useTheme() {
  return useContext(ThemeContext);
}
