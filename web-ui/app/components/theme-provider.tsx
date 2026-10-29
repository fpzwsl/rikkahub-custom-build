import { createContext, useContext, useEffect, useState } from "react";

export type ThemeMode = "dark" | "light" | "system";
export type Theme = ThemeMode;
export type ColorTheme = "default" | "claude" | "t3-chat" | "mono" | "bubblegum" | "custom";
export type LayoutStyle = "default" | "deepseek";

export const COLOR_THEMES: ColorTheme[] = [
  "default",
  "claude",
  "t3-chat",
  "mono",
  "bubblegum",
  "custom",
];

const COLOR_THEME_STORAGE_SUFFIX = "-color";
const CUSTOM_THEME_LIGHT_STORAGE_SUFFIX = "-custom-light";
const CUSTOM_THEME_DARK_STORAGE_SUFFIX = "-custom-dark";
const CUSTOM_THEME_STYLE_ID = "rikkahub-custom-theme";

type ThemeProviderProps = {
  children: React.ReactNode;
  defaultTheme?: ThemeMode;
  defaultColorTheme?: ColorTheme;
  storageKey?: string;
};

export type CustomThemeCss = {
  light: string;
  dark: string;
};

type ThemeProviderState = {
  theme: ThemeMode;
  setTheme: (theme: ThemeMode) => void;
  colorTheme: ColorTheme;
  setColorTheme: (theme: ColorTheme) => void;
  layoutStyle: LayoutStyle;
  setLayoutStyle: (style: LayoutStyle) => void;
  customThemeCss: CustomThemeCss;
  setCustomThemeCss: (theme: CustomThemeCss) => void;
};

const initialState: ThemeProviderState = {
  theme: "system",
  colorTheme: "default",
  customThemeCss: {
    light: "",
    dark: "",
  },
  setTheme: () => null,
  setColorTheme: () => null,
  layoutStyle: "default",
  setLayoutStyle: () => null,
  setCustomThemeCss: () => null,
};

const ThemeProviderContext = createContext<ThemeProviderState>(initialState);

function isThemeMode(value: string | null): value is ThemeMode {
  return value === "light" || value === "dark" || value === "system";
}

function isColorTheme(value: string | null): value is ColorTheme {
  return !!value && COLOR_THEMES.includes(value as ColorTheme);
}

function isLayoutStyle(value: string | null): value is LayoutStyle {
  return value === "default" || value === "deepseek";
}

function removeBlacklistedCss(value: string): string {
  return value
    .replace(/@theme\s+inline\s*\{[\s\S]*?\}/g, "")
    .replace(/(^|\n)\s*body\s*\{[\s\S]*?\}/g, "")
    .trim();
}

