# 阶段 1：基础设施与骨架 · 实施计划

> **给执行者：** 本计划按任务逐个执行。**每个任务结束后必须停下来，运行该任务的验证命令并确认输出符合预期**，再进入下一个任务。步骤使用复选框（- [ ]）语法跟踪进度。
> 注：本环境未安装 superpowers 的执行子技能，采用「每个任务由一个子代理独立执行 + 主代理在任务间验收」的方式替代。

> 📖 术语：本文出现的缩写（Parent/Child、embedding、RRF、QU、HNSW、e2e……）在 `docs/术语表.md` 有中文全称、为什么需要它、在本项目里长什么样。写法约定见 `docs/文档写作规范.md`。

**Goal:** 让本地跑起一套可复现的基础设施——PostgreSQL(pgvector + zhparser) 能用脚本断言证明可用，Spring Boot + Spring AI 骨架能编译并通过向量读写集成测试。全程不需要任何 API Key。

**Architecture:** 用自定义 Docker 镜像把 pgvector 与 zhparser 两个 PostgreSQL 扩展固化下来，避免"每台机器装法不同"。数据库初始化用挂载的 SQL 脚本完成，因此环境可一键重建。Java 侧先用单 Maven 模块起步（YAGNI：只有一个模块时父 POM 纯属开销），待阶段 4 拆出检索模块时再转为多模块——这一决策登记在路线图的偏差表中。

**Tech Stack:** Java 21 · Spring Boot 4.0.8 · Spring AI 2.0.1 · PostgreSQL 16 · pgvector · zhparser(SCWS 1.2.3) · Docker Compose · JUnit 5

---

## File Structure

仓库根目录：`D:/Project/myproject/financial-ai-project`

| 文件 | 职责 |
|---|---|
| `.gitignore` | 忽略构建产物、IDE 文件、Word 锁文件、本地密钥 |
| `README.md` | 环境要求与一键启动说明 |
| `docker/postgres/Dockerfile` | 构建带 pgvector + zhparser 的 PostgreSQL 16 镜像 |
| `docker-compose.yml` | 本地依赖编排（本阶段只有 postgres；后续阶段追加服务） |
| `docker/postgres/init/01-extensions.sql` | 创建扩展与中文分词配置 |
| `docker/postgres/init/02-schema.sql` | 业务表结构（document / document_chunk / financial_fact） |
| `scripts/verify-db.sh` | **断言式**验证 pgvector 与中文分词真的可用 |
| `pom.xml` | Maven 依赖与版本锁定 |
| `src/main/java/com/finagent/FinAgentApplication.java` | Spring Boot 入口 |
| `src/main/resources/application.yml` | 数据源与 Spring AI（百炼 OpenAI 兼容）配置 |
| `src/test/resources/application-test.yml` | 测试配置：假 API Key + 关闭真实 embedding 自动装配 |
| `src/test/java/com/finagent/VectorStoreSmokeTest.java` | 向量写入/查询集成测试（不依赖外部 API） |

**为什么把验证脚本单独抽出：** `scripts/verify-db.sh` 是本阶段的核心资产。它把"我觉得装好了"变成可重复执行的断言，后续每台机器（包括面试官如果 clone 你的项目）都能一条命令确认环境正确。

---

## Task 1: 初始化独立 git 仓库

当前仓库根是 `D:/Project/myproject`，其中混有无关项目。把 `financial-ai-project/` 独立成库，让提交历史只属于这个项目。

**Files:**
- Create: `.gitignore`
- Create: `README.md`

- [ ] **Step 1: 确认工作目录并初始化仓库**

Run:
```bash
cd /d/Project/myproject/financial-ai-project
git init -b main
```

Expected: `Initialized empty Git repository in D:/Project/myproject/financial-ai-project/.git/`

- [ ] **Step 2: 写入 .gitignore**

```gitignore
# 构建产物
target/
*.class

# IDE
.idea/
*.iml
.vscode/

# Word 锁文件（设计文档在 Word 中打开时会产生）
~$*

# 本地密钥与运行数据
.env
docker/volumes/
*.log
```

- [ ] **Step 3: 写入 README.md**

