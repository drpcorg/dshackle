FROM eclipse-temurin:25

RUN apt-get update -y && apt-get install -y libcurl4-openssl-dev libcjson-dev
