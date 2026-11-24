#!/usr/bin/env python3
"""
GPU Cluster Hub -- synthetic data generator (CS 166 Phase 1)

Produces one CSV per table plus a psql loader script (load_data.sql) in the
output directory.  Output is deterministic for a given --seed and --scale.

    python3 data/generate_data.py                       # small set -> data/
    python3 data/generate_data.py --scale large --out data_large

How the data is made
--------------------
* Hardware (GPU types, nodes, GPUs) and the model / environment catalog are
  hand-written below.  Model and GPU names are real products; parameter
  counts, checkpoint sizes and minimum VRAM are rounded public figures or
  estimates and should be read as approximate.
* People, groups, memberships, reservations and jobs are random but obey
  every business rule the schema enforces (no double booking, weekly group
  quotas, jobs inside their reservation window, jobs only load model versions
  verified for their environment, staged checkpoints fit on node NVMe).
  The database triggers re-check all of this while loading.
* People are fictional; e-mail addresses use the reserved example.edu domain.
"""
import argparse
import csv
import os
import random
from datetime import date, datetime, timedelta

# ---------------------------------------------------------------------------
# Static catalog
# ---------------------------------------------------------------------------
# type_name, vendor, architecture, vram_gb, compute_capability, fp16_tflops (dense), bf16
GPU_TYPES = [
    ("H100-SXM5-80GB", "NVIDIA", "Hopper",       80, 9.0, 989.4, True),
    ("A100-SXM4-80GB", "NVIDIA", "Ampere",       80, 8.0, 312.0, True),
    ("A100-PCIE-40GB", "NVIDIA", "Ampere",       40, 8.0, 312.0, True),
    ("L40S",           "NVIDIA", "Ada Lovelace", 48, 8.9, 362.1, True),
    ("RTX-A6000",      "NVIDIA", "Ampere",       48, 8.6, 154.8, True),
    ("A30",            "NVIDIA", "Ampere",       24, 8.0, 165.0, True),
    ("V100-SXM2-32GB", "NVIDIA", "Volta",        32, 7.0, 125.0, False),
]

# Node templates: prefix, gpu type, gpus/node, partition, cores, ram, nvme_tb, max_cuda, count(small)
NODE_TEMPLATES = [
    ("gpu-h100",  "H100-SXM5-80GB", 8, "gpu-long",  112, 2048, 15.36, 12.6, 2),
    ("gpu-a100",  "A100-SXM4-80GB", 8, "gpu",       128, 1024,  7.68, 12.4, 3),
    ("gpu-a100p", "A100-PCIE-40GB", 4, "gpu",        64,  512,  3.84, 12.2, 1),
    ("gpu-l40s",  "L40S",           4, "gpu-short",  64,  512,  3.84, 12.4, 2),
    ("gpu-a6000", "RTX-A6000",      4, "gpu-short",  48,  384,  3.84, 12.2, 2),
    ("gpu-v100",  "V100-SXM2-32GB", 4, "gpu",        40,  256,  1.92, 11.8, 1),
    ("gpu-debug", "A30",            2, "debug",      32,  128,  0.48, 12.2, 1),
]

# Software environments: key, name, python, cuda, framework, framework_version, image
ENVS = [
    ("t25",   "torch2.5-cu126-py312",   "3.12", 12.6, "PyTorch",    "2.5.1",  "docker://pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime"),
    ("t24",   "torch2.4-cu124-py311",   "3.11", 12.4, "PyTorch",    "2.4.1",  "docker://pytorch/pytorch:2.4.1-cuda12.4-cudnn9-runtime"),
    ("t23",   "torch2.3-cu121-py310",   "3.10", 12.1, "PyTorch",    "2.3.1",  None),
    ("t21",   "torch2.1-cu118-py310",   "3.10", 11.8, "PyTorch",    "2.1.2",  None),
    ("t113",  "torch1.13-cu117-py39",   "3.9",  11.7, "PyTorch",    "1.13.1", None),
    ("t110",  "torch1.10-cu113-py38",   "3.8",  11.3, "PyTorch",    "1.10.2", None),
    ("py36",  "legacy-py36-cu102",      "3.6",  10.2, "PyTorch",    "1.8.2",  None),
    ("vllm",  "vllm0.6-cu124-py311",    "3.11", 12.4, "PyTorch",    "2.4.0",  "docker://vllm/vllm-openai:v0.6.3"),
    ("diff",  "diffusers0.31-cu121-py310", "3.10", 12.1, "PyTorch", "2.3.1",  None),
    ("jax",   "jax0.4-cu123-py311",     "3.11", 12.3, "JAX",        "0.4.30", None),
    ("tf",    "tf2.15-cu121-py310",     "3.10", 12.1, "TensorFlow", "2.15.1", "docker://tensorflow/tensorflow:2.15.0-gpu"),
    ("onnx",  "onnxrt1.19-cu122-py311", "3.11", 12.2, "ONNX",       "1.19.2", None),
]