````markdown
# 金融研报智能分析 Agent（Demo）

基于 Spring Boot + Spring AI 的金融研报 RAG 问答 Demo。

## 环境要求

- JDK 21
- Maven 3.9+
- Docker Desktop

## 一键启动依赖

```bash
docker compose up -d
bash scripts/verify-db.sh
```

`verify-db.sh` 全部输出 PASS 即表示 pgvector 与中文分词可用。

详见 `docs/superpowers/plans/` 下的开发路线图与阶段计划。
````

- [ ] **Step 4: 提交**

```bash
git add .gitignore README.md
git commit -m "chore: 初始化独立仓库与忽略规则"
```

Expected: `1 file changed` 或 `2 files changed`，无报错。

---

## Task 2: 构建带 pgvector + zhparser 的 PostgreSQL 镜像

**Files:**
- Create: `docker/postgres/Dockerfile`

- [ ] **Step 1: 先确认 SCWS 源码可获取（本计划唯一的外部不确定性）**

zhparser 依赖 SCWS 分词库。上游主站是 xunsearch.com，GitHub 上有镜像仓库 hightman/scws。**先验证再写 Dockerfile**，避免构建到一半才发现问题。

Run:
```bash
curl -sIL -o /dev/null -w '%{http_code}\n' https://github.com/hightman/scws/archive/refs/tags/v1.2.3.tar.gz
curl -sL https://github.com/hightman/scws/archive/refs/tags/v1.2.3.tar.gz | tar -tz | grep -E '/(configure|configure\.ac)$' | head -n 3
```

Expected: 第一条输出 `200`；第二条至少能匹配到 `.../configure` 或 `.../configure.ac`。

**按 Step 1 的结果分流：**
- 输出 `200` 且能看到 `configure` → 直接做 Step 2。
- 输出 `200` 但只有 `configure.ac` → Step 2 的 Dockerfile 已用 `[ -f configure ] || autoreconf -fi` 覆盖，无需改动。
- 输出 `404` 或无匹配 → tag 名可能不是 `v1.2.3`。执行 `git ls-remote --tags https://github.com/hightman/scws.git` 查真实 tag 并替换 URL；或改用上游发行包 `https://www.xunsearch.com/scws/down/scws-1.2.3.tar.bz2`（同时把 Dockerfile 里的 `tar -xzf` 改为 `tar -xjf`）。

- [ ] **Step 2: 写入 Dockerfile**

Dockerfile 同时兼容"归档内含 configure"与"只含 configure.ac"两种情况，并暴露 `ZHPARSER_REF` 以便固定版本。

```dockerfile
FROM pgvector/pgvector:pg16

USER root

# 编译依赖（autoconf/automake/libtool 用于 configure.ac 需自动生成 configure 的情况）
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

# SCWS 1.2.3（zhparser 依赖的分词底层库）
RUN set -eux; \
    wget -q -O /tmp/scws.tar.gz https://github.com/hightman/scws/archive/refs/tags/v1.2.3.tar.gz; \
    mkdir -p /tmp/scws; \
    tar -xzf /tmp/scws.tar.gz -C /tmp/scws --strip-components=1; \
    cd /tmp/scws; \
    [ -f configure ] || autoreconf -fi; \
    ./configure --prefix=/usr/local; \
    make -j"$(nproc)"; \
    make install; \
    echo /usr/local/lib > /etc/ld.so.conf.d/scws.conf; \
    ldconfig; \
    rm -rf /tmp/scws /tmp/scws.tar.gz

# zhparser；用 ARG 固定 commit，避免上游 master 漂移导致"一键重建"结果不同
ARG ZHPARSER_REF=master
RUN set -eux; \
    git clone https://github.com/amutu/zhparser.git /tmp/zhparser; \
    cd /tmp/zhparser; \
    git checkout "${ZHPARSER_REF}"; \
    SCWS_HOME=/usr/local make; \
    make install; \
    git rev-parse HEAD > /zhparser-commit.txt; \
    rm -rf /tmp/zhparser/.git

USER postgres
```

- [ ] **Step 3: 构建镜像**

Run:
```bash
docker build -t finagent-postgres:16 docker/postgres
```

