"""持久化层：SQLite 连接、建表、账号与业务数据读写，以及内存验证码存储。

并发约定：sqlite3 连接本身非线程安全，账号/数据/验证码的读写统一经
_store_lock 串行化。所有 SQL 与比较逻辑与拆分前保持一致。
"""

import hashlib
import secrets
import sqlite3
import threading
import time
from typing import Dict, List, Optional

from .config import DB_PATH, PASSWORD_HASH_ITERATIONS

# 验证码是短时效数据，仍放内存即可（重启后重新获取验证码，不影响账号）。
# email -> {"code", "expire_at", "sent_at"}
_codes: Dict[str, dict] = {}
# 并发读写保护：验证码 + SQLite 连接（sqlite3 连接本身非线程安全，统一串行化）
_store_lock = threading.Lock()

# 连接在模块加载时建立一次，配合 _store_lock 串行访问，避免多线程竞争。
_db_conn = sqlite3.connect(DB_PATH, check_same_thread=False)
_db_conn.row_factory = sqlite3.Row


# ---------- 密码哈希 ----------
def hash_password(password: str, salt_hex: Optional[str] = None) -> tuple:
    """返回 (salt_hex, hash_hex)。PBKDF2-HMAC-SHA256，标准库实现，不引入额外依赖。"""
    salt = bytes.fromhex(salt_hex) if salt_hex else secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, PASSWORD_HASH_ITERATIONS
    )
    return salt.hex(), digest.hex()


def verify_password(password: str, salt_hex: str, expected_hash: str) -> bool:
    _, actual_hash = hash_password(password, salt_hex)
    return secrets.compare_digest(actual_hash, expected_hash)


def init_db() -> None:
    """建表：users（账号主体）与 user_data（按用户隔离的业务数据）。"""
    with _store_lock:
        _db_conn.execute("PRAGMA journal_mode=WAL")
        _db_conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                email         TEXT    NOT NULL UNIQUE,
                nickname      TEXT,
                salt          TEXT,
                password_hash TEXT,
                created_at    INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS user_data (
                user_id    INTEGER NOT NULL,
                data_key   TEXT    NOT NULL,
                value      TEXT    NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, data_key)
            );
            """
        )
        _db_conn.commit()


def _row_to_user(row: Optional[sqlite3.Row]) -> Optional[dict]:
    """把 users 表的一行转换成与旧内存结构一致的 dict。"""
    if row is None:
        return None
    return {
        "user_id": row["id"],
        "email": row["email"],
        "nickname": row["nickname"],
        "salt": row["salt"],
        "password_hash": row["password_hash"],
    }


def db_get_user_by_email(email: str) -> Optional[dict]:
    with _store_lock:
        row = _db_conn.execute(
            "SELECT * FROM users WHERE email = ?", (email,)
        ).fetchone()
    return _row_to_user(row)


def db_get_user_by_id(user_id: int) -> Optional[dict]:
    with _store_lock:
        row = _db_conn.execute(
            "SELECT * FROM users WHERE id = ?", (user_id,)
        ).fetchone()
    return _row_to_user(row)


def upsert_user(email: str, password: Optional[str]) -> dict:
    """创建（或更新）账号并持久化到数据库，返回用户记录。

    已存在时：若传入 password 则更新密码，否则保持原样（验证码登录场景）。
    """
    existing = db_get_user_by_email(email)
    if existing is None:
        salt, password_hash = hash_password(password) if password else (None, None)
        with _store_lock:
            cursor = _db_conn.execute(
                "INSERT INTO users (email, nickname, salt, password_hash, created_at)"
                " VALUES (?, ?, ?, ?, ?)",
                (email, email.split("@")[0], salt, password_hash, int(time.time())),
            )
            _db_conn.commit()
            user_id = cursor.lastrowid
        return {"user_id": user_id, "email": email, "nickname": email.split("@")[0]}

    if password:
        set_user_password(email, password)
    return existing


def set_user_password(email: str, password: str) -> None:
    """更新指定账号的密码哈希（注册补设密码 / 重置 / 修改密码共用）。"""
    salt, password_hash = hash_password(password)
    with _store_lock:
        _db_conn.execute(
            "UPDATE users SET salt = ?, password_hash = ? WHERE email = ?",
            (salt, password_hash, email),
        )
        _db_conn.commit()


# ---------- 业务数据（按用户隔离，key -> JSON 文档） ----------
def db_get_all_user_data(user_id: int) -> List[dict]:
    with _store_lock:
        rows = _db_conn.execute(
            "SELECT data_key, value, updated_at FROM user_data WHERE user_id = ?",
            (user_id,),
        ).fetchall()
    return [
        {"key": r["data_key"], "value": r["value"], "updatedAt": r["updated_at"]}
        for r in rows
    ]


def db_upsert_user_data(user_id: int, key: str, value: str, updated_at: int) -> None:
    """写入一条业务数据；仅在传入的 updated_at 不早于已存时间时才覆盖。

    这样即使客户端因网络乱序把旧版本后发到服务端，也不会把更新的数据冲掉
    （时间戳由客户端提供，比较的是同一来源的时间，不受服务端时钟影响）。
    """
    with _store_lock:
        _db_conn.execute(
            "INSERT INTO user_data (user_id, data_key, value, updated_at)"
            " VALUES (?, ?, ?, ?)"
            " ON CONFLICT(user_id, data_key) DO UPDATE SET"
            " value = excluded.value, updated_at = excluded.updated_at"
            " WHERE excluded.updated_at >= user_data.updated_at",
            (user_id, key, value, updated_at),
        )
        _db_conn.commit()
