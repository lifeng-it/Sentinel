package com.alibaba.csp.sentinel.dashboard.repository.metric;

import com.alibaba.csp.sentinel.dashboard.controller.MetricController;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.MetricEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.influxdb.MetricPO;
import com.alibaba.csp.sentinel.dashboard.util.InfluxDBUtils;
import com.alibaba.csp.sentinel.util.StringUtil;
import org.apache.commons.lang.time.DateFormatUtils;
import org.apache.commons.lang.time.DateUtils;
import org.influxdb.InfluxDB;
import org.influxdb.dto.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Created by lifeng on 2019/6/17.
 */
@Repository("influxDBMetricsRepository")
public class InfluxDBMetricsRepository implements MetricsRepository<MetricEntity>{
    private static Logger logger = LoggerFactory.getLogger(MetricController.class);
    /**时间格式*/
    private static final String DATE_FORMAT_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";

    /**数据库名称*/
    private static final String SENTINEL_DATABASE = "sentinel_db";

    /**数据表名称*/
    private static final String METRIC_MEASUREMENT = "sentinel_metric";

    /**北京时间领先UTC时间8小时 UTC: Universal Time Coordinated,世界统一时间*/
    private static final Integer UTC_8 = 8;

    @Override
    public void save(MetricEntity metric) {
        if (metric == null || StringUtil.isBlank(metric.getApp())) {
            return;
        }

        logger.info("保存influxdb 数据");
        InfluxDBUtils.insert(SENTINEL_DATABASE, new InfluxDBUtils.InfluxDBInsertCallback() {
            @Override
            public void doCallBack(String database, InfluxDB influxDB) {
                if (metric.getId() == null) {
                    metric.setId(System.currentTimeMillis());
                }
                doSave(influxDB, metric);
            }
        });
    }

    @Override
    public void saveAll(Iterable<MetricEntity> metrics) {
        if (metrics == null) {
            return;
        }

        Iterator<MetricEntity> iterator = metrics.iterator();
        boolean next = iterator.hasNext();
        if (!next) {
            return;
        }

        InfluxDBUtils.insert(SENTINEL_DATABASE, new InfluxDBUtils.InfluxDBInsertCallback() {
            @Override
            public void doCallBack(String database, InfluxDB influxDB) {
                while (iterator.hasNext()) {
                    MetricEntity metric = iterator.next();
                    if (metric.getId() == null) {
                        metric.setId(System.currentTimeMillis());
                    }
                    doSave(influxDB, metric);
                }
            }
        });
    }