Expected: 结尾为 `naming to docker.io/library/finagent-postgres:16` 且无 error。首次构建约 3~8 分钟。

- [ ] **Step 4: 验证扩展文件已进入镜像**

Run:
```bash
docker run --rm finagent-postgres:16 ls /usr/share/postgresql/16/extension/ | grep -E 'zhparser|vector'
```

Expected: 输出包含 `vector.control`、`vector--*.sql`、`zhparser.control`、`zhparser--*.sql`。

- [ ] **Step 5: 固化版本（这一步才让"一键重建"成立）**

Run:
```bash
docker image inspect pgvector/pgvector:pg16 --format '{{index .RepoDigests 0}}'
docker run --rm finagent-postgres:16 cat /zhparser-commit.txt
```

Expected: 分别输出 `pgvector/pgvector@sha256:...` 与一个 40 位 commit SHA。

把 Dockerfile 的 `FROM pgvector/pgvector:pg16` 改为 `FROM pgvector/pgvector@sha256:...`（用第一条输出），把 `ARG ZHPARSER_REF=master` 改为第二条输出的 SHA，然后重跑 Step 3、Step 4 确认仍能构建。

- [ ] **Step 6: 提交**

```bash
git add docker/postgres/Dockerfile
git commit -m "feat: 构建含 pgvector 与 zhparser 的 PostgreSQL 镜像（已固定版本）"
```

---

## Task 3: docker-compose 编排

**Files:**
- Create: `docker-compose.yml`

- [ ] **Step 1: 写入 docker-compose.yml**

```yaml
services:
  postgres:
    build: ./docker/postgres
    image: finagent-postgres:16
    container_name: finagent-postgres
    environment:
      POSTGRES_DB: finagent
      POSTGRES_USER: finagent
      POSTGRES_PASSWORD: finagent
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./docker/postgres/init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U finagent -d finagent"]
      interval: 5s
      timeout: 3s
      retries: 20

volumes:
  pgdata:
```

- [ ] **Step 2: 启动并确认健康**

Run:
```bash
docker compose up -d
sleep 8
docker compose ps
```

Expected: `finagent-postgres` 一行 STATUS 为 `Up ... (healthy)`。

- [ ] **Step 3: 确认能连上**

Run:
```bash
docker compose exec -T postgres psql -U finagent -d finagent -c "select version();"
```

Expected: 输出一行 PostgreSQL 16.x 版本信息。

- [ ] **Step 4: 提交**

```bash
git add docker-compose.yml
git commit -m "feat: 本地依赖编排"
```

---

## Task 4: 初始化 SQL（扩展 + 表结构）

**关键：** init 目录下的脚本**只在数据卷为空时执行一次**。修改后必须 `docker compose down -v` 重建，否则改动静默不生效——这是最容易浪费半小时的坑。

**Files:**
- Create: `docker/postgres/init/01-extensions.sql`
- Create: `docker/postgres/init/02-schema.sql`

- [ ] **Step 1: 写入 01-extensions.sql**

```sql
-- 向量检索
CREATE EXTENSION IF NOT EXISTS vector;

-- 中文分词
CREATE EXTENSION IF NOT EXISTS zhparser;

-- 中文文本搜索配置：以 zhparser 为解析器，把名词/动词/形容词/成语等映射到 simple 词典
DROP TEXT SEARCH CONFIGURATION IF EXISTS chinese_zh;
CREATE TEXT SEARCH CONFIGURATION chinese_zh (PARSER = zhparser);
ALTER TEXT SEARCH CONFIGURATION chinese_zh ADD MAPPING FOR n,v,a,i,e,l,j WITH simple;
```

- [ ] **Step 2: 写入 02-schema.sql**

