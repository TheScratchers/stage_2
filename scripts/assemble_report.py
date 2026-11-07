#!/usr/bin/env python3
"""
Assembles all individual Markdown chapters from docs/report/ into a unified,
consistent report document and compiles it to PDF with Table of Contents and page numbers.
"""

import os
import re
import subprocess
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
REPORT_DIR = BASE_DIR / "docs" / "report"
OUTPUT_MD = BASE_DIR / "docs" / "report_assembled.md"
OUTPUT_PDF = BASE_DIR / "docs" / "report.pdf"

def read_file(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()

def clean_mermaid(text):
    # For typst PDF compilation, convert mermaid blocks into formatted code blocks or text
    # so typst renders them cleanly without missing compiler errors
    return re.sub(r'```mermaid\n(.*?)\n```', r'```text\n[Architecture Diagram]\n\1\n```', text, flags=re.DOTALL)

def assemble():
    print("Assembling report sections...")

    portada = read_file(REPORT_DIR / "portada.md")
    introduccion = read_file(REPORT_DIR / "introduccion.md")
    arquitectura = clean_mermaid(read_file(REPORT_DIR / "arquitectura.md"))
    api = read_file(REPORT_DIR / "api.md")
    ingestion = read_file(REPORT_DIR / "ingestion.md")
    indexing_full = read_file(REPORT_DIR / "indexing.md")
    search = read_file(REPORT_DIR / "search.md")
    design = read_file(REPORT_DIR / "design-decisions.md")
    ingestion_bench = read_file(REPORT_DIR / "ingestion-benchmarks.md")
    integration_bench = read_file(REPORT_DIR / "integration-benchmarks.md")
    conclusions = read_file(REPORT_DIR / "conclusions.md")

    # Split indexing.md into service implementation (5.2) and JMH microbenchmarks (7.2)
    split_marker = "## Microbenchmarks (JMH)"
    if split_marker in indexing_full:
        indexing_impl, indexing_jmh = indexing_full.split(split_marker, 1)
        indexing_jmh = "## 7.2 Indexing JMH Microbenchmarks\n" + indexing_jmh
    else:
        indexing_impl = indexing_full
        indexing_jmh = ""

    # Normalize section headers
    # 5.x Ingestion -> 5.1 Ingestion Service
    ingestion = re.sub(r'^# 5\.x Ingestion Service', '# 5.1 Ingestion Service', ingestion, flags=re.MULTILINE)
    ingestion = re.sub(r'## 5\.x\.', '## 5.1.', ingestion)

    # 5.x Indexing -> 5.2 Indexing Service
    indexing_impl = re.sub(r'^# 5\.x Indexing Service', '# 5.2 Indexing Service', indexing_impl, flags=re.MULTILINE)

    # 5.x Search -> 5.3 Search Service
    search = re.sub(r'^# 5\.x Search Service', '# 5.3 Search Service', search, flags=re.MULTILINE)

    # 7.z Ingestion Benchmarks -> 7.1 Ingestion Throughput Benchmarks
    ingestion_bench = re.sub(r'^# 7\.z Ingestion Throughput Benchmarks', '# 7.1 Ingestion Throughput Benchmarks', ingestion_bench, flags=re.MULTILINE)
    ingestion_bench = re.sub(r'## 7\.z\.', '## 7.1.', ingestion_bench)

    # 7.x Integration Benchmarks -> 7.3 Integration Benchmarks
    integration_bench = re.sub(r'^# 7\.x Integration benchmarks', '# 7.3 Integration Benchmarks', integration_bench, flags=re.MULTILINE)

    # 8.x Conclusions -> 8. Conclusions and Future Work
    conclusions = re.sub(r'^# 8\.x Conclusions', '# 8. Conclusions', conclusions, flags=re.MULTILINE)

    # Section 7 introduction wrapper
    bench_intro = """# 7. Empirical Benchmarks and Performance Evaluation

This section presents the official empirical measurements of the system executed on the team reference machine (Intel Core i7-13700KF, 32 GB DDR5 RAM, 1 TB NVMe SSD, Windows 11, Temurin JDK 21.0.12). 

Performance is evaluated across three complementary dimensions:
1. **Section 7.1:** Concurrent throughput and latency of the Ingestion Service under fresh and idempotent workloads.
2. **Section 7.2:** Component-level microbenchmarks (JMH) isolating text parsing, tokenization, and SQLite persistence.
3. **Section 7.3:** System-wide integration benchmarks measuring end-to-end choreography, Search concurrency scaling (1 vs. 2 replicas), and SQLite single-writer contention.
"""

    sections = [
        portada,
        "\n\\newpage\n",
        introduccion,
        "\n\\newpage\n",
        arquitectura,
        "\n\\newpage\n",
        api,
        "\n\\newpage\n",
        "# 5. Service Implementation Details\n\nThis section details the internal architecture, classes, and operational mechanics of the three autonomous microservices comprising the search engine.",
        ingestion,
        indexing_impl,
        search,
        "\n\\newpage\n",
        design,
        "\n\\newpage\n",
        bench_intro,
        ingestion_bench,
        indexing_jmh,
        integration_bench,
        "\n\\newpage\n",
        conclusions
    ]

    assembled_content = "\n\n".join(sections)
    with open(OUTPUT_MD, "w", encoding="utf-8") as f:
        f.write(assembled_content)

    print(f"Assembled markdown written to {OUTPUT_MD}")

    # Compile with pandoc and typst
    print("Compiling PDF with pandoc and typst...")
    cmd = [
        "pandoc",
        str(OUTPUT_MD),
        "-o", str(OUTPUT_PDF),
        "--pdf-engine=typst",
        "--resource-path=" + str(REPORT_DIR),
        "--toc",
        "--toc-depth=3",
        "-V", "lang=en",
        "-V", "papersize=a4",
        "-V", "margin-top=2.5cm",
        "-V", "margin-bottom=2.5cm",
        "-V", "margin-left=2.5cm",
        "-V", "margin-right=2.5cm"
    ]
    subprocess.run(cmd, check=True)
    print(f"Report PDF successfully compiled to {OUTPUT_PDF}")

if __name__ == "__main__":
    assemble()
