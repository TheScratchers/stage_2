#!/usr/bin/env python3
"""Builds docs/report.pdf from the report sections in docs/report/*.md.

Usage, from the repository root (needs pandoc >= 3.1 and typst >= 0.13 on the PATH):
    python scripts/build_report.py

The Markdown sections are the single source of the report: this script only assembles them in the
order of section 7.1 of the assignment, gives the cross-references their final numbers, replaces
each Mermaid diagram with its rendered image (docs/report/figures/architecture-<n>.png, regenerated
with `mmdc` when a diagram changes), converts the result to Typst with pandoc and compiles it with
Typst into an A4 PDF with a cover page, a table of contents and page numbers. The intermediate
docs/report.typ is kept so that the PDF can be inspected or rebuilt with `typst compile`.
"""
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SECTIONS = ROOT / "docs" / "report"
OUTPUT_TYP = ROOT / "docs" / "report.typ"
OUTPUT_PDF = ROOT / "docs" / "report.pdf"

# (file, part, extra heading levels, local section number for "5.x"), in report order.
# indexing.md holds two parts: the Indexing Service (section 5) and its JMH results (section 7).
PARTS = [
    ("introduccion.md", None, 0, None),
    ("arquitectura.md", None, 0, None),
    ("api.md", None, 0, None),
    ("# 5. Microservice implementation", None, 0, None),
    ("ingestion.md", None, 1, "5.1"),
    ("indexing.md", "service", 1, "5.2"),
    ("search.md", None, 1, "5.3"),
    ("design-decisions.md", None, 0, None),
    ("# 7. Benchmarks and results", None, 0, None),
    ("ingestion-benchmarks.md", None, 1, None),
    ("indexing.md", "jmh", 1, None),
    ("integration-benchmarks.md", None, 1, None),
    ("conclusions.md", None, 0, None),
]

# Provisional numbers used while the sections were written separately -> final numbers.
GLOBAL_RENUMBERING = [
    ("section 5.x, Indexing", "section 5.2"),
    ("(section 5.x)", "(section 5.2)"),
    ("7.z", "7.1"),
    ("7.y", "7.2"),
    ("7.x", "7.3"),
    ("# 8.x Conclusions and future improvements: towards the Stage 3 cluster",
     "# 8. Conclusions and future improvements"),
]

PREAMBLE = r"""
#set document(title: "Stage 2: Service-Oriented Search Engine", author: ("Pablo Martínez Suárez", "Amado Artiles Rodríguez", "Aimar Alejandro Santana"))
#set text(font: "Libertinus Serif", size: 10.5pt, lang: "en")
#set par(justify: true, leading: 0.6em)
#show raw: set text(font: "DejaVu Sans Mono", size: 7.6pt)
#show raw.where(block: true): block.with(fill: luma(246), inset: 7pt, radius: 3pt, width: 100%)
#show heading: set text(fill: rgb("#0f3875"))
#show heading.where(level: 1): it => { pagebreak(weak: true); v(0.4em); it; v(0.3em) }
#set table(stroke: 0.4pt + luma(170), inset: 4pt)
#show table: set text(size: 8.4pt)
#show table: set par(justify: false)
#show figure: set block(breakable: true)
#show link: set text(fill: rgb("#1d4ed8"))
"""

PAGE_SETUP = r"""
#set page(
  paper: "a4",
  margin: (top: 2.3cm, bottom: 2.3cm, x: 2.2cm),
  header: context {
    if counter(page).get().first() > 1 [
      #set text(size: 8pt, fill: luma(110))
      Stage 2: Service-Oriented Search Engine #h(1fr) TheScratchers · Big Data · ULPGC
      #v(-6pt) #line(length: 100%, stroke: 0.4pt + luma(190))
    ]
  },
  footer: context {
    if counter(page).get().first() > 1 [
      #set text(size: 8pt, fill: luma(110))
      #h(1fr) Page #counter(page).display("1 / 1", both: true)
    ]
  },
)
"""


def run(command, **kwargs):
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", **kwargs)
    if result.returncode != 0:
        sys.exit(f"{' '.join(command[:2])} failed:\n{result.stderr}")
    return result.stdout


