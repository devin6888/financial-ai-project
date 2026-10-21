"""服务配置：集中处理环境变量，尤其是 HuggingFace 镜像。

本机 huggingface.co 不可达、hf-mirror.com 可达（阶段 2 实测），
因此默认值直接指向镜像，避免模型下载卡死。
"""
from __future__ import annotations

import os

# 必须在导入 docling 之前设置，否则拿不到
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

from pydantic_settings import BaseSettings, SettingsConfigDict  # noqa: E402


class Settings(BaseSettings):
    """服务配置，可用 PARSER_ 前缀的环境变量覆盖。"""

    hf_endpoint: str = "https://hf-mirror.com"
    host: str = "127.0.0.1"
    port: int = 8000
    # 允许的最大上传字节数（默认 64MB，够放几百页年报）
    max_upload_bytes: int = 64 * 1024 * 1024
    # 解析页数上限；0 表示不限制（作业模式下由页范围参数控制）
    max_pages: int = 0

    # 评审 L1：pydantic v2 已弃用 class Config（会报 PydanticDeprecatedSince20）
    model_config = SettingsConfigDict(env_prefix="PARSER_")


settings = Settings()
