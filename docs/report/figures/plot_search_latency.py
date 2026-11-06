"""Draws Figure 7.x.1 of the report from the official search benchmark results.

Usage, from the repository root (needs matplotlib):
    python docs/report/figures/plot_search_latency.py

Reads benchmarks/results/20261105_search-latency-{1-replica,2-replicas}/summary.csv and writes
docs/report/figures/search-latency.png. Throughput and latency have different units, so they are
drawn in two panels that share the x axis instead of on two y scales.
"""
import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import matplotlib.ticker  # noqa: E402

RESULTS = Path("benchmarks/results")
OUTPUT = Path("docs/report/figures/search-latency.png")
SERIES = [  # fixed categorical order: slot 1 blue, slot 2 orange
    ("1 replica", RESULTS / "20261105_search-latency-1-replica" / "summary.csv", "#2a78d6", "o"),
    ("2 replicas", RESULTS / "20261105_search-latency-2-replicas" / "summary.csv", "#eb6834", "s"),
]
SURFACE, INK, MUTED, GRID = "#ffffff", "#0b0b0b", "#52514e", "#e4e3df"


def read(path):
    with open(path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    return ([int(r["clients"]) for r in rows], [float(r["throughput_rps"]) for r in rows],
            [float(r["p50_ms"]) for r in rows])


def style(ax, title, ylabel):
    ax.set_facecolor(SURFACE)
    ax.set_title(title, loc="left", fontsize=11, color=INK, fontweight="bold")
    ax.set_xscale("log", base=2)
    ax.set_xticks([1, 2, 4, 8, 16, 32, 64])
    ax.set_xticklabels(["1", "2", "4", "8", "16", "32", "64"])
    ax.set_xlabel("Concurrent clients", color=MUTED)
    ax.set_ylabel(ylabel, color=MUTED)
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(GRID)
    ax.tick_params(colors=MUTED, labelsize=9)
    ax.set_ylim(bottom=0)


def main():
    fig, (left, right) = plt.subplots(1, 2, figsize=(10, 4), dpi=200, facecolor=SURFACE)
    for name, path, color, marker in SERIES:
        clients, throughput, p50 = read(path)
        for ax, values in ((left, throughput), (right, p50)):
            ax.plot(clients, values, color=color, linewidth=2, marker=marker, markersize=6,
                    markeredgecolor=SURFACE, markeredgewidth=1.5, label=name)
    style(left, "Throughput", "Requests per second")
    style(right, "Median latency (p50)", "Milliseconds")
    left.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: f"{v:,.0f}"))

    # Direct labels at the saturation point, so identity never depends on colour alone.
    left.annotate("peak: 5,523 req/s\nat 16 clients (1 replica)", xy=(16, 5523), xytext=(1.1, 4300),
                  fontsize=8, color=INK, arrowprops=dict(arrowstyle="-", color=MUTED, linewidth=0.8))
    right.annotate("64 clients: about 12 ms\n(clients waiting in the queue)", xy=(64, 11.93), xytext=(4, 9.5),
                   fontsize=8, color=INK, arrowprops=dict(arrowstyle="-", color=MUTED, linewidth=0.8))
    left.legend(frameon=False, fontsize=9, loc="lower right")

    fig.text(0.01, 0.01, "Source: benchmarks/results/20261105_search-latency-*/summary.csv · reference machine "
             "(Intel Core i7-13700KF, 24 logical processors) · commit 04132ab", fontsize=7, color=MUTED)
    fig.tight_layout(rect=(0, 0.04, 1, 1))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUTPUT, facecolor=SURFACE)
    print("written", OUTPUT)


if __name__ == "__main__":
    main()