LLM_ENVS = ["t25", "t24", "t23", "vllm"]
MODERN = ["t25", "t24", "t23", "t21"]
# model name, task, framework, license, url, description,
#   [(version_tag, params_B, precision, min_vram_gb, ckpt_gb, released, deprecated, [env keys])]
MODELS = [
    ("Llama-3.1-8B-Instruct", "LLM", "PyTorch", "Llama 3.1 Community", "https://huggingface.co/meta-llama/Llama-3.1-8B-Instruct",
     "General instruction-tuned LLM",
     [("bf16", 8.03, "bf16", 18, 16.1, "2024-07-23", False, LLM_ENVS),
      ("awq-int4", 8.03, "int4", 8, 5.7, "2024-08-05", False, ["t24", "vllm"])]),
    ("Llama-3.1-70B-Instruct", "LLM", "PyTorch", "Llama 3.1 Community", "https://huggingface.co/meta-llama/Llama-3.1-70B-Instruct",
     "Large instruction-tuned LLM; needs several GPUs in bf16",
     [("bf16", 70.6, "bf16", 160, 141.0, "2024-07-23", False, ["t24", "vllm"]),
      ("awq-int4", 70.6, "int4", 48, 39.8, "2024-08-05", False, ["vllm"])]),
    ("Llama-2-7b-chat", "LLM", "PyTorch", "Llama 2 Community", "https://huggingface.co/meta-llama/Llama-2-7b-chat-hf",
     "Superseded chat model kept for reproducing older papers",
     [("hf-fp16", 6.74, "fp16", 16, 13.5, "2023-07-18", True, ["t21", "t23"])]),
    ("Qwen2.5-7B-Instruct", "LLM", "PyTorch", "Apache-2.0", "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct",
     "Multilingual instruction LLM",
     [("bf16", 7.62, "bf16", 18, 15.2, "2024-09-19", False, LLM_ENVS)]),
    ("Qwen2.5-32B-Instruct", "LLM", "PyTorch", "Apache-2.0", "https://huggingface.co/Qwen/Qwen2.5-32B-Instruct",
     "Mid-size instruction LLM",
     [("bf16", 32.8, "bf16", 72, 65.5, "2024-09-19", False, ["t24", "vllm"]),
      ("gptq-int4", 32.8, "int4", 24, 19.3, "2024-09-25", False, ["vllm"])]),
    ("Mistral-7B-Instruct-v0.3", "LLM", "PyTorch", "Apache-2.0", "https://huggingface.co/mistralai/Mistral-7B-Instruct-v0.3",
     "Compact instruction LLM",
     [("bf16", 7.25, "bf16", 16, 14.5, "2024-05-22", False, LLM_ENVS)]),
    ("Phi-3.5-mini-instruct", "LLM", "PyTorch", "MIT", "https://huggingface.co/microsoft/Phi-3.5-mini-instruct",
     "Small LLM for fast prototyping",
     [("bf16", 3.82, "bf16", 10, 7.6, "2024-08-20", False, LLM_ENVS)]),
    ("gemma-2-9b-it", "LLM", "JAX", "Gemma Terms of Use", "https://huggingface.co/google/gemma-2-9b-it",
     "Instruction-tuned Gemma 2",
     [("jax-bf16", 9.24, "bf16", 22, 18.5, "2024-06-27", False, ["jax"])]),
    ("DeepSeek-R1-Distill-Qwen-14B", "LLM", "PyTorch", "MIT", "https://huggingface.co/deepseek-ai/DeepSeek-R1-Distill-Qwen-14B",
     "Reasoning-distilled LLM",
     [("bf16", 14.8, "bf16", 34, 29.5, "2025-01-20", False, ["t25", "t24", "vllm"])]),
    ("ViT-B-16", "Vision", "JAX", "Apache-2.0", "https://github.com/google-research/vision_transformer",
     "Image classification backbone",
     [("in21k-ft-in1k", 0.086, "fp32", 2, 0.35, "2021-06-18", False, ["jax"])]),
    ("CLIP-ViT-L-14", "Multimodal", "PyTorch", "MIT", "https://huggingface.co/openai/clip-vit-large-patch14",
     "Image-text embedding model",
     [("openai", 0.428, "fp32", 4, 1.71, "2021-01-05", False, MODERN + ["t113"])]),
    ("SAM-ViT-H", "Vision", "PyTorch", "Apache-2.0", "https://github.com/facebookresearch/segment-anything",
     "Promptable segmentation",
     [("sam_vit_h_4b8939", 0.636, "fp32", 8, 2.56, "2023-04-05", False, MODERN)]),
    ("DINOv2-ViT-g-14", "Vision", "PyTorch", "Apache-2.0", "https://github.com/facebookresearch/dinov2",
     "Self-supervised vision features",
     [("lvd142m", 1.14, "fp32", 8, 4.5, "2023-04-14", False, MODERN)]),
    ("YOLOv8x", "Detection", "PyTorch", "AGPL-3.0", "https://github.com/ultralytics/ultralytics",
     "Real-time object detection",
     [("v8.2", 0.068, "fp32", 4, 0.13, "2024-04-01", False, MODERN),
      ("v8.2-onnx", 0.068, "fp16", 4, 0.07, "2024-04-01", False, ["onnx"])]),
    ("EfficientNet-B7", "Vision", "TensorFlow", "Apache-2.0", "https://github.com/tensorflow/tpu",
     "Image classification (TF Keras applications)",
     [("imagenet", 0.066, "fp32", 4, 0.26, "2019-05-28", False, ["tf"])]),
    ("Stable-Diffusion-XL-base-1.0", "Diffusion", "PyTorch", "CreativeML OpenRAIL++-M", "https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0",
     "Text-to-image diffusion",
     [("1.0-fp16", 3.5, "fp16", 12, 6.9, "2023-07-26", False, ["diff", "t24"])]),
    ("Stable-Diffusion-v1-5", "Diffusion", "PyTorch", "CreativeML OpenRAIL-M", "https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5",
     "Older text-to-image model used as a baseline",
     [("1.5-ema", 0.98, "fp32", 8, 4.27, "2022-10-20", True, ["diff", "t21"])]),
    ("FLUX.1-dev", "Diffusion", "PyTorch", "FLUX.1 [dev] Non-Commercial", "https://huggingface.co/black-forest-labs/FLUX.1-dev",
     "Rectified-flow text-to-image",
     [("bf16", 12.0, "bf16", 32, 23.8, "2024-08-01", False, ["diff", "t24"])]),
    ("Wan2.1-T2V", "Diffusion", "PyTorch", "Apache-2.0", "https://github.com/Wan-Video/Wan2.1",
     "Text-to-video diffusion transformer",
     [("14B", 14.0, "bf16", 48, 69.0, "2025-02-25", False, ["t25", "t24"]),
      ("1.3B", 1.3, "bf16", 10, 8.2, "2025-02-25", False, ["t25", "t24", "diff"])]),
    ("CogVideoX-5B", "Diffusion", "PyTorch", "CogVideoX License", "https://huggingface.co/THUDM/CogVideoX-5b",
     "Text-to-video generation",
     [("bf16", 5.0, "bf16", 18, 21.6, "2024-08-27", False, ["diff", "t24"])]),
    ("Whisper-large-v3", "Audio", "PyTorch", "MIT", "https://huggingface.co/openai/whisper-large-v3",
     "Speech recognition",
     [("large-v3", 1.55, "fp16", 10, 3.1, "2023-11-06", False, MODERN),
      ("large-v3-onnx", 1.55, "fp16", 10, 3.2, "2024-01-10", False, ["onnx"])]),
    ("MusicGen-medium", "Audio", "PyTorch", "CC-BY-NC-4.0", "https://huggingface.co/facebook/musicgen-medium",
     "Text-to-music generation",
     [("1.5B", 1.5, "fp32", 12, 6.0, "2023-06-08", False, ["t23", "t21"])]),
    ("LLaVA-1.6-Mistral-7B", "Multimodal", "PyTorch", "Apache-2.0", "https://huggingface.co/llava-hf/llava-v1.6-mistral-7b-hf",
     "Vision-language assistant",
     [("hf-bf16", 7.57, "bf16", 18, 15.1, "2024-01-30", False, ["t24", "t23", "vllm"])]),
    ("Qwen2-VL-7B-Instruct", "Multimodal", "PyTorch", "Apache-2.0", "https://huggingface.co/Qwen/Qwen2-VL-7B-Instruct",
     "Vision-language model with video input",
     [("bf16", 8.29, "bf16", 20, 16.6, "2024-08-29", False, ["t25", "t24", "vllm"])]),
    ("LAA-Net", "Detection", "PyTorch", "Research-only", None,
     "Deepfake detector (localized artifact attention)",
     [("ffpp-c23", 0.05, "fp32", 6, 0.6, "2024-03-01", False, ["t113", "t110"])]),
    ("UIA-ViT", "Detection", "PyTorch", "Research-only", None,
     "Deepfake detector (unsupervised inconsistency-aware ViT)",
     [("ffpp-c23", 0.087, "fp32", 6, 0.35, "2022-07-01", False, ["t110", "py36"])]),
    ("AltFreezing", "Detection", "PyTorch", "Research-only", None,
     "Video deepfake detector (spatiotemporal)",
     [("ffpp", 0.04, "fp32", 8, 0.45, "2023-06-01", False, ["t113", "t110"])]),
    ("Xception-FF++", "Detection", "PyTorch", "Research-only", None,
     "Classic face-forgery baseline",
     [("c23", 0.022, "fp32", 4, 0.09, "2019-01-25", False, ["py36", "t110", "t113"])]),
]

