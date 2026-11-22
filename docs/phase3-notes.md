# Phase 3: Design system notes

## What exists
- `css/theme.css`: all colours defined once as tokens on `.root` (dark navy surfaces, one blue accent, status colours).
  Styles buttons (primary / secondary / danger / ghost / icon), inputs, combo box, date picker, check box, tables,
  scrollbars, menus, tooltips, dialogs, badges, stat cards, empty states, toasts, sidebar and top bar.
- `ui/Icon`: 19 built-in vector icons (no image files). Coloured by CSS classes (`icon`, `icon-danger`, ...).
- Reusable components (package `ui`): `Toasts`, `AppDialog` (confirm / destructive confirm / message), `EmptyState`,
  `LoadingOverlay`, `DataTable` (loading / ready / empty / error states), `Pager`, `FormField` (required marker + inline error),
  `SearchField`, `StatusBadge`, `StatCard`.
- `navigation/AppShell`: persistent sidebar with section headings and active-page indicator, top bar with page title,
  per-page action buttons, notification bell with unread badge, account menu (shown after login in Phase 4).
  Pages register with `shell.register(Page, supplier)`; unregistered pages show a "Coming soon" state.
  `shell.setAllowedPages(...)` will hide sidebar items by role.
- `ui/GalleryPage` ("UI Gallery" in the sidebar): every component on one page with sample data. Developer-only; removed in Phase 7.

## Rules for later phases
- Never style a screen inline; add a class to `theme.css` instead.
- Use `FormField` for every form input, `DataTable` + `Pager` for every list, `AppDialog.confirm(..., destructive=true)`
  before destructive actions, `Toasts` for feedback. Show only user-safe messages.

## Not covered yet
- Automated UI tests (TestFX) are added together with the first real screens in Phase 4.
- Wizard/stepper component is built with the Issue Book screen (Phase 4).
