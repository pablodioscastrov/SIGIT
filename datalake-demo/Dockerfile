FROM python:3.12-slim
RUN pip install --no-cache-dir "azure-storage-blob==12.23.0"
WORKDIR /data
ENV PYTHONUNBUFFERED=1
COPY sync.py /app/sync.py
CMD ["python3", "/app/sync.py"]
