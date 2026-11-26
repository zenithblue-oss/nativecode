---
name: design-mobile-compose-ui
description: Design and review phone-first Android and Jetpack Compose screens: layout, touch targets, type, color, motion, insets and accessibility. Use when building or judging a mobile UI so it does not look like a default template.
compatibility: NativeCode ARM64 PRoot; Kotlin/Compose project sources and Gradle builds as supplied by the project
metadata:
  title: Design Mobile UI
  category: PROJECT
  group: Design
  status: authored; not independently device-tested
---

# Design Mobile UI (Jetpack Compose)

**Use for:** a new Android screen, a redesign, or a UI review of a Compose (or View) app.

**Deliver:** working Compose code that follows the project's existing theme, plus a short list of the design decisions you made.

## Before you design

1. Read the project's theme first (`Theme.kt`, `Color.kt`, `Type.kt`, any `DesignTokens`). Reuse its colors, shapes and spacing. Never add a second palette next to it.
2. Name the screen's one job in a sentence. Everything on the screen either serves that job or is removed.
3. Pick one primary action. Give it the strongest visual weight; every other action is secondary or tertiary.

## Layout and spacing

- Use a 4 dp base grid; common steps 4, 8, 12, 16, 24, 32. Screen edge padding 16 dp on phones. Put the steps in named constants, not inline numbers.
- Touch targets are at least 48 x 48 dp, with 8 dp between neighbours. A 24 dp icon sits in a 48 dp hit area (`IconButton`, or `Modifier.minimumInteractiveComponentSize()`).
- Group related items with whitespace first, dividers last. Do not wrap every group in a card.
- Thumb zone: primary actions and navigation live in the lower half. Destructive actions are never next to the primary action without a gap or confirmation.
- Edge to edge: call `enableEdgeToEdge()`, then apply `WindowInsets.systemBars`, `ime` and `displayCutout` through `Scaffold` padding or `Modifier.windowInsetsPadding`. A list scrolls under the bars with content padding, it is not clipped by them.
- Scroll with `LazyColumn` and stable `key`s. Never nest a vertical scroller in another vertical scroller.

## Adaptive

- Design for 360 dp wide first, then verify 320 dp (small), 600 dp (foldable and tablet) and landscape.
- Use `currentWindowAdaptiveInfo().windowSizeClass`: Compact = one pane and bottom bar, Medium = navigation rail, Expanded = list-detail with two panes.
- Text must survive 200 percent font scale: use `sp`, `Modifier.heightIn(min = ...)` instead of fixed heights, `maxLines` plus `overflow = Ellipsis` only where truncation is acceptable.

## Typography

- Take sizes from `MaterialTheme.typography` roles (display, headline, title, body, label); do not invent font sizes per screen.
- Build hierarchy with size, weight and color together, not size alone. Body is 14 to 16 sp with line height about 1.4 to 1.5. Lines longer than about 70 characters are hard to read: cap content width on wide screens.
- One typeface family per product, two at most. Use tabular numbers (`fontFeatureSettings = "tnum"`) for values that change in place.

## Color

- Use color roles (`primary`, `onPrimary`, `surfaceContainer`, `outline`, `error`), never raw hex in composables.
- Neutral surfaces carry most of the screen; one accent marks the primary action and the selected state. State is never conveyed by color alone: add an icon, a label or a shape change.
- Contrast: text 4.5:1, large text and icons 3:1. Check dark and light themes both. In dark theme use lighter surfaces for elevation, not shadows.
- Avoid the default-AI look: purple to blue gradients, glowing buttons, glass cards on every item, emoji as icons, identical rounded cards in a grid.

## Components and states

- Every interactive element has all states: enabled, pressed, focused, disabled, error and loading. Show a skeleton or progress while loading, an explanation plus action when empty, and a retry when failed.
- Icons come from one vector set (Material Symbols or the project's drawables), one size and one stroke weight. Never use emoji or font glyphs as icons.
- Dialogs for decisions only. Prefer a snackbar with Undo over a confirmation dialog for reversible actions.
- Bottom navigation holds three to five top-level destinations. Back always works and leaves state intact.

## Motion

- Motion explains change: enter, exit, reorder, state. 150 to 300 ms, standard easing (`FastOutSlowInEasing`), no bounce on utility screens.
- Use `AnimatedVisibility`, `animateContentSize`, `animate*AsState` and shared-element transitions where the item stays recognisable. Respect the system "remove animations" setting.

## Accessibility

- `contentDescription` on every informative icon; `null` on decorative ones. Use `Modifier.semantics { ... }` for custom controls, `clearAndSetSemantics` to merge a row into one announcement.
- Logical focus order, visible focus indicator for keyboard and switch access, `Role` for custom clickables (`Modifier.clickable(onClickLabel = ...)`).
- Test with TalkBack once, with 200 percent font scale once, and in grayscale once.

## Verify

- Add `@Preview` for light, dark, small width and large font scale.
- Build with `./gradlew :app:assembleDebug`, install, and look at the real screen at its shipped size before calling it done.
- Finish with a checklist reply: primary action, touch targets, insets, contrast, states, font scale, dark theme, TalkBack.
