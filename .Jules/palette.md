## 2026-11-03 - Search Clear Pattern in Steampunk Design System
**Learning:** Filter and search inputs rendered within modal overlays (like PublicDirectory in ServerManager) need explicit trailing padding (e.g. `padding-right: 36px`) and an absolute positioned clear button with proper `aria-label` translation to prevent input text collision and maintain accessibility.
**Action:** When adding text clear buttons to inputs, wrap the input in a relative container, add trailing padding to the input class, and provide localized `aria-label`s for all supported locales.
