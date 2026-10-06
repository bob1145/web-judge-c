ARG BASE_IMAGE=alpine@sha256:4b7ce07002c69e8f3d704a9c5d6fd3053be500b7f1c69fc0d80990c2ad8dd412
FROM ${BASE_IMAGE}

ENV LC_ALL=C TMPDIR=/scratch
WORKDIR /scratch
CMD ["/bin/false"]

COPY packages/ /tmp/executor-packages/

RUN cd /tmp/executor-packages \
    && sha256sum ./*.apk > /tmp/executor-packages.actual \
    && cmp SHA256SUMS /tmp/executor-packages.actual \
    && mkdir -p /var/cache/apk \
    && apk --repositories-file /dev/null --no-network --cache-dir /var/cache/apk add ./*.apk \
    && apk info -e g++ \
    && g++ --version \
    && rm -f /var/cache/apk/*.apk \
    && mkdir -p /scratch \
    && chown 65532:65532 /scratch \
    && chmod 0700 /scratch \
    && rm -rf /tmp/executor-packages /tmp/executor-packages.actual

USER 65532:65532