```sql
-- 公司
CREATE TABLE IF NOT EXISTS company (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(16) NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    market      VARCHAR(16),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 文档
CREATE TABLE IF NOT EXISTS document (
    id           BIGSERIAL PRIMARY KEY,
    company_id   BIGINT REFERENCES company(id),
    doc_type     VARCHAR(32)  NOT NULL,
    doc_year     INT,
    title        VARCHAR(512),
    object_key   VARCHAR(512) NOT NULL,
    content_hash VARCHAR(64)  NOT NULL UNIQUE,
    parse_status VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    version      INT          NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_document_company ON document(company_id, doc_year);

-- 切块：Parent(上下文单元) 与 Child(检索单元)
CREATE TABLE IF NOT EXISTS document_chunk (
    id                     BIGSERIAL PRIMARY KEY,
    document_id            BIGINT NOT NULL REFERENCES document(id),
    parent_id              BIGINT REFERENCES document_chunk(id),
    chunk_level            VARCHAR(8)  NOT NULL,
    chunk_type             VARCHAR(16) NOT NULL,
    section_path           TEXT,
    title                  TEXT,
    content                TEXT NOT NULL,
    page_start             INT,
    page_end               INT,
    source_block_ids       TEXT,
    token_count            INT,
    chunk_index            INT,
    status                 VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    parser_version         VARCHAR(32),
    chunk_strategy_version VARCHAR(32),
    embedding              vector(1024),
    embedding_model        VARCHAR(64),
    embedding_version      VARCHAR(32),
    metadata               JSONB,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_chunk_document ON document_chunk(document_id, chunk_level, status);
CREATE INDEX IF NOT EXISTS idx_chunk_parent   ON document_chunk(parent_id);

-- 全文检索列（Sparse 路），使用自定义中文配置
ALTER TABLE document_chunk ADD COLUMN IF NOT EXISTS content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('chinese_zh', coalesce(title,'') || ' ' || content)) STORED;
CREATE INDEX IF NOT EXISTS idx_chunk_tsv ON document_chunk USING GIN(content_tsv);

-- 向量索引：1024 维，HNSW
CREATE INDEX IF NOT EXISTS idx_chunk_embedding ON document_chunk
    USING hnsw (embedding vector_cosine_ops);

-- 结构化事实（V1 轻量版，来源 PDF）
CREATE TABLE IF NOT EXISTS financial_fact (
    id             BIGSERIAL PRIMARY KEY,
    document_id    BIGINT NOT NULL REFERENCES document(id),
    chunk_id       BIGINT REFERENCES document_chunk(id),
    company_id     BIGINT REFERENCES company(id),
    metric         VARCHAR(64) NOT NULL,
    period         VARCHAR(32),
    value          NUMERIC(24,4),
    unit           VARCHAR(16),
    scope          VARCHAR(32),
    evidence_pages TEXT,
    confidence     NUMERIC(4,3),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_fact_company_metric ON financial_fact(company_id, metric, period);
```

- [ ] **Step 3: 重建容器让 init 脚本生效**

Run:
```bash
docker compose down -v
docker compose up -d
sleep 10
docker compose ps
```

Expected: 再次 `healthy`。

- [ ] **Step 4: 确认表已建好**

Run:
```bash
docker compose exec -T postgres psql -U finagent -d finagent -c "\dt"
```

Expected: 列出 `company`、`document`、`document_chunk`、`financial_fact` 四张表。

- [ ] **Step 5: 提交**

```bash
git add docker/postgres/init
git commit -m "feat: 数据库扩展与业务表初始化脚本"
```

---

## Task 5: 数据库断言验证脚本

这是本阶段最有价值的产出：把环境正确性变成可重复执行的断言。

**Files:**
- Create: `scripts/verify-db.sh`

- [ ] **Step 1: 写入验证脚本**

