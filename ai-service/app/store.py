"""单进程 SQLite 会话持久化；事务保证消息幂等和同会话串行生成。"""
import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from uuid import uuid4

from .business import ApiError

DISCLAIMER = "AI 生成，仅供参考"


def now():
    return datetime.now(timezone.utc).isoformat()


def uid(prefix):
    return prefix + "_" + uuid4().hex


class Store:
    def __init__(self, path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.db() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY, owner TEXT NOT NULL, data TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS session_owner ON sessions(owner);
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY, session_id TEXT NOT NULL, client_id TEXT NOT NULL,
                    role TEXT NOT NULL, sequence INTEGER NOT NULL, request TEXT NOT NULL,
                    data TEXT NOT NULL, created REAL NOT NULL DEFAULT (unixepoch()),
                    UNIQUE(session_id, client_id, role));
                CREATE INDEX IF NOT EXISTS message_session ON messages(session_id, sequence);
            """)
        # 单 worker 启动时把上次中断的占位记录收尾，不让会话永久停在“思考中”。
        with self.db() as db:
            for row in db.execute("SELECT data FROM messages WHERE role='assistant'").fetchall():
                m = json.loads(row[0])
                if m["status"] == "streaming":
                    self.fail(m, "AI_UPSTREAM_UNAVAILABLE", "服务重启，本次回复已中断，请重新提问")
                    self._save_message(db, m)

    @contextmanager
    def db(self):
        connection = sqlite3.connect(self.path, timeout=5)
        connection.row_factory = sqlite3.Row
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def session(self, sid, owner, db=None):
        if db is None:
            with self.db() as db:
                return self.session(sid, owner, db)
        row = db.execute("SELECT data FROM sessions WHERE id=? AND owner=?", (sid, owner)).fetchone()
        if not row:
            raise ApiError(404, "AI_SESSION_NOT_FOUND", "会话不存在或不可访问")
        return json.loads(row[0])

    def create(self, owner, product_id):
        s = dict(id=uid("chat"), ownerType="user" if owner.startswith("buyer:") else "guest",
                 productId=product_id, title="新会话", status="active", lastMessageAt=None,
                 createdAt=now(), closedAt=None)
        with self.db() as db:
            count = db.execute("SELECT COUNT(*) FROM sessions WHERE owner=?", (owner,)).fetchone()[0]
            if count >= 200:
                raise ApiError(429, "RATE_LIMITED", "会话数量已达上限，请使用已有会话")
            db.execute("INSERT INTO sessions VALUES (?,?,?)", (s["id"], owner, json.dumps(s)))
        return s

    def sessions(self, owner, status=None):
        with self.db() as db:
            items = [json.loads(r[0]) for r in db.execute("SELECT data FROM sessions WHERE owner=?", (owner,))]
        return sorted([s for s in items if status is None or s["status"] == status],
                      key=lambda s: (s["lastMessageAt"] or s["createdAt"], s["id"]), reverse=True)

    def messages(self, sid):
        with self.db() as db:
            return [json.loads(r[0]) for r in db.execute(
                "SELECT data FROM messages WHERE session_id=? ORDER BY sequence", (sid,))]

    def message(self, sid, mid):
        with self.db() as db:
            row = db.execute("SELECT data FROM messages WHERE session_id=? AND id=?", (sid, mid)).fetchone()
        if not row:
            raise ApiError(404, "AI_MESSAGE_NOT_FOUND", "消息不存在或不可访问")
        return json.loads(row[0])

    def existing(self, sid, body):
        with self.db() as db:
            row = db.execute("SELECT request,data FROM messages WHERE session_id=? AND client_id=? AND role='assistant'",
                             (sid, body["clientMessageId"])).fetchone()
        if row:
            if json.loads(row["request"]) != body:
                raise ApiError(409, "IDEMPOTENCY_CONFLICT", "同一个消息编号不能用于不同问题")
            message = json.loads(row["data"])
            if message["status"] == "streaming":
                raise ApiError(409, "AI_MESSAGE_IN_PROGRESS", "回复生成中，请读取原消息")
            return message
        return None

    def begin(self, sid, owner, body):
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            s = self.session(sid, owner, db)
            if s["status"] != "active":
                raise ApiError(409, "AI_SESSION_CLOSED", "会话已结束，请创建新会话")
            if db.execute("SELECT 1 FROM messages WHERE session_id=? AND json_extract(data,'$.status')='streaming'", (sid,)).fetchone():
                raise ApiError(409, "AI_MESSAGE_IN_PROGRESS", "请等待当前回复完成")
            count = db.execute("""SELECT COUNT(*) FROM messages m JOIN sessions s ON m.session_id=s.id
                                  WHERE s.owner=? AND m.role='user' AND m.created>unixepoch()-60""", (owner,)).fetchone()[0]
            if count >= 10:
                raise ApiError(429, "RATE_LIMITED", "提问过于频繁，请稍后再试")
            seq = db.execute("SELECT COALESCE(MAX(sequence),0) FROM messages WHERE session_id=?", (sid,)).fetchone()[0]
            pair = []
            for i, role in enumerate(("user", "assistant"), 1):
                m = dict(id=uid("msg"), sequence=seq+i, sessionId=sid, role=role,
                         clientMessageId=body["clientMessageId"], content=body["content"] if role == "user" else "",
                         status="completed" if role == "user" else "streaming", intent=None, outcome=None,
                         sources=[], productCards=[], orderCards=[], actions=[], suggestedQuestions=[],
                         feedback="none", disclaimer=DISCLAIMER if role == "assistant" else None,
                         errorCode=None, createdAt=now(), completedAt=None)
                if role == "assistant":
                    m["userMessageId"] = pair[0]["id"]
                db.execute("INSERT INTO messages(id,session_id,client_id,role,sequence,request,data) VALUES (?,?,?,?,?,?,?)",
                           (m["id"], sid, body["clientMessageId"], role, seq+i, json.dumps(body), json.dumps(m)))
                pair.append(m)
            if seq == 0:
                s["title"] = body["content"][:50]
            s["lastMessageAt"] = now()
            db.execute("UPDATE sessions SET data=? WHERE id=?", (json.dumps(s), sid))
            return pair

    @staticmethod
    def _save_message(db, message):
        db.execute("UPDATE messages SET data=? WHERE id=?", (json.dumps(message), message["id"]))

    def save(self, message):
        with self.db() as db:
            self._save_message(db, message)

    @staticmethod
    def fail(message, code, text):
        message.update(status="failed", errorCode=code, errorMessage=text, completedAt=now(), outcome="unresolved",
                       actions=[{"type": "contact_shop", "label": "联系店主"}])

    def feedback(self, sid, mid, value, reason):
        m = self.message(sid, mid)
        if m["role"] != "assistant" or m["status"] != "completed":
            raise ApiError(409, "AI_MESSAGE_NOT_FEEDBACKABLE", "只能评价已完成的助手回复")
        m.update(feedback=value, feedbackReason=reason)
        self.save(m)
        return dict(messageId=mid, value=value, reason=reason, updatedAt=now())

    def close(self, sid, owner):
        with self.db() as db:
            s = self.session(sid, owner, db)
            if db.execute("SELECT 1 FROM messages WHERE session_id=? AND json_extract(data,'$.status')='streaming'", (sid,)).fetchone():
                raise ApiError(409, "AI_MESSAGE_IN_PROGRESS", "回复生成中，暂不能结束会话")
            if s["status"] == "active":
                s.update(status="closed", closedAt=now())
                db.execute("UPDATE sessions SET data=? WHERE id=?", (json.dumps(s), sid))
            return s
