from craftpy import *
import ledger

# Bank: keep emeralds in an account, take them out, send them to other players.
#
# The window (ui/bank.json) and the commands do the same three things through the functions below. All the checking is
# here on the server: the window only sends what the player typed, and ledger.py decides if it is allowed.

EMERALD = "minecraft:emerald"
STATUS = {}   # player uuid -> the last answer shown at the bottom of the window (not saved)


# ---- accounts: saved with the world in storage, one entry per player, and a name directory for sending ----

def key(uuid):
    return "account:" + uuid


def load(uuid):
    return ledger.clean_account(storage.get(key(uuid)))


def save(uuid, account):
    storage.set(key(uuid), account)


def remember(player):
    """Writes the player into the directory (name -> uuid), so others can send to them even when they are offline."""
    names = storage.get("names", {})
    if not isinstance(names, dict):
        names = {}
    if names.get(player.name.lower()) != [player.uuid, player.name]:
        names[player.name.lower()] = [player.uuid, player.name]
        storage.set("names", names)
    if storage.get(key(player.uuid)) is None:
        save(player.uuid, ledger.new_account())


def find(name):
    """(uuid, name) of a known player by the name that was typed, or None."""
    clean = ledger.clean_name(name)
    entry = storage.get("names", {}).get(clean.lower()) if clean else None
    return (entry[0], entry[1]) if isinstance(entry, list) and len(entry) == 2 else None


def answer(player, text):
    STATUS[player.uuid] = text
    player.actionbar(text)


# ---- the three things you can do ----

def do_deposit(player, amount):
    remember(player)
    account = load(player.uuid)
    problem = ledger.check_deposit(account, amount, player.count(EMERALD))
    if problem:
        return answer(player, problem)
    taken = player.take(EMERALD, amount)          # what really left the inventory is what is booked
    if taken > 0:
        ledger.deposit(account, taken)
        save(player.uuid, account)
    answer(player, "Deposited %d emerald(s). Balance: %d." % (taken, account["balance"]))


def do_withdraw(player, amount):
    remember(player)
    account = load(player.uuid)
    problem = ledger.check_withdraw(account, amount)
    if problem:
        return answer(player, problem)
    ledger.withdraw(account, amount)              # book it first, so a crash cannot hand out emeralds twice
    save(player.uuid, account)
    try:
        for stack in ledger.stacks(amount):
            player.give(EMERALD, stack)           # a full inventory drops the rest at your feet
    except Exception as error:                    # the emeralds did not arrive: take the booking back
        account = load(player.uuid)
        account["balance"] += amount
        account["log"].pop(0)
        save(player.uuid, account)
        return answer(player, "Could not hand out the emeralds: " + str(error))
    answer(player, "Withdrew %d emerald(s). Balance: %d." % (amount, account["balance"]))


def do_send(player, name, amount):
    remember(player)
    found = find(name)
    source = load(player.uuid)
    target = load(found[0]) if found else None
    problem = ledger.check_transfer(source, target, amount, bool(found) and found[0] == player.uuid)
    if problem:
        return answer(player, problem)
    ledger.transfer(source, target, amount, player.name, found[1])
    save(player.uuid, source)
    save(found[0], target)
    answer(player, "Sent %d emerald(s) to %s. Balance: %d." % (amount, found[1], source["balance"]))
    other = Player.named(found[1])
    if other:                                     # online: tell them now, offline: they hear at the next join
        target["unread"] = 0
        save(found[0], target)
        other.say("%s sent you %d emerald(s) in the bank. Balance: %d." % (player.name, amount, target["balance"]))


# ---- commands: the same actions without the window ----

@command("balance", level=0)
def balance(ctx):
    """/bank:balance - how many emeralds are in your account."""
    remember(ctx.player)
    ctx.player.say("Bank balance:", load(ctx.player.uuid)["balance"], "emerald(s)")


@command("deposit", level=0)
def deposit_command(ctx, amount: int):
    """/bank:deposit <amount> - put emeralds from your inventory into your account."""
    value = ledger.parse_amount(amount)
    do_deposit(ctx.player, value) if value else ctx.player.say("Choose an amount from 1 to", ledger.MAX_AMOUNT)


@command("withdraw", level=0)
def withdraw_command(ctx, amount: int):
    """/bank:withdraw <amount> - take emeralds out of your account."""
    value = ledger.parse_amount(amount)
    do_withdraw(ctx.player, value) if value else ctx.player.say("Choose an amount from 1 to", ledger.MAX_AMOUNT)


@command("send", level=0)
def send_command(ctx, name: str, amount: int):
    """/bank:send <player> <amount> - send emeralds from your account to another player."""
    value = ledger.parse_amount(amount)
    do_send(ctx.player, name, value) if value else ctx.player.say("Choose an amount from 1 to", ledger.MAX_AMOUNT)


# ---- the window ----

def typed_amount(ctx):
    return ledger.parse_amount(ctx.values.get("amount"))


@ui.data("bank")
def bank_data(ctx):
    remember(ctx.player)
    account = load(ctx.player.uuid)
    return dict(
        balance="Balance: %d emerald(s)" % account["balance"],
        carrying="In your inventory: %d" % ctx.player.count(EMERALD),
        history=account["log"] or ["Nothing yet. Deposit some emeralds."],
        status=STATUS.get(ctx.player.uuid, ""))


@action("bank_deposit")
def bank_deposit(ctx):
    amount = typed_amount(ctx)
    do_deposit(ctx.player, amount) if amount else answer(ctx.player, "Type an amount from 1 to %d." % ledger.MAX_AMOUNT)


@action("bank_deposit_all")
def bank_deposit_all(ctx):
    carried = ctx.player.count(EMERALD)
    do_deposit(ctx.player, min(carried, ledger.MAX_AMOUNT)) if carried else answer(ctx.player, "You carry no emeralds.")


@action("bank_withdraw")
def bank_withdraw(ctx):
    amount = typed_amount(ctx)
    do_withdraw(ctx.player, amount) if amount else answer(ctx.player, "Type an amount from 1 to %d." % ledger.MAX_AMOUNT)


@action("bank_withdraw_all")
def bank_withdraw_all(ctx):
    balance = load(ctx.player.uuid)["balance"]
    do_withdraw(ctx.player, min(balance, ledger.MAX_AMOUNT)) if balance else answer(ctx.player, "Your account is empty.")


@action("bank_send")
def bank_send(ctx):
    amount = typed_amount(ctx)
    if not amount:
        return answer(ctx.player, "Type an amount from 1 to %d." % ledger.MAX_AMOUNT)
    if not ledger.clean_name(ctx.values.get("recipient")):
        return answer(ctx.player, "Type the name of the player to send to.")
    do_send(ctx.player, ctx.values.get("recipient"), amount)


@command("open", level=0)
def open_bank(ctx):
    """/bank:open - open the bank window."""
    ui.open(ctx.player, "bank")


# ---- joining ----

@on.player_join
def joined(event):
    remember(event.player)
    account = load(event.player.uuid)
    if account["unread"]:
        event.player.say("Bank: you received emeralds while you were away. /bank:open shows them.")
        account["unread"] = 0
        save(event.player.uuid, account)
