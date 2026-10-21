# Task Journal

A personal checklist per player, with optional reminders.

- Module id: `task_journal`
- Works on: client and server
- CraftPy Python API: 2.9
- Asks for: no permissions

## What it does

- Up to 32 tasks of up to 100 characters each, per world and per player.
- Select a task and press the finish button to mark it done or open again; the summary shows how many are finished.
- Reminders, when on, tell you every five minutes how many tasks are still open.

## How to use it

- `Ctrl+Shift+J` or `/task_journal:open` opens the window. Change the key in Controls or on the Module Keybinds page.
- `/task_journal:reminders true` or `false` turns the reminders on or off; the window has a checkbox for the same.

## How it is made

- `ui/journal.json` is the window layout. Edit it visually in the UI maker.
- `code/main.py`: `@ui.data('journal')` supplies the task list, the summary and the reminder state;
  the actions `add_task`, `finish_task` and `journal_reminders` change the saved data on the server.
