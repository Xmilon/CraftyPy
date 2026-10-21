# Server Toolkit

A few helpers for running a server: status, a join greeting and operator-only commands.

- Module id: `server_toolkit`
- Works on: client and server
- CraftPy Python API: 2.9
- Asks for: no permissions

## What it does

- **Status**: players online, the maximum and the average tick time.
- **Greeting**: tells a joining player that the status command exists.
- **Operator commands**: announce a message, set the day, clear the weather. Anyone else is ignored silently.

## Commands

- `/server_toolkit:status` (everyone)
- `/server_toolkit:announce <text>` (operators; broadcast, cut to 180 characters)
- `/server_toolkit:day` (operators; sets daytime in your dimension)
- `/server_toolkit:clear` (operators; clears the weather in your dimension)
- `Ctrl+Shift+I` shows the status. Change the key in Controls or on the Module Keybinds page.

## How it is made

`code/main.py` checks `ctx.player.operator` inside each operator command, so the check cannot be skipped from the
client. Add new operator tools the same way.
