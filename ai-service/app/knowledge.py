"""异步构建本地向量；问答只检索已发布且当前版本就绪的知识。"""
import asyncio
import threading

import numpy as np

from .business import ApiError


class Knowledge:
    def __init__(self, settings, repository):
        self.settings, self.repository = settings, repository
        self.model = None
        self.lock = threading.Lock()
        self.worker = None

    def _model(self):
        if self.model is None:
            from fastembed import TextEmbedding
            self.model = TextEmbedding(model_name=self.settings.embedding_model,
                                       cache_dir=str(self.settings.model_cache), threads=2)
        return self.model

    def schedule(self):
        # 单 worker 逐条更新；重复发布不会并发加载多份模型。
        if self.worker is None or self.worker.done():
            self.worker = asyncio.create_task(self._run())

    async def stop(self):
        if self.worker:
            self.worker.cancel()
            await asyncio.gather(self.worker, return_exceptions=True)

    async def _run(self):
        while pending := self.repository.pending():
            entry = pending[0]
            self.repository.index_result(entry, 'indexing')
            try:
                vectors = await asyncio.to_thread(self.embed_entry, entry)
                self.repository.index_result(entry, 'ready', vectors)
            except asyncio.CancelledError:
                self.repository.index_result(entry, 'pending')
                raise
            except Exception:
                # 不保存模型内部异常或堆栈，后台可见失败状态并重新发布重试。
                self.repository.index_result(entry, 'failed')

    def embed_entry(self, entry):
        content = entry['content'] if entry['format'] == 'article' else entry['question'] + '\n' + entry['answer']
        # 分块带重叠，保证长文章尾部也能被检索，而非被模型截断。
        chunks = [content[i:i+350] for i in range(0, len(content), 300)]
        with self.lock:
            vectors = list(self._model().embed([entry['title'] + '\n' + text for text in chunks]))
        return [{'text': text, 'vector': np.asarray(vector).tolist()} for text, vector in zip(chunks, vectors)]

    def search(self, query, pet_type=None, breed=None):
        with self.lock:
            vector = np.array(next(iter(self._model().query_embed(query))))
        candidates = []
        for entry, chunks in self.repository.ready():
            if pet_type and entry['petType'] not in (pet_type, 'both'):
                continue
            if entry['breedNames'] and breed not in entry['breedNames']:
                continue
            matrix = np.array([chunk['vector'] for chunk in chunks])
            scores = matrix @ vector / (np.linalg.norm(matrix, axis=1) * np.linalg.norm(vector) + 1e-9)
            best = int(np.argmax(scores))
            if scores[best] >= 0.55:
                candidates.append((float(scores[best]), entry, chunks[best]['text']))
        results = []
        for _, entry, text in sorted(candidates, key=lambda item: item[0], reverse=True):
            # 检索期间可能发生编辑/归档/删除，返回前复核当前资格。
            try:
                current = self.repository.get(entry['id'])
            except ApiError:
                continue
            if current['status'] != 'published' or current['indexStatus'] != 'ready' or current['version'] != entry['version']:
                continue
            results.append(dict(knowledgeId=entry['id'], version=entry['version'], title=entry['title'],
                                sourceName=entry['sourceName'], sourceUrl=entry.get('sourceUrl'), excerpt=text[:300]))
            if len(results) == 3:
                break
        return results