FIRST = ["Aarav", "Maya", "Wei", "Sofia", "Daniel", "Priya", "Lucas", "Mei", "Omar", "Elena",
         "Jun", "Grace", "Mateo", "Aisha", "Ethan", "Hana", "Ravi", "Chloe", "Diego", "Yuki",
         "Noah", "Fatima", "Leo", "Isabel", "Kenji", "Amara", "Samuel", "Lina", "Arjun", "Zoe",
         "Tomas", "Nadia", "Victor", "Iris", "Kai", "Leila", "Marco", "Anya", "Felix", "Sara"]
LAST = ["Chen", "Patel", "Garcia", "Kim", "Nguyen", "Singh", "Lopez", "Wang", "Martinez", "Liu",
        "Shah", "Rossi", "Ahmed", "Zhang", "Tanaka", "Cohen", "Rivera", "Park", "Okafor", "Silva",
        "Huang", "Kumar", "Novak", "Haddad", "Ivanova", "Moreno", "Schmidt", "Yamamoto", "Reyes", "Das"]

GROUP_NAMES = [
    ("Video Understanding Lab", "Electrical and Computer Engineering", 900),
    ("Language Systems Lab", "Computer Science and Engineering", 1200),
    ("Robot Perception Lab", "Mechanical Engineering", 500),
    ("Computational Genomics Lab", "Bioengineering", 300),
    ("Trustworthy AI Lab", "Computer Science and Engineering", 800),
    ("Smart Mobility Lab", "Civil and Environmental Engineering", 600),
]
GROUP_PREFIX = ["Video", "Language", "Robot", "Genomics", "Trustworthy", "Mobility", "Vision",
                "Speech", "Graph", "Medical Imaging", "Climate", "Edge AI"]
