# ============================================================================
# yang-ai-agent 后端 Dockerfile
# Spring Boot 4 (Java 17) 应用，端口 8123，context-path=/api
#
# 构建：docker build -t yang-ai-agent .
# 运行：docker run -d -p 8123:8123 -v chat_memories:/app/chat_memories yang-ai-agent
# 推荐直接用 docker-compose 一键启动前后端，见 docker-compose.yml
# ============================================================================

# ---- 阶段一：构建（Maven + JDK 17）----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷贝 pom.xml，预下载依赖以利用构建缓存
COPY pom.xml .
RUN mvn -B dependency:go-offline -DskipTests || true

# 拷贝源码并打包（跳过全部测试，含测试编译）
COPY src ./src
RUN mvn -B clean package -Dmaven.test.skip=true

# ---- 阶段二：运行（JRE 17，更小的镜像）----
FROM eclipse-temurin:17-jre
WORKDIR /app

# 时区与 JVM 参数
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-Xms512m -Xmx1g"

# 安装 curl（供健康检查使用）并清理 apt 缓存
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 应用运行期需要写入的目录（聊天记忆 / PDF / 下载），挂载为数据卷
RUN mkdir -p /app/chat_memories /app/pdfs /app/downloads

# 复制可执行 fat jar（application-local.yml 已随资源打进 jar，含默认配置）
COPY --from=build /build/target/yang-ai-agent-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8123

# 使用 sh -c 以支持 $JAVA_OPTS 展开
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
