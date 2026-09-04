#!/usr/bin/env python3
"""Analyze instrumented timing/AMap soak logs; no phone data or coordinates are read."""
import argparse
import csv
import json
import io
from pathlib import Path
from statistics import median


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("csv", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--allow-incomplete", action="store_true", help="Analyze complete rows before a corrupt/truncated EOF; never declare a full soak passed")
    args = parser.parse_args()
    raw = args.csv.read_bytes()
    discarded_tail = 0
    if b"\0" in raw:
        prefix, _, tail = raw.partition(b"\0")
        if not args.allow_incomplete or tail.strip(b"\0"):
            raise SystemExit("Corrupt data: preserve the source and inspect it before analysis")
        discarded_tail = len(raw) - len(prefix)
        raw = prefix
    if not raw.endswith(b"\n"):
        if not args.allow_incomplete:
            raise SystemExit("Truncated final row")
        prefix, _, tail = raw.rpartition(b"\n")
        discarded_tail += len(tail)
        raw = prefix + b"\n"
    rows = [{key: float(value) for key, value in row.items()} for row in csv.DictReader(io.StringIO(raw.decode()))]
    if not rows:
        raise SystemExit("No samples")
    out = args.output or args.csv.parent
    out.mkdir(parents=True, exist_ok=True)
    warm = [r for r in rows if 60_000 <= r["elapsed_ms"] <= 360_000] or rows[:max(1, len(rows)//5)]
    tail = [r for r in rows if r["elapsed_ms"] >= rows[-1]["elapsed_ms"] - 300_000]
    steady = [r for r in rows if r["elapsed_ms"] > 10_000]
    summary = {
        "incomplete_input": args.allow_incomplete, "discarded_tail_bytes": discarded_tail,
        "samples": len(rows), "elapsed_seconds": rows[-1]["elapsed_ms"] / 1000,
        "max_sample_interval_ms": max((b["elapsed_ms"] - a["elapsed_ms"] for a, b in zip(rows, rows[1:])), default=0),
        "missing_timer_samples_after_10s": sum(r["timer_ms"] < 0 for r in steady),
        "max_timer_error_ms_after_10s": max((r["timer_error_ms"] for r in steady), default=0),
        "regressions": sum(b["timer_ms"] < a["timer_ms"] for a, b in zip(steady, steady[1:])),
        "stalls": sum(b["timer_ms"] == a["timer_ms"] for a, b in zip(steady, steady[1:])),
        "frames": rows[-1]["frames"], "max_frame_ms": rows[-1]["max_frame_ms"],
        "over_700ms_frames": rows[-1]["over_700ms_frames"],
    }
    for key in ("java_used_kb", "native_alloc_kb", "pss_kb", "private_dirty_kb"):
        summary[key] = {
            "minimum_mib": min(r[key] for r in rows) / 1024,
            "maximum_mib": max(r[key] for r in rows) / 1024,
            "warm_median_mib": median(r[key] for r in warm) / 1024,
            "last_5min_median_mib": median(r[key] for r in tail) / 1024,
            "median_delta_mib": (median(r[key] for r in tail) - median(r[key] for r in warm)) / 1024,
        }
    (out / "soak-analysis.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    lines = ["# 实时计时 / 高德长跑数据分析", "", f"样本 {len(rows)}；覆盖到 {summary['elapsed_seconds'] / 60:.2f} 分钟。", "",
             f"热身10秒后最大计时误差 {summary['max_timer_error_ms_after_10s']:.0f} ms；计时缺失 {summary['missing_timer_samples_after_10s']}；倒退 {summary['regressions']}；停滞 {summary['stalls']}。", "",
             f"最大采样间隔 {summary['max_sample_interval_ms']:.0f} ms；最长帧间隔 {summary['max_frame_ms']:.1f} ms；超过700ms的帧间隔 {summary['over_700ms_frames']:.0f} 次。", "",
             "| 指标 | 全程最小 MiB | 全程最大 MiB | 1–6分钟中位数 MiB | 最后5分钟中位数 MiB | 差值 MiB |", "|---|---:|---:|---:|---:|---:|"]
    if args.allow_incomplete:
        lines[2:2] = [f"**中断运行，仅分析完整行；未证明完整长测通过。原始文件保留，尾部排除 {discarded_tail} 字节。**", ""]
    for key in ("java_used_kb", "native_alloc_kb", "pss_kb", "private_dirty_kb"):
        v = summary[key]
        lines.append(f"| {key} | {v['minimum_mib']:.2f} | {v['maximum_mib']:.2f} | {v['warm_median_mib']:.2f} | {v['last_5min_median_mib']:.2f} | {v['median_delta_mib']:+.2f} |")
    lines += ["", "这是当前模拟器/构建/输入下的观测，不等于所有设备无泄漏。PSS会随GC、代码页和SDK缓存波动；native allocated不是完整native RSS。帧计数是UI Choreographer回调，不是高德GL线程帧率。", ""]
    (out / "soak-analysis.md").write_text("\n".join(lines))
    width, height = 1100, 620
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">', '<rect width="1100" height="620" fill="#0c1117"/>', '<style>text{fill:#d7dde5;font:14px sans-serif}</style>', '<text x="62" y="30" font-size="20">AMap + live timer soak / measured samples</text>']
    last = rows[-1]["elapsed_ms"]
    def panel(y, panel_height, keys, maximum, title):
        left, right = 70, 1030
        svg.append(f'<text x="{left}" y="{y - 15}">{title}</text>')
        for fraction in (0, .5, 1):
            yy = y + panel_height * (1 - fraction)
            svg.append(f'<path d="M{left},{yy} H{right}" stroke="#33404d" fill="none"/>')
            svg.append(f'<text x="5" y="{yy + 5}">{maximum * fraction:.0f}</text>')
        for key, divisor, color, label in keys:
            points = " ".join(f"{left + r['elapsed_ms'] / last * (right-left):.2f},{y + panel_height * (1 - min(maximum, max(0, r[key] / divisor)) / maximum):.2f}" for r in rows)
            svg.append(f'<polyline fill="none" stroke="{color}" stroke-width="1.2" points="{points}"/>')
        for i, (_, _, color, label) in enumerate(keys):
            xx = left + i * 225
            svg.append(f'<rect x="{xx}" y="{y + panel_height + 12}" width="15" height="5" fill="{color}"/><text x="{xx + 22}" y="{y + panel_height + 20}">{label}</text>')
    panel(70, 270, [("pss_kb", 1024, "#bd8cff", "PSS MiB"), ("native_alloc_kb", 1024, "#43d7a2", "Native allocated MiB"), ("java_used_kb", 1024, "#ffcd6c", "Java used MiB")], max(r["pss_kb"] for r in rows) / 1024 * 1.1, "Memory (MiB)")
    panel(425, 125, [("timer_error_ms", 1, "#66b9ff", "Timer error ms")], max(100, max(r["timer_error_ms"] for r in rows if r["timer_error_ms"] >= 0)), "Timer display error (ms)")
    for fraction in (0, .25, .5, .75, 1):
        svg.append(f'<text x="{70 + fraction * 960}" y="605">{last / 60000 * fraction:.1f} min</text>')
    svg.append('</svg>')
    (out / "soak-memory-timer.svg").write_text("\n".join(svg))
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
