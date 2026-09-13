"""验证审核发布、索引失败与竞态、权限、归档撤回及重启持久化。"""
import asyncio
import json
import threading

import httpx
import pytest
import pytest_asyncio

from app.business import BusinessClient
from app.config import Settings
from app.knowledge_store import KnowledgeStore
from app.main import create_app


class Embedding:
    fail = False

    def embed(self, documents):
        if self.fail:
            raise RuntimeError('private-model-path')
        return iter([[1., 0.] for _ in documents])

    def query_embed(self, query):
        return iter([[1., 0.]])


def entry(**changes):
    return dict(title='猫咪护理', category='feeding', petType='cat', format='article', content='提供清洁饮水',
                sourceName='测试审核来源', **changes)


@pytest_asyncio.fixture
async def env(tmp_path):
    path = tmp_path / 'seed.json'
    path.write_text('[]')
    settings = Settings(knowledge_path=path, db_path=tmp_path / 'ai.db', api_key='')
    def java(request):
        bearer = request.headers.get('Authorization')
        if bearer not in ('Bearer admin', 'Bearer buyer'):
            return httpx.Response(401, json={})
        return httpx.Response(200, json={'code':'OK', 'data':{'id':'u1','role':bearer.split()[1]}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(java)) as http:
        app = create_app(settings, business=BusinessClient(settings, http))
        knowledge = app.state.knowledge
        knowledge.model = Embedding()
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://ai',
                                         headers={'Authorization':'Bearer admin'}) as client:
                yield client, knowledge, settings


BASE = '/api/v1/admin/knowledge/entries'


async def create(client, **changes):
    res = await client.post(BASE, json=entry(**changes))
    assert res.status_code == 201, res.text
    return res.json()['data']


async def update(client, old, **changes):
    fields = ('title','category','petType','format','content','question','answer','sourceName','sourceUrl','breedNames','status','version')
    body = {k: old[k] for k in fields}
    res = await client.put(BASE+'/'+old['id'], json={**body, **changes})
    return res


async def test_draft_publish_edit_archive_and_restart(env):
    client, knowledge, settings = env
    draft = await create(client)
    await knowledge.worker
    assert knowledge.search('猫咪护理') == []
    published = (await update(client, draft, status='published')).json()['data']
    assert published['indexStatus'] == 'pending'
    await knowledge.worker
    assert knowledge.search('猫咪护理')[0]['version'] == 2
    ready = (await client.get(BASE+'/'+draft['id'])).json()['data']
    assert ready['indexedVersion'] == ready['version'] and ready['reviewedBy'] == 'admin:u1'
    edited = (await update(client, ready, content='更新后的护理说明')).json()['data']
    # HTTP 返回时新索引可能已完成，但任何情况下都不能再返回旧版本。
    assert all(source['version'] == edited['version'] for source in knowledge.search('猫咪护理'))
    await knowledge.worker
    assert knowledge.search('猫咪护理')[0]['excerpt'] == '更新后的护理说明'
    assert (await update(client, edited, status='archived')).status_code == 200
    assert knowledge.search('猫咪护理') == []
    restarted = KnowledgeStore(knowledge.repository.store, settings)
    assert restarted.get(draft['id'])['status'] == 'archived'


async def test_version_conflict_does_not_overwrite(env):
    client, knowledge, _ = env
    draft = await create(client)
    assert (await update(client, draft, content='已修改')).status_code == 200
    conflict = await update(client, draft, content='过期编辑')
    assert conflict.status_code == 409
    assert knowledge.repository.get(draft['id'])['content'] == '已修改'


async def test_failed_index_can_be_republished(env):
    client, knowledge, _ = env
    knowledge.model.fail = True
    draft = await create(client, status='published')
    await knowledge.worker
    failed = (await client.get(BASE+'/'+draft['id'])).json()['data']
    assert failed['indexStatus'] == 'failed' and 'private-model-path' not in json.dumps(failed)
    assert knowledge.search('猫咪护理') == []
    knowledge.model.fail = False
    assert (await update(client, failed, status='published')).status_code == 200
    await knowledge.worker
    assert knowledge.search('猫咪护理')[0]['knowledgeId'] == draft['id']


