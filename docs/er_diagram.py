#!/usr/bin/env python3
"""GPU Cluster Hub ER diagram in the class's crow's-foot notation.

Entities are rectangles listing their attributes (key underlined), and
relationships are diamonds. The mark drawn where a line meets an entity tells
how many of THAT entity relate to one instance on the other side of the
diamond:
    ||   exactly one        (mandatory one)
    o|   zero or one        (optional one)
    |<   one or many        (mandatory many)
    o<   zero or many       (optional many)
The mark next to the entity is the maximum (| or crow's foot), and the mark
farther out is the minimum (| mandatory, o optional).

Also drawn: the weak entity ModelVersion and its identifying relationship
HasVersion (double border, partial key dashed), and the aggregation of
MemberOf (dashed box) that Books attaches to.

Renders SVG -> PNG + single-page PDF via Playwright/Chromium.
"""
import math
import os
import subprocess

W, H = 2480, 1820
FONT = "Helvetica, Arial, sans-serif"
INK = "#111"
svg = []
add = svg.append
SHAPE = {}


def tw(text, size):
    return len(text) * size * 0.56


# --------------------------------------------------------------- entities
def entity(key, name, x, y, attrs, weak=False):
    """attrs: list of (name, kind) kind in None | 'pk' | 'partial'."""
    head = 46
    row = 23
    w = max(tw(name, 22) + 50, max(tw(a, 17) for a, _ in attrs) + 44, 210)
    h = head + row * len(attrs) + 14
    x0, y0 = x - w / 2, y - h / 2
    SHAPE[key] = dict(x=x, y=y, w=w, h=h, kind="E")
    add(f'<rect x="{x0:.1f}" y="{y0:.1f}" width="{w:.1f}" height="{h:.1f}" fill="#fff" stroke="{INK}" stroke-width="2.2"/>')
    if weak:   # double border for a weak entity
        add(f'<rect x="{x0+6:.1f}" y="{y0+6:.1f}" width="{w-12:.1f}" height="{h-12:.1f}" fill="none" stroke="{INK}" stroke-width="1.4"/>')
    add(f'<rect x="{x0+ (7 if weak else 1.1):.1f}" y="{y0+(7 if weak else 1.1):.1f}" width="{w-(14 if weak else 2.2):.1f}" height="{head-(7 if weak else 1.1):.1f}" fill="#eef1f5"/>')
    add(f'<text x="{x:.1f}" y="{y0+31:.1f}" text-anchor="middle" font-size="22" font-weight="700">{name}</text>')
    add(f'<line x1="{x0:.1f}" y1="{y0+head:.1f}" x2="{x0+w:.1f}" y2="{y0+head:.1f}" stroke="{INK}" stroke-width="1.4"/>')
    for i, (a, k) in enumerate(attrs):
        ty = y0 + head + 24 + i * row
        tx = x0 + 18
        weight = ' font-weight="700"' if k else ""
        add(f'<text x="{tx:.1f}" y="{ty:.1f}" font-size="17"{weight}>{a}</text>')
        if k:
            dash = ' stroke-dasharray="5,3"' if k == "partial" else ""
            add(f'<line x1="{tx:.1f}" y1="{ty+4:.1f}" x2="{tx+tw(a,17)*1.04:.1f}" y2="{ty+4:.1f}" stroke="{INK}" stroke-width="1.5"{dash}/>')
            label = "PK" if k == "pk" else "partial key"
            add(f'<text x="{x0+w-12:.1f}" y="{ty:.1f}" text-anchor="end" font-size="13" fill="#555">{label}</text>')


