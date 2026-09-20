"""The run ledger: what a phase already made, so a re-run adds rather than duplicates.

The app has no "create if absent" on most endpoints, and several things it creates are
legitimately duplicable (two purchase orders to the same vendor on the same day are not a
mistake). So idempotency cannot come from the API; it comes from remembering, under a key
the script chooses, the id of every row it made.

One JSON file per API base + tenant, so pointing the same scripts at a second environment
keeps a separate ledger. Written after every change, because a phase that dies half way
through must be resumable.
"""

from __future__ import annotations

import json
import os
import re
import tempfile
from pathlib import Path
from typing import Any, Callable, Optional


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:60]


class State:
    def __init__(self, state_dir: Path, api: str, tenant: str) -> None:
        self.path = Path(state_dir) / f"{_slug(api)}__{_slug(tenant)}.json"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.data: dict[str, Any] = {}
        if self.path.exists():
            self.data = json.loads(self.path.read_text())

    # ---- raw access -------------------------------------------------------

    def get(self, key: str) -> Optional[Any]:
        return self.data.get(key)

    def put(self, key: str, value: Any) -> Any:
        self.data[key] = value
        self._flush()
        return value

    def has(self, key: str) -> bool:
        return key in self.data

    def forget(self, prefix: str) -> int:
        """Drop every key under a prefix. For re-running one phase from scratch."""
        gone = [k for k in self.data if k.startswith(prefix)]
        for k in gone:
            del self.data[k]
        self._flush()
        return len(gone)

    def keys(self, prefix: str) -> list[str]:
        return sorted(k for k in self.data if k.startswith(prefix))

    # ---- the one everything uses -----------------------------------------

    def once(self, key: str, make: Callable[[], Any]) -> tuple[Any, bool]:
        """Return (value, created). Calls `make` only the first time for this key."""
        if key in self.data:
            return self.data[key], False
        value = make()
        self.data[key] = value
        self._flush()
        return value, True

    # ---- disk -------------------------------------------------------------

    def _flush(self) -> None:
        # Atomic: a killed script must never leave a half-written ledger, or the next
        # run loses every id it holds and makes everything a second time.
        fd, tmp = tempfile.mkstemp(dir=str(self.path.parent), suffix=".tmp")
        try:
            with os.fdopen(fd, "w") as handle:
                json.dump(self.data, handle, indent=1, sort_keys=True)
            os.replace(tmp, self.path)
        except BaseException:
            if os.path.exists(tmp):
                os.unlink(tmp)
            raise
