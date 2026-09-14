"""统计以实际受理记录为准，校验自然日边界、去重、缺失样本与身份隔离。"""
from datetime import datetime

import pytest

from test_agent import env, session, question

BASE = '/api/v1/admin/ai/statistics'
ADMIN = {'Authorization':'Bearer admin'}


async def test_real_generation_timing_feedback_and_replay(env):
    client, agent, store, *_ = env
    path = await session(client)
    headers, body = {'X-Guest-Token':'guest1'}, question()
    agent.delay = .02
    await client.post(path+'/messages', json=body, headers=headers)
    await client.post(path+'/messages', json=body, headers=headers)
    mid = store.messages(path.rsplit('/',1)[-1])[-1]['id']
    await client.put(path+f'/messages/{mid}/feedback', json={'value':'down'}, headers=headers)
    stats = (await client.get(BASE, headers=ADMIN)).json()['data']['totals']
    assert stats['questionCount'] == stats['sessionCount'] == stats['completedCount'] == 1
    assert stats['negativeCount'] == 1 and stats['failedCount'] == 0
    assert stats['firstTokenSamples'] == stats['generationSamples'] == 1
    assert 10 <= stats['avgFirstTokenMs'] <= stats['avgGenerationMs']
    agent.failure = True
    await client.post(path+'/messages', json=question(), headers=headers)
    stats = (await client.get(BASE, headers=ADMIN)).json()['data']['totals']
    assert stats['questionCount'] == 2 and stats['failedCount'] == 1


async def test_timezone_boundaries_range_dedup_empty_days_and_missing_timings(env):
    client, _, store, *_ = env
    sid = store.create('guest:history', None)['id']
    # 同一会话跨两天，起点前一秒及终点时刻都不应进入所选日期。
    for stamp in ['2025-01-01T15:59:59+00:00','2025-01-01T16:00:00+00:00',
                  '2025-01-02T16:00:00+00:00','2025-01-04T16:00:00+00:00']:
        _, message = store.begin(sid,'guest:history', question())
        message.update(status='completed')
        store.save(message)
        with store.db() as db:
            db.execute('UPDATE messages SET created=? WHERE id=?',(datetime.fromisoformat(stamp).timestamp(), message['id']))
    response = await client.get(BASE,headers=ADMIN,params={'startDate':'2025-01-02','endDate':'2025-01-04'})
    data = response.json()['data']
    assert data['totals']['questionCount'] == 2 and data['totals']['sessionCount'] == 1
    assert [row['sessionCount'] for row in data['daily']] == [1,1,0]
    assert data['totals']['avgFirstTokenMs'] is None and data['totals']['firstTokenSamples'] == 0
    assert 'history' not in response.text and '预约确认' not in response.text


@pytest.mark.parametrize('params',[{'startDate':'2025-02-01','endDate':'2025-01-01'},
    {'startDate':'2025-01-01','endDate':'2025-12-31'}, {'startDate':'not-a-date'}, {'endDate':'2099-01-01'}])
async def test_invalid_ranges_rejected(env, params):
    client, *_ = env
    assert (await client.get(BASE,headers=ADMIN,params=params)).status_code == 400


@pytest.mark.parametrize('headers,status',[({},401),({'Authorization':'Bearer buyer'},403),
    ({'X-Guest-Token':'guest'},403),({'Authorization':'Bearer revoked'},401)])
async def test_statistics_admin_only(env, headers, status):
    client, *_ = env
    assert (await client.get(BASE,headers=headers)).status_code == status
