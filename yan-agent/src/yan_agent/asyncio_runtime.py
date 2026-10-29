import asyncio
import sys
from collections.abc import Callable, Coroutine
from typing import Any


def compatible_loop_factory() -> Callable[[], asyncio.AbstractEventLoop] | None:
    if sys.platform == "win32":
        return asyncio.SelectorEventLoop
    return None


def run[Result](coroutine: Coroutine[Any, Any, Result]) -> Result:
    return asyncio.run(coroutine, loop_factory=compatible_loop_factory())