def read_section(name, part):
    text = (SECTIONS / name).read_text(encoding="utf-8")
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)  # editorial notes are not part of the report
    if part is not None:
        service, _, jmh = text.partition("\n# 7.y ")
        text = service if part == "service" else "# 7.y " + jmh
    return text


# Captions of the Mermaid diagrams of arquitectura.md, in order of appearance.
DIAGRAM_CAPTIONS = [
    "Figure 3.1. Processes, front-end and internal interactions, broker destinations and shared storage, "
    "all on one host (rendered from the Mermaid source of this section).",
    "Figure 3.2. Threads and processing steps inside each service JVM.",
]
# Diagrams too wide for a portrait page are placed alone on a landscape page.
LANDSCAPE_DIAGRAMS = {1}


def replace_mermaid(text, counter):
    def figure(_match):
        counter[0] += 1
        n = counter[0]
        caption = DIAGRAM_CAPTIONS[n - 1] if n <= len(DIAGRAM_CAPTIONS) else ""
        marker = "LANDSCAPE-FIGURE" if n in LANDSCAPE_DIAGRAMS else "FIGURE"
        return f"\n\n{marker}:architecture-{n}.png:{caption}\n\n"
    return re.sub(r"```mermaid\n.*?```", figure, text, flags=re.S)


def place_diagrams(typ):
    """Turns the diagram markers left by replace_mermaid into Typst figures."""
    def figure(match):
        kind, image, caption = match.group(1), match.group(2), match.group(3).replace('"', "'")
        block = (f'#figure(image("report/figures/{image}", width: 100%), '
                 f'caption: [{caption}], supplement: none, numbering: none)')
        return f"#page(flipped: true)[{block}]" if kind == "LANDSCAPE-FIGURE" else block
    return re.sub(r"^(LANDSCAPE-FIGURE|FIGURE):([\w.-]+):(.*)$", figure, typ, flags=re.M)


def shift_headings(text, levels):
    if levels == 0:
        return text
    return re.sub(r"^(#{1,5}) ", lambda m: m.group(1) + "#" * levels + " ", text, flags=re.M)


def assemble():
    mermaid = [0]
    chunks = []
    for name, part, levels, number in PARTS:
        if name.startswith("#"):
            chunks.append(name + "\n")
            continue
        text = read_section(name, part)
        if number is not None:
            text = text.replace("5.x", number)
        for old, new in GLOBAL_RENUMBERING:
            text = text.replace(old, new)
        text = replace_mermaid(text, mermaid)
        text = text.replace("](figures/", "](report/figures/")
        chunks.append(shift_headings(text, levels))
    leftovers = sorted(set(re.findall(r"\b[5-8]\.[xyz]\b", "\n".join(chunks))))
    if leftovers:
        sys.exit(f"Provisional section numbers left in the text: {leftovers}")
    return "\n\n".join(chunks)


def to_typst(markdown):
    return run(["pandoc", "--from", "gfm", "--to", "typst", "--columns", "100", "--wrap", "preserve"],
               input=markdown)


def main():
    for tool in ("pandoc", "typst"):
        if shutil.which(tool) is None:
            sys.exit(f"{tool} is not on the PATH")
    cover = to_typst(read_section("portada.md", None))
    body = to_typst(assemble())
    # Figures use the full text width; pandoc keeps their natural size otherwise.
    body = re.sub(r'#box\(image\("([^"]+)"(, alt: "[^"]*")?\)\)', r'#image("\1", width: 100%\2)', body)
    body = place_diagrams(body)
    typ = (PREAMBLE + PAGE_SETUP
           + "\n#[\n#set heading(outlined: false)\n" + cover + "\n]\n"
           + "\n#pagebreak()\n#outline(title: [Table of Contents], depth: 2, indent: auto)\n"
           + body)
    OUTPUT_TYP.write_text(typ, encoding="utf-8")
    run(["typst", "compile", "--root", str(ROOT), str(OUTPUT_TYP), str(OUTPUT_PDF)])
    print(f"written {OUTPUT_PDF.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
