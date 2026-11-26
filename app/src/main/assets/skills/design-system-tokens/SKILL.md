---
name: design-system-tokens
description: Create or tidy a design system built on tokens: color, type, spacing, radius, elevation and motion defined once and used everywhere, for web (CSS variables) and Android (Compose theme), with a DESIGN.md that agents and people follow.
compatibility: NativeCode ARM64 PRoot; plain files, no extra tools required
metadata:
  title: Design System Tokens
  category: PROJECT
  group: Design
  status: authored; not independently device-tested
---

# Design System Tokens

**Use for:** starting a design system, replacing scattered hex values and magic numbers, adding dark mode or a second theme, or documenting the look so every later change stays consistent.

**Deliver:** a token file, the platform theme that consumes it, and a short `DESIGN.md`.

## 1. Inspect before inventing

- Search the project for existing colors, font sizes, spacing and radii (`grep -rE "#[0-9a-fA-F]{3,8}|[0-9]+px|[0-9]+\.dp"`). The values used most often are the real system; promote them to tokens instead of picking new ones.
- Keep the brand the project already has. Change values only when asked.

## 2. Three layers

1. **Primitive**: raw values with no meaning. `gray-900: #14161a`, `space-4: 16px`.
2. **Semantic**: purpose. `color.surface`, `color.text`, `color.text-muted`, `color.accent`, `color.danger`, `space.page`, `radius.control`. Components use only this layer.
3. **Component** (only when needed): `button.padding-x`, `card.radius`, pointing at semantic tokens.

Themes (light, dark, high contrast) swap the primitive mapping behind the same semantic names. Components never know which theme is active.

## 3. What to define

- **Color**: surface (3 steps), border, text (default, muted, disabled, on-accent), accent (default, hover, pressed), and status (success, warning, danger, info). One accent. Check contrast: text 4.5:1, large text and UI parts 3:1, in every theme.
- **Type**: a scale of 6 to 8 steps (for example 12, 14, 16, 20, 24, 32, 48) with size, line height and weight per role (caption, body, label, title, headline, display). One or two families.
- **Space**: a 4 px base scale (4, 8, 12, 16, 24, 32, 48, 64). No other values in layout code.
- **Radius** (2 to 4 steps), **border width**, **elevation** (shadow or surface tint, 3 levels), **motion** (durations 120, 200, 320 ms; one or two easings), **z-index** layers, **breakpoints**.

## 4. Web output (CSS variables)

```css
:root {
  --gray-900: #14161a; --gray-50: #f6f7f9; --blue-600: #2458d6;
  --color-surface: var(--gray-50);
  --color-text: var(--gray-900);
  --color-accent: var(--blue-600);
  --space-2: 8px; --space-4: 16px; --radius-control: 8px;
  --font-body: system-ui, sans-serif; --text-body: 1rem/1.5 var(--font-body);
}
@media (prefers-color-scheme: dark) {
  :root { --color-surface: #14161a; --color-text: #f6f7f9; }
}
:root[data-theme="dark"] { --color-surface: #14161a; --color-text: #f6f7f9; }
```

Rules: components reference `var(--color-*)` and `var(--space-*)` only. A raw color or pixel value in a component is a bug. Use `rem` for type, `clamp()` for fluid headings, and `color-scheme: light dark`.

## 5. Android output (Compose)

- Put colors in `Color.kt` as primitives, map them into `lightColorScheme(...)` and `darkColorScheme(...)` roles in `Theme.kt`.
- Spacing and radius as an immutable object (`object Space { val s = 8.dp ... }`) or a `CompositionLocal` when themes change them.
- Type in `Typography(...)`. Screens read `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*`, never hex or ad hoc `sp`.

## 6. Write DESIGN.md

Keep it under two pages: purpose and tone in two sentences; the token table (name, value, use); do and do not rules (one accent, no gradients on surfaces, spacing only from the scale); component states; accessibility floor; where the tokens live in code. Agents read this file first on every UI task.

## 7. Migrate safely

- Replace values in small steps, one screen or component at a time, and build after each step.
- Add a lint-style check: a script or test that fails on raw hex colors and non-scale spacing in component files.
- Screenshot before and after at phone width, in light and dark. The look should not change unless the user asked for a change.
