# Jupyter-style keyboard controls

Java Lab uses the **JupyterLab notebook command/edit scheme**. It is similar to vi's modal approach: **Escape** selects command mode, and **Enter** selects edit mode. The status bar identifies the current mode. Clicking an editor enters edit mode; clicking a cell prompt or output enters command mode.

The defaults were checked against JupyterLab **4.6.x** and the classic Notebook documentation on **2026-10-12**:

- [JupyterLab command reference (complete command list)](https://jupyterlab.readthedocs.io/en/stable/user/commands.html)
- [Notebook shortcuts schema](https://github.com/jupyterlab/jupyterlab/blob/4.6.x/packages/notebook-extension/schema/tracker.json)
- [Run/kernel/menu shortcuts](https://github.com/jupyterlab/jupyterlab/blob/4.6.x/packages/mainmenu-extension/schema/plugin.json)
- [CodeMirror editor shortcuts](https://github.com/jupyterlab/jupyterlab/blob/4.6.x/packages/codemirror-extension/schema/plugin.json)
- [Shift-Tab tooltip binding](https://github.com/jupyterlab/jupyterlab/blob/4.6.x/packages/tooltip-extension/schema/notebooks.json)
- [Command palette binding](https://github.com/jupyterlab/jupyterlab/blob/4.6.x/packages/apputils-extension/schema/palette.json)
- [Classic Notebook modal controls](https://jupyter-notebook.readthedocs.io/en/stable/examples/Notebook/Notebook%20Basics.html)

`Ctrl / Cmd` means Ctrl on Windows/Linux and Command on macOS; Java Lab also accepts Ctrl for its explicit accelerator commands on macOS. Native textarea text movement/selection follows your OS. Capital letters below mean **Shift + letter**. Sequences such as **D D** are two separate presses, within one second, in the same notebook/cell. Holding a key does not trigger a double-key action.

## Running cells — either mode

| Keys | Action |
| --- | --- |
| Shift + Enter | Run selected cells in document order and select the next cell; at the end, create an editable cell |
| Ctrl / Cmd + Enter | Run selected cells and keep the current mode/selection |
| Alt + Enter | Run selected cells and insert a new editable cell below |
| Alt + Shift + V | Open Vibecode for the selected Java code cell (including selected text in its editor) |

Explicit execution stops at the first error. Interrupting also stops a pending multi-cell run.

## Command mode

| Keys | Action |
| --- | --- |
| Enter | Edit the active cell, including unrendering Markdown |
| ↑ / K, ↓ / J | Select previous / next visible cell |
| Home / End | Select first / last visible cell |
| Shift + ↑ / K, Shift + ↓ / J | Extend or shrink the selection around its anchor |
| Shift + Home / End | Extend selection to the first / last visible cell |
| Ctrl / Cmd + A | Select all cells |
| Escape | Reduce a multi-cell selection to the active cell |
| A / B | Insert code cell above / below |
| Shift + A / B | Insert an editable Markdown heading above / below |
| Y / M / R | Convert selected cells to Java code / Markdown / raw |
| T | Raw-cell classic alias |
| 1–6 | Convert to Markdown and set heading level |
| X / C / V | Cut / copy / paste selected cells below |
| Shift + V | Paste above (classic alias) |
| Ctrl / Cmd + X / C / V | Accelerator equivalents for the cell clipboard |
| D, D | Delete selected cells |
| Z / Shift + Z | Undo / redo cell action |
| Ctrl / Cmd + Z / Shift + Z; Ctrl + Y | Undo / redo cell action |
| Shift + M | Merge selected cells; with one selected cell, merge with the next |
| Ctrl + Backspace | Merge with the preceding cell |
| Ctrl + Shift + M | Merge with the following cell |
| Ctrl + Shift + ↑ / ↓ | Move selected cells up / down |
| L / Shift + L | Toggle active-cell / notebook-wide line numbering |
| O | Toggle selected cell outputs (classic alias) |
| Shift + O | Toggle output scrolling (classic alias) |
| ← / → | Collapse / expand a heading, or navigate to the preceding / following heading |
| Ctrl + Shift + ← / → | Collapse / expand all headings |
| Shift + R | Toggle side-by-side input/output layout |
| I, I | Interrupt the Java kernel |
| 0, 0 | Open the restart-kernel dialog |
| S | Save (classic alias) |
| H / ? | Show the complete implemented shortcut list |

Shift-clicking a cell prompt/output extends selection. Cell clipboard data is shared across Java Lab tabs in the same browser session. Pasted cells receive new ids while keeping their source, metadata, and stored outputs. Cell-action undo/redo is separate from text undo and preserves unrelated later edits/results where the action did not alter those fields.

## Edit mode

| Keys | Action |
| --- | --- |
| Escape / Ctrl + M | Enter command mode; completion/inspection popups are dismissed first |
| Tab | Indent selected lines / leading whitespace, or request Java completion after code |
| Shift + Tab | Outdent selected/indented lines, or inspect Java signatures at the cursor |
| Ctrl / Cmd + Space | Request completion explicitly |
| ↑ / ↓ in completion popup | Select a suggestion |
| Enter / Tab in completion popup | Accept a suggestion |
| Shift + Tab in completion popup | Select the preceding suggestion |
| Ctrl / Cmd + [ / ] | Outdent / indent |
| Ctrl / Cmd + / | Toggle line comments (Markdown uses HTML comment markers) |
| Alt + A | Toggle block comments |
| Ctrl / Cmd + D or Ctrl / Cmd + Shift + K | Delete current/selected lines |
| Ctrl / Cmd + Shift + D | Select the next occurrence; native editor uses one selection |
| Ctrl / Cmd + B in Markdown | Toggle bold around selected text |
| Ctrl / Cmd + Z | Undo a text edit |
| Ctrl / Cmd + Shift + Z or Ctrl + Y | Redo a text edit |
| Ctrl + Shift + - | Split the cell at the cursor |
| ↑ / ↓ at the first / last editor line | Move into the preceding / following cell editor |
| Alt + ↑ / ↓ | Browse this notebook session's executed code history |

Regular typing, text selection, word movement, Home/End, and platform clipboard shortcuts use the native editor. Java Lab implements the notebook modes; it does not replace text editing with a full Vim keymap. Inspection uses the active JShell session and returns signatures without executing code.

## Application and file browser

| Keys | Action |
| --- | --- |
| Ctrl / Cmd + S | Save current notebook |
| Ctrl / Cmd + Shift + S | Save a notebook copy under another name |
| Ctrl / Cmd + F / H | Find / find and replace notebook source |
| Ctrl / Cmd + G / Shift + G | Next / previous source match |
| Ctrl / Cmd + Shift + C | JupyterLab command palette |
| Ctrl / Cmd + Shift + P | Classic palette alias |
| Ctrl / Cmd + Shift + H | Show shortcuts |
| Ctrl / Cmd + B | Toggle left sidebar; Markdown editor's bold binding takes precedence |
| Ctrl / Cmd + Shift + F / Alt + 1 | Open/focus file browser |
| Alt + W / Ctrl + Shift + Q | Close notebook tab and its kernel |
| Ctrl / Cmd + Shift + [ / ] | Activate preceding / following notebook tab |
| Ctrl / Cmd + Shift + T | Reopen the most recently closed notebook |
| Ctrl / Cmd + Shift + D outside the editor | Toggle simple interface |
| Ctrl / Cmd + P | Print notebook |
| Alt + N | New Java notebook (Java Lab shortcut) |
| ↑ / ↓ on a file row | Focus preceding / following file |
| Enter on a notebook row | Open notebook |
| F2 on a notebook row | Rename notebook |
| Delete on a notebook row | Delete notebook via the existing dialog |
| Ctrl / Cmd + D on a notebook row | Duplicate notebook file |

Shortcuts do not treat letters typed in search fields, Grid.js filters, dialogs, or other form inputs as cell commands. Browser/OS-reserved keys depend on whether the browser delivers the event. Jupyter actions for terminals, debuggers, settings extensions, or additional dock panels are outside Java Lab's current notebook interface and are not remapped to unrelated commands.
