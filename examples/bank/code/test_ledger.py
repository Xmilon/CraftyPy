# Checks of the rules in ledger.py (Ctrl+Shift+F10 runs every test_* function of code/test_*.py).
import ledger


def test_amounts_are_whole_numbers_from_one_to_the_limit():
    assert ledger.parse_amount("5") == 5
    assert ledger.parse_amount(" 12 ") == 12
    assert ledger.parse_amount(ledger.MAX_AMOUNT) == ledger.MAX_AMOUNT
    for bad in ("", "0", "-3", "2.5", "ten", None, ledger.MAX_AMOUNT + 1, "1e3"):
        assert ledger.parse_amount(bad) is None, bad


def test_names():
    assert ledger.clean_name(" Steve_01 ") == "Steve_01"
    for bad in ("", "ab", "x" * 17, "bad name", "a;b", None, "naïve1"):
        assert ledger.clean_name(bad) is None, bad


def test_deposit_and_withdraw_move_the_balance_and_write_the_history():
    account = ledger.new_account()
    assert ledger.check_deposit(account, 10, 5) is not None          # carries only 5
    assert ledger.check_deposit(account, 5, 5) is None
    ledger.deposit(account, 5)
    assert account["balance"] == 5 and account["log"][0] == "Deposited 5"
    assert ledger.check_withdraw(account, 6) is not None
    assert ledger.check_withdraw(account, 5) is None
    ledger.withdraw(account, 5)
    assert account["balance"] == 0 and account["log"][0] == "Withdrew 5"


def test_an_account_has_a_ceiling():
    account = ledger.new_account()
    account["balance"] = ledger.MAX_BALANCE - 3
    assert ledger.room(account) == 3
    assert ledger.check_deposit(account, 4, 10) is not None
    assert ledger.check_deposit(account, 3, 10) is None


def test_transfer_checks_the_other_player_and_books_both_sides():
    source, target = ledger.new_account(), ledger.new_account()
    source["balance"] = 10
    assert "no account" in ledger.check_transfer(source, None, 1, False)
    assert "yourself" in ledger.check_transfer(source, target, 1, True)
    assert "only 10" in ledger.check_transfer(source, target, 11, False)
    target["balance"] = ledger.MAX_BALANCE
    assert "cannot hold" in ledger.check_transfer(source, target, 1, False)
    target["balance"] = 0
    assert ledger.check_transfer(source, target, 4, False) is None
    ledger.transfer(source, target, 4, "Alex", "Steve")
    assert source["balance"] == 6 and target["balance"] == 4
    assert source["log"][0] == "Sent 4 to Steve" and target["log"][0] == "Received 4 from Alex"
    assert target["unread"] == 1 and source["unread"] == 0


def test_history_keeps_only_the_latest_lines():
    account = ledger.new_account()
    for n in range(ledger.LOG_SIZE + 5):
        ledger.note(account, "line %d" % n)
    assert len(account["log"]) == ledger.LOG_SIZE and account["log"][0] == "line %d" % (ledger.LOG_SIZE + 4)


def test_a_damaged_account_is_repaired_not_trusted():
    assert ledger.clean_account(None) == ledger.new_account()
    assert ledger.clean_account("nonsense") == ledger.new_account()
    fixed = ledger.clean_account({"balance": -5, "log": ["ok", 3, None], "unread": True})
    assert fixed == {"balance": 0, "log": ["ok"], "unread": 0}, fixed
    assert ledger.clean_account({"balance": 10 ** 12})["balance"] == ledger.MAX_BALANCE
    assert ledger.clean_account({"balance": True})["balance"] == 0


def test_amounts_are_cut_into_stacks():
    assert ledger.stacks(1) == [1]
    assert ledger.stacks(64) == [64]
    assert ledger.stacks(130) == [64, 64, 2]
    assert sum(ledger.stacks(100000)) == 100000