    @Override
    public List<MetricEntity> queryByAppAndResourceBetween(String app, String resource, long startTime, long endTime) {
        List<MetricEntity> results = new ArrayList<MetricEntity>();
        if (StringUtil.isBlank(app)) {
            return results;
        }

        if (StringUtil.isBlank(resource)) {
            return results;
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM " + METRIC_MEASUREMENT);
        sql.append(" WHERE app=$app");
        sql.append(" AND resource=$resource");
        sql.append(" AND time>=$startTime");
        sql.append(" AND time<=$endTime");

        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put("app", app);
        paramMap.put("resource", resource);
        //日期类型有问题
        //paramMap.put("startTime", DateFormatUtils.format(new Date(startTime), DATE_FORMAT_PATTERN));
        //paramMap.put("endTime", DateFormatUtils.format(new Date(endTime), DATE_FORMAT_PATTERN));
        paramMap.put("startTime", startTime*1000000L);
        paramMap.put("endTime", endTime*1000000L);
        List<MetricPO> metricPOS = InfluxDBUtils.queryList(SENTINEL_DATABASE, sql.toString(), paramMap, MetricPO.class);

        if (CollectionUtils.isEmpty(metricPOS)) {
            return results;
        }

        for (MetricPO metricPO : metricPOS) {
            results.add(convertToMetricEntity(metricPO));
        }

        return results;
    }

    @Override
    public List<String> listResourcesOfApp(String app) {
        List<String> results = new ArrayList<>();
        if (StringUtil.isBlank(app)) {
            return results;
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM " + METRIC_MEASUREMENT);
        sql.append(" WHERE app=$app");
        sql.append(" AND time>=$startTime");

        Map<String, Object> paramMap = new HashMap<String, Object>();
        long startTime = System.currentTimeMillis() - 1000 * 60 * 5;
        paramMap.put("app", app);
        paramMap.put("startTime",startTime*1000000);//纳秒
        //paramMap.put("startTime", DateFormatUtils.format(new Date(startTime), DATE_FORMAT_PATTERN));//发现日期类型查询有问题

        List<MetricPO> metricPOS = InfluxDBUtils.queryList(SENTINEL_DATABASE, sql.toString(), paramMap, MetricPO.class);

        if (CollectionUtils.isEmpty(metricPOS)) {
            return results;
        }

        List<MetricEntity> metricEntities = new ArrayList<MetricEntity>();
        for (MetricPO metricPO : metricPOS) {
            metricEntities.add(convertToMetricEntity(metricPO));
        }

        Map<String, MetricEntity> resourceCount = new HashMap<>(32);

        for (MetricEntity metricEntity : metricEntities) {
            String resource = metricEntity.getResource();
            if (resourceCount.containsKey(resource)) {
                MetricEntity oldEntity = resourceCount.get(resource);
                oldEntity.addPassQps(metricEntity.getPassQps());
                oldEntity.addRtAndSuccessQps(metricEntity.getRt(), metricEntity.getSuccessQps());
                oldEntity.addBlockQps(metricEntity.getBlockQps());
                oldEntity.addExceptionQps(metricEntity.getExceptionQps());
                oldEntity.addCount(1);
            } else {
                resourceCount.put(resource, MetricEntity.copyOf(metricEntity));
            }
        }

        // Order by last minute b_qps DESC.
        return resourceCount.entrySet()
                .stream()
                .sorted((o1, o2) -> {
                    MetricEntity e1 = o1.getValue();
                    MetricEntity e2 = o2.getValue();
                    int t = e2.getBlockQps().compareTo(e1.getBlockQps());
                    if (t != 0) {
                        return t;
                    }
                    return e2.getPassQps().compareTo(e1.getPassQps());
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private MetricEntity convertToMetricEntity(MetricPO metricPO) {
        MetricEntity metricEntity = new MetricEntity();

        metricEntity.setId(metricPO.getId());
        metricEntity.setGmtCreate(new Date(metricPO.getGmtCreate()));
        metricEntity.setGmtModified(new Date(metricPO.getGmtModified()));
        metricEntity.setApp(metricPO.getApp());
        metricEntity.setTimestamp(Date.from(metricPO.getTime().minusMillis(TimeUnit.HOURS.toMillis(UTC_8))));// 查询数据减8小时
        metricEntity.setResource(metricPO.getResource());
        metricEntity.setPassQps(metricPO.getPassQps());
        metricEntity.setSuccessQps(metricPO.getSuccessQps());
        metricEntity.setBlockQps(metricPO.getBlockQps());
        metricEntity.setExceptionQps(metricPO.getExceptionQps());
        metricEntity.setRt(metricPO.getRt());
        metricEntity.setCount(metricPO.getCount());

        return metricEntity;
    }

    private void doSave(InfluxDB influxDB, MetricEntity metric) {
        logger.info("保存influxDB.write");
        influxDB.write(Point.measurement(METRIC_MEASUREMENT)
                .time(DateUtils.addHours(metric.getTimestamp(), 0).getTime(), TimeUnit.MILLISECONDS)// 因InfluxDB默认UTC时间，按北京时间算写入数据加8小时
                .tag("app", metric.getApp())
                .tag("resource", metric.getResource())
                .addField("id", metric.getId())
                .addField("gmtCreate", metric.getGmtCreate().getTime())
                .addField("gmtModified", metric.getGmtModified().getTime())
                .addField("passQps", metric.getPassQps())
                .addField("successQps", metric.getSuccessQps())
                .addField("blockQps", metric.getBlockQps())
                .addField("exceptionQps", metric.getExceptionQps())
                .addField("rt", metric.getRt())
                .addField("count", metric.getCount())
                .addField("resourceCode", metric.getResourceCode())
                .build());
    }
}
