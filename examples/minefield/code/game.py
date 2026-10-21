# The rules of the minefield, with nothing from the game in it: a grid, hidden mines, numbers and flood opening.
import random

SIZE = 8
MINES = 10


class Game:
    def __init__(self, size=SIZE, mines=MINES):
        self.size = size
        self.mines = mines
        self.mine = [[False] * size for _ in range(size)]
        self.near = [[0] * size for _ in range(size)]
        self.opened = [[False] * size for _ in range(size)]
        self.flagged = [[False] * size for _ in range(size)]
        self.placed = False
        self.state = "playing"            # playing, lost or won
        self.hit = None                   # (row, col) of the mine that was stepped on

    # -- helpers -------------------------------------------------------------------------------------------------
    def around(self, r, c):
        for dr in (-1, 0, 1):
            for dc in (-1, 0, 1):
                nr, nc = r + dr, c + dc
                if (dr or dc) and 0 <= nr < self.size and 0 <= nc < self.size:
                    yield nr, nc

    def safe_total(self):
        return self.size * self.size - self.mines

    def cleared(self):
        return sum(1 for r in range(self.size) for c in range(self.size) if self.opened[r][c])

    def safe_left(self):
        return self.safe_total() - self.cleared()

    def flags(self):
        return sum(1 for r in range(self.size) for c in range(self.size) if self.flagged[r][c])

    def mine_cells(self):
        return [(r, c) for r in range(self.size) for c in range(self.size) if self.mine[r][c]]

    # -- play ----------------------------------------------------------------------------------------------------
    def _place(self, first_r, first_c):
        """Mines are laid after the first click, never on it or right next to it."""
        free = [(r, c) for r in range(self.size) for c in range(self.size)
                if abs(r - first_r) > 1 or abs(c - first_c) > 1]
        for r, c in random.sample(free, self.mines):
            self.mine[r][c] = True
        for r in range(self.size):
            for c in range(self.size):
                self.near[r][c] = sum(1 for nr, nc in self.around(r, c) if self.mine[nr][nc])
        self.placed = True

    def flag(self, r, c):
        if self.state == "playing" and not self.opened[r][c]:
            self.flagged[r][c] = not self.flagged[r][c]

    def open(self, r, c):
        """Opens a tile. Returns "boom", "won" or "ok"."""
        if self.state != "playing" or self.opened[r][c] or self.flagged[r][c]:
            return "ok"
        if not self.placed:
            self._place(r, c)
        if self.mine[r][c]:
            self.state = "lost"
            self.hit = (r, c)
            self.opened[r][c] = True
            return "boom"
        todo = [(r, c)]
        while todo:
            cr, cc = todo.pop()
            if self.opened[cr][cc] or self.mine[cr][cc]:
                continue
            self.opened[cr][cc] = True
            self.flagged[cr][cc] = False
            if self.near[cr][cc] == 0:
                todo.extend(self.around(cr, cc))
        if self.safe_left() == 0:
            self.state = "won"
            return "won"
        return "ok"
