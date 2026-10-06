ARG BASE_IMAGE=alpine@sha256:4b7ce07002c69e8f3d704a9c5d6fd3053be500b7f1c69fc0d80990c2ad8dd412
FROM ${BASE_IMAGE}

ENV LC_ALL=C PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /opt/web-judge

COPY packages/ /tmp/supervisor-packages/
RUN cd /tmp/supervisor-packages \
    && sha256sum ./*.apk > /tmp/supervisor-packages.actual \
    && cmp SHA256SUMS /tmp/supervisor-packages.actual \
    && mkdir -p /var/cache/apk \
    && apk --repositories-file /dev/null --no-network --cache-dir /var/cache/apk add ./*.apk \
    && apk info -e python3 docker-cli \
    && python3 --version \
    && docker --version \
    && ! command -v dockerd \
    && ! command -v gcc \
    && ! command -v pip \
    && ! command -v pip3 \
    && rm -f /var/cache/apk/*.apk \
    && rm -rf /tmp/supervisor-packages /tmp/supervisor-packages.actual

COPY sources/ /opt/web-judge/
RUN cd /opt/web-judge \
    && sha256sum ./*.py > /tmp/supervisor-sources.actual \
    && cmp SHA256SUMS /tmp/supervisor-sources.actual \
    && chmod 0555 /opt/web-judge \
    && chmod 0444 /opt/web-judge/*.py /opt/web-judge/SHA256SUMS \
    && rm /tmp/supervisor-sources.actual

USER root
ENTRYPOINT ["python3", "-B", "/opt/web-judge/linux_supervisor.py"]
