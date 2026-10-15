# AUDITSUPPORT.md - Modern UI reference (as used by CraftPy)

Only APIs that exist in Modern UI 3.13 (`ModernUI/core`, `ModernUI-MC`) and that CraftPy uses.
Source checkouts: `C:\Users\Xmilon\IdeaProjects\ModernUI\ModernUI` (core) and
`...\ModernUI\ModernUI-MC` (Minecraft integration, branch 1.21.1).
Modern UI has **no** RecyclerView, CardView, AlertDialog, AppCompat or Glide.

## Screens and lifecycle

| Need | API |
|------|-----|
| Menu screen for a container menu | `icyllis.modernui.mc.neoforge.MenuScreenFactory.create(menu -> fragment)` in `RegisterMenuScreensEvent` |
| Plain screen | `MuiModApi.get().createScreen(fragment)` + `Minecraft.setScreen` |
| Screen behaviour | main `Fragment` implements `icyllis.modernui.mc.ScreenCallback` (`isBackKey`, `isPauseScreen`, `shouldBlurBackground`) |
| Page switching | `getChildFragmentManager().beginTransaction().replace(id, fragment, tag)` into a `FragmentContainerView` |
| Run on UI thread | `View.post(Runnable)` / `MuiModApi.postToUiThread` |

Note: menu screens treat the inventory key (E) as "back" by default. CraftPy overrides
`isBackKey` to Escape only, so E can be typed in the editor.

## Look and feel (Modern UI settings style)

| Element | API |
|---------|-----|
| Outlined card | `icyllis.modernui.mc.ui.ThemeControl.makeOutlinedCard(context, view, new TypedValue())` |
| Divider | `ThemeControl.makeDivider(view[, vertical])` + `LinearLayout.setShowDividers/setDividerDrawable` |
| Width-clamped page scroll | `icyllis.modernui.mc.ui.ClampingScrollView.setChildMaxWidth` |
| Theme colors | `theme.resolveAttribute(R.ns, R.attr.colorXxx, value, true)` -> `value.data` (`R.attr` color names are `String`s) |
| Text styles | `TextView.setTextAppearance(R.attr.textAppearanceTitleMedium ...)` (these are `ResourceId`s) |
| Buttons | `new Button(ctx, null, R.attr.buttonStyle / buttonTonalStyle / buttonOutlinedStyle / borderlessButtonStyle)` |
| Text field | `new EditText(ctx, null, R.attr.editTextOutlinedStyle)` |
| Nav pill + ripple | `ShapeDrawable` (corner radius 1000) inside `RippleDrawable(ColorStateList, content, mask)` |

## Widgets

`LinearLayout`, `FrameLayout`, `ScrollView` (`setFillViewport`, `fullScroll`),
`HorizontalScrollView`, `TextView`, `EditText`, `Button`, `RadioGroup`/`RadioButton`, `Switch`
(`setOnCheckedChangeListener((view, checked) -> ...)`), `PopupMenu` (`getMenu().add(...)`,
`setOnMenuItemClickListener`), `PopupWindow` (`showAsDropDown`, used for prompts), context menus
via `View.setOnCreateContextMenuListener` (right click).

## Text

- Monospace: `Typeface.MONOSPACED`.
- Highlighting: `Editable.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)`;
  `Spanned.getSpans` returns a `List`.
- Key handling: `View.setOnKeyListener((v, keyCode, event) -> ...)`, `KeyEvent.KEY_*`,
  `isCtrlPressed()`. `EditText` already supports Ctrl+Z / Ctrl+Y.

## Markdown

```java
Markflow markflow = Markflow.create(context);
markflow.setMarkdown(textView, markdown); // UI thread
```

## Gradle

```gradle
repositories { maven { url 'https://maven.izzel.io/releases/' } }
dependencies {
    compileOnly("icyllis.modernui:ModernUI-NeoForge:1.21.1-3.13.0.1") { transitive = false }
    compileOnly("dev.icyllis:modernui-core:3.13.0") { transitive = false }
    compileOnly("dev.icyllis:arc3d-core:2026.2.0") { transitive = false }
    compileOnly("icyllis.modernui:ModernUI-Markflow:3.12.0") { transitive = false }
    compileOnly("org.commonmark:commonmark:0.25.0") { transitive = false }
}
```
The runtime comes from the Modern UI mod jar (in dev: `run/mods/`).