def rel(key, name, x, y, identifying=False):
    w = max(150, tw(name, 18) + 76)
    h = 82
    SHAPE[key] = dict(x=x, y=y, w=w, h=h, kind="R")
    pts = f"{x-w/2},{y} {x},{y-h/2} {x+w/2},{y} {x},{y+h/2}"
    add(f'<polygon points="{pts}" fill="#fff" stroke="{INK}" stroke-width="2"/>')
    if identifying:   # double diamond for the identifying relationship
        k = 0.86
        pts2 = f"{x-w/2*k},{y} {x},{y-h/2*k} {x+w/2*k},{y} {x},{y+h/2*k}"
        add(f'<polygon points="{pts2}" fill="none" stroke="{INK}" stroke-width="1.3"/>')
    add(f'<text x="{x}" y="{y+6}" text-anchor="middle" font-size="18" font-weight="700">{name}</text>')


def border_point(key, tx, ty):
    e = SHAPE[key]
    x, y = e["x"], e["y"]
    dx, dy = tx - x, ty - y
    if e["kind"] == "E":
        sx = (e["w"] / 2) / abs(dx) if dx else 1e9
        sy = (e["h"] / 2) / abs(dy) if dy else 1e9
        s = min(sx, sy)
    else:
        s = 1 / (abs(dx) / (e["w"] / 2) + abs(dy) / (e["h"] / 2))
    return x + dx * s, y + dy * s


# --------------------------------------------------------------- crow's-foot marks
def mark(px, py, ux, uy, kind):
    """Draw cardinality mark at entity border point (px,py); (ux,uy) = unit
    vector pointing away from the entity along the line."""
    nx, ny = -uy, ux
    out = []

    def bar(d, half=11):
        cx, cy = px + ux * d, py + uy * d
        out.append(f'<line x1="{cx+nx*half:.1f}" y1="{cy+ny*half:.1f}" x2="{cx-nx*half:.1f}" y2="{cy-ny*half:.1f}" stroke="{INK}" stroke-width="2"/>')

    def circle(d, r=7):
        cx, cy = px + ux * d, py + uy * d
        out.append(f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r}" fill="#fff" stroke="{INK}" stroke-width="2"/>')

    def foot(length=20, spread=13):
        ax, ay = px + ux * length, py + uy * length
        for s in (-1, 0, 1):
            ex, ey = px + nx * spread * s, py + ny * spread * s
            out.append(f'<line x1="{ax:.1f}" y1="{ay:.1f}" x2="{ex:.1f}" y2="{ey:.1f}" stroke="{INK}" stroke-width="2"/>')

    if kind == "one":          # ||  exactly one
        bar(9); bar(19)
    elif kind == "zero_one":   # o|
        bar(9); circle(28)
    elif kind == "many":       # |<  one or many
        foot(); bar(29)
    elif kind == "zero_many":  # o<
        foot(); circle(33)
    add("".join(out))


def border_perp(key, vx, vy):
    """Border point reached by a straight horizontal/vertical segment from (vx,vy)."""
    e = SHAPE[key]
    x0, x1 = e["x"] - e["w"] / 2, e["x"] + e["w"] / 2
    y0, y1 = e["y"] - e["h"] / 2, e["y"] + e["h"] / 2
    if x0 <= vx <= x1:
        return vx, (y0 if vy < y0 else y1)
    if y0 <= vy <= y1:
        return (x0 if vx < x0 else x1), vy
    return border_point(key, vx, vy)


def link(ent, diamond, kind, via=None):
    """Line from an entity to a relationship diamond, with the cardinality
    mark for `ent` at the entity end. via = elbow points (entity side first)."""
    via = via or []
    first = via[0] if via else (SHAPE[diamond]["x"], SHAPE[diamond]["y"])
    last = via[-1] if via else (SHAPE[ent]["x"], SHAPE[ent]["y"])
    p0 = border_perp(ent, *first) if via else border_point(ent, *first)
    p1 = border_point(diamond, *last)
    pts = [p0] + via + [p1]
    d = "M" + " L".join(f"{a:.1f},{b:.1f}" for a, b in pts)
    add(f'<path d="{d}" fill="none" stroke="{INK}" stroke-width="1.8" stroke-linejoin="round"/>')
    ux, uy = first[0] - p0[0], first[1] - p0[1]
    n = math.hypot(ux, uy)
    mark(p0[0], p0[1], ux / n, uy / n, kind)


