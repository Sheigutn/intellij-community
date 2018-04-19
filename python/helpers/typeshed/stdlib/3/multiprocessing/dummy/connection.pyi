from typing import Any, List, Optional, Tuple, Type, TypeVar

from queue import Queue

__all__ = ...  # type: List[str]
families = ...  # type: List[None]

_TConnection = TypeVar('_TConnection', bound=Connection)
_TListener = TypeVar('_TListener', bound=Listener)

class Connection(object):
    _in = ...  # type: Any
    _out = ...  # type: Any
    recv = ...  # type: Any
    recv_bytes = ...  # type: Any
    send = ...  # type: Any
    send_bytes = ...  # type: Any
    def __enter__(self: _TConnection) -> _TConnection: ...
    def __exit__(self, exc_type, exc_value, exc_tb) -> None: ...
    def __init__(self, _in, _out) -> None: ...
    def close(self) -> None: ...
    def poll(self, timeout: float=...) -> bool: ...

class Listener(object):
    _backlog_queue = ...  # type: Optional[Queue]
    @property
    def address(self) -> Optional[Queue]: ...
    def __enter__(self: _TListener) -> _TListener: ...
    def __exit__(self, exc_type, exc_value, exc_tb) -> None: ...
    def __init__(self, address=..., family=..., backlog=...) -> None: ...
    def accept(self) -> Connection: ...
    def close(self) -> None: ...


def Client(address) -> Connection: ...
def Pipe(duplex: bool=...) -> Tuple[Connection, Connection]: ...
