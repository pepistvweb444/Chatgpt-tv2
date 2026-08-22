FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends git ffmpeg build-essential && rm -rf /var/lib/apt/lists/*
WORKDIR /opt
RUN git clone --depth 1 https://github.com/myshell-ai/OpenVoice.git
WORKDIR /opt/OpenVoice
RUN pip install --no-cache-dir -r requirements.txt && pip install --no-cache-dir -e .
COPY openvoice-service/requirements.txt /tmp/jarvis-requirements.txt
RUN pip install --no-cache-dir -r /tmp/jarvis-requirements.txt && python -m unidic download || true
COPY openvoice-service/app.py /app/app.py
ENV PYTHONPATH=/opt/OpenVoice
ENV OPENVOICE_CHECKPOINTS=/opt/OpenVoice/checkpoints_v2
ENV OPENVOICE_DATA=/data
VOLUME ["/data"]
EXPOSE 8000
CMD ["uvicorn", "app:app", "--app-dir", "/app", "--host", "0.0.0.0", "--port", "8000"]
