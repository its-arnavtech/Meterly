"""Extract a DOCX into reviewable Markdown without modifying the source file."""

from __future__ import annotations

import argparse
from pathlib import Path

from docx import Document
from docx.document import Document as DocumentType
from docx.table import Table
from docx.text.paragraph import Paragraph


def iter_blocks(parent: DocumentType):
    for child in parent.element.body.iterchildren():
        if child.tag.endswith("}p"):
            yield Paragraph(child, parent)
        elif child.tag.endswith("}tbl"):
            yield Table(child, parent)


def escape_cell(value: str) -> str:
    return " ".join(value.split()).replace("|", r"\|")


def extract(source: Path, destination: Path) -> None:
    document = Document(source)
    lines: list[str] = []

    for block in iter_blocks(document):
        if isinstance(block, Paragraph):
            text = block.text.strip()
            if not text:
                continue

            style = block.style.name if block.style else ""
            if style.startswith("Heading"):
                try:
                    level = min(int(style.split()[-1]), 6)
                except ValueError:
                    level = 2
                lines.extend((f"{'#' * level} {text}", ""))
            elif "List Bullet" in style:
                lines.extend((f"- {text}", ""))
            elif "List Number" in style:
                lines.extend((f"1. {text}", ""))
            else:
                lines.extend((text, ""))
        else:
            rows = [[escape_cell(cell.text) for cell in row.cells] for row in block.rows]
            if not rows:
                continue
            width = max(len(row) for row in rows)
            normalized = [row + [""] * (width - len(row)) for row in rows]
            lines.append("| " + " | ".join(normalized[0]) + " |")
            lines.append("| " + " | ".join(["---"] * width) + " |")
            lines.extend("| " + " | ".join(row) + " |" for row in normalized[1:])
            lines.append("")

    destination.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    extract(args.source, args.destination)


if __name__ == "__main__":
    main()
