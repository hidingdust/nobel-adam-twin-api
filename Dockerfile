FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 由部署脚本把 target/*.jar 复制成 app.jar 后一起上传
COPY app.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-Xms96m -Xmx224m -XX:MaxMetaspaceSize=96m -XX:+UseSerialGC"
ENV TZ=Asia/Shanghai

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
