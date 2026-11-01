"""Small, dependency-free helpers for reading and rewriting smali files."""
from __future__ import annotations

import itertools
import re
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator

_SIG = re.compile(r"^(?P<name>.+?)\((?P<params>[^)]*)\)(?P<ret>.+)$")
_CONST_STRING = re.compile(
    r'^(?P<head>\s*const-string(?:/jumbo)?\s+[vp]\d+,\s*)"(?P<s>.*)"\s*$'
)
_FIELD_REF = re.compile(
    r"^\s*[is]get[\w-]*\s+[vp]\d+,\s*(?:[vp]\d+,\s*)?(?P<ref>L\S+->\S+)\s*$"
)
_DESCRIPTOR = re.compile(r"\[*(?:L[^;]+;|[ZBSCIJFD])")


def count_params(params: str) -> int:
    """Number of parameters in a smali parameter descriptor string."""
    return len(_DESCRIPTOR.findall(params))


@dataclass
class Method:
    start: int  # index of the ".method" line
    end: int  # index of the ".end method" line
    modifiers: tuple[str, ...]
    name: str
    params: str
    ret: str

    @property
    def signature(self) -> str:
        return f"{self.name}({self.params}){self.ret}"

    @property
    def is_concrete(self) -> bool:
        return not ({"abstract", "native"} & set(self.modifiers))

    @property
    def returns_reference(self) -> bool:
        return self.ret[:1] in ("L", "[")


class SmaliFile:
    def __init__(self, path: Path, text: str | None = None) -> None:
        self.path = path
        if text is None:
            text = path.read_text(encoding="utf-8", errors="surrogateescape")
        self.text = text
        self.lines = text.split("\n")

    @property
    def class_name(self) -> str:
        for line in self.lines[:5]:
            if line.startswith(".class"):
                return line.split()[-1]
        return self.path.stem

    def methods(self) -> list[Method]:
        found: list[Method] = []
        start = -1
        header = ""
        for i, line in enumerate(self.lines):
            if line.startswith(".method "):
                start, header = i, line
            elif line.startswith(".end method") and start >= 0:
                tokens = header.split()[1:]
                match = _SIG.match(tokens[-1]) if tokens else None
                if match:
                    found.append(
                        Method(
                            start,
                            i,
                            tuple(tokens[:-1]),
                            match["name"],
                            match["params"],
                            match["ret"],
                        )
                    )
                start = -1
        return found

    def body(self, m: Method) -> list[str]:
        return self.lines[m.start + 1 : m.end]

    def strings(self, m: Method) -> Iterator[str]:
        for line in self.body(m):
            hit = _CONST_STRING.match(line)
            if hit:
                yield hit["s"]

    def rewrite_strings(self, m: Method, should_rewrite, prefix: str) -> int:
        """Prefix every matching const-string in the method; returns how many changed."""
        changed = 0
        for i in range(m.start + 1, m.end):
            hit = _CONST_STRING.match(self.lines[i])
            if hit and not hit["s"].startswith(prefix) and should_rewrite(hit["s"]):
                self.lines[i] = f'{hit["head"]}"{prefix}{hit["s"]}"'
                changed += 1
        return changed

    def inject_at_start(self, m: Method, lines: list[str]) -> None:
        """Insert lines before the method's first real instruction (after .locals/.param/annotations)."""
        body = self.body(m)
        idx = len(body)
        in_annotation = False
        for i, line in enumerate(body):
            t = line.strip()
            if t.startswith(".annotation"):
                in_annotation = True
                continue
            if t.startswith(".end annotation"):
                in_annotation = False
                continue
            if in_annotation or not t or t[0] in ".#:":
                continue
            idx = i
            break
        self.lines[m.start + 1 : m.end] = body[:idx] + lines + body[idx:]

    def field_refs(self, m: Method) -> Iterator[str]:
        for line in self.body(m):
            hit = _FIELD_REF.match(line)
            if hit:
                yield hit["ref"]

    def instruction_count(self, m: Method) -> int:
        n = 0
        for line in self.body(m):
            s = line.strip()
            if s and s[0] not in ".#:":
                n += 1
        return n

    def replace_body(self, m: Method, body: list[str]) -> None:
        self.lines[m.start + 1 : m.end] = body

    def save(self) -> None:
        self.path.write_text(
            "\n".join(self.lines), encoding="utf-8", errors="surrogateescape"
        )


def make_stub(ret: str, boolean: bool = False) -> list[str]:
    """Method body that immediately returns false/0/null/void (or true)."""
    if ret == "V":
        return ["    .locals 0", "", "    return-void"]
    if ret in ("J", "D"):
        return ["    .locals 2", "", "    const-wide/16 v0, 0x0", "", "    return-wide v0"]
    if ret in ("Z", "B", "S", "C", "I", "F"):
        value = "0x1" if (ret == "Z" and boolean) else "0x0"
        return ["    .locals 1", "", f"    const/4 v0, {value}", "", "    return v0"]
    return ["    .locals 1", "", "    const/4 v0, 0x0", "", "    return-object v0"]


def smali_roots(root: Path) -> list[Path]:
    return sorted(
        p
        for p in root.iterdir()
        if p.is_dir() and (p.name == "smali" or p.name.startswith("smali_"))
    )


def iter_smali_files(root: Path) -> Iterator[Path]:
    for directory in smali_roots(root):
        yield from directory.rglob("*.smali")


def find_candidates(
    root: Path, needles: dict[str, Iterable[str]], workers: int = 8
) -> dict[str, list[Path]]:
    """Single pass over every smali file; returns, per key, files containing any needle."""
    encoded = {k: tuple(n.encode() for n in v) for k, v in needles.items()}
    hits: dict[str, list[Path]] = {k: [] for k in needles}

    def scan(path: Path) -> tuple[Path, list[str]]:
        data = path.read_bytes()
        return path, [k for k, ns in encoded.items() if any(n in data for n in ns)]

    files = iter_smali_files(root)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        while True:
            batch = list(itertools.islice(files, 5000))
            if not batch:
                break
            for path, keys in pool.map(scan, batch):
                for key in keys:
                    hits[key].append(path)
    return hits
