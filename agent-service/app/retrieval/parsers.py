"""Safe text extraction for supported document formats."""

from __future__ import annotations

import csv
import io
import zipfile
from dataclasses import dataclass


class DocumentParseError(ValueError):
    """Raised when input is malformed or exceeds configured extraction limits."""


@dataclass(frozen=True)
class ParseLimits:
    """Maximum structural and text extraction work for one document."""

    max_pages: int = 100
    max_rows: int = 100_000
    max_characters: int = 500_000


def extract_text(filename: str, data: bytes, limits: ParseLimits) -> str:
    """Extract bounded text from PDF, DOCX, TXT, or CSV document bytes."""
    extension = filename.rsplit(".", 1)[-1].casefold() if "." in filename else ""
    try:
        if extension == "pdf":
            from pypdf import PdfReader

            reader = PdfReader(io.BytesIO(data), strict=True)
            if len(reader.pages) > limits.max_pages:
                raise DocumentParseError("PDF exceeds the maximum page count")
            pieces: list[str] = []
            characters = 0
            for page in reader.pages:
                text = page.extract_text() or ""
                characters += len(text)
                if characters > limits.max_characters:
                    raise DocumentParseError("Document exceeds the extracted character limit")
                pieces.append(text)
            return "\n".join(pieces)
        if extension == "docx":
            from docx import Document

            try:
                with zipfile.ZipFile(io.BytesIO(data)) as archive:
                    members = archive.infolist()
                    if len(members) > 5000 or sum(member.file_size for member in members) > limits.max_characters * 10:
                        raise DocumentParseError("DOCX exceeds the decompressed size limit")
            except DocumentParseError:
                raise
            except Exception as error:
                raise DocumentParseError("Could not parse document content") from error

            document = Document(io.BytesIO(data))
            pieces = [paragraph.text for paragraph in document.paragraphs]
            rows = 0
            for table in document.tables:
                for row in table.rows:
                    rows += 1
                    if rows > limits.max_rows:
                        raise DocumentParseError("DOCX exceeds the maximum row count")
                    pieces.append(" | ".join(cell.text for cell in row.cells))
            return _bounded_join(pieces, limits.max_characters)
        if extension in {"txt", "csv"}:
            text = data.decode("utf-8-sig", errors="strict")
            if len(text) > limits.max_characters:
                raise DocumentParseError("Document exceeds the extracted character limit")
            if extension == "txt":
                return text
            rows: list[str] = []
            for row in csv.reader(io.StringIO(text, newline="")):
                rows.append(" | ".join(row))
                if len(rows) > limits.max_rows:
                    raise DocumentParseError("CSV exceeds the maximum row count")
            return _bounded_join(rows, limits.max_characters)
        raise DocumentParseError("Unsupported document type")
    except DocumentParseError:
        raise
    except Exception as error:
        raise DocumentParseError("Could not parse document content") from error


def _bounded_join(pieces: list[str], max_characters: int) -> str:
    result = "\n".join(pieces)
    if len(result) > max_characters:
        raise DocumentParseError("Document exceeds the extracted character limit")
    return result
