# ============================================================
# PostgreSQL 16 + pgvector + zhparser（多阶段构建）
#
# 为什么用多阶段：
#   编译 SCWS / zhparser 需要完整构建工具链
#   （build-essential / autoconf / automake / libtool / postgresql-server-dev-16），
#   约 1.3 GB。这些只在编译时需要，不应进入运行镜像。
#   多阶段让它们只存在于 builder 阶段，最终镜像从 ~2 GB 降到 ~700 MB。
#
# 构建：docker compose build postgres
# ============================================================

# ---------------- 阶段 1：构建 ----------------
FROM pgvector/pgvector:pg16 AS builder

USER root

# 编译依赖（只存在于本阶段，不进最终镜像）
#   build-essential / postgresql-server-dev-16 : 编译 PG 扩展
#   autoconf automake libtool                 : SCWS 归档若只含 configure.ac 时需要
#   wget git                                  : 获取源码
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        build-essential \
        autoconf \
        automake \
        libtool \
        ca-certificates \
        git \
        wget \
        postgresql-server-dev-16; \
    rm -rf /var/lib/apt/lists/*

# SCWS 1.2.3 —— zhparser 依赖的分词底层库
RUN set -eux; \
    wget -q -O /tmp/scws.tar.gz https://github.com/hightman/scws/archive/refs/tags/1.2.3.tar.gz; \
    mkdir -p /tmp/scws; \
    tar -xzf /tmp/scws.tar.gz -C /tmp/scws --strip-components=1; \
    cd /tmp/scws; \
    touch NEWS README AUTHORS ChangeLog; \
    [ -f configure ] || autoreconf -fi; \
    ./configure --prefix=/usr/local; \
    make -j"$(nproc)"; \
    make install; \
    echo /usr/local/lib > /etc/ld.so.conf.d/scws.conf; \
    ldconfig; \
    rm -rf /tmp/scws /tmp/scws.tar.gz

# zhparser —— 用 ARG 固定 commit，避免上游 master 漂移
ARG ZHPARSER_REF=master
RUN set -eux; \
    git clone https://github.com/amutu/zhparser.git /tmp/zhparser; \
    cd /tmp/zhparser; \
    git checkout "$ZHPARSER_REF"; \
    SCWS_HOME=/usr/local make; \
    make install; \
    git rev-parse HEAD > /zhparser-commit.txt; \
    rm -rf /tmp/zhparser/.git

# ---------------- 阶段 2：运行 ----------------
# 干净的 pgvector 基础镜像 + 只拷入产物，不携带任何构建工具链
FROM pgvector/pgvector:pg16

USER root

# SCWS 运行库（libscws.so 及其软链接）
COPY --from=builder /usr/local/ /usr/local/

# zhparser 动态库
COPY --from=builder /usr/lib/postgresql/16/lib/zhparser.so /usr/lib/postgresql/16/lib/
COPY --from=builder /usr/lib/postgresql/16/lib/bitcode/zhparser /usr/lib/postgresql/16/lib/bitcode/zhparser
COPY --from=builder /usr/lib/postgresql/16/lib/bitcode/zhparser.index.bc /usr/lib/postgresql/16/lib/bitcode/

# zhparser 控制文件与各版本升级脚本（通配符匹配全部 zhparser*）
COPY --from=builder /usr/share/postgresql/16/extension/zhparser* /usr/share/postgresql/16/extension/

# 分词词典与规则
COPY --from=builder /usr/share/postgresql/16/tsearch_data/dict.utf8.xdb /usr/share/postgresql/16/tsearch_data/
COPY --from=builder /usr/share/postgresql/16/tsearch_data/rules.utf8.ini /usr/share/postgresql/16/tsearch_data/

# 版本记录（便于复现）
COPY --from=builder /zhparser-commit.txt /zhparser-commit.txt

# 让动态链接器找到 libscws
RUN set -eux; \
    echo /usr/local/lib > /etc/ld.so.conf.d/scws.conf; \
    ldconfig

USER postgres
