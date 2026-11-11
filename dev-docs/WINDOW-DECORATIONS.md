# Custom Window Decorations + Embedded Menu Bar (FlatLAF-style)

Goal: our own title bar with the menu embedded in it, like FlatLAF
([docs](https://www.formdev.com/flatlaf/window-decorations/)), with the **minimum
custom Swing we can get away with**. Related TODOs: `TODO.md:78-84`.

**Design principle:** reuse stock Swing layout (`JRootPane.RootLayout`) instead of
writing a `RootPaneUI`/`RootLayout`. The title bar is a `JMenuBar` subclass that
*occupies the menu-bar slot*, so the content pane, toolbar, and menu-popover
machinery are untouched.

---

## 1. The mechanism (verified under Xvfb, 2026-11)

Three facts make the minimal design work:

1. **A `JMenuBar` can be the title bar.** Put `[icon][menus][title↔][min][max][close]`
   as children of a `JMenuBar` (its default `BoxLayout` lays them out left→right;
   the title label gets `maxWidth=MAX` so it stretches). `setJMenuBar(titleBar)`
   puts it in the slot `JRootPane.RootLayout` already reserves — **no custom
   layout needed**. Toolbar stays `"North"` in the content pane, content stays
   `"Center"`: zero layout changes.

2. **Mouse presses propagate to the nearest ancestor with a listener.** Proven:
   a listener-less opaque child (`JLabel` title/icon) does **not** swallow the
   press — it bubbles to the `JMenuBar` drag handler. A child that *has* a
   listener (a `JMenu`, a window button) consumes it. So:
   - click a menu → menu opens, **no drag** (menu consumes);
   - click the title text or icon → **drag fires** (labels are listener-less);
   - click a window button → button action (button consumes).

3. **Mnemonics and accelerators work with the menu bar repurposed.** `Alt+F`
   opens the menu, `Ctrl+S` fires the accelerator, even though the menu bar is
   the title bar and the real menus are nested inside it.

Nested variant also verified: the real per-mode `MenuBar` lives as a **child** of
the title bar (`[icon][realMenuBar↔][title↔][buttons]`), keeping its `menuName`
identity intact; menu open / accel / mnemonic all work through the nesting.

---

## 2. Architecture (Style 1: persistent title bar, swappable menus)

```mermaid
flowchart TB
    subgraph RP [JRootPane — stock RootLayout, no custom UI]
        MB["TitleBar (JMenuBar subclass) — setJMenuBar once<br/>[icon] [menusSlot] [title ↔] [min][max][close]<br/>drag listener on itself"]
        CP["contentPane (BorderLayout) — unchanged"]
        TB["ToolBar — 'North', unchanged"]
        EP["EditorPane / sidebar — 'Center', unchanged"]
    end
    MB -->|setMenus() swaps child on mode change| RM["real MenuBar (per-mode, menuName)"]
    CP --> TB
    CP --> EP
```

- **`TitleBar` is created once** in the `Frame` constructor and `setJMenuBar`'d
  once. It survives every mode switch and every `resetDisplay()`.
- **`setMenu()` swaps the nested real `MenuBar`** via `titleBar.setMenus(...)`,
  not `setJMenuBar(...)`. The title bar's drag listener, buttons, and theming are
  never re-attached.
- The title bar reads the window **title** and **icon** via property listeners
  (`"title"`, `"iconImage"`) — `titleChanged()`/`setIconImages()` need no change.
- Active/inactive colors via the title bar's own `WindowListener` — self-contained.

This is ~25% of FlatLAF's decoration code because we **skip**: custom
`RootPaneUI`, custom `RootLayout`, the separate mouse-layer (only needed for
full-window-content mode), the native Windows DWM border, and all macOS
full-window/screen-menu machinery.

---

## 3. Components to build

| File | ~LOC | What |
|---|---|---|
| `TitleBar.java` | ~180 | `JMenuBar` subclass: icon + menu slot + title + buttons, drag, double-click, active/inactive, `setMenus()` |
| `WindowResizer.java` | ~150 | focused port of `FlatWindowResizer.WindowResizer`: 4 border comps + cursor-stabilizer, min-size clamp, recompute-from-current-bounds |
| `Frame.java` | ~25 | create `TitleBar` in ctor (gated), route `setMenu()` through it, `setUndecorated` before first show |
| `DefaultLookAndFeel.java` | ~15 | `TitlePane.*` theme defaults + the enable gate |
| 3 SVGs | trivial | `title-minimize`, `title-maximize`, `title-restore` (reuse `close.svg` style) |

**~370 LOC total**, two self-contained classes. No `RootPaneUI`, no
`RootLayout`, no mouse-layer, no native/JNI.

### 3.1 `TitleBar extends JMenuBar`

Layout (BoxLayout X, proven): `[icon] [menusSlot] [title↔] [min] [max] [close]`

- **icon**: `JLabel`, listener-less (drags). Image from
  `SwingUtilities.getWindowAncestor(this).getIconImages()`; listen `"iconImage"`.
  Reuse `j-logo` at `UIScale.scale(16)`.
- **menusSlot**: holds the current real `MenuBar` (opaque=false so the title-bar
  background shows through). `setMenus(JMenuBar m)` removes the old child,
  inserts the new one at the slot index.
- **title**: `JLabel`, listener-less, `maxWidth=MAX` (stretches), centered.
  Text from window `"title"` property.
- **buttons**: `JButton` × 3, `focusable=false`, `contentAreaFilled=false`,
  icons via `Icons.getIconFromFile("title-minimize|maximize|restore", size)`,
  hover/pressed fill painted in the button's own `paintComponent` (a tiny
  anonymous subclass or a shared `TitleButtonUI`).
  - minimize → `setExtendedState(state | ICONIFIED)`
  - maximize/restore → `setMaximizedBounds(screen − insets)` then
    `setExtendedState(MAXIMIZED_BOTH)` / clear (Java 25: no version caveats)
  - close → `window.dispatchEvent(new WindowEvent(window, WINDOW_CLOSING))`
    (preserves `DO_NOTHING_ON_CLOSE` → `killFrame`)
- **drag** (listener on the title bar itself): `mousePressed` stores
  `dragOffset = screenPoint − window.location`; `mouseDragged` →
  `window.setLocation(screen − dragOffset)`. Restore-from-maximized on drag start
  (port FlatLAF `mouseDragged` maximized-restore block).
- **double-click**: on title → maximize/restore; on icon → close.
- **active/inactive**: `WindowListener.windowActivated/Deactivated` → swap
  background/foreground (`TitlePane.background`/`inactiveBackground`), repaint.
- **maximize state**: `WindowStateListener` → swap maximize↔restore button icon.

### 3.2 `WindowResizer` (focused port)

Only the frame case (no internal-frame/abstract hierarchy). Four invisible
`JComponent` border strips added to the **root pane** above the glass pane
(insert index 1), `UIScale.scale(5)` thick, `UIScale.scale(16)` corners:

- `mouseMoved` → switch cursor (leading-corner / center / trailing-corner).
- `mousePressed` → record offsets from mouse to all four window edges.
- `mouseDragged` → recompute new bounds **from current window bounds each event**
  (WM-safe), clamp to min size (default `UIScale.scale(150×50)`),
  `window.setBounds(r)` + immediate re-layout of the strips.
- transparent center comp covers the root pane during drag so the resize cursor
  doesn't flicker when the mouse moves inside a shrinking window.
- hidden when maximized / fullscreen / not resizable.

Port source: `/tmp/flatlaf/.../FlatWindowResizer.java` `WindowResizer` +
`DragBorderComponent` (drop the abstract base, internal-frame variant, and the
Linux native `moveOrResizeWindow` calls — pure `setBounds`).

### 3.3 Frame integration

In `Frame` constructor (before first show — `setUndecorated` requires
non-displayable; our show path is `placeWindow()`/`AdjustPlacementRunnable`, so
the ctor is safe):

```java
// Frame ctor, after setIconImages():
if (WindowDecorations.enabled()) {
    setUndecorated(true);
    titleBar = new TitleBar();
    setJMenuBar(titleBar);
    windowResizer = new WindowResizer(this);
}
```

`setMenu()` (Frame.java:618) becomes:

```java
public void setMenu() {
    final Mode mode = currentEditor.getMode();
    final MenuBar oldMenus = titleBar != null ? titleBar.getMenus()
                                            : (MenuBar) getJMenuBar();
    if (oldMenus == null || Platform.isPlatformMacOSX()
            || oldMenus.getMenuName() != mode.getMenuName()) {
        final MenuBar menus = mode.createMenuBar(this);
        if (titleBar != null) titleBar.setMenus(menus);
        else setJMenuBar(menus);
        validate();
    }
}
```

`resetDisplay()` (Frame.java:1571) already calls `setJMenuBar(mode.createMenuBar(this))`
— change to the same `titleBar != null ? setMenus : setJMenuBar` branch. The
title bar itself is **not** recreated (it persists across `resetDisplay`); only
its nested menus swap and its theme is refreshed via `updateUI`/re-read defaults.

### 3.4 Theming

New UI defaults in `DefaultLookAndFeel` (custom-theme path only), derived from
the j editor theme via `DefaultTheme.blend`/`isDark`:

| Key | Derivation |
|---|---|
| `TitlePane.background` | `color.background` blended toward `color.text` (light: −4%, dark: +6%) |
| `TitlePane.inactiveBackground` | same, closer to background |
| `TitlePane.foreground` | `color.text` |
| `TitlePane.inactiveForeground` | `color.text` @ ~50% |
| `TitlePane.buttonHover` | `TitlePane.background` ± small delta |
| `TitlePane.closeHover` | `#c42b1c` (FlatLAF red) |

All pixel constants through `UIScale.scale()`. Live theme reload already re-runs
`setLookAndFeel()` + `resetDisplay()` per frame (`Buffer.java:1541-1547`) — the
title bar re-reads defaults there; make `TitleBar` re-read in `updateUI()`.

---

## 4. Platform gating (`WindowDecorations.enabled()`)

| Platform | Decorate? | Why |
|---|---|---|
| macOS | **No** | native title bar + screen menu bar; `Platform.isPlatformMacOSX()` short-circuits |
| Linux **X11** | **Yes** | full control; WM cooperates with `setLocation`/`setBounds` |
| Linux **Wayland** (JBR `WLToolkit`) | **No** | "windows cannot place themselves" (`build.clj:295-306`) — undecorating leaves no move/resize. Detect `System.getProperty("awt.toolkit.name","").contains("WL")` or `Toolkit` class name contains `WL` |
| Windows 10/11 | **Yes** (custom resize, no snap animation) | without native DWM code we lose snap/animation but keep a working custom border. Acceptable; native border is a possible later add-on |

Gate = `(Linux && !Wayland) || Windows`, **and** the custom-theme path
(`Editor.lookAndFeel == null`), so users who picked `"Metal"`/`"System"`/`"Aqua"`
keep stock behavior — mirrors how `ButtonUI` is skipped on macOS
(`DefaultLookAndFeel.java:94-96`).

---

## 5. Phased plan (each phase independently shippable)

| Phase | Deliverable | Verify |
|---|---|---|
| 1 | `TitleBar` (icon, title, buttons, drag, double-click, active/inactive) on Linux X11; `setMenu()` routing | `bb run` X11: drag, min/max/restore/close, focus colors, mode-switch menu swap |
| 2 | `WindowResizer` port (4 borders + center comp + min clamp) | manual resize all edges/corners + `bb fuzz-windows` |
| 3 | Theming: `TitlePane.*` from editor theme, live reload | edit theme file → title recolors without restart |
| 4 | Platform gates: macOS untouched, Wayland off, Windows custom-resize | `bb run-wayland` (native deco), macOS smoke, Windows smoke |
| 5 | Polish: icon-click system menu (right-click title bar), mnemonic gap tuning | manual |

## 6. Explicitly NOT building (to stay minimal)

- Custom `RootPaneUI` / `RootLayout` — stock `RootLayout` suffices (§1.1).
- Separate mouse-layer — only needed for FlatLAF's full-window-content mode.
- Native Windows DWM border / JNA WM utils — pure Java resize is enough; native
  is a possible later add-on for snap animation.
- macOS full-window-content / screen-menu-bar integration — macOS stays native.
- Dialog decorations — `AboutDialog` already undecorated; other `AbstractDialog`
  subclasses keep native decorations (out of scope).

## 7. Gotchas (ours, verified)

1. **`MenuItem` captures accelerator colors statically** at class-load
   (`MenuItem.java:33-36`) — don't repeat; read `UIManager` at paint/install time.
2. **Menu rebuild identity**: `setMenu()` compares `menuName`
   (`Frame.java:620-621`); `TitleBar.getMenus()` must expose the nested bar's
   `menuName` so the check still works after a swap.
3. **Maximize/taskbar**: `setMaximizedBounds(gc.getBounds() − getScreenInsets(gc))`
   before `MAXIMIZED_BOTH` (Java 25, no caveats). Keep `storeExtendedState`/
   `retrieveExtendedState` semantics for session restore (`Frame.java:133-139`).
4. **`Frame.processEvent` drops non-KeyEvents at frame level**
   (`Frame.java:149-152`) — title-bar listeners are on the title bar, not the
   frame, so unaffected.
5. **`bb fuzz-windows`** stresses random resize/maximize/close — the resizer must
   recompute from current bounds each event (WM-safe), as FlatLAF does.
6. **Close = `WINDOW_CLOSING`**, never `dispose()`, so `killFrame` runs.
7. **HiDPI**: every constant through `UIScale.scale()`; re-read on
   `UIScale.refresh`/`reset`.
