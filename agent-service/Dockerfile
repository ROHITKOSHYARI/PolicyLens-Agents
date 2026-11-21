# syntax=docker/dockerfile:1.7
FROM python:3.12-slim AS build
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /build
COPY requirements.txt .
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --prefix=/install -r requirements.txt
ARG EMBEDDING_MODEL=sentence-transformers/all-MiniLM-L6-v2
ENV PYTHONPATH=/install/lib/python3.12/site-packages HF_HOME=/model-cache
RUN --mount=type=cache,target=/root/.cache/huggingface \
    HF_HOME=/root/.cache/huggingface python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('${EMBEDDING_MODEL}', cache_folder='/root/.cache/huggingface')" \
    && mkdir -p /model-cache && cp -a /root/.cache/huggingface/. /model-cache/

FROM python:3.12-slim AS runtime
ARG EMBEDDING_MODEL=sentence-transformers/all-MiniLM-L6-v2
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    HF_HOME=/opt/model-cache TRANSFORMERS_OFFLINE=1 HF_HUB_OFFLINE=1 \
    EMBEDDING_MODEL=${EMBEDDING_MODEL} \
    CHROMA_PATH=/data/chroma CHUNK_DB_PATH=/data/chunks.sqlite3
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app /data /opt/model-cache && chown -R app:app /app /data /opt/model-cache
WORKDIR /app
COPY --from=build /install /usr/local
COPY --from=build --chown=app:app /model-cache /opt/model-cache
COPY --chown=app:app app ./app
USER app
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