async def test_archiving_during_index_never_resurrects_old_vector(env):
    client, knowledge, _ = env
    started, release = threading.Event(), threading.Event()
    class Blocking(Embedding):
        def embed(self, documents):
            started.set()
            assert release.wait(3)
            return super().embed(documents)
    knowledge.model = Blocking()
    published = await create(client, status='published')
    await asyncio.to_thread(started.wait, 2)
    try:
        assert (await update(client, published, status='archived')).status_code == 200
    finally:
        release.set()
    await knowledge.worker
    assert knowledge.repository.ready() == []
    assert knowledge.repository.get(published['id'])['status'] == 'archived'


@pytest.mark.parametrize('headers,status', [({'Authorization':'Bearer buyer'},403),
    ({'Authorization':'Bearer revoked'},401),({'Authorization':'','X-Guest-Token':'guest'},403),({'Authorization':''},401)])
async def test_admin_only_on_all_read_and_write_routes(env, headers, status):
    client, _, _ = env
    for method, path, kwargs in [('GET',BASE,{}),('GET',BASE+'/missing',{}),('POST',BASE,{'json':entry()}),
                                ('PUT',BASE+'/missing',{'json':{**entry(),'version':1}}),('DELETE',BASE+'/missing?version=1',{})]:
        response = await client.request(method,path,headers=headers,**kwargs)
        assert response.status_code == status
        assert response.json()['code'] == ('FORBIDDEN' if status == 403 else 'UNAUTHORIZED')


@pytest.mark.parametrize('changes', [ {'sourceUrl':'javascript:alert(1)'}, {'sourceUrl':'http://example.com'},
    {'sourceUrl':'https://user:pass@example.com'}, {'sourceUrl':'https://127.0.0.1/a'},
    {'sourceUrl':'https://example.com/?api_key=secret'}, {'content':'  '}, {'petType':'rabbit'},
    {'format':'qa'}, {'question':'文章不能同时包含问题'}, {'reviewedBy':'forged'}])
async def test_invalid_entries_rejected(env, changes):
    client, _, _ = env
    response = await client.post(BASE,json={**entry(),**changes})
    assert response.status_code == 400


async def test_qa_long_article_search_filters_and_delete(env):
    client, knowledge, _ = env
    response = await client.post(BASE,json={**entry(),'format':'qa','content':None,'question':'如何饮水？','answer':'保持清洁','status':'published'})
    assert response.status_code == 201
    qa = response.json()['data']
    await knowledge.worker
    assert '保持清洁' in knowledge.search('饮水')[0]['excerpt']
    result = await client.get(BASE,params={'petType':'cat','format':'qa','keyword':'保持','pageSize':1})
    assert result.json()['data']['total'] == 1
    vectors = knowledge.embed_entry({**qa,'format':'article','content':'前文'*300+'结尾事实'})
    assert len(vectors)>1 and '结尾事实' in vectors[-1]['text']
    assert (await client.delete(BASE+'/'+qa['id'],params={'version':qa['version']})).status_code == 200
    assert knowledge.search('饮水') == []
    assert (await client.get(BASE+'/'+qa['id'])).status_code == 404


async def test_seed_imported_once_without_overwriting_admin_edit(env):
    client, knowledge, settings = env
    draft = await create(client)
    settings.knowledge_path.write_text(json.dumps([{'id':'new-file-entry'}]))
    # 首次导入标记存在后不再读取或覆盖种子文件。
    again = KnowledgeStore(knowledge.repository.store, settings)
    assert len(again.list()) == 1 and again.get(draft['id'])['status'] == 'draft'


async def test_pet_type_and_breed_scope_respected(env):
    client, knowledge, _ = env
    await create(client, status='published', breedNames=['布偶猫'])
    await knowledge.worker
    assert knowledge.search('护理', 'dog', '布偶猫') == []
    assert knowledge.search('护理', 'cat', '英短') == []
    assert knowledge.search('护理', 'cat') == []
    assert knowledge.search('护理', 'cat', '布偶猫')
