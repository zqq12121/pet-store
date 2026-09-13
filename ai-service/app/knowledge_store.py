"""正文、版本和向量共用 SQLite 事务，防止旧索引覆盖已归档的新版本。"""
import json

from .business import ApiError
from .store import now, uid


class KnowledgeStore:
    def __init__(self, store, settings):
        self.store, self.model_name = store, settings.embedding_model
        with store.db() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS knowledge (
                    id TEXT PRIMARY KEY, data TEXT NOT NULL, vectors TEXT, model TEXT);
                CREATE TABLE IF NOT EXISTS knowledge_migrations (name TEXT PRIMARY KEY);
            ''')
            # 仅首次导入原文件；后续启动不覆盖后台编辑或重新发布已归档内容。
            if not db.execute("SELECT 1 FROM knowledge_migrations WHERE name='initial-file'").fetchone():
                for item in json.loads(settings.knowledge_path.read_text()):
                    entry = dict(category='feeding', petType='both', format='article', breedNames=[],
                                 question=None, answer=None, sourceUrl=None, createdAt=now(), updatedAt=now())
                    entry.update(item)
                    entry['petType'] = 'cat' if item['id'].startswith('cat-') else 'dog' if item['id'].startswith('dog-') else 'both'
                    entry['category'] = 'health' if any(word in item['id'] for word in ('health', 'vet')) else 'grooming' if 'grooming' in item['id'] else 'feeding'
                    entry.update(indexStatus='pending' if entry['status'] == 'published' else 'not_indexed',
                                 indexedVersion=None, indexErrorCode=None)
                    db.execute('INSERT INTO knowledge(id,data) VALUES (?,?)', (entry['id'], json.dumps(entry)))
                db.execute("INSERT INTO knowledge_migrations VALUES ('initial-file')")
            # 重启恢复被中断的索引；嵌入模型变更时不混用旧向量。
            for row in db.execute('SELECT * FROM knowledge').fetchall():
                entry = json.loads(row['data'])
                if entry['status'] == 'published' and (entry['indexStatus'] == 'indexing' or
                        (entry['indexStatus'] == 'ready' and row['model'] != self.model_name)):
                    entry.update(indexStatus='pending', indexedVersion=None)
                    db.execute('UPDATE knowledge SET data=?,vectors=NULL WHERE id=?', (json.dumps(entry), entry['id']))

    def get(self, entry_id, db=None):
        if db is None:
            with self.store.db() as db:
                return self.get(entry_id, db)
        row = db.execute('SELECT data FROM knowledge WHERE id=?', (entry_id,)).fetchone()
        if not row or json.loads(row[0])['status'] == 'deleted':
            raise ApiError(404, 'KNOWLEDGE_NOT_FOUND', '知识不存在')
        return json.loads(row[0])

    def list(self, keyword='', **filters):
        with self.store.db() as db:
            entries = [json.loads(r[0]) for r in db.execute('SELECT data FROM knowledge')]
        return sorted([e for e in entries if e['status'] != 'deleted'
                       and all(not v or e.get(k) == v for k, v in filters.items())
                       and keyword.casefold() in ' '.join(str(e.get(k) or '') for k in
                                                         ('title', 'content', 'question', 'answer')).casefold()],
                      key=lambda e: (e['updatedAt'], e['id']), reverse=True)

    def save(self, body, actor, entry_id=None):
        with self.store.db() as db:
            db.execute('BEGIN IMMEDIATE')
            old = self.get(entry_id, db) if entry_id else None
            if old and old['version'] != body['version']:
                raise ApiError(409, 'VERSION_CONFLICT', '内容已被其他操作修改，请刷新后重新编辑')
            entry = {**body, 'id': entry_id or uid('kb'), 'version': old['version']+1 if old else 1,
                     'createdAt': old['createdAt'] if old else now(), 'updatedAt': now(),
                     'updatedBy': actor, 'reviewedBy': actor if body['status'] == 'published' else None,
                     'reviewedAt': now() if body['status'] == 'published' else None,
                     'indexStatus': 'pending' if body['status'] == 'published' else 'not_indexed',
                     'indexedVersion': None, 'indexErrorCode': None}
            # 保存立即清除旧向量资格；新正文成功保存不代表新向量已可检索。
            db.execute('INSERT INTO knowledge(id,data) VALUES (?,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data,vectors=NULL,model=NULL',
                       (entry['id'], json.dumps(entry)))
        return entry

    def delete(self, entry_id, version, actor):
        entry = self.get(entry_id)
        self.save({**entry, 'version': version, 'status': 'deleted'}, actor, entry_id)

    def pending(self):
        return [e for e in self.list(status='published') if e['indexStatus'] == 'pending']

    def index_result(self, entry, state, vectors=None):
        with self.store.db() as db:
            db.execute('BEGIN IMMEDIATE')
            try:
                current = self.get(entry['id'], db)
            except ApiError:
                return
            if current['version'] != entry['version'] or current['status'] != 'published':
                return
            current.update(indexStatus=state, indexedVersion=current['version'] if state == 'ready' else None,
                           indexErrorCode='EMBEDDING_FAILED' if state == 'failed' else None)
            db.execute('UPDATE knowledge SET data=?,vectors=?,model=? WHERE id=?',
                       (json.dumps(current), json.dumps(vectors) if vectors is not None else None, self.model_name, entry['id']))

    def ready(self):
        with self.store.db() as db:
            rows = db.execute('SELECT data,vectors FROM knowledge WHERE model=? AND vectors IS NOT NULL', (self.model_name,)).fetchall()
        return [(e, json.loads(r['vectors'])) for r in rows if
                (e := json.loads(r['data']))['status'] == 'published' and e['indexStatus'] == 'ready'
                and e['version'] == e['indexedVersion']]