GROUP_SUFFIX = ["Lab", "Group"]
DEPTS = ["Electrical and Computer Engineering", "Computer Science and Engineering",
         "Mechanical Engineering", "Bioengineering", "Civil and Environmental Engineering"]

PURPOSES = {
    "LLM": ["LoRA fine-tune", "eval harness run", "RLHF reward model sweep", "inference benchmark"],
    "Vision": ["backbone pretraining", "segmentation eval", "feature extraction"],
    "Diffusion": ["video generation batch", "adversarial video synthesis", "sampler ablation"],
    "Audio": ["ASR transcription batch", "music generation study"],
    "Multimodal": ["VLM instruction tuning", "captioning eval"],
    "Detection": ["deepfake detector eval", "adversarial robustness test", "detector retraining"],
    "Other": ["misc experiment"],
}


def ts(d):
    return d.strftime("%Y-%m-%d %H:%M:%S") if d else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scale", choices=["small", "large"], default="small")
    ap.add_argument("--out", default=None)
    ap.add_argument("--seed", type=int, default=166)
    args = ap.parse_args()
    out = args.out or ("data" if args.scale == "small" else "data_large")
    os.makedirs(out, exist_ok=True)
    rnd = random.Random(args.seed)
    big = args.scale == "large"

    # Snapshot time: the "current moment" the data describes.
    anchor = datetime(2026, 11, 2, 9, 0)                    # Monday 09:00
    span_end = datetime(2026, 11, 23)                       # bookings end 3 weeks ahead
    weeks = 156 if big else 10
    span_start = datetime(2026, 11, 2) - timedelta(weeks=weeks - 1)   # a Monday
    node_mult = 4 if big else 1

    rows = {}

    # ---- GPU types ----
    rows["gpu_type"] = []
    type_id = {}
    type_vram = {}
    for i, t in enumerate(GPU_TYPES, 1):
        type_id[t[0]] = i
        type_vram[i] = t[3]
        rows["gpu_type"].append([i, *t[:6], str(t[6]).lower()])

    # -- Nodes and GPUs ---
    rows["compute_node"], rows["gpu"] = [], []
    nodes = []          # dicts
    gpus = []           # dicts
    nid = gid = 0
    for (pref, tname, per, part, cores, ram, nvme, cuda, cnt) in NODE_TEMPLATES:
        for k in range(1, cnt * node_mult + 1):
            nid += 1
            host = f"{pref}-{k:02d}"
            status = "UP"
            nodes.append(dict(id=nid, host=host, cuda=cuda, nvme=nvme, status=status, gpus=[]))
            rack = f"R{(nid - 1) // 4 + 1:02d}-U{((nid - 1) % 4) * 10 + 4:02d}"
            rows["compute_node"].append([nid, host, part, cores, ram, nvme, cuda, status, rack])
            for s in range(per):
                gid += 1
                serial = f"{tname[:4].replace('-', '')}{rnd.randint(10**9, 10**10 - 1)}"
                g = dict(id=gid, node=nid, type=type_id[tname], vram=type_vram[type_id[tname]],
                         status="AVAILABLE", busy=[])
                gpus.append(g)
                nodes[-1]["gpus"].append(g)
                inst = date(2022, 1, 1) + timedelta(days=rnd.randint(0, 900))
                rows["gpu"].append([gid, nid, s, type_id[tname], serial, "AVAILABLE", inst])
    # A few realistic non-available states (applied after creation)
    def set_gpu_status(g, st):
        g["status"] = st
        rows["gpu"][g["id"] - 1][5] = st
    set_gpu_status(nodes[1]["gpus"][3], "MAINTENANCE")        # one H100 in maintenance
    set_gpu_status(nodes[2]["gpus"][7], "RETIRED")            # one A100 retired
    v100 = next(n for n in nodes if n["host"].startswith("gpu-v100"))
    v100["status"] = "DRAIN"
    rows["compute_node"][v100["id"] - 1][7] = "DRAIN"

    # --- Users ---
    n_groups = 24 if big else len(GROUP_NAMES)
    role_counts = ({"Faculty": n_groups + 2, "Postdoc": 5, "PhD": 30, "MS": 15, "Undergrad": 10, "Staff": 3}
                   if not big else
                   {"Faculty": n_groups + 8, "Postdoc": 30, "PhD": 200, "MS": 110, "Undergrad": 60, "Staff": 6})
    rows["cluster_user"] = []
    users = []
    used_net = set()
    uid = 0
    for role, cnt in role_counts.items():
        for _ in range(cnt):
            uid += 1
            while True:
                f, l = rnd.choice(FIRST), rnd.choice(LAST)
                net = f"{f[0]}{l}".lower()[:8] + f"{rnd.randint(1, 999):03d}"
                if net not in used_net:
                    used_net.add(net)
                    break
            name = f"{'Dr. ' if role == 'Faculty' else ''}{f} {l}"
            email = f"{net}@gpuhub.example.edu"
            is_admin = role == "Staff" and len([u for u in users if u["admin"]]) < 2
            active = not (role in ("MS", "Undergrad") and rnd.random() < 0.08)
            created = datetime(2023, 9, 1) + timedelta(days=rnd.randint(0, 900), hours=rnd.randint(8, 18))
            created = min(created, span_start - timedelta(days=1))
            users.append(dict(id=uid, role=role, admin=is_admin, active=active))
            rows["cluster_user"].append([uid, net, name, email, role, str(is_admin).lower(),
                                         str(active).lower(), ts(created)])
    faculty = [u for u in users if u["role"] == "Faculty"]
    students = [u for u in users if u["role"] in ("Postdoc", "PhD", "MS", "Undergrad")]
    staff = [u for u in users if u["role"] == "Staff"]

    # --- Groups and memberships ---
    rows["research_group"], rows["membership"] = [], []
    groups = []
    names_used = set()
    for gi in range(1, n_groups + 1):
        if not big:
            gname, dept, quota = GROUP_NAMES[gi - 1]
        else:
            while True:
                gname = f"{rnd.choice(GROUP_PREFIX)} {rnd.choice(['Intelligence', 'Learning', 'Systems', 'Computing', 'Analytics'])} {rnd.choice(GROUP_SUFFIX)}"
                if gname not in names_used:
                    break
            dept = rnd.choice(DEPTS)
            quota = rnd.choice([200, 300, 400, 500, 600, 800])
        names_used.add(gname)
        pi = faculty[gi - 1]
        created = date(2023, 6, 1) + timedelta(days=rnd.randint(0, 300))
        groups.append(dict(id=gi, quota=quota, pi=pi["id"], members=[pi["id"]], created=created))
        rows["research_group"].append([gi, gname, dept, pi["id"], quota, created])
        rows["membership"].append([pi["id"], gi, "Manager", created])
    member_of = {u["id"]: set() for u in users}
    for g in groups:
        member_of[g["pi"]].add(g["id"])
    # extra faculty collaborate with two groups
    for u in faculty[n_groups:]:
        for g in rnd.sample(groups, 2):
            g["members"].append(u["id"])
            member_of[u["id"]].add(g["id"])
            rows["membership"].append([u["id"], g["id"], "Collaborator",
                                       g["created"] + timedelta(days=rnd.randint(10, 200))])
    for u in students:
        k = 2 if rnd.random() < 0.15 else 1
        for g in rnd.sample(groups, k):
            role = "Collaborator" if k == 2 and g["id"] == max(member_of[u["id"]] | {g["id"]}) and member_of[u["id"]] else (
                "Manager" if u["role"] == "Postdoc" and rnd.random() < 0.5 else "Member")
            g["members"].append(u["id"])
            member_of[u["id"]].add(g["id"])
            j = g["created"] + timedelta(days=rnd.randint(5, 400))
            rows["membership"].append([u["id"], g["id"], role, min(j, span_start.date() - timedelta(days=1))])
    # staff (admins) belong to no lab; one staff member collaborates with one group
    rows["membership"].append([staff[-1]["id"], groups[0]["id"], "Collaborator", date(2024, 1, 15)])
    groups[0]["members"].append(staff[-1]["id"])

    # --- Models, versions, envs ---
    rows["software_env"] = []
    env_id, env_cuda = {}, {}
    for i, e in enumerate(ENVS, 1):
        env_id[e[0]] = i
        env_cuda[i] = e[3]
        rows["software_env"].append([i, e[1], e[2], e[3], e[4], e[5], e[6],
                                     date(2023, 1, 1) + timedelta(days=rnd.randint(0, 600))])
    rows["ai_model"], rows["model_version"], rows["env_supports_version"] = [], [], []
    versions = []          # dicts
    maint_pool = [u for u in users if u["role"] in ("Postdoc", "PhD", "Staff")]
    for mi, (mname, task, fw, lic, url, desc, vers) in enumerate(MODELS, 1):
        m = rnd.choice(maint_pool)
        rows["ai_model"].append([mi, mname, task, fw, lic, url, m["id"], desc,
                                 date(2024, 1, 1) + timedelta(days=rnd.randint(0, 500))])
        for (tag, params, prec, vram, size, rel, dep, envs) in vers:
            v = dict(model=mi, tag=tag, vram=vram, size=size, task=task, dep=dep,
                     envs=[env_id[k] for k in envs], name=mname)
            versions.append(v)
            rows["model_version"].append([mi, tag, params, prec, vram, size, rel, str(dep).lower()])
            for e in v["envs"]:
                rows["env_supports_version"].append([e, mi, tag,
                                                     date(2025, 1, 1) + timedelta(days=rnd.randint(0, 500)),
                                                     None if rnd.random() < 0.7 else rnd.choice(
                                                         ["needs flash-attn", "verified with batch size 1",
                                                          "tested on A100 and H100", "slow on V100"])])
    env_versions = {}
    for v in versions:
        for e in v["envs"]:
            env_versions.setdefault(e, []).append(v)

    # --- Checkpoints staged on nodes ---
    rows["node_hosts_version"] = []
    never_staged = {(v["model"], v["tag"]) for v in versions[-3:]}   # a few stay unstaged
    for n in nodes:
        cap = n["nvme"] * 1000 * 0.8
        used = 0.0
        nvram = n["gpus"][0]["vram"] * len(n["gpus"])
        cand = [v for v in versions if (v["model"], v["tag"]) not in never_staged
                and v["vram"] <= nvram and not v["dep"]]
        rnd.shuffle(cand)
        take = rnd.randint(4, 9) if not big else rnd.randint(3, 8)
        for v in cand[:take]:
            if used + v["size"] > cap:
                continue
            used += v["size"]
            slug = v["name"].lower().replace(".", "_")
            rows["node_hosts_version"].append([n["id"], v["model"], v["tag"],
                                               f"/scratch/models/{slug}/{v['tag']}",
                                               date(2025, 6, 1) + timedelta(days=rnd.randint(0, 450))])
            n.setdefault("hosted", []).append(v)

    # --- Reservations ---
    rows["reservation"], rows["reservation_gpu"] = [], []
    res_list = []
    dur_choices = [1, 2, 3, 4, 6, 8, 12, 24, 48]
    dur_w = [6, 10, 8, 10, 10, 10, 8, 5, 2]
    ngpu_choices = [1, 2, 4, 8]
    ngpu_w = [50, 25, 18, 7]

    def free(g, s, e):
        return all(e <= bs or s >= be for (bs, be) in g["busy"])

    week = span_start
    while week < span_end:
        is_future = week > anchor
        for g in groups:
            fill = rnd.uniform(0.15, 0.45) if is_future else rnd.uniform(0.55, 0.95)
            budget = g["quota"] * fill
            used = 0.0
            tries = 0
            while used < budget and tries < 400:
                tries += 1
                user = rnd.choice(g["members"])
                if user == g["pi"] and rnd.random() < 0.85:
                    continue
                n_g = rnd.choices(ngpu_choices, ngpu_w)[0]
                dur = rnd.choices(dur_choices, dur_w)[0]
                start = week + timedelta(hours=rnd.randint(0, 167))
                end = start + timedelta(hours=dur)
                if end > span_end or (is_future and start < anchor):
                    continue
                gh = n_g * dur
                if used + gh > g["quota"]:
                    continue
                if end <= anchor:
                    status = "CANCELLED" if rnd.random() < 0.08 else "COMPLETED"
                elif start <= anchor:
                    status = "ACTIVE"
                else:
                    status = rnd.choices(["CONFIRMED", "PENDING", "CANCELLED"], [60, 30, 10])[0]
                bookable_now = status in ("PENDING", "CONFIRMED", "ACTIVE")
                cand_nodes = [n for n in nodes if len(n["gpus"]) >= n_g
                              and (not bookable_now or n["status"] == "UP")]
                rnd.shuffle(cand_nodes)
                chosen = None
                for n in cand_nodes:
                    ok = [x for x in n["gpus"]
                          if (not bookable_now or x["status"] == "AVAILABLE")
                          and x["status"] != "RETIRED"
                          and (status == "CANCELLED" or free(x, start, end))]
                    if len(ok) >= n_g:
                        chosen = (n, rnd.sample(ok, n_g))
                        break
                if not chosen:
                    continue
                node, gl = chosen
                if status != "CANCELLED":
                    for x in gl:
                        x["busy"].append((start, end))
                    used += gh
                created = start - timedelta(minutes=rnd.randint(30, 60 * 24 * 6))
                cat = rnd.choice([v["task"] for v in node.get("hosted", versions)] or ["Other"])
                purpose = rnd.choice(PURPOSES.get(cat, PURPOSES["Other"]))
                res_list.append(dict(user=user, group=g["id"], start=start, end=end, status=status,
                                     created=created, purpose=purpose, node=node, gpus=gl))
        week += timedelta(weeks=1)

    res_list.sort(key=lambda r: (r["start"], r["group"]))
    for i, r in enumerate(res_list, 1):
        r["id"] = i
        rows["reservation"].append([i, r["user"], r["group"], ts(r["start"]), ts(r["end"]),
                                    r["purpose"], r["status"], ts(r["created"])])
        for x in sorted(r["gpus"], key=lambda x: x["id"]):
            rows["reservation_gpu"].append([i, x["id"]])

    # --- Jobs ---
    rows["job"], rows["job_loads_version"] = [], []
    jobs = []
    roles_single = (["inference", "fine-tune", "evaluation", "pretrain"], [40, 30, 22, 8])
    for r in res_list:
        if r["status"] in ("CANCELLED",):
            continue
        if r["status"] in ("PENDING", "CONFIRMED"):
            if r["start"] - anchor < timedelta(days=3) and rnd.random() < 0.3:
                k_jobs = 1          # someone already queued a job
            else:
                continue
        else:
            k_jobs = rnd.choices([1, 2, 3, 4, 5, 6], [20, 25, 20, 15, 12, 8])[0]
        node = r["node"]
        total_vram = sum(x["vram"] for x in r["gpus"])
        envs_ok = [e for e in env_versions
                   if env_cuda[e] <= node["cuda"]
                   and any(v["vram"] <= total_vram for v in env_versions[e])]
        if not envs_ok:
            continue
        # a reservation tends to reuse one environment, sometimes two
        res_envs = rnd.sample(envs_ok, min(len(envs_ok), rnd.choice([1, 1, 1, 2])))
        dur_min = int((r["end"] - r["start"]).total_seconds() // 60)
        for _ in range(k_jobs):
            e = rnd.choice(res_envs)
            pool = [v for v in env_versions[e] if v["vram"] <= total_vram]
            hosted = [v for v in pool if v in node.get("hosted", [])]
            if hosted and rnd.random() < 0.75:
                pool = hosted              # people prefer checkpoints already on the node
            nload = min(len(pool), rnd.choices([1, 2, 3], [75, 20, 5])[0])
            loaded = rnd.sample(pool, nload)
            if r["status"] in ("PENDING", "CONFIRMED"):
                submit = anchor - timedelta(minutes=rnd.randint(5, 600))
                start = end = None
                state = "QUEUED"
            else:
                submit = r["start"] + timedelta(minutes=rnd.randint(-90, max(1, int(dur_min * 0.7))))
                start = max(submit, r["start"]) + timedelta(minutes=rnd.randint(0, 20))
                if start >= r["end"]:
                    start = r["end"] - timedelta(minutes=5)
                    submit = min(submit, start)
                remaining = int((r["end"] - start).total_seconds() // 60)
                length = rnd.randint(min(5, remaining), max(min(5, remaining), remaining))
                end = start + timedelta(minutes=length)
                state = rnd.choices(["COMPLETED", "FAILED", "TIMEOUT", "CANCELLED"], [72, 15, 6, 7])[0]
                if state == "TIMEOUT":
                    end = r["end"]
                if state == "CANCELLED" and rnd.random() < 0.5:
                    start = end = None
                if r["status"] == "ACTIVE":
                    if submit > anchor:
                        submit = anchor - timedelta(minutes=rnd.randint(1, 60))
                        start = end = None
                        state = "QUEUED"
                    elif start is not None and start > anchor:
                        start = end = None
                        state = "QUEUED"
                    elif end is not None and end > anchor:
                        end = None
                        state = "RUNNING"
            exit_code = 0 if state == "COMPLETED" else (rnd.choice([1, 2, 137, 139]) if state == "FAILED" else None)
            need = sum(v["vram"] for v in loaded)
            if start is not None:
                peak = round(min(total_vram, need * rnd.uniform(0.8, 1.6)), 1)
                util = round(rnd.uniform(15, 98), 2)
            else:
                peak = util = None
            mname = loaded[0]["name"].split("-")[0].lower()
            jobs.append(dict(res=r["id"], env=e, name=f"{mname}-{rnd.choice(['run', 'exp', 'sweep', 'eval', 'train'])}{rnd.randint(1, 999)}",
                             submit=submit, start=start, end=end, state=state, exit=exit_code,
                             peak=peak, util=util, loaded=loaded))
    jobs.sort(key=lambda j: (j["submit"], j["res"]))
    for i, j in enumerate(jobs, 1):
        rows["job"].append([i, j["res"], j["env"], j["name"], ts(j["submit"]), ts(j["start"]), ts(j["end"]),
                            j["state"], j["exit"], j["peak"], j["util"]])
        lj = j["loaded"]
        for idx, v in enumerate(lj):
            if len(lj) == 1:
                role = rnd.choices(*roles_single)[0]
            elif idx == 0:
                role = "attack" if v["task"] in ("Diffusion", "Detection") else "distillation"
            else:
                role = "evaluation" if v["task"] == "Detection" else "inference"
            rows["job_loads_version"].append([i, v["model"], v["tag"], role])

    # ---- Write CSVs + loader ----
    order = ["cluster_user", "research_group", "membership", "gpu_type", "compute_node", "gpu",
             "ai_model", "model_version", "software_env", "node_hosts_version", "env_supports_version",
             "reservation", "reservation_gpu", "job", "job_loads_version"]
    cols = {
        "cluster_user": "user_id,net_id,full_name,email,user_role,is_admin,is_active,created_at",
        "research_group": "group_id,group_name,department,pi_user_id,weekly_gpu_hour_quota,created_on",
        "membership": "user_id,group_id,member_role,joined_on",
        "gpu_type": "gpu_type_id,type_name,vendor,architecture,vram_gb,compute_capability,fp16_tflops,supports_bf16",
        "compute_node": "node_id,hostname,partition_name,cpu_cores,ram_gb,local_nvme_tb,max_cuda_version,node_status,rack_location",
        "gpu": "gpu_id,node_id,slot_index,gpu_type_id,serial_number,gpu_status,installed_on",
        "ai_model": "model_id,model_name,task_category,framework,license,source_url,maintainer_id,description,added_on",
        "model_version": "model_id,version_tag,param_count_b,weight_precision,min_vram_gb,checkpoint_size_gb,released_on,is_deprecated",
        "software_env": "env_id,env_name,python_version,cuda_version,framework,framework_version,container_image,created_on",
        "node_hosts_version": "node_id,model_id,version_tag,local_path,staged_on",
        "env_supports_version": "env_id,model_id,version_tag,verified_on,notes",
        "reservation": "reservation_id,user_id,group_id,start_time,end_time,purpose,res_status,created_at",
        "reservation_gpu": "reservation_id,gpu_id",
        "job": "job_id,reservation_id,env_id,job_name,submit_time,start_time,end_time,job_state,exit_code,peak_vram_gb,avg_gpu_util_pct",
        "job_loads_version": "job_id,model_id,version_tag,usage_role",
    }
    for t in order:
        with open(os.path.join(out, f"{t}.csv"), "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(cols[t].split(","))
            for row in rows[t]:
                w.writerow(["" if x is None else x for x in row])

    identity = {"cluster_user": "user_id", "research_group": "group_id", "gpu_type": "gpu_type_id",
                "compute_node": "node_id", "gpu": "gpu_id", "ai_model": "model_id",
                "software_env": "env_id", "reservation": "reservation_id", "job": "job_id"}
    with open(os.path.join(out, "load_data.sql"), "w") as fh:
        fh.write(f"-- Generated by generate_data.py --scale {args.scale} --seed {args.seed}\n"
                 f"-- Run from the project root:  psql -d gpuhub -f {out}/load_data.sql\n"
                 "\\set ON_ERROR_STOP on\nSET search_path TO gpuhub;\n\n"
                 "-- One transaction: deferred constraints (group PI membership,\n"
                 "-- reservation-has-GPU) are checked at COMMIT; triggers check every row.\nBEGIN;\n")
        for t in order:
            fh.write(f"\\copy {t} ({cols[t]}) FROM '{out}/{t}.csv' WITH (FORMAT csv, HEADER true)\n")
        fh.write("COMMIT;\n\n-- Move identity sequences past the loaded ids\n")
        for t, c in identity.items():
            fh.write(f"SELECT setval(pg_get_serial_sequence('gpuhub.{t}', '{c}'), (SELECT MAX({c}) FROM {t}));\n")
        fh.write("\nANALYZE;\n")

    print(f"Wrote {out}/ :", ", ".join(f"{t}={len(rows[t])}" for t in order))


if __name__ == "__main__":
    main()
