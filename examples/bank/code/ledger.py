# The rules of the bank as plain functions: no game, no storage, so test_ledger.py can check every one of them.
# main.py moves the emeralds and saves the accounts; this file only decides what is allowed and what the books say.

MAX_AMOUNT = 100000        # most emeralds in one deposit, withdrawal or transfer
MAX_BALANCE = 2000000000   # an account never holds more than this
LOG_SIZE = 12              # how many recent lines an account remembers


def parse_amount(text):
    """The whole number the player typed, or None when it is empty, not a number, below 1 or above MAX_AMOUNT."""
    try:
        value = int(str(text).strip())
    except (TypeError, ValueError):
        return None
    return value if 1 <= value <= MAX_AMOUNT else None


def clean_name(text):
    """A Minecraft name (3 to 16 letters, digits, _) from what was typed, or None."""
    name = str(text or "").strip()
    return name if 3 <= len(name) <= 16 and all(c.isalnum() and c.isascii() or c == "_" for c in name) else None


def new_account():
    return {"balance": 0, "log": [], "unread": 0}


def clean_account(raw):
    """An account read back from storage: anything missing or of the wrong type is repaired, never trusted."""
    account = new_account()
    if isinstance(raw, dict):
        balance = raw.get("balance")
        if isinstance(balance, int) and not isinstance(balance, bool):
            account["balance"] = max(0, min(MAX_BALANCE, balance))
        log = raw.get("log")
        if isinstance(log, list):
            account["log"] = [str(line)[:80] for line in log if isinstance(line, str)][:LOG_SIZE]
        unread = raw.get("unread")
        if isinstance(unread, int) and not isinstance(unread, bool):
            account["unread"] = max(0, min(LOG_SIZE, unread))
    return account


def note(account, line, unread=False):
    """Adds a line at the top of the history; unread lines are told to the player at the next join."""
    account["log"].insert(0, line)
    del account["log"][LOG_SIZE:]
    if unread:
        account["unread"] = min(LOG_SIZE, account["unread"] + 1)


def room(account):
    """How many more emeralds the account can take."""
    return MAX_BALANCE - account["balance"]


def check_deposit(account, amount, carried):
    """None when the deposit is fine, otherwise why not."""
    if amount > carried:
        return "You carry only %d emerald(s)." % carried
    if amount > room(account):
        return "The account cannot hold that many emeralds."
    return None


def check_withdraw(account, amount):
    if amount > account["balance"]:
        return "Your balance is only %d emerald(s)." % account["balance"]
    return None


def check_transfer(source, target, amount, same_player):
    """target is the account of the other player, or None when there is none (they never joined)."""
    if target is None:
        return "That player has no account yet: they have to join the server once."
    if same_player:
        return "You cannot send emeralds to yourself."
    problem = check_withdraw(source, amount)
    if problem:
        return problem
    if amount > room(target):
        return "That account cannot hold that many emeralds."
    return None


def deposit(account, amount):
    account["balance"] += amount
    note(account, "Deposited %d" % amount)


def withdraw(account, amount):
    account["balance"] -= amount
    note(account, "Withdrew %d" % amount)


def transfer(source, target, amount, source_name, target_name):
    """Moves emeralds between two accounts. The caller has checked check_transfer and that the target has room."""
    source["balance"] -= amount
    target["balance"] += amount
    note(source, "Sent %d to %s" % (amount, target_name))
    note(target, "Received %d from %s" % (amount, source_name), unread=True)


def stacks(amount, size=64):
    """An amount cut into stacks that fit one inventory slot: 130 -> [64, 64, 2]."""
    return [min(size, amount - done) for done in range(0, amount, size)]
