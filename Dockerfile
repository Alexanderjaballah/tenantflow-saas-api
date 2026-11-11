FROM eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918
WORKDIR /app
COPY build/libs/tenantflow-saas-api-1.0.0.jar app.jar
EXPOSE 8080
USER 1001
ENTRYPOINT ["java", "-jar", "app.jar"]
