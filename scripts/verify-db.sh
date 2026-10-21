#!/usr/bin/env bash
# ============================================================
# 断言 pgvector 与 zhparser 真的可用。任一检查失败即以非零码退出。
# 运行：bash scripts/verify-db.sh
# 前置：docker compose up -d 且容器 healthy
# ============================================================
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

check "中文分词配置 chinese_zh 存在" \
      "1" \
      "SELECT count(*) FROM pg_ts_config WHERE cfgname='chinese_zh';"

# 核心断言：zhparser 能把无空格的中文切成多个词，simple 只能切成 1 个
check "中文分词真的切开了词（zhparser 优于 simple）" \
      "t" \
      "SELECT (array_length(string_to_array(to_tsvector('chinese_zh','贵州茅台2025年营业收入同比增长')::text,' '),1)
             > array_length(string_to_array(to_tsvector('simple','贵州茅台2025年营业收入同比增长')::text,' '),1));"

# ---------- 自建种子数据：脚本必须能在空库上独立跑通 ----------
# ON CONFLICT ... DO UPDATE ... RETURNING 保证重复执行既幂等又能取到 id
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

# 清理本次写入的验证数据（种子行保留，供后续阶段使用）
$PSQL -c "DELETE FROM document_chunk WHERE content='verify';" >/dev/null 2>&1

if [[ $FAILED -eq 0 ]]; then
  echo "---- 全部通过 ----"
else
  echo "---- 存在失败项，见上方 FAIL ----"
fi
exit $FAILED
