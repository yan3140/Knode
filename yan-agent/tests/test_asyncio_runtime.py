import asyncio

from yan_agent import asyncio_runtime


def test_windows_uses_selector_event_loop(monkeypatch) -> None:
    monkeypatch.setattr(asyncio_runtime.sys, "platform", "win32")

    factory = asyncio_runtime.compatible_loop_factory()

    assert factory is asyncio.SelectorEventLoop


def test_non_windows_keeps_platform_default(monkeypatch) -> None:
    monkeypatch.setattr(asyncio_runtime.sys, "platform", "linux")

    assert asyncio_runtime.compatible_loop_factory() is None