```bash
#!/usr/bin/env bash
# 断言 pgvector 与 zhparser 真的可用。任一检查失败即以非零码退出。
set -uo pipefail

PSQL="docker compose exec -T postgres psql -U finagent -d finagent -tAq"
FAILED=0

check() {
  local name="$1" expected="$2" sql="$3"
  local got
  got="$($PSQL -c "$sql" 2>&1 | tr -d '\r' | head -n1)"
  if [[ "$got" == "$expected" ]]; then
    echo "PASS  $name"
  else
    echo "FAIL  $name  (expected=$expected got=$got)"
    FAILED=1
  fi
}

check "pgvector 扩展已安装" \
      "1" \
      "SELECT count(*) FROM pg_extension WHERE extname='vector';"

check "zhparser 扩展已安装" \
      "1" \
      "SELECT count(*) FROM pg_extension WHERE extname='zhparser';"

check "中文分词配置存在" \
      "1" \
      "SELECT count(*) FROM pg_ts_config WHERE cfgname='chinese_zh';"

# 核心断言：zhparser 能把无空格的中文切成多个词，simple 只能切成 1 个
check "中文分词真的切开了词（zhparser 优于 simple）" \
      "t" \
      "SELECT (array_length(string_to_array(to_tsvector('chinese_zh','贵州茅台2025年营业收入同比增长')::text,' '),1)
             > array_length(string_to_array(to_tsvector('simple','贵州茅台2025年营业收入同比增长')::text,' '),1));"

# 自建种子数据：脚本必须能在空库上独立跑通（Task 9 会先 down -v 清空数据）。
# ON CONFLICT ... DO UPDATE ... RETURNING 保证重复执行既幂等又能取到 id。
SEED_COMPANY_ID="$($PSQL -c "INSERT INTO company(code,name) VALUES ('600519','贵州茅台')
    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name RETURNING id;")"
SEED_DOC_ID="$($PSQL -c "INSERT INTO document(company_id,doc_type,doc_year,title,object_key,content_hash)
    VALUES ($SEED_COMPANY_ID,'ANNUAL_REPORT',2025,'验证用文档','seed/verify.pdf','hash-verify')
    ON CONFLICT (content_hash) DO UPDATE SET title = EXCLUDED.title RETURNING id;")"
echo "种子数据: company_id=$SEED_COMPANY_ID document_id=$SEED_DOC_ID"

check "1024 维向量可写入" \
      "1" \
      "INSERT INTO document_chunk(document_id,chunk_level,chunk_type,content,embedding)
         VALUES ($SEED_DOC_ID,'CHILD','TEXT','verify',array_fill(0.01::real,ARRAY[1024])::vector)
         RETURNING 1;"

check "向量相似度可计算" \
      "t" \
      "SELECT ((array_fill(0.01::real,ARRAY[1024])::vector <=> array_fill(0.02::real,ARRAY[1024])::vector) IS NOT NULL);"

# 清理本次写入的验证数据
$PSQL -c "DELETE FROM document_chunk WHERE content='verify';" >/dev/null 2>&1

if [[ $FAILED -eq 0 ]]; then
  echo "---- 全部通过 ----"
else
  echo "---- 存在失败项，见上方 FAIL ----"
fi
exit $FAILED
```

- [ ] **Step 2: 运行验证脚本**

Run:
```bash
bash scripts/verify-db.sh
```

Expected: 6 行 `PASS` + `---- 全部通过 ----`，退出码 0。

**注意**：脚本会自建种子数据（`company` + `document`），因此**可以在空库上独立跑通**。这一点很关键——Task 9 的复现验证会先 `docker compose down -v` 清空数据。种子写入使用 `ON CONFLICT ... RETURNING id`，重复执行不会产生重复行。

- [ ] **Step 3: 提交**

```bash
git add scripts/verify-db.sh
git commit -m "feat: 容器化 PostgreSQL 并断言验证 pgvector 与中文分词"
```

---

## Task 6: Maven 项目与依赖版本锁定

**Files:**
- Create: `pom.xml`

- [ ] **Step 1: 写入 pom.xml**

版本已核实：Spring Boot `4.0.8`（当前 4.0.x 最新 patch）、Spring AI `2.0.1`。starter 名称来自官方参考手册，不是猜的。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.8</version>
    <relativePath/>
  </parent>

  <groupId>com.finagent</groupId>
  <artifactId>financial-agent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>financial-agent</name>
  <description>金融研报智能分析 Agent（Demo）</description>

  <properties>
    <java.version>21</java.version>
    <spring-ai.version>2.0.1</spring-ai.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>2.0.1</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>

    <!-- Spring AI：聊天 + 向量模型（走百炼 OpenAI 兼容接口） -->
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>
    <!-- Spring AI：PgVector 向量库 -->
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
    </dependency>

    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: 编译（会下载依赖，首次约 2~5 分钟）**

