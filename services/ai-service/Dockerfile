FROM python:3.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
# Event schemas shared with the catalog service (docker-compose passes the repo's schemas/ folder).
COPY --from=schemas . ./schemas
ENV SCHEMAS_DIR=/app/schemas
RUN useradd --system --uid 1001 skinvidhi
USER skinvidhi
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