function scopeCustomThemeCss(value: string, mode: "light" | "dark"): string {
  const trimmed = value.trim();
  if (!trimmed) {
    return "";
  }

  const filtered = removeBlacklistedCss(trimmed);
  if (!filtered) {
    return "";
  }

  if (mode === "light") {
    const scoped = filtered.replace(
      /(^|\n)\s*:root(?!\.dark)(?!\[data-theme="custom"\])\s*\{/g,
      '$1:root[data-theme="custom"] {',
    );

    if (/:root\[data-theme="custom"\]\s*\{/.test(scoped)) {
      return scoped;
    }

    return `:root[data-theme="custom"] {\n${filtered}\n}`;
  }

  const scopedDarkRoot = filtered.replace(
    /(^|\n)\s*:root\.dark(?!\[data-theme="custom"\])\s*\{/g,
    '$1:root.dark[data-theme="custom"] {',
  );
  const scoped = scopedDarkRoot.replace(
    /(^|\n)\s*\.dark(?![a-zA-Z0-9_-])\s*\{/g,
    '$1:root.dark[data-theme="custom"] {',
  );

  if (/:root\.dark\[data-theme="custom"\]\s*\{/.test(scoped)) {
    return scoped;
  }

  return `:root.dark[data-theme="custom"] {\n${filtered}\n}`;
}

export function ThemeProvider({
  children,
  defaultTheme = "system",
  defaultColorTheme = "default",
  storageKey = "vite-ui-theme",
  ...props
}: ThemeProviderProps) {
  const colorThemeStorageKey = `${storageKey}${COLOR_THEME_STORAGE_SUFFIX}`;
  const layoutStyleStorageKey = `${storageKey}-layout`;
  const customThemeLightStorageKey = `${storageKey}${CUSTOM_THEME_LIGHT_STORAGE_SUFFIX}`;
  const customThemeDarkStorageKey = `${storageKey}${CUSTOM_THEME_DARK_STORAGE_SUFFIX}`;

  const [theme, setTheme] = useState<ThemeMode>(() => {
    const stored = localStorage.getItem(storageKey);
    return isThemeMode(stored) ? stored : defaultTheme;
  });

  const [colorTheme, setColorTheme] = useState<ColorTheme>(() => {
    const stored = localStorage.getItem(colorThemeStorageKey);
    return isColorTheme(stored) ? stored : defaultColorTheme;
  });

  const [layoutStyle, setLayoutStyle] = useState<LayoutStyle>(() => {
    const stored = localStorage.getItem(layoutStyleStorageKey);
    return isLayoutStyle(stored) ? stored : "default";
  });

  const [customThemeCss, setCustomThemeCss] = useState<CustomThemeCss>(() => ({
    light: localStorage.getItem(customThemeLightStorageKey) ?? "",
    dark: localStorage.getItem(customThemeDarkStorageKey) ?? "",
  }));

  useEffect(() => {
    const root = window.document.documentElement;
    const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");

    const applyMode = (mode: ThemeMode) => {
      root.classList.remove("light", "dark");

      if (mode === "system") {
        root.classList.add(mediaQuery.matches ? "dark" : "light");
        return;
      }

      root.classList.add(mode);
    };

    applyMode(theme);

    if (theme !== "system") {
      return;
    }

    const onSystemThemeChange = () => {
      applyMode("system");
    };

    mediaQuery.addEventListener("change", onSystemThemeChange);

    return () => {
      mediaQuery.removeEventListener("change", onSystemThemeChange);
    };
  }, [theme]);

  useEffect(() => {
    const root = window.document.documentElement;
    root.dataset.theme = colorTheme;
  }, [colorTheme]);

  useEffect(() => {
    window.document.documentElement.dataset.layout = layoutStyle;
  }, [layoutStyle]);

  useEffect(() => {
    const lightCss = scopeCustomThemeCss(customThemeCss.light, "light");
    const darkCss = scopeCustomThemeCss(customThemeCss.dark, "dark");
    const cssBlocks = [lightCss, darkCss].filter(Boolean).join("\n\n");

    const existingStyle = document.getElementById(CUSTOM_THEME_STYLE_ID);

    if (!cssBlocks) {
      existingStyle?.remove();
      return;
    }

    const styleElement = existingStyle ?? document.createElement("style");
    styleElement.id = CUSTOM_THEME_STYLE_ID;
    styleElement.textContent = cssBlocks;

    if (!existingStyle) {
      document.head.appendChild(styleElement);
    }
  }, [customThemeCss.dark, customThemeCss.light]);

  const value = {
    theme,
    colorTheme,
    layoutStyle,
    customThemeCss,
    setTheme: (theme: ThemeMode) => {
      localStorage.setItem(storageKey, theme);
      setTheme(theme);
    },
    setColorTheme: (theme: ColorTheme) => {
      localStorage.setItem(colorThemeStorageKey, theme);
      setColorTheme(theme);
    },
    setLayoutStyle: (style: LayoutStyle) => {
      localStorage.setItem(layoutStyleStorageKey, style);
      setLayoutStyle(style);
    },
    setCustomThemeCss: (themeCss: CustomThemeCss) => {
      localStorage.setItem(customThemeLightStorageKey, themeCss.light);
      localStorage.setItem(customThemeDarkStorageKey, themeCss.dark);
      setCustomThemeCss(themeCss);
    },
  };

  return (
    <ThemeProviderContext.Provider {...props} value={value}>
      {children}
    </ThemeProviderContext.Provider>
  );
}

export const useTheme = () => {
  const context = useContext(ThemeProviderContext);

  if (context === undefined) throw new Error("useTheme must be used within a ThemeProvider");

  return context;
};
