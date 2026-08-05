from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
APP_YML = ROOT / "yan-admin" / "src" / "main" / "resources" / "application.yml"
MYSQL = Path(r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")
RESULTS_DIR = ROOT / "scripts" / "load" / "results"


@dataclass(frozen=True)
class DbConfig:
    host: str
    port: str
    database: str
    username: str
    password: str


def yaml_scalars(text: str, key: str) -> list[str]:
    prefix = f"{key}:"
    values: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith(prefix):
            values.append(stripped[len(prefix) :].strip().strip("'\""))
    return values


def read_config() -> DbConfig:
    text = APP_YML.read_text(encoding="utf-8")
    raw_url = next((value for value in yaml_scalars(text, "url") if value.startswith("jdbc:mysql://")), None)
    usernames = yaml_scalars(text, "username")
    passwords = yaml_scalars(text, "password")
    if not (raw_url and usernames and passwords):
        raise RuntimeError("Could not parse datasource settings from application.yml")
    match = re.search(r"jdbc:mysql://([^:/]+):([0-9]+)/([^? \t]+)", raw_url.replace("\\", ""))
    if not match:
        raise RuntimeError("Could not parse MySQL JDBC URL from application.yml")
    return DbConfig(match.group(1), match.group(2), match.group(3), usernames[0], passwords[0])


def mysql_base_cmd(config: DbConfig) -> list[str]:
    return [
        str(MYSQL),
        "--batch",
        "--raw",
        "--skip-column-names",
        "--default-character-set=utf8mb4",
        "-h",
        config.host,
        "-P",
        config.port,
        "-u",
        config.username,
        config.database,
    ]


def mysql_env(config: DbConfig) -> dict[str, str]:
    env = os.environ.copy()
    env["MYSQL_PWD"] = config.password
    return env


def query_scalar(config: DbConfig, sql: str) -> str:
    cmd = mysql_base_cmd(config) + ["-e", sql]
    completed = subprocess.run(
        cmd,
        env=mysql_env(config),
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if completed.returncode != 0:
        raise RuntimeError(completed.stdout.strip())
    return completed.stdout.strip().splitlines()[0] if completed.stdout.strip() else ""


def execute_sql(config: DbConfig, sql: str) -> None:
    completed = subprocess.run(
        mysql_base_cmd(config),
        env=mysql_env(config),
        input=sql,
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if completed.returncode != 0:
        raise RuntimeError(completed.stdout.strip())


def sql_string(value: object) -> str:
    if value is None:
        return "NULL"
    if value in {"NOW()", "NOW(3)"}:
        return str(value)
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def chunks(items: list[tuple[object, ...]], size: int = 500) -> list[list[tuple[object, ...]]]:
    return [items[index : index + size] for index in range(0, len(items), size)]


def insert_values(config: DbConfig, table: str, columns: list[str], rows: list[tuple[object, ...]], ignore: bool = True) -> None:
    if not rows:
        return
    keyword = "INSERT IGNORE" if ignore else "INSERT"
    column_sql = ",".join(columns)
    for batch in chunks(rows):
        values = ",\n".join("(" + ",".join(sql_string(value) for value in row) + ")" for row in batch)
        execute_sql(config, f"{keyword} INTO {table} ({column_sql}) VALUES\n{values};")


def run_id() -> str:
    return datetime.now().strftime("load_%Y%m%d_%H%M%S")


def id_base() -> int:
    return int(time.time() * 1000) * 1000


def category_id(config: DbConfig) -> int:
    try:
        value = query_scalar(
            config,
            "SELECT COALESCE((SELECT id FROM yan_category WHERE status='0' AND del_flag=0 ORDER BY id LIMIT 1),1);",
        )
        return int(value or "1")
    except Exception:
        return 1


def make_users(base: int, count: int, prefix: str) -> list[tuple[object, ...]]:
    now = "NOW()"
    rows: list[tuple[object, ...]] = []
    for offset in range(count):
        user_id = base + offset
        rows.append(
            (
                user_id,
                f"{prefix}_user_{offset}",
                f"{prefix}用户{offset}",
                "load-test-only",
                "0",
                "0",
                f"{prefix}_{offset}@load.test",
                None,
                "2",
                "",
                1,
                now,
                1,
                now,
                0,
            )
        )
    return rows


def insert_users(config: DbConfig, rows: list[tuple[object, ...]]) -> None:
    columns = [
        "id",
        "user_name",
        "nick_name",
        "password",
        "type",
        "status",
        "email",
        "phonenumber",
        "sex",
        "avatar",
        "create_by",
        "create_time",
        "update_by",
        "update_time",
        "del_flag",
    ]
    insert_values(config, "sys_user", columns, rows)


def insert_articles(config: DbConfig, base: int, count: int, author_id: int, prefix: str, cid: int) -> list[int]:
    rows: list[tuple[object, ...]] = []
    article_ids: list[int] = []
    for offset in range(count):
        article_id = base + offset
        article_ids.append(article_id)
        title = f"{prefix} 高并发压测文章 {offset}"
        content = (
            f"# {title}\n\n"
            f"YanBlog load test keyword {prefix}. "
            "这是一篇用于 ES、缓存、评论、浏览量压测的保留数据。"
        )
        rows.append(
            (
                article_id,
                title,
                content,
                f"{prefix} 摘要 {offset}",
                cid,
                "",
                "0",
                "0",
                0,
                "1",
                author_id,
                "NOW()",
                author_id,
                "NOW()",
                0,
            )
        )
    insert_values(
        config,
        "yan_article",
        [
            "id",
            "title",
            "content",
            "summary",
            "category_id",
            "thumbnail",
            "is_top",
            "status",
            "view_count",
            "is_comment",
            "create_by",
            "create_time",
            "update_by",
            "update_time",
            "del_flag",
        ],
        rows,
    )
    return article_ids


def insert_comments(config: DbConfig, base: int, count: int, article_id: int, user_start: int, prefix: str) -> list[int]:
    rows: list[tuple[object, ...]] = []
    comment_ids: list[int] = []
    for offset in range(count):
        comment_id = base + offset
        comment_ids.append(comment_id)
        rows.append(
            (
                comment_id,
                "0",
                article_id,
                -1,
                f"{prefix} 高并发评论 {offset}",
                -1,
                -1,
                user_start + (offset % 100),
                "NOW()",
                user_start + (offset % 100),
                "NOW()",
                0,
            )
        )
    insert_values(
        config,
        "yan_comment",
        [
            "id",
            "type",
            "article_id",
            "root_id",
            "content",
            "to_comment_user_id",
            "to_comment_id",
            "create_by",
            "create_time",
            "update_by",
            "update_time",
            "del_flag",
        ],
        rows,
    )
    return comment_ids


def insert_follows(config: DbConfig, follower_ids: list[int], author_id: int) -> None:
    rows = [(follower_id, author_id, "NOW()") for follower_id in follower_ids]
    insert_values(config, "yan_user_follow", ["follower_id", "followee_id", "create_time"], rows)


def insert_search_outbox(config: DbConfig, article_ids: list[int]) -> None:
    rows = [(article_id, f"ARTICLE_SEARCH:{article_id}", "PENDING", 0, 8, "NOW(3)") for article_id in article_ids]
    insert_values(
        config,
        "yan_article_search_outbox",
        ["article_id", "task_key", "status", "retry_count", "max_retry", "next_retry_at"],
        rows,
        ignore=False,
    )


def insert_notification_outbox(config: DbConfig, article_id: int, author_id: int) -> None:
    rows = [(article_id, author_id, f"ARTICLE_PUBLISH_NOTIFY:{author_id}:{article_id}", 0, "PENDING", 0, 8, "NOW(3)")]
    insert_values(
        config,
        "yan_notification_outbox",
        ["article_id", "author_id", "task_key", "cursor_follower_id", "status", "retry_count", "max_retry", "next_retry_at"],
        rows,
        ignore=False,
    )


def insert_notification_delivery_outbox(config: DbConfig, env: dict[str, object], count: int) -> list[int]:
    first_comment_id = int(env["firstCommentId"])
    user_start = int(env["commentUserStart"])
    article_id = int(env["commentArticleId"])
    rows: list[tuple[object, ...]] = []
    comment_ids: list[int] = []
    for offset in range(count):
        comment_id = first_comment_id + offset
        comment_ids.append(comment_id)
        receiver_id = user_start + ((offset + 1) % 100)
        actor_id = user_start + (offset % 100)
        rows.append(
            (
                f"COMMENT:{comment_id}",
                receiver_id,
                actor_id,
                article_id,
                comment_id,
                None,
                "COMMENT",
                f"{env['prefix']} direct notification load {offset}",
                "PENDING",
                0,
                8,
                "NOW(3)",
            )
        )
    insert_values(
        config,
        "yan_notification_delivery_outbox",
        [
            "task_key",
            "receiver_id",
            "actor_id",
            "article_id",
            "comment_id",
            "root_comment_id",
            "type",
            "content",
            "status",
            "retry_count",
            "max_retry",
            "next_retry_at",
        ],
        rows,
        ignore=True,
    )
    return comment_ids


def count_query(config: DbConfig, sql: str) -> int:
    return int(query_scalar(config, sql) or "0")


def wait_until(label: str, probe, expected: int, timeout_seconds: int) -> dict[str, object]:
    started = time.perf_counter()
    last = 0
    while True:
        last = int(probe())
        elapsed = time.perf_counter() - started
        if last >= expected:
            return {"label": label, "ok": True, "expected": expected, "actual": last, "seconds": round(elapsed, 3)}
        if elapsed >= timeout_seconds:
            return {"label": label, "ok": False, "expected": expected, "actual": last, "seconds": round(elapsed, 3)}
        print(f"[wait] {label}: {last}/{expected}, elapsed={elapsed:.1f}s", flush=True)
        time.sleep(2)


def prepare(args: argparse.Namespace) -> dict[str, object]:
    config = read_config()
    prefix = args.prefix or run_id()
    base = id_base()
    cid = category_id(config)
    author_id = base
    regular_author_id = base + 100_000_000
    large_author_id = base + 200_000_000
    user_start = base + 1_000_000
    regular_follower_start = base + 300_000_000
    large_follower_start = base + 400_000_000

    print(f"[prepare] prefix={prefix}")
    insert_users(config, make_users(author_id, 1, prefix + "_author"))
    insert_users(config, make_users(regular_author_id, 1, prefix + "_regular_author"))
    insert_users(config, make_users(large_author_id, 1, prefix + "_large_author"))
    insert_users(config, make_users(user_start, max(args.comment_users, 100), prefix + "_comment"))
    insert_users(config, make_users(regular_follower_start, args.regular_followers, prefix + "_regular_follower"))
    insert_users(config, make_users(large_follower_start, args.large_followers, prefix + "_large_follower"))

    articles = insert_articles(config, base + 10_000_000, args.articles, author_id, prefix, cid)
    comment_article_id = articles[0]
    comment_ids = insert_comments(config, base + 20_000_000, args.comments, comment_article_id, user_start, prefix)
    insert_follows(config, [regular_follower_start + i for i in range(args.regular_followers)], regular_author_id)
    insert_follows(config, [large_follower_start + i for i in range(args.large_followers)], large_author_id)

    env = {
        "prefix": prefix,
        "categoryId": cid,
        "articleIds": articles[: min(100, len(articles))],
        "firstArticleId": articles[0],
        "commentArticleId": comment_article_id,
        "commentIds": comment_ids[: min(100, len(comment_ids))],
        "firstCommentId": comment_ids[0] if comment_ids else None,
        "commentUserStart": user_start,
        "authorId": author_id,
        "regularAuthorId": regular_author_id,
        "largeAuthorId": large_author_id,
        "regularFollowers": args.regular_followers,
        "largeFollowers": args.large_followers,
        "articles": args.articles,
        "comments": args.comments,
        "keyword": prefix,
    }
    result_dir = RESULTS_DIR / prefix
    result_dir.mkdir(parents=True, exist_ok=True)
    (result_dir / "seed-env.json").write_text(json.dumps(env, ensure_ascii=False, indent=2), encoding="utf-8")
    (RESULTS_DIR / "latest-seed-env.json").write_text(json.dumps(env, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(env, ensure_ascii=False, indent=2))
    return env


def measure_async(args: argparse.Namespace) -> dict[str, object]:
    env = prepare(args)
    config = read_config()
    prefix = env["prefix"]
    result_dir = RESULTS_DIR / str(prefix)
    article_ids = [int(article_id) for article_id in env["articleIds"]]
    es_ids = article_ids[: min(args.es_events, len(article_ids))]
    if len(es_ids) < args.es_events:
        start = max(article_ids) + 1
        extra = insert_articles(config, start, args.es_events - len(es_ids), int(env["authorId"]), str(prefix), int(env["categoryId"]))
        es_ids.extend(extra)
    regular_article_id = int(env["firstArticleId"]) + 90_000_000
    large_article_id = int(env["firstArticleId"]) + 90_000_001
    insert_articles(config, regular_article_id, 1, int(env["regularAuthorId"]), str(prefix) + "_regular_publish", int(env["categoryId"]))
    insert_articles(config, large_article_id, 1, int(env["largeAuthorId"]), str(prefix) + "_large_publish", int(env["categoryId"]))

    print(f"[measure] enqueue ES events={len(es_ids)}")
    insert_search_outbox(config, es_ids)
    print("[measure] enqueue notification regular author")
    insert_notification_outbox(config, regular_article_id, int(env["regularAuthorId"]))
    print("[measure] enqueue notification large author")
    insert_notification_outbox(config, large_article_id, int(env["largeAuthorId"]))
    print(f"[measure] enqueue direct notification events={args.direct_notifications}")
    direct_comment_ids = insert_notification_delivery_outbox(config, env, args.direct_notifications)

    min_es_id = min(es_ids)
    max_es_id = max(es_ids)
    results = [
        wait_until(
            "ES outbox SUCCEEDED",
            lambda: count_query(
                config,
                f"SELECT COUNT(1) FROM yan_article_search_outbox WHERE article_id BETWEEN {min_es_id} AND {max_es_id} AND status='SUCCEEDED';",
            ),
            len(es_ids),
            args.timeout,
        ),
        wait_until(
            "regular author notifications inserted",
            lambda: count_query(
                config,
                f"SELECT COUNT(1) FROM yan_user_notification WHERE article_id={regular_article_id} AND type='ARTICLE_PUBLISHED';",
            ),
            int(env["regularFollowers"]),
            args.timeout,
        ),
        wait_until(
            "large author publish stream inserted",
            lambda: count_query(
                config,
                f"SELECT COUNT(1) FROM yan_author_publish_event WHERE article_id={large_article_id};",
            ),
            1,
            args.timeout,
        ),
        wait_until(
            "direct notifications inserted",
            lambda: count_query(
                config,
                f"SELECT COUNT(1) FROM yan_user_notification "
                f"WHERE article_id={int(env['commentArticleId'])} AND type='COMMENT' "
                f"AND comment_id BETWEEN {min(direct_comment_ids)} AND {max(direct_comment_ids)};",
            ),
            len(direct_comment_ids),
            args.timeout,
        ),
    ]
    large_direct_notifications = count_query(
        config,
        f"SELECT COUNT(1) FROM yan_user_notification WHERE article_id={large_article_id} AND type='ARTICLE_PUBLISHED';",
    )
    report = {
        "prefix": prefix,
        "timestamp": datetime.now().isoformat(timespec="seconds"),
        "esEvents": len(es_ids),
        "regularFollowers": env["regularFollowers"],
        "largeFollowers": env["largeFollowers"],
        "directNotifications": len(direct_comment_ids),
        "regularArticleId": regular_article_id,
        "largeArticleId": large_article_id,
        "largeDirectNotifications": large_direct_notifications,
        "checks": results,
    }
    (result_dir / "async-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return report


def status(args: argparse.Namespace) -> dict[str, object]:
    config = read_config()
    env_file = Path(args.env_file)
    env = json.loads(env_file.read_text(encoding="utf-8"))
    first = int(env["firstArticleId"])
    lo = first
    hi = first + int(env["articles"]) - 1
    status_summary = query_scalar(
        config,
        f"SELECT GROUP_CONCAT(CONCAT(status,':',cnt) ORDER BY status SEPARATOR ', ') "
        f"FROM (SELECT status,COUNT(1) cnt FROM yan_article_search_outbox "
        f"WHERE article_id BETWEEN {lo} AND {hi} GROUP BY status) s;",
    )
    report = {
        "prefix": env["prefix"],
        "articleIdRange": [lo, hi],
        "searchOutbox": status_summary,
        "duePending": count_query(config, "SELECT COUNT(1) FROM yan_article_search_outbox WHERE status='PENDING' AND next_retry_at <= NOW(3);"),
        "processing": count_query(config, "SELECT COUNT(1) FROM yan_article_search_outbox WHERE status='PROCESSING';"),
        "latestError": query_scalar(
            config,
            f"SELECT COALESCE(last_error,'') FROM yan_article_search_outbox "
            f"WHERE article_id BETWEEN {lo} AND {hi} AND last_error IS NOT NULL "
            f"ORDER BY update_time DESC LIMIT 1;",
        ),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="YanBlog high-concurrency database seed and async measurements.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    def add_seed_options(target: argparse.ArgumentParser) -> None:
        target.add_argument("--prefix", default=None, help="Run prefix. Defaults to load_YYYYMMDD_HHMMSS.")
        target.add_argument("--articles", type=int, default=10_000)
        target.add_argument("--comments", type=int, default=10_000)
        target.add_argument("--comment-users", type=int, default=200)
        target.add_argument("--regular-followers", type=int, default=1_000)
        target.add_argument("--large-followers", type=int, default=10_000)

    prepare_parser = subparsers.add_parser("prepare")
    add_seed_options(prepare_parser)

    measure_parser = subparsers.add_parser("measure-async")
    add_seed_options(measure_parser)
    measure_parser.add_argument("--es-events", type=int, default=10_000)
    measure_parser.add_argument("--direct-notifications", type=int, default=10_000)
    measure_parser.add_argument("--timeout", type=int, default=180)

    status_parser = subparsers.add_parser("status")
    status_parser.add_argument("--env-file", default=str(RESULTS_DIR / "latest-seed-env.json"))

    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args)
    elif args.command == "measure-async":
        measure_async(args)
    elif args.command == "status":
        status(args)


if __name__ == "__main__":
    main()
