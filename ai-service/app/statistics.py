"""按上海自然日汇总持久消息；只返回聚合值，不暴露会话正文或身份。"""
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from fastapi import Depends, Query, Request

from .business import ApiError
from .store import now


def statistics(store, start, end):
    zone = ZoneInfo('Asia/Shanghai')
    lower = datetime.combine(start, time.min, zone).timestamp()
    upper = datetime.combine(end + timedelta(days=1), time.min, zone).timestamp()
    with store.db() as db:
        # 一条助手记录对应一次被受理的提问；幂等重放不会新增记录。
        rows = db.execute('''
            SELECT date(created, 'unixepoch', '+8 hours') day,
                   COUNT(*) questions, COUNT(DISTINCT session_id) sessions,
                   SUM(json_extract(data,'$.status')='completed') completed,
                   SUM(json_extract(data,'$.status')='failed') failed,
                   SUM(json_extract(data,'$.status')='streaming') streaming,
                   SUM(json_extract(data,'$.feedback')='up') positive,
                   SUM(json_extract(data,'$.feedback')='down') negative,
                   SUM(json_extract(data,'$.firstTokenMs')) first_total,
                   COUNT(json_extract(data,'$.firstTokenMs')) first_samples,
                   SUM(json_extract(data,'$.generationMs')) duration_total,
                   COUNT(json_extract(data,'$.generationMs')) duration_samples
            FROM messages WHERE role='assistant' AND created>=? AND created<?
            GROUP BY day ORDER BY day
        ''', (lower, upper)).fetchall()
        unique = db.execute("SELECT COUNT(DISTINCT session_id) FROM messages WHERE role='assistant' AND created>=? AND created<?",
                            (lower, upper)).fetchone()[0]
        knowledge = dict(db.execute("SELECT json_extract(data,'$.status'), COUNT(*) FROM knowledge WHERE json_extract(data,'$.status')!='deleted' GROUP BY 1").fetchall())
        indices = dict(db.execute("SELECT json_extract(data,'$.indexStatus'), COUNT(*) FROM knowledge WHERE json_extract(data,'$.status')='published' GROUP BY 1").fetchall())
    fields = ('questions', 'sessions', 'completed', 'failed', 'streaming', 'positive', 'negative',
              'first_total', 'first_samples', 'duration_total', 'duration_samples')
    totals = {key: sum(row[key] or 0 for row in rows) for key in fields}
    totals['sessions'] = unique

    def project(row):
        return dict(questionCount=row['questions'], sessionCount=row['sessions'], completedCount=row['completed'],
                    failedCount=row['failed'], streamingCount=row['streaming'], positiveCount=row['positive'],
                    negativeCount=row['negative'], firstTokenSamples=row['first_samples'], generationSamples=row['duration_samples'],
                    avgFirstTokenMs=round(row['first_total']/row['first_samples']) if row['first_samples'] else None,
                    avgGenerationMs=round(row['duration_total']/row['duration_samples']) if row['duration_samples'] else None)
    by_day = {row['day']: row for row in rows}
    daily = []
    for offset in range((end-start).days+1):
        day = (start+timedelta(days=offset)).isoformat()
        row = by_day.get(day, dict.fromkeys(fields, 0))
        daily.append(dict(date=day, **project(row)))
    return dict(startDate=start.isoformat(), endDate=end.isoformat(), timezone='Asia/Shanghai',
                totals=project(totals), daily=daily, knowledge=dict(total=sum(knowledge.values()),
                draft=knowledge.get('draft', 0), published=knowledge.get('published', 0), archived=knowledge.get('archived', 0),
                ready=indices.get('ready', 0), pending=indices.get('pending', 0)+indices.get('indexing', 0), failed=indices.get('failed', 0)), updatedAt=now())


def register_statistics_api(app, business, store, envelope):
    async def administrator(request: Request):
        return await business.identify_admin(request.headers.get('Authorization'), request.headers.get('X-Guest-Token'))

    @app.get('/api/v1/admin/ai/statistics')
    async def report(startDate: date | None = Query(None), endDate: date | None = Query(None), actor=Depends(administrator)):
        today = datetime.now(ZoneInfo('Asia/Shanghai')).date()
        start, end = startDate or today, endDate or today
        if start > end or end > today or (end-start).days >= 93:
            raise ApiError(400, 'VALIDATION_ERROR', '请选择不超过今天、起止有序且最多 93 天的日期范围')
        return envelope(statistics(store, start, end))