Run:
```bash
mvn -q -B -DskipTests compile
```

Expected: 无输出即成功（`-q` 抑制了正常日志）。退出码 0。

- [ ] **Step 3: 确认 Spring AI 依赖真的解析到了**

Run:
```bash
mvn -B dependency:tree -Dincludes=org.springframework.ai
```

Expected: 输出中出现 `org.springframework.ai:spring-ai-starter-model-openai:jar:2.0.1` 与 `org.springframework.ai:spring-ai-starter-vector-store-pgvector:jar:2.0.1`。

**若版本不存在**（例如官方已发布 2.0.2）：把 `pom.xml` 里两处 `2.0.1` 同时改掉，重新执行 Step 2 与 Step 3。**不要只改一处**。

- [ ] **Step 4: 提交**

```bash
git add pom.xml
git commit -m "build: 引入 Spring Boot 4.0.8 与 Spring AI 2.0.1"
```

---

## Task 7: Spring Boot 骨架与配置

**Files:**
- Create: `src/main/java/com/finagent/FinAgentApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/resources/application-test.yml`

- [ ] **Step 1: 写入主类**

```java
package com.finagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FinAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinAgentApplication.class, args);
    }
}
```

- [ ] **Step 2: 写入 application.yml**

**注意这里没有写 `api-key`**：百炼的 Key 通过环境变量注入，Spring Boot 的宽松绑定会自动把 `SPRING_AI_OPENAI_API_KEY` 映射到 `spring.ai.openai.api-key`。这样密钥不会进仓库，也不需要写占位符表达式。

```yaml
spring:
  application:
    name: financial-agent

  datasource:
    url: jdbc:postgresql://localhost:5432/finagent
    username: finagent
    password: finagent

  ai:
    openai:
      # 百炼 OpenAI 兼容模式；Spring AI 会在其后拼接 /v1/chat/completions 等路径
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      chat:
        options:
          model: qwen-plus
          temperature: 0.2
      embedding:
        options:
          model: text-embedding-v4
          # 必须显式指定：默认 2048 维会超出 pgvector 的 vector 索引上限（2000）
          dimensions: 1024
    # 说明：阶段 1 的 main profile 不启用 PgVectorStore 的持久化配置。
    # PgVectorStore 默认使用自己的 vector_store 表，而设计文档 §8.8 要求 embedding 存在
    # document_chunk.embedding —— 两者归属尚未决定，留到阶段 3（见文末「未决问题」）。
    # 测试 profile 用 initialize-schema: true 自建 vector_store_test 表，仅用于验证接线。

server:
  port: 8080

logging:
  level:
    com.finagent: DEBUG
```

- [ ] **Step 3: 写入 application-test.yml**

测试用假 Key（不会真的发请求），并让 PgVectorStore 自建测试表 `vector_store_test`，顺便验证它的自动建表能力。

```yaml
spring:
  ai:
    openai:
      # 假 Key：仅为让自动装配通过，测试中不做真实调用
      api-key: test-key-not-used
    vectorstore:
      pgvector:
        initialize-schema: true
        dimensions: 1024
        index-type: hnsw
        distance-type: cosine_distance
        table-name: vector_store_test
  datasource:
    url: jdbc:postgresql://localhost:5432/finagent
    username: finagent
    password: finagent
```

- [ ] **Step 4: 确认能打包**

Run:
```bash
mvn -q -B -DskipTests package
```

Expected: 退出码 0，生成 `target/financial-agent-0.1.0-SNAPSHOT.jar`。

- [ ] **Step 5: 提交**

```bash
git add src/main/java src/main/resources src/test/resources
git commit -m "feat: Spring Boot 骨架与百炼/PgVector 配置"
```

---

## Task 8: 向量读写集成测试（不需要 API Key）

**前置条件**：`docker compose up -d` 且 `bash scripts/verify-db.sh` 通过。

**Files:**
- Create: `src/test/java/com/finagent/VectorStoreSmokeTest.java`

- [ ] **Step 1: 写入测试类**

三个测试：前两个不打网络，第三个在检测到 Key 时才运行。