def rel_attr(diamond, name, dx, dy):
    """Relationship attribute as a small oval tied to its diamond."""
    e = SHAPE[diamond]
    ax, ay = e["x"] + dx, e["y"] + dy
    rx, ry = tw(name, 15) / 2 + 14, 17
    bx, by = border_point(diamond, ax, ay)
    ddx, ddy = bx - ax, by - ay
    t = 1 / math.sqrt((ddx / rx) ** 2 + (ddy / ry) ** 2)
    add(f'<line x1="{bx:.1f}" y1="{by:.1f}" x2="{ax+ddx*t:.1f}" y2="{ay+ddy*t:.1f}" stroke="{INK}" stroke-width="1.1" stroke-dasharray="4,3"/>')
    add(f'<ellipse cx="{ax:.1f}" cy="{ay:.1f}" rx="{rx:.1f}" ry="{ry}" fill="#fff" stroke="{INK}" stroke-width="1.2"/>')
    add(f'<text x="{ax:.1f}" y="{ay+5:.1f}" text-anchor="middle" font-size="15" font-style="italic">{name}</text>')


# --------------------------------------------------------------- canvas
add(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="{FONT}" fill="{INK}">')
add(f'<rect width="{W}" height="{H}" fill="#fff"/>')
add('<text x="40" y="56" font-size="32" font-weight="700">GPU Cluster Hub — ER Diagram</text>')
add('<text x="40" y="88" font-size="17" fill="#444">CS 166 Phase 1 · crow\'s-foot notation · 10 entity sets, 13 relationship sets (5 many-to-many)</text>')

# aggregation box around MemberOf (drawn first, behind)
AGG = dict(x=715, y=520, w=1250, h=420, kind="E")
SHAPE["AGG"] = AGG
add(f'<rect x="{AGG["x"]-AGG["w"]/2}" y="{AGG["y"]-AGG["h"]/2}" width="{AGG["w"]}" height="{AGG["h"]}" fill="none" stroke="{INK}" stroke-width="2" stroke-dasharray="14,8" rx="6"/>')
add(f'<text x="{AGG["x"]+AGG["w"]/2-14}" y="{AGG["y"]+AGG["h"]/2-12}" text-anchor="end" font-size="15" font-style="italic" fill="#444">aggregation: a membership (user in group) books reservations</text>')

entity("U", "ClusterUser", 240, 520, [("user_id", "pk"), ("net_id", None), ("full_name", None), ("email", None),
                                       ("user_role", None), ("is_admin", None), ("is_active", None), ("created_at", None)])
entity("G", "ResearchGroup", 1180, 520, [("group_id", "pk"), ("group_name", None), ("department", None),
                                          ("weekly_gpu_hour_quota", None), ("created_on", None)])
rel("MEM", "MemberOf", 700, 470)
rel("LEADS", "Leads", 700, 640)

entity("M", "AIModel", 2150, 230, [("model_id", "pk"), ("model_name", None), ("task_category", None), ("framework", None),
                                   ("license", None), ("source_url", None), ("description", None), ("added_on", None)])
rel("MAINT", "Maintains", 1200, 190)
rel("HASV", "HasVersion", 2150, 470, identifying=True)
entity("MV", "ModelVersion", 2150, 735, [("version_tag", "partial"), ("param_count_b", None), ("weight_precision", None),
                                         ("min_vram_gb", None), ("checkpoint_size_gb", None), ("released_on", None),
                                         ("is_deprecated", None)], weak=True)
rel("HOSTS", "Hosts", 2150, 1050)
entity("N", "ComputeNode", 2150, 1400, [("node_id", "pk"), ("hostname", None), ("partition_name", None), ("cpu_cores", None),
                                        ("ram_gb", None), ("local_nvme_tb", None), ("max_cuda_version", None),
                                        ("node_status", None), ("rack_location", None)])
rel("HOUSES", "Houses", 1680, 1400)
entity("GPU", "GPU", 1200, 1400, [("gpu_id", "pk"), ("slot_index", None), ("serial_number", None),
                                  ("gpu_status", None), ("installed_on", None)])
rel("ISTYPE", "IsOfType", 1200, 1650)
entity("T", "GPUType", 1680, 1640, [("gpu_type_id", "pk"), ("type_name", None), ("vendor", None), ("architecture", None),
                                    ("vram_gb", None), ("compute_capability", None), ("fp16_tflops", None),
                                    ("supports_bf16", None)])

rel("BOOKS", "Books", 330, 1000)
entity("R", "Reservation", 330, 1400, [("reservation_id", "pk"), ("start_time", None), ("end_time", None), ("purpose", None),
                                       ("res_status", None), ("created_at", None)])
rel("RESV", "Reserves", 760, 1400)
rel("RUNSU", "RunsUnder", 760, 1130)
entity("J", "Job", 1150, 1000, [("job_id", "pk"), ("job_name", None), ("submit_time", None), ("start_time", None),
                                ("end_time", None), ("job_state", None), ("exit_code", None), ("peak_vram_gb", None),
                                ("avg_gpu_util_pct", None)])
rel("RUNSIN", "RunsIn", 1450, 900)
entity("E", "SoftwareEnv", 1700, 760, [("env_id", "pk"), ("env_name", None), ("python_version", None), ("cuda_version", None),
                                       ("framework", None), ("framework_version", None), ("container_image", None),
                                       ("created_on", None)])
rel("COMPAT", "CompatibleWith", 1700, 470)
rel("LOADS", "Loads", 1700, 1130)

# --------------------------------------------------------------- relationships
# The mark at an entity = how many of that entity per ONE instance on the other side.
link("U", "MEM", "many", via=[(420, 470)])                       # a group has 1..* members
link("G", "MEM", "zero_many", via=[(1000, 470)])                  # a user is in 0..* groups (admins: none)
link("U", "LEADS", "one", via=[(420, 600)])  # a group has exactly one PI
link("G", "LEADS", "zero_many", via=[(1000, 560)])                # a user leads 0..* groups
link("U", "MAINT", "one", via=[(240, 190)])    # a model has exactly one maintainer
link("M", "MAINT", "zero_many", via=[(1990, 190)])                # a user maintains 0..* models
link("M", "HASV", "one")                       # a version belongs to exactly one model
link("MV", "HASV", "zero_many")                # a model has 0..* versions
link("MV", "HOSTS", "zero_many")               # a node hosts 0..* checkpoints
link("N", "HOSTS", "zero_many")                # a checkpoint is staged on 0..* nodes
link("N", "HOUSES", "one")                     # a GPU is in exactly one node
link("GPU", "HOUSES", "zero_many")             # a node houses 0..* GPUs
link("T", "ISTYPE", "one")                     # a GPU is exactly one type
link("GPU", "ISTYPE", "zero_many")             # a type has 0..* GPUs
link("AGG", "BOOKS", "one", via=[(330, 800)])                    # a reservation is made by exactly one membership
link("R", "BOOKS", "zero_many")                # a membership books 0..* reservations
link("GPU", "RESV", "many")                    # a reservation holds 1..* GPUs
link("R", "RESV", "zero_many")                 # a GPU is in 0..* reservations
link("R", "RUNSU", "one", via=[(500, 1335)])                      # a job runs under exactly one reservation
link("J", "RUNSU", "zero_many", via=[(1000, 1060)])                # a reservation has 0..* jobs
link("E", "RUNSIN", "one", via=[(1550, 840)])                     # a job runs in exactly one env
link("J", "RUNSIN", "zero_many", via=[(1310, 945)])               # an env runs 0..* jobs
link("E", "COMPAT", "zero_many")               # a version is verified in 0..* envs
link("MV", "COMPAT", "zero_many", via=[(1985, 690)])              # an env supports 0..* versions
link("MV", "LOADS", "zero_many", via=[(1985, 800)])               # a job loads 0..* versions
link("J", "LOADS", "zero_many", via=[(1310, 1060)])                # a version is loaded by 0..* jobs

rel_attr("MEM", "member_role", -95, -95)
rel_attr("MEM", "joined_on", 95, -95)
rel_attr("HOSTS", "local_path", 200, -30)
rel_attr("HOSTS", "staged_on", 200, 30)
rel_attr("COMPAT", "verified_on", -90, -80)
rel_attr("COMPAT", "notes", 90, -80)
rel_attr("LOADS", "usage_role", 0, 85)

# --------------------------------------------------------------- legend
lx, ly = 60, 1560
add(f'<rect x="{lx-20}" y="{ly-36}" width="720" height="240" fill="#fff" stroke="#999"/>')
add(f'<text x="{lx}" y="{ly-8}" font-size="18" font-weight="700">Legend</text>')
add(f'<text x="{lx+330}" y="{ly-8}" font-size="14" fill="#444">mark at an entity = how many of it per one on the other side</text>')
rows = [("one", "exactly one (mandatory)"), ("zero_one", "zero or one (optional)"),
        ("many", "one or many (mandatory)"), ("zero_many", "zero or many (optional)")]
for i, (k, txt) in enumerate(rows):
    yy = ly + 28 + i * 38
    add(f'<rect x="{lx}" y="{yy-14}" width="38" height="28" fill="#fff" stroke="{INK}" stroke-width="1.6"/>')
    add(f'<line x1="{lx+38}" y1="{yy}" x2="{lx+120}" y2="{yy}" stroke="{INK}" stroke-width="1.8"/>')
    mark(lx + 38, yy, 1, 0, k)
    add(f'<text x="{lx+135}" y="{yy+6}" font-size="16">{txt}</text>')
add(f'<rect x="{lx+380}" y="{ly+14}" width="40" height="28" fill="#fff" stroke="{INK}" stroke-width="2"/>'
    f'<rect x="{lx+384}" y="{ly+18}" width="32" height="20" fill="none" stroke="{INK}" stroke-width="1.2"/>')
add(f'<text x="{lx+432}" y="{ly+34}" font-size="16">weak entity / identifying rel.</text>')
add(f'<text x="{lx+380}" y="{ly+80}" font-size="16" font-weight="700"><tspan text-decoration="underline">key</tspan></text>')
add(f'<text x="{lx+432}" y="{ly+80}" font-size="16">primary key (dashed: partial key)</text>')
add(f'<rect x="{lx+380}" y="{ly+102}" width="40" height="26" fill="none" stroke="{INK}" stroke-width="1.6" stroke-dasharray="7,4"/>')
add(f'<text x="{lx+432}" y="{ly+121}" font-size="16">aggregation</text>')
add(f'<ellipse cx="{lx+400}" cy="{ly+158}" rx="22" ry="12" fill="#fff" stroke="{INK}" stroke-width="1.2"/>')
add(f'<text x="{lx+432}" y="{ly+164}" font-size="16">relationship attribute</text>')

add("</svg>")

here = os.path.dirname(os.path.abspath(__file__))
svg_path = os.path.join(here, "er_diagram.svg")
open(svg_path, "w").write("\n".join(svg))
js = f"""
const {{ chromium }} = require('playwright');
(async () => {{
  const b = await chromium.launch();
  const p = await b.newPage({{ viewport: {{ width: {W}, height: {H} }} }});
  await p.goto('file://{svg_path}');
  await p.screenshot({{ path: '{here}/er_diagram.png' }});
  await p.pdf({{ path: '{here}/gpuhub_er_diagram.pdf', width: '{W}px', height: '{H}px', printBackground: true, pageRanges: '1' }});
  await b.close();
}})();
"""
jsp = os.path.join(here, "_render.js")
open(jsp, "w").write(js)
subprocess.run(["node", jsp], check=True)
os.remove(jsp)
print("wrote er_diagram.svg / er_diagram.png / gpuhub_er_diagram.pdf")
