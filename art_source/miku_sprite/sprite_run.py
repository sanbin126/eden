import asyncio
import io
import json
import os
import sys
import uuid

sys.path.insert(0, r"c:\Users\suwei\dev\backend")

import httpx  # noqa: E402
import numpy as np  # noqa: E402
from PIL import Image, ImageDraw, ImageFilter  # noqa: E402
from sqlalchemy import select  # noqa: E402

from app.core.database import async_session  # noqa: E402
from app.models.model_task import ModelTask  # noqa: E402
from app.tasks.async_db import run_async  # noqa: E402
from app.tasks.image_generation import _process_image_generation_task_async  # noqa: E402

MAP_PATH = r"c:\Users\suwei\dev\sprite-work\task_map.json"
OUT_DIR = r"c:\Users\suwei\dev\sprite-work"
CANVAS = (580, 2014)
TERMINAL = {"succeeded", "failed", "cancelled"}


async def task_row(task_id: str) -> ModelTask | None:
    async with async_session() as db:
        return (
            await db.execute(select(ModelTask).where(ModelTask.id == uuid.UUID(task_id)))
        ).scalar_one_or_none()


def cutout(raw: Image.Image) -> Image.Image:
    rgba = raw.convert("RGBA")
    alpha = rgba.split()[-1]
    if alpha.getextrema()[0] < 250:
        return rgba
    rgb = rgba.convert("RGB")
    probe = rgb.copy()
    width, height = probe.size
    seeds = [(x, 1) for x in range(0, width, 8)]
    seeds += [(x, height - 2) for x in range(0, width, 8)]
    seeds += [(1, y) for y in range(0, height, 8)]
    seeds += [(width - 2, y) for y in range(0, height, 8)]
    for seed in seeds:
        if probe.getpixel(seed) == (255, 0, 255):
            continue
        ImageDraw.floodfill(probe, seed, (255, 0, 255), thresh=24)
    arr = np.array(probe)
    magenta = (arr[:, :, 0] == 255) & (arr[:, :, 1] == 0) & (arr[:, :, 2] == 255)
    # 兜底：与画布边框连通的近白低饱和像素一律视为背景，处理纸纹/渐变残留
    fg = (~(magenta | border_white(rgb))).astype(np.uint8) * 255
    fg_img = Image.fromarray(fg)
    # 开运算：去掉背景噪点/残留白点，同时保留发丝等细长结构
    fg_img = fg_img.filter(ImageFilter.MinFilter(3)).filter(ImageFilter.MaxFilter(3))
    # 只保留主体连通区域，剔除零星碎块
    fg_img = keep_main_region(fg_img)
    # 清掉主体左右极边缘残留的背景带（如门框旁的灰白条）
    fg_img = trim_edge_backdrop(fg_img, rgb)
    # 收缩 1px 去掉白边残留
    fg_img = fg_img.filter(ImageFilter.MinFilter(3))
    # 去掉贴边近白残留（纸纹与投影亮边）
    fg_img = strip_halo(fg_img, rgb, radius=10)
    # 清理上述步骤切出的细长孤立条带，并只保留主体
    fg_img = drop_thin_runs(fg_img, max_width=10)
    fg_img = keep_main_region(fg_img)
    # 剔除被躯干包围的纸白背景空腔（如双腿之间、手臂与身体之间）
    fg_img = remove_paper_pockets(fg_img, rgb)
    # 羽化边缘抗锯齿
    soft = fg_img.filter(ImageFilter.GaussianBlur(0.8))
    alpha_np = np.array(soft).astype(np.float32) / 255.0
    # 去白边：按 alpha 反预乘白底
    color = np.array(rgb).astype(np.float32)
    safe = np.maximum(alpha_np, 0.05)[..., None]
    despilled = np.clip((color - (1.0 - alpha_np[..., None]) * 255.0) / safe, 0, 255)
    out = np.dstack([despilled, alpha_np * 255.0]).astype(np.uint8)
    return Image.fromarray(out, mode="RGBA")


def border_white(rgb: Image.Image) -> np.ndarray:
    """标记与画布四边连通的「近白低饱和」像素。"""
    px = np.array(rgb).astype(np.int16)
    mn = px.min(axis=2)
    mx = px.max(axis=2)
    cand = (mn >= 208) & ((mx - mn) <= 20)
    mask = Image.fromarray(np.where(cand, 255, 0).astype(np.uint8))
    width, height = mask.size
    seeds = [(x, 0) for x in range(0, width, 4)] + [(x, height - 1) for x in range(0, width, 4)]
    seeds += [(0, y) for y in range(0, height, 4)] + [(width - 1, y) for y in range(0, height, 4)]
    for seed in seeds:
        if mask.getpixel(seed) == 255:
            ImageDraw.floodfill(mask, seed, 128, thresh=0)
    return np.array(mask) == 128