```java
package com.finagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
// 每个测试方法在事务中执行并自动回滚，保证测试可重复运行且不污染开发库
@Transactional
class VectorStoreSmokeTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private VectorStore vectorStore;

    @Test
    void springAiBeansAreWired() {
        assertThat(embeddingModel).isNotNull();
        assertThat(vectorStore).isNotNull();
    }

    @Test
    void pgvectorRoundTripWorksWith1024Dimensions() {
        jdbcTemplate.update(
                "INSERT INTO company(code, name) VALUES ('600519','贵州茅台') ON CONFLICT (code) DO NOTHING");
        Long companyId = jdbcTemplate.queryForObject(
                "SELECT id FROM company WHERE code='600519' ORDER BY id DESC LIMIT 1", Long.class);

        jdbcTemplate.update(
                "INSERT INTO document(company_id, doc_type, doc_year, title, object_key, content_hash) "
                        + "VALUES (?, 'ANNUAL_REPORT', 2025, '冒烟测试', 'test/smoke.pdf', 'hash-smoke')",
                companyId);
        Long docId = jdbcTemplate.queryForObject(
                "SELECT id FROM document WHERE content_hash='hash-smoke' ORDER BY id DESC LIMIT 1", Long.class);

        jdbcTemplate.update(
                "INSERT INTO document_chunk(document_id, chunk_level, chunk_type, content, page_start, embedding) "
                        + "VALUES (?, 'CHILD', 'TEXT', '2025 年营业收入', 128, ?::vector)",
                docId, vectorLiteral(1024, 0.01f));

        Double distance = jdbcTemplate.queryForObject(
                "SELECT embedding <=> ?::vector FROM document_chunk WHERE document_id = ? ORDER BY id DESC LIMIT 1",
                Double.class, vectorLiteral(1024, 0.02f), docId);

        assertThat(distance).isNotNull();
        assertThat(distance).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SPRING_AI_OPENAI_API_KEY", matches = ".+")
    void dashscopeEmbeddingReturns1024Dimensions() {
        float[] vector = embeddingModel.embed("2025 年营业收入同比增长");
        assertThat(vector).hasSize(1024);
    }

    private static String vectorLiteral(int dimensions, float value) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(value);
        }
        return sb.append(']').toString();
    }
}
```

- [ ] **Step 2: 运行测试**

Run:
```bash
mvn -B test
```

Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 1`（第三个测试因未设 Key 被跳过）。若全部通过，说明 Spring AI 与 pgvector 的接线正确。

- [ ] **Step 3: 有 Key 时再跑一次真实调用**

Run:
```bash
export SPRING_AI_OPENAI_API_KEY=sk-你的百炼Key
mvn -B test -Dtest=VectorStoreSmokeTest
```

Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`。这一步证明百炼兼容接口确实返回 1024 维向量——**这是本次要验证的核心事实**。

- [ ] **Step 4: 提交**

```bash
git add src/test/java
git commit -m "test: 向量读写集成测试（含需 Key 的真实 embedding 用例）"
```

---

## Task 9: 收尾验证与 README 补全

- [ ] **Step 1: 从零重建环境，确认可复现**

```bash
docker compose down -v
docker compose up -d
sleep 10
bash scripts/verify-db.sh
mvn -B test
```

Expected: `verify-db.sh` 全部 PASS，`mvn test` 无失败。**这一步是本阶段唯一的正式验收动作**——它证明整套环境可以在一台干净机器上重建。

- [ ] **Step 2: 补全 README 的"已知限制"一节**

在 `README.md` 末尾追加：

```markdown
## 已知限制（阶段 1）

- 本阶段尚未接入 Docling 解析服务与百炼 API，问答链路不可用（见阶段 2~5 计划）。
- `mvn test` 需要先 `docker compose up -d`。
- 中文分词依赖 zhparser 的默认词典；金融专有名词（如"扣非净利润"）的切分效果需在阶段 4 用真实语料验证。
- 设计文档 v0.10 中 §4.1 容量/SLO 与 §21.3 容灾 RPO/RTO 属生产运维目标，开发阶段不以此作为验收条件；功能范围仍按设计文档完整建设。
```

- [ ] **Step 3: 最终提交**

