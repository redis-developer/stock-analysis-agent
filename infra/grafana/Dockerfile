FROM grafana/grafana-oss:13.0.2

USER root

RUN grafana cli --pluginsDir /var/lib/grafana/plugins plugins install redis-datasource

COPY provisioning /etc/grafana/provisioning
COPY dashboards /opt/stock-analysis-grafana/dashboards
COPY entrypoint.sh /opt/stock-analysis-grafana/entrypoint.sh

RUN chmod +x /opt/stock-analysis-grafana/entrypoint.sh \
    && chown -R 472:0 /etc/grafana/provisioning /opt/stock-analysis-grafana

USER 472

ENV STOCK_ANALYSIS_AGENT_APP_BASE_URL=http://localhost:8080

ENTRYPOINT ["/opt/stock-analysis-grafana/entrypoint.sh"]