def strip_halo(mask: Image.Image, rgb: Image.Image, radius: int) -> Image.Image:
    """清除主体轮廓内侧 radius 像素范围内的近白低饱和像素（纸纹/投影亮边）。"""
    size = radius * 2 + 1
    inner = np.array(mask.filter(ImageFilter.MinFilter(size))).astype(np.int16)
    band = np.array(mask).astype(np.int16) - inner > 0
    px = np.array(rgb).astype(np.int16)
    whiteish = (px.min(axis=2) >= 232) & ((px.max(axis=2) - px.min(axis=2)) <= 24)
    keep = (np.array(mask) > 0) & ~(band & whiteish)
    return Image.fromarray((keep * 255).astype(np.uint8))


def drop_thin_runs(mask: Image.Image, max_width: int = 4) -> Image.Image:
    """按行剔除宽度不超过 max_width 的细长孤立条带（如门框边缘线）。"""
    solid = np.array(mask) > 0
    keep = np.zeros_like(solid)
    for y in range(solid.shape[0]):
        row = solid[y]
        if not row.any():
            continue
        edges = np.nonzero(np.diff(np.concatenate(([0], row.astype(np.int8), [0]))))[0]
        for start, end in zip(edges[::2], edges[1::2]):
            if end - start > max_width:
                keep[y, start:end] = True
    return Image.fromarray((keep * 255).astype(np.uint8))


def remove_paper_pockets(mask: Image.Image, rgb: Image.Image) -> Image.Image:
    """剔除被躯干包围的纸白背景空腔（双腿之间、手臂与身体之间等）。

    只处理大面积、位于头部以下、且四周偏暗的纯白区域，避免误伤衬衫高光与眼白。
    """
    solid = np.array(mask) > 0
    height = solid.shape[0]
    px = np.array(rgb).astype(np.int16)
    mn = px.min(axis=2)
    chroma = px.max(axis=2) - mn
    cand = solid & (mn >= 236) & (chroma <= 12)
    probe = Image.fromarray(np.where(cand, 255, 0).astype(np.uint8)).convert("RGB")
    check = np.where(cand, 255, 0).astype(np.uint8)
    min_area = 400
    ys, xs = np.nonzero(check)
    removed = np.zeros_like(solid)
    for y, x in zip(ys, xs):
        if check[y, x] != 255:
            continue
        ImageDraw.floodfill(probe, (int(x), int(y)), (128, 128, 128), thresh=0)
        comp = np.array(probe)[:, :, 0] == 128
        ImageDraw.floodfill(probe, (int(x), int(y)), (64, 64, 64), thresh=0)
        check = np.array(probe)[:, :, 0]
        area = int(comp.sum())
        if area < min_area:
            continue
        cy, cx = np.nonzero(comp)
        if cy.min() < height * 0.25:
            continue
        ring = np.zeros_like(comp)
        ring[max(0, cy.min() - 4) : cy.max() + 5, max(0, cx.min() - 4) : cx.max() + 5] = True
        ring &= solid & ~comp
        if not ring.any():
            continue
        if float(np.median(mn[ring])) >= 170:
            continue
        removed |= comp
    if not removed.any():
        return mask
    keep = solid & ~removed
    return Image.fromarray((keep * 255).astype(np.uint8))


def trim_edge_backdrop(mask: Image.Image, rgb: Image.Image, frac: float = 0.10) -> Image.Image:
    """清除主体轮廓左右边缘带内的近白低饱和残留。"""
    solid = np.array(mask) > 0
    ys, xs = np.nonzero(solid)
    if not len(xs):
        return mask
    left, right = int(xs.min()), int(xs.max())
    edge = max(6, int((right - left + 1) * frac))
    px = np.array(rgb).astype(np.int16)
    mn = px.min(axis=2)
    neutral = (mn >= 228) & ((px.max(axis=2) - mn) <= 20)
    cols = np.zeros_like(solid)
    cols[:, left : left + edge] = True
    cols[:, max(0, right - edge + 1) : right + 1] = True
    solid &= ~(cols & neutral)
    return Image.fromarray((solid * 255).astype(np.uint8))


