# HIỆN KHÔNG DÙNG. Từ 14/09 BE chạy jar qua systemd trên VPS Vietnix, không qua Docker.
# Giữ lại phòng khi cần đóng ảnh; đừng đọc tệp này để đoán BE đang chạy ở đâu.
# Hai tầng để ảnh cuối chỉ có JRE + jar, không kéo theo Maven và ~/.m2.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# Copy pom TRƯỚC src: sửa code mà không đổi dependency thì tầng tải thư viện
# vẫn còn trong cache, build lại nhanh hơn nhiều.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Máy free thường chỉ có 512 MB. Mặc định JVM lấy 25% RAM là quá ít và sẽ GC liên tục;
# SerialGC hợp với máy 1 nhân hơn G1 vì không tốn thread nền.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k"

# Cổng thật do biến PORT quyết định (xem application.properties). 8080 chỉ là mặc định.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
