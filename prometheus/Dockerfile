FROM prom/prometheus:latest
COPY prometheus.yml /etc/prometheus/prometheus.yml
CMD ["--config.file=/etc/prometheus/prometheus.yml", \
     "--storage.tsdb.retention.time=30d", \
     "--web.enable-lifecycle", \
     "--web.listen-address=:9090"]
