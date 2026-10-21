# Bank

Keep emeralds safe in an account, take them out again, or send them to other players, even when they are offline.

- Module id: `bank`
- Works on: client and server (the server holds the accounts)
- CraftPy Python API: 2.10
- In the Hub: **Bank** in the Apps tab (declared under `apps` in `project.json`; the icon is `assets/icon.png`)
- Needs: nothing but CraftPy; asks for no permissions

## How to use it

Open the Hub, then Apps, then **Bank**. Or run `/bank:open`.

- **Deposit** moves the typed number of emeralds from your inventory into your account; **Deposit all** moves every emerald you carry.
- **Withdraw** and **Withdraw all** hand emeralds back (in stacks of 64; a full inventory drops the rest at your feet).
- **Send**: type a player name and an amount. The player must have joined the server once (so the bank knows them). Online
  players are told at once; offline players hear about it the next time they join.
- **Recent** lists your last twelve movements.
- The same things as commands: `/bank:balance`, `/bank:deposit <n>`, `/bank:withdraw <n>`, `/bank:send <player> <n>`.

Amounts are whole numbers from 1 to 100 000 per action; an account holds at most 2 000 000 000.

## How it is made

- `ui/bank.json` is the window, drawn in the UI maker; its labels and the history list are bound to `@ui.data('bank')`.
- `code/ledger.py` holds the rules as plain functions (what is allowed, what the books say), with no game in them.
  `code/test_ledger.py` checks them (Ctrl+Shift+F10).
- `code/main.py` moves the emeralds (`player.count`, `player.take`, `player.give`) and keeps accounts in `storage`, saved with the world.
  - The server decides everything: the window only sends what you typed.
  - A deposit books what really left the inventory; a withdrawal books first and gives afterwards, and takes the booking
    back if the emeralds cannot be handed out.
  - Names are looked up in a directory of players who joined, never by what the client claims.