```bash
git add README.md
git commit -m "docs: 补全运行说明与阶段 1 已知限制"
git log --oneline
```

Expected: 看到 9 个提交（Task 1~9 各一个），均为本项目的原子提交。

---

## 验收标准

阶段 1 是否完成，**只看第一张表**——它全部不依赖 API Key：

| 验收项 | 验证命令 | 期望 |
|---|---|---|
| 环境可一键重建 | `docker compose down -v && docker compose up -d` | 容器 healthy |
| pgvector 可用 | `bash scripts/verify-db.sh` | 全部 PASS，退出码 0 |
| 中文分词可用 | 同上（第 4 条断言） | PASS |
| 4 张业务表存在 | `docker compose exec -T postgres psql -U finagent -d finagent -c "\\dt"` | 列出 4 张表 |
| 项目可编译 | `mvn -q -B -DskipTests compile` | 退出码 0 |
| Spring AI 接线正确 | `mvn -B test` | 0 失败（1 skipped） |

**可选加分项（需要百炼 API Key，不设 Key 时跳过）：**

| 验收项 | 验证命令 | 期望 |
|---|---|---|
| 百炼真实返回 1024 维 | 设 `SPRING_AI_OPENAI_API_KEY` 后 `mvn -B test` | 0 失败，0 skipped |

这一项之所以单列：它是**对「百炼 text-embedding-v4 支持 OpenAI 兼容接口且 dimensions 参数生效」这一关键假设的实证**，但即使暂时没有 Key，阶段 1 的其余部分依然完整可交付。

## 本阶段的边界（这些后续都会做，不是砍需求）

阶段 1 只铺地基。下面这些是**后续阶段的正式交付内容**，此处只是不提前做：

| 暂不做 | 计划在 |
|---|---|
| Kafka / Worker / DLQ | 阶段 7（异步与任务化） |
| 多 Maven 模块拆分 | 阶段 4（financial-rag 拆成独立模块） |
| 认证、多租户、限流 | 阶段 8（权限与多租户） |
| REST Controller | 阶段 5（问答与引用） |
| Redis 缓存 | 阶段 3 起按需引入 |
| 四层 Memory | 阶段 9 |
| LangGraph4j Agent 与 HITL | 阶段 10 |
| 完整评测集（≥300 题） | 阶段 6 起，随功能扩展 |

**换句话说：阶段 1 的"不做"是排期问题，不是范围裁剪。**

## 偏差登记

实施中若发现本计划的假设不成立，登记到路线图的偏差表，**不要静默绕过**：

| 位置 | 本计划的假设 | 若实际情况不同该怎么办 |
|---|---|---|
| Task 2 | SCWS 能从 GitHub 源码编译 | 换备用源或社区镜像，见 Task 2 的失败说明 |
| Task 6 | Spring Boot 4.0.8 与 Spring AI 2.0.1 兼容 | 若装配失败，回退到 Spring AI 官方文档注明的对应 Boot 版本 |
| Task 7 | `spring.ai.openai.embedding.options.dimensions` 生效 | 若百炼忽略该参数返回 2048 维，改用 `halfvec(2048)`，并 **回写设计文档 §14.1**（这是 P0 级决策变更） |
| Task 8 | PgVectorStore 自建表可用 | 若建表失败，把 `initialize-schema` 改回 false，改用 JDBC 直连验证 |

## 交给后续阶段的未决问题

1. **PgVectorStore 与业务表的关系未定**：设计文档 §8.8 要求 embedding 存在 `document_chunk.embedding`，而 PgVectorStore 使用自己的 `vector_store` 表。阶段 3 必须二选一（用 PgVectorStore 的默认表并改设计文档，或用 JDBC 自己管表并放弃 PgVectorStore）——**这是本 Demo 最值得在面试里讲的架构取舍之一**。
2. **zhparser 词典专业化**：阶段 4 需用真实语料评估是否需要加载金融自定义词典。
3. **Sparse 路与 Dense 路的分工**：zhparser 的 `chinese_zh` 配置映射了 n/v/a/i/e/l/j 七类词性，实际召回质量待阶段 4 用评测集验证。
