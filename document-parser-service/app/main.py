"""解析服务的 HTTP 接口。

设计取舍：接收 multipart 上传的 PDF 字节，而不是让 Python 去 COS 取文件。
理由：解析服务的职责边界是「看懂文档」，不该同时持有对象存储或 DB 凭据；
文件从哪来（COS / 本地 / 未来其他源）与结果存哪，都由 Java 侧决定。

两条路径：
  - POST /parse   同步，适合小页范围与手工验证
  - POST /jobs    异步作业，长文档走这条 —— 181 页约 25 分钟，同步 HTTP 在这个
    量级上是缺陷：网络抖动全部重来、worker 被占死、客户端看不到进度。
    GET /jobs/{id} 查询状态与结果。
"""
from __future__ import annotations

import logging
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse

from app.config import settings
from app.converter import parse_pdf

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger(__name__)

app = FastAPI(title="金融研报解析服务", version="0.1.0")


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "hf_endpoint": settings.hf_endpoint}


def _validate(file: UploadFile, page_start, page_end):
    """同步端点与作业端点的共用校验。返回 (bytes, page_range)。"""
    raw = file.file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="上传文件为空")
    if len(raw) > settings.max_upload_bytes:
        raise HTTPException(status_code=413,
                            detail=f"文件超过上限 {settings.max_upload_bytes} 字节")
    if not raw.startswith(b"%PDF"):
        raise HTTPException(status_code=400, detail="不是合法 PDF（缺少 %PDF 魔数）")
    page_range = None
    if page_start is not None and page_end is not None:
        if page_start < 1 or page_end < page_start:
            raise HTTPException(status_code=400, detail="页范围非法")
        page_range = (page_start, page_end)
    return raw, page_range


@app.post("/parse")
def parse(
    file: UploadFile = File(...),
    page_start: int | None = Form(None),
    page_end: int | None = Form(None),
) -> JSONResponse:
    """同步解析上传的 PDF，返回 NormalizedDocument。小页范围用这条。

    刻意用同步 def：parse_pdf 是分钟级 CPU 任务，async def 会占住事件循环，
    把 /health 和并发请求全卡死。FastAPI 会把同步端点丢进线程池执行。
    """
    raw, page_range = _validate(file, page_start, page_end)

    with tempfile.TemporaryDirectory() as tmp:
        # 不能直接用 file.filename —— 可含 ../ 穿越临时目录
        path = Path(tmp) / Path(file.filename or "upload.pdf").name
        path.write_bytes(raw)
        try:
            doc = parse_pdf(path, page_range=page_range)
        except Exception as exc:
            logger.exception("解析失败")
            raise HTTPException(status_code=500,
                                detail=f"解析失败: {type(exc).__name__}: {exc}") from exc

    return JSONResponse(content=doc.model_dump(mode="json"))


# ---------------- 作业模式 ----------------
# 阶段 2 的作业状态在进程内存里，因此：
#   1) uvicorn 必须以单 worker 运行（--workers 默认 1，不要调大）
#   2) 服务重启后作业丢失 —— 刻意简化，阶段 7 换成 Kafka + 状态表
_JOB_DIR = Path(tempfile.gettempdir()) / "finagent-parse-jobs"
_JOB_DIR.mkdir(parents=True, exist_ok=True)


@dataclass
class Job:
    job_id: str
    source_name: str
    status: str = "pending"        # pending | running | done | failed
    submitted_at: float = field(default_factory=time.time)
    started_at: float | None = None
    finished_at: float | None = None
    page_range: tuple[int, int] | None = None
    pages_parsed: int | None = None
    result: dict | None = None
    error: str | None = None

    @property
    def elapsed_seconds(self) -> float:
        end = self.finished_at or time.time()
        return round(end - (self.started_at or self.submitted_at), 1)


_JOBS: dict[str, Job] = {}


def _run_job(job: Job, pdf_path: Path) -> None:
    """后台线程里跑解析。

    Docling 是 CPU 密集任务，用线程就够（单机、人工触发，并发极低）。
    关键：后台线程的异常必须写回 job —— 否则调用方会一直轮询到超时，
    永远不知道实际早已失败。
    """
    job.status = "running"
    job.started_at = time.time()
    try:
        doc = parse_pdf(pdf_path, page_range=job.page_range)
        job.result = doc.model_dump(mode="json")
        job.pages_parsed = doc.meta.page_count
        job.status = "done"
    except Exception as exc:
        logger.exception("作业 %s 解析失败", job.job_id)
        job.error = f"{type(exc).__name__}: {exc}"
        job.status = "failed"
    finally:
        job.finished_at = time.time()
        pdf_path.unlink(missing_ok=True)


@app.post("/jobs", status_code=202)
def submit_job(
    file: UploadFile = File(...),
    page_start: int | None = Form(None),
    page_end: int | None = Form(None),
) -> dict:
    """提交解析作业，立即返回 job_id。长文档走这条。"""
    raw, page_range = _validate(file, page_start, page_end)
    job_id = uuid.uuid4().hex
    path = _JOB_DIR / f"{job_id}.pdf"
    path.write_bytes(raw)   # 必须落到请求范围之外：请求结束临时目录会被清掉
    job = Job(job_id=job_id, source_name=file.filename or "upload.pdf", page_range=page_range)
    _JOBS[job_id] = job
    threading.Thread(target=_run_job, args=(job, path), daemon=True).start()
    logger.info("已提交作业 %s (%s, %d 字节)", job_id, job.source_name, len(raw))
    return {"job_id": job_id, "status": job.status}


@app.get("/jobs/{job_id}")
def get_job(job_id: str) -> dict:
    """查询作业。result 只在 done 时返回。"""
    job = _JOBS.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail=f"作业不存在: {job_id}")
    return {
        "job_id": job.job_id,
        "status": job.status,
        "elapsed_seconds": job.elapsed_seconds,
        "pages_parsed": job.pages_parsed,
        "error": job.error,
        "result": job.result if job.status == "done" else None,
    }
