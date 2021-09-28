FROM openjdk:11-jdk-slim as builder
ENV DEBIAN_FRONTEND noninteractive
RUN apt-get update && \
    apt-get install -y --no-install-recommends apt-transport-https apt-utils bc dirmngr gnupg curl
RUN echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list  && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get upgrade -y && \
    apt-get install -y --no-install-recommends sbt
COPY ["build.sbt", "/assembler/"]
COPY ["project", "/assembler/project"]
COPY . /assembler
WORKDIR /assembler
RUN export SBT_OPTS="-Xms1024M -Xmx4G -Xss2M -XX:MaxMetaspaceSize=2G" && \
    sbt -Dsbt.rootdir=true ";update;compile;assembly"
RUN mkdir ./AmmExecutor && \
    mv `find . -name AmmExecutor-assembly-*.jar` ./AmmExecutor/AmmExecutor.jar && \
    mv ./modules/amm-executor/src/main/resources/application.conf ./AmmExecutor
RUN mkdir ./PoolResolver && \
    mv `find . -name PoolResolver-assembly-*.jar` ./PoolResolver/PoolResolver.jar && \
    mv ./modules/pool-resolver/src/main/resources/application.conf ./PoolResolver
RUN mkdir ./UtxoTracker && \
    mv `find . -name UtxoTracker-assembly-*.jar` ./UtxoTracker/UtxoTracker.jar && \
    mv ./modules/utxo-tracker/src/main/resources/application.conf ./UtxoTracker


FROM openjdk:11-jre-slim
VOLUME /app-data
RUN mkdir /ergodex
COPY --from=builder /assembler/AmmExecutor /ergodex/AmmExecutor
COPY --from=builder /assembler/PoolResolver /ergodex/PoolResolver
COPY --from=builder /assembler/UtxoTracker /ergodex/UtxoTracker
WORKDIR /ergodex


