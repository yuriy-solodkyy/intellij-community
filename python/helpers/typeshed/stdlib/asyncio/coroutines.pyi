from typing import Any, Callable, TypeVar

_F = TypeVar("_F", bound=Callable[..., Any])

def coroutine(func: _F) -> _F: ...
def iscoroutinefunction(func: object) -> bool: ...
def iscoroutine(obj: object) -> bool: ...
