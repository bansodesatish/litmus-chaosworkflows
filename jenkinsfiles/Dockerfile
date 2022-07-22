FROM jenkinsxio/builder-base:0.1.275
ARG VERSION=v4.2.0 
ARG BINARY=yq_linux_amd64
RUN wget https://litmusctl-production-bucket.s3.amazonaws.com/litmusctl-linux-amd64-v0.7.0.tar.gz -O litmusctl.tar.gz&&\
    tar -zxvf litmusctl.tar.gz && rm litmusctl.tar.gz &&\
    chmod +x litmusctl &&\
    mv litmusctl /usr/local/bin/litmusctl &&\
    wget https://github.com/mikefarah/yq/releases/download/${VERSION}/${BINARY} -O /usr/bin/yq &&\
    chmod +x /usr/bin/yq
