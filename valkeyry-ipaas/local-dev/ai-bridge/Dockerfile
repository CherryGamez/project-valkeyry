FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
# Emergent integrations require a custom index
RUN pip install --no-cache-dir -r requirements.txt \
      --extra-index-url https://d33sy5i8bnduwe.cloudfront.net/simple/

COPY main.py .

EXPOSE 8090
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8090"]
