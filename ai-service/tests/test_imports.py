"""覆盖模板、部分成功、结构预检、公式、幂等、重启与管理员权限。"""
import asyncio
import csv
import io
import json
from uuid import uuid4

import pytest
from openpyxl import load_workbook

from app.knowledge_import import HEADERS, EXAMPLES, Imports, MIMES
from app.knowledge_api import KnowledgeInput
from test_knowledge import env, create

BASE = '/api/v1/admin/knowledge'


def csv_file(rows):
    output = io.StringIO()
    csv.writer(output).writerows(rows)
    return output.getvalue().encode('utf-8-sig')


async def submit(client, payload, format='csv', key=None):
    return await client.post(BASE+'/import-jobs', params={'format':format}, content=payload,
                             headers={'Content-Type':MIMES[format], 'Idempotency-Key':key or str(uuid4())})


async def finished(client, response):
    assert response.status_code == 202, response.text
    location = response.headers['Location']
    for _ in range(100):
        job = (await client.get(location)).json()['data']
        if job['status'] not in ('queued', 'running'):
            return job
        await asyncio.sleep(.01)
    pytest.fail('导入任务未结束')


@pytest.mark.parametrize('format', ['csv', 'xlsx'])
async def test_templates_import_as_drafts_and_replay_without_duplicates(env, format):
    client, knowledge, settings = env
    template = await client.get(BASE+'/import-template', params={'format':format})
    assert template.status_code == 200 and 'attachment;' in template.headers['Content-Disposition']
    key = str(uuid4())
    first = await submit(client, template.content, format, key)
    job = await finished(client, first)
    assert (job['status'], job['successCount'], job['failedCount']) == ('succeeded', 2, 0)
    entries = knowledge.repository.list()
    assert len(entries) == 2 and all(e['status']=='draft' and e['reviewedBy'] is None for e in entries)
    replay = await submit(client, template.content, format, key)
    assert replay.json()['data']['id'] == job['id']
    assert len(knowledge.repository.list()) == 2 and knowledge.repository.ready() == []
    conflict = await submit(client, b'changed', format, key)
    assert conflict.status_code == 409
    restarted = Imports(knowledge.repository, KnowledgeInput)
    assert restarted.get(job['id'])['status'] == 'succeeded'


async def test_partial_import_errors_are_paginated_and_do_not_leak_content(env):
    client, knowledge, _ = env
    invalid = [*EXAMPLES[0]]
    invalid[9] = 'https://example.com/?api_key=do-not-expose'
    formula = [*EXAMPLES[0]]
    formula[0] = '=HYPERLINK("private")'
    job = await finished(client, await submit(client, csv_file([HEADERS, EXAMPLES[0], invalid, formula])))
    assert (job['status'], job['successCount'], job['failedCount']) == ('partial_succeeded', 1, 2)
    errors = (await client.get(BASE+f"/jobs/{job['id']}/errors", params={'page':2,'pageSize':1})).json()['data']
    assert errors['total'] == 2 and errors['items'][0]['rowNumber'] == 4
    all_errors = (await client.get(BASE+f"/jobs/{job['id']}/errors")).text
    assert 'do-not-expose' not in all_errors and 'HYPERLINK' not in all_errors
    assert len(knowledge.repository.list()) == 1


@pytest.mark.parametrize('rows,code', [
    ([HEADERS+['status'], EXAMPLES[0]+['published']], 'IMPORT_INVALID_HEADER'),
    ([HEADERS]+[EXAMPLES[0]]*5001, 'IMPORT_ROW_LIMIT_EXCEEDED'),
    ([HEADERS], 'IMPORT_FILE_INVALID'),
])
async def test_structural_failure_writes_no_drafts(env, rows, code):
    client, knowledge, _ = env
    job = await finished(client, await submit(client, csv_file(rows)))
    assert job['status'] == 'failed' and job['errorCode'] == code and job['successCount'] == 0
    assert knowledge.repository.list() == []


