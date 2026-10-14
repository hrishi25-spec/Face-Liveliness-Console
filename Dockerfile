FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache libstdc++

# Low-end defaults: cap the heap at half the container's RAM (the rest goes to
# native memory — OpenCV/ONNX matrices are off-heap), use SerialGC for the
# smallest native footprint, and exit on OOM instead of limping along.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50.0 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

WORKDIR /app

# Run as an unprivileged user — the JVM extracts OpenCV natives and Haar
# cascades into /tmp, which stays writable for everyone.
#
# The user is created BEFORE the copy so the jar can arrive already owned by it.
# A `chown -R` afterwards would rewrite every file into a new layer, storing the
# ~94 MB jar a second time (docker history showed 93.5 MB + 93.6 MB for what is
# one file), and `COPY --chown` costs nothing.
RUN addgroup -S mosip && adduser -S -G mosip mosip

# Built from the slim profile — build the jar first:
#   ./mvnw clean package -DskipTests -Pslim
#
# This image runs on exactly one platform, and it is Alpine: the OpenCV native
# is glibc-linked and cannot load there either way, so the other seven
# platforms' natives (~100 MB of the ~200 MB jar) are dead weight. The slim
# profile keeps the java bindings and the linux-x86_64 native, which is what a
# glibc Linux container actually loads.
#
# The -slim classifier is pinned so the COPY is unambiguous — a slim build also
# leaves the thin jar beside the repackaged one, and a bare `*.jar` glob would
# match both.
COPY --chown=mosip:mosip target/pad-liveness-backend-*-slim.jar app.jar

USER mosip

EXPOSE 8000

CMD ["java", "-jar", "app.jar"]