def keep_main_region(mask: Image.Image) -> Image.Image:
    arr = np.array(mask)
    solid = arr > 128
    if not solid.any():
        return mask
    row_counts = solid.sum(axis=1)
    cy = int(row_counts.argmax())
    xs = np.nonzero(solid[cy])[0]
    cx = int(xs[len(xs) // 2])
    probe = mask.copy()
    ImageDraw.floodfill(probe, (cx, cy), 0, thresh=0)
    main = solid & (np.array(probe) < 128)
    return Image.fromarray(np.where(main, arr, 0).astype(np.uint8))


def content_bbox(mask: Image.Image, min_frac: float = 0.02) -> tuple[int, int, int, int] | None:
    """按行/列不透明覆盖率取包围盒，忽略边缘零星残点避免主体被压小。"""
    solid = np.array(mask) > 128
    if not solid.any():
        return None
    height, width = solid.shape
    col_ok = solid.sum(axis=0) >= max(6, int(height * min_frac))
    row_ok = solid.sum(axis=1) >= max(6, int(width * min_frac))
    xs = np.nonzero(col_ok)[0]
    ys = np.nonzero(row_ok)[0]
    if not len(xs) or not len(ys):
        return None
    return int(xs[0]), int(ys[0]), int(xs[-1]) + 1, int(ys[-1]) + 1


def to_canvas(sprite: Image.Image) -> Image.Image:
    bbox = content_bbox(sprite.split()[-1])
    if bbox:
        sprite = sprite.crop(bbox)
    max_w, max_h = CANVAS[0], CANVAS[1] - 8
    scale = min(max_w / sprite.width, max_h / sprite.height)
    new_size = (max(1, round(sprite.width * scale)), max(1, round(sprite.height * scale)))
    sprite = sprite.resize(new_size, Image.LANCZOS)
    canvas = Image.new("RGBA", CANVAS, (0, 0, 0, 0))
    canvas.paste(
        sprite,
        ((CANVAS[0] - sprite.width) // 2, CANVAS[1] - 4 - sprite.height),
        sprite,
    )
    return canvas


async def collect(entry: dict) -> None:
    from app.services.storage import storage_service

    name = entry["name"]
    row = await task_row(entry["task_id"])
    if row is None or row.status != "succeeded":
        print(f"SKIP {name} status={getattr(row, 'status', 'missing')} error={getattr(row, 'error_message', None)}", flush=True)
        return
    item_ids = (row.result_data or {}).get("item_ids") or []
    if not item_ids:
        print(f"SKIP {name} no item_ids", flush=True)
        return
    url = storage_service.get_signed_item_url(item_ids[0])
    with httpx.Client(timeout=90.0, follow_redirects=True) as client:
        resp = client.get(url)
        resp.raise_for_status()
    raw_path = os.path.join(OUT_DIR, f"{name}_raw.png")
    with open(raw_path, "wb") as fh:
        fh.write(resp.content)
    with Image.open(io.BytesIO(resp.content)) as img:
        print(f"RAW {name} size={img.size} mode={img.mode} item={item_ids[0]}", flush=True)
        final = to_canvas(cutout(img))
    final_path = os.path.join(OUT_DIR, f"{name}.png")
    final.save(final_path, format="PNG")
    print(f"SAVED {final_path} size={final.size}", flush=True)


async def pipeline(entries: list[dict]) -> None:
    for entry in entries:
        name = entry["name"]
        for _ in range(150):
            row = await task_row(entry["task_id"])
            status = row.status if row else "missing"
            if status in TERMINAL:
                print(f"DONE {name} status={status}", flush=True)
                break
            if status == "pending":
                try:
                    await _process_image_generation_task_async(None, entry["task_id"])
                except Exception as exc:  # noqa: BLE001
                    print(f"ERROR {name} {type(exc).__name__}: {exc}", flush=True)
            await asyncio.sleep(8)
        await collect(entry)
        await asyncio.sleep(10)


def recut(names: list[str]) -> None:
    for name in names:
        raw_path = os.path.join(OUT_DIR, f"{name}_raw.png")
        if not os.path.exists(raw_path):
            print(f"SKIP {name} no raw", flush=True)
            continue
        with Image.open(raw_path) as img:
            final = to_canvas(cutout(img))
        final_path = os.path.join(OUT_DIR, f"{name}.png")
        final.save(final_path, format="PNG")
        arr = np.array(final)
        opaque = arr[:, :, 3] > 250
        white = (arr[:, :, :3].min(axis=2) >= 245) & opaque
        print(
            f"RECUT {name} size={final.size} opaque={int(opaque.sum())} white_left={int(white.sum())}",
            flush=True,
        )


def main() -> None:
    args = sys.argv[1:]
    if args and args[0] == "recut":
        recut(args[1:])
        return
    wanted = set(args)
    with open(MAP_PATH, encoding="utf-8") as fh:
        entries = json.load(fh)
    if wanted:
        entries = [e for e in entries if e["name"] in wanted]
    run_async(pipeline(entries))
    print("PIPELINE DONE", flush=True)


if __name__ == "__main__":
    main()