async def test_excel_formula_is_rejected_and_corrupt_file_is_not_imported(env):
    client, knowledge, _ = env
    response = await client.get(BASE+'/import-template?format=xlsx')
    book = load_workbook(io.BytesIO(response.content))
    book.active['F2'] = '=1+1'
    data = io.BytesIO()
    book.save(data)
    book.close()
    job = await finished(client, await submit(client, data.getvalue(), 'xlsx'))
    assert job['status'] == 'partial_succeeded' and job['failedCount'] == 1
    corrupt = await finished(client, await submit(client, b'not a zip', 'xlsx'))
    assert corrupt['errorCode'] == 'IMPORT_FILE_INVALID'
    assert len(knowledge.repository.list()) == 1


async def test_size_mime_key_and_permissions(env):
    client, _, _ = env
    assert (await submit(client, b'a'*(10*1024*1024+1))).status_code == 413
    assert (await client.post(BASE+'/import-jobs?format=csv', content='a', headers={'Idempotency-Key':str(uuid4()),'Content-Type':'text/html'})).status_code == 415
    assert (await submit(client, b'a', key='invalid')).status_code == 400
    for headers, status in [({'Authorization':'Bearer buyer'},403), ({'Authorization':''},401),
                            ({'Authorization':'Bearer revoked'},401), ({'X-Guest-Token':'guest'},403)]:
        for method, path in [('GET','/import-template?format=csv'),('GET','/jobs'),('GET','/jobs/missing'),
                             ('GET','/jobs/missing/errors'),('POST','/import-jobs?format=csv'),
                             ('POST','/entries/missing/retry-index?version=1')]:
            response = await client.request(method, BASE+path, headers={**headers,'Idempotency-Key':str(uuid4()),'Content-Type':'text/csv'}, content=b'')
            assert response.status_code == status, response.text


async def test_retry_failed_index_without_changing_review_or_version(env):
    client, knowledge, _ = env
    knowledge.model.fail = True
    item = await create(client, status='published')
    await knowledge.worker
    knowledge.model.fail = False
    result = await client.post(BASE+f"/entries/{item['id']}/retry-index?version=1")
    assert result.status_code == 200
    await knowledge.worker
    current = knowledge.repository.get(item['id'])
    assert current['version'] == 1 and current['reviewedAt'] == item['reviewedAt']
    assert current['indexStatus'] == 'ready'
    assert (await client.post(BASE+f"/entries/{item['id']}/retry-index?version=1")).status_code == 409
    assert (await client.post(BASE+f"/entries/{item['id']}/retry-index?version=2")).status_code == 409


async def test_restarted_incomplete_job_is_failed(env):
    _, knowledge, _ = env
    with knowledge.repository.store.db() as db:
        db.execute('INSERT INTO knowledge_imports VALUES (?,?,?,?,?)',
                   ('interrupted','admin:u1',str(uuid4()),'digest',json.dumps({'id':'interrupted','status':'running'})))
    restarted = Imports(knowledge.repository, KnowledgeInput)
    assert restarted.get('interrupted')['errorCode'] == 'IMPORT_INTERRUPTED'


async def test_database_failure_rolls_back_all_drafts(env, monkeypatch):
    client, knowledge, _ = env
    original = knowledge.repository.save
    calls = 0
    def fail_second(*args, **kwargs):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise RuntimeError('private database error')
        return original(*args, **kwargs)
    monkeypatch.setattr(knowledge.repository, 'save', fail_second)
    job = await finished(client, await submit(client, csv_file([HEADERS, *EXAMPLES])))
    assert job['status'] == 'failed' and job['successCount'] == 0
    assert knowledge.repository.list() == []
    assert 'private' not in json.dumps(job)


async def test_knowledge_statistics_show_current_status(env):
    client, knowledge, _ = env
    await create(client)
    await create(client, status='archived')
    await create(client, status='published')
    await knowledge.worker
    knowledge.model.fail = True
    await create(client, status='published')
    await knowledge.worker
    data = (await client.get('/api/v1/admin/ai/statistics')).json()['data']
    assert data['knowledge'] == dict(total=4, draft=1, archived=1, published=2, ready=1, pending=0, failed=1)
