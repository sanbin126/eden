import asyncio
import copy
import io
import json
import sys
import uuid

sys.path.insert(0, r"c:\Users\suwei\dev\backend")
sys.path.insert(0, r"c:\Users\suwei\dev\sprite-work")

from PIL import Image  # noqa: E402
from sqlalchemy import select  # noqa: E402

from app.core.database import async_session  # noqa: E402
from app.models.model_task import ModelTask  # noqa: E402
from app.models.project import Project  # noqa: E402
from app.services.storage import storage_service  # noqa: E402
from app.services.task_billing import TaskBillingService  # noqa: E402
from app.services.task_tracker import TaskTrackerService  # noqa: E402
from sprite_defs import NEGATIVE, SPRITES  # noqa: E402

TEMPLATE_TASK_ID = uuid.UUID("3f1d6853-c636-4e2c-8187-56ef352316db")
REF_PATH = (
    r"c:\Users\suwei\Documents\Tencent Files\1914236264\nt_qq\nt_data\Pic\2026-10\Ori"
    r"\b830b73efa25d1516e3ea58e6e9d470e.png"
)
BODY_REF_PATH = (
    r"c:\Users\suwei\Documents\Tencent Files\1914236264\nt_qq\nt_data\Pic\2026-10\Ori"
    r"\3e595c9fec1eb0e650aef4f7adaf1543.jpg"
)
MAP_PATH = r"c:\Users\suwei\dev\sprite-work\task_map.json"
PROJECT_NAME = "立绘 · 中野三玖（差分）"
COST = 50
SIZE = "1536x2688"


def flatten_reference(path: str) -> bytes:
    with Image.open(path) as img:
        rgba = img.convert("RGBA")
        canvas = Image.new("RGB", rgba.size, (255, 255, 255))
        canvas.paste(rgba, mask=rgba.split()[-1])
        buf = io.BytesIO()
        canvas.save(buf, format="PNG")
        return buf.getvalue()


async def get_project(db, user_id, tenant_id) -> Project:
    existing = (
        await db.execute(
            select(Project).where(Project.name == PROJECT_NAME, Project.created_by == user_id)
        )
    ).scalar_one_or_none()
    if existing:
        return existing
    project = Project(
        tenant_id=tenant_id,
        name=PROJECT_NAME,
        project_type="image",
        description="中野三玖立绘差分（15 张），参考图锁定人物设定与服装",
        created_by=user_id,
    )
    db.add(project)
    await db.commit()
    await db.refresh(project)
    print(f"PROJECT {project.id}", flush=True)
    return project


async def main() -> None:
    wanted = sys.argv[1:]
    selected = [(n, p) for n, p in SPRITES if not wanted or n in wanted]
    if not selected:
        raise SystemExit("no sprite matched")

    async with async_session() as db:
        template = (
            await db.execute(select(ModelTask).where(ModelTask.id == TEMPLATE_TASK_ID))
        ).scalar_one()
        user_id = uuid.UUID(str(template.user_id))
        tenant_id = uuid.UUID(str(template.tenant_id))
        project = await get_project(db, user_id, tenant_id)
        parent_uri = f"project:{project.id}"

        ref_bytes = flatten_reference(REF_PATH)
        body_bytes = flatten_reference(BODY_REF_PATH)
        ref_items = [
            storage_service.upload_bytes_get_item_id(
                ref_bytes,
                prefix=f"dramafactory/dev/generated-images/{project.id}/references",
                filename=f"miku_reference_{uuid.uuid4().hex[:8]}.png",
                content_type="image/png",
            ),
            storage_service.upload_bytes_get_item_id(
                body_bytes,
                prefix=f"dramafactory/dev/generated-images/{project.id}/references",
                filename=f"miku_body_reference_{uuid.uuid4().hex[:8]}.png",
                content_type="image/png",
            ),
        ]
        for item in ref_items:
            print(f"REFERENCE {item}", flush=True)

        tracker = TaskTrackerService(db)
        billing = TaskBillingService(db)
        created: list[dict[str, str]] = []

        for name, prompt in selected:
            task_id = uuid.uuid4()
            input_data = copy.deepcopy(template.input_data)
            input_data.update(
                {
                    "prompt": prompt,
                    "prompt_preview": prompt,
                    "negative_prompt": NEGATIVE,
                    "size": SIZE,
                    "project_id": str(project.id),
                    "parent_uri": parent_uri,
                    "reference_item_ids": ref_items,
                    "reference_labels": ["Miku design", "Miku body proportion"],
                    "reference_item_count": len(ref_items),
                    "credit_reservation_key": f"task:{task_id}:0:0",
                }
            )
            request_payload = copy.deepcopy(template.request_payload)
            request_payload.update(
                {
                    "prompt": prompt,
                    "negative_prompt": NEGATIVE,
                    "size": SIZE,
                    "reference_item_ids": ref_items,
                    "reference_labels": ["Miku design", "Miku body proportion"],
                    "reference_item_count": len(ref_items),
                }
            )

            await tracker.create_task(
                user_id=user_id,
                tenant_id=tenant_id,
                task_type="image_generation",
                parent_uri=parent_uri,
                task_id=task_id,
                input_data=input_data,
                request_payload=request_payload,
            )
            await billing.reserve(
                task_id, user_id=user_id, amount=COST, reference_type="image_generation"
            )
            await db.commit()
            created.append(
                {"name": name, "task_id": str(task_id), "project_id": str(project.id)}
            )
            print(f"CREATED {name} {task_id}", flush=True)

        with open(MAP_PATH, "w", encoding="utf-8") as fh:
            json.dump(created, fh, ensure_ascii=False, indent=2)
        print(f"TOTAL {len(created)} tasks, cost {len(created) * COST} credits", flush=True)


asyncio.run(main())